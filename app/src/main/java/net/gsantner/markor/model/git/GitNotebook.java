/*#######################################################
 *
 *   Git sync session for the notebook folder.
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.git;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;

import net.gsantner.markor.R;
import net.gsantner.markor.model.AppSettings;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 笔记本 git 同步的会话层：git 操作一律在单线程后台执行，同一时刻只跑一个，结果回主线程。
 * 触发方式有三种：编辑后防抖、前台定时器、系统后台任务（GitSyncJob）。
 */
public final class GitNotebook {

    private static final long DEBOUNCE_MS = 3000L;

    public interface Ui {
        void onGitMessage(String message, boolean isError);

        void onGitConflict(List<String> paths, String stage);
    }

    private interface Task {
        void run(GitRepo repo, AppSettings settings) throws Exception;
    }

    public interface StatusCallback {
        void callback(String text);
    }

    public interface CloneCallback {
        void callback(@Nullable File directory, @Nullable String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    @Nullable
    private static WeakReference<Ui> _uiRef = null;
    @Nullable
    private static Runnable _debounce = null;
    @Nullable
    private static Runnable _timer = null;

    /**
     * 提示的兜底出口。界面（MainActivity）一暂停就 detach，而同步按钮在设置页和文件浏览器里也能按，
     * 只走 Ui 回调的话结果会被整个丢掉 —— 用户看到的就是「点了没反应」。记下 application context，
     * 没有界面时改用 Toast 把结果说出来。
     */
    @Nullable
    private static volatile Context _appContext = null;

    /** 作者未配置的提示一次进程只弹一次，免得自动同步每轮都刷 */
    private static final AtomicBoolean _authorHinted = new AtomicBoolean(false);

    private GitNotebook() {
    }

    private static void remember(@Nullable final Context context) {
        if (context != null) {
            _appContext = context.getApplicationContext();
        }
    }

    public static boolean isAvailable(@Nullable final AppSettings settings) {
        return settings != null && settings.isGitSyncEnabled()
                && settings.getGitRepositoryDirectory() != null;
    }

    public static void attach(final Ui ui) {
        _uiRef = new WeakReference<>(ui);
    }

    public static void detach(final Ui ui) {
        if (_uiRef != null && _uiRef.get() == ui) {
            _uiRef = null;
        }
        stopTimer();
    }

    /** 前台定时器只在界面还在时运行，后台那条路交给 WorkManager */
    public static void startTimer(final Context context) {
        remember(context);
        stopTimer();
        final AppSettings settings = AppSettings.get(context);
        final int minutes = settings.getGitSyncIntervalMinutes();
        if (!isAvailable(settings) || minutes <= 0) {
            return;
        }
        _timer = new Runnable() {
            @Override
            public void run() {
                requestSync(context.getApplicationContext(), false, true);
                MAIN.postDelayed(this, minutes * 60_000L);
            }
        };
        MAIN.postDelayed(_timer, minutes * 60_000L);
    }

    public static void stopTimer() {
        if (_timer != null) {
            MAIN.removeCallbacks(_timer);
            _timer = null;
        }
    }

    /**
     * 编辑 / 新建 / 重命名 / 删除之后调用：保存即提交，提交不等防抖；
     * 远端那部分（拉 + 推）仍然 3s 防抖，连续改动并成一次网络往返。
     */
    public static void onFileSystemChanged(final Context context, @Nullable final File file) {
        final AppSettings settings = AppSettings.get(context);
        if (!isAvailable(settings) || !settings.isGitCommitOnEditEnabled() || file == null) {
            return;
        }
        final File repository = settings.getGitRepositoryDirectory();
        if (repository == null || !isInside(repository, file)) {
            return;
        }
        final Context app = context.getApplicationContext();
        remember(app);
        // 立刻提交：本地改动先落成一个 commit，不等定时器也不等防抖
        commitOnSave(app, settings);
        if (_debounce != null) {
            MAIN.removeCallbacks(_debounce);
        }
        _debounce = () -> {
            _debounce = null;
            requestSync(app, false, true);
        };
        MAIN.postDelayed(_debounce, DEBOUNCE_MS);
    }

    /** 保存后的自动提交：成功不弹提示（每次保存都弹太吵），失败与配置缺失照样反馈 */
    private static void commitOnSave(final Context context, final AppSettings settings) {
        warnIfAuthorMissing(settings);
        run(settings, (repo, appSettings) -> {
            if (repo.commitNow()) {
                appSettings.setGitLastSyncInfo(timestamp() + " · "
                        + context.getString(R.string.git_committed));
            }
        }, null);
    }

    /**
     * 打开笔记本时：遗留冲突先报给界面，没有冲突才拉一次远端。
     * 离线或没有凭据时这里静默失败，不打断读笔记。
     */
    public static void onNotebookOpened(final Context context) {
        final AppSettings settings = AppSettings.get(context);
        remember(context);
        if (!isAvailable(settings)) {
            return;
        }
        startTimer(context);
        // 走 requestSync 同一条串行路径，免得和编辑后防抖的同步撞 index.lock
        run(settings, (repo, appSettings) -> {
            final GitRepo.Info info = repo.status();
            if (!info.conflicts.isEmpty()) {
                postConflict(info.conflicts, "unresolved");
                return;
            }
            final GitRepo.SyncResult result = repo.pullOnly();
            if (result.conflict) {
                postConflict(result.conflicts, result.stage);
            } else if (result.pulled) {
                appSettings.setGitLastSyncInfo(summarize(appSettings, result));
                postMessage(context.getString(R.string.git_pulled), false);
            }
        }, null);
    }

    /**
     * 冷启动时立刻拉一次远端，不等界面出现（从编辑器 / 通知 / 小组件进来时 MainActivity 可能一直不在前台）。
     * 成败只写进「最近同步」；冲突留给 onNotebookOpened，界面在时再弹对话框。
     */
    public static void onAppStarted(final Context context) {
        final AppSettings settings = AppSettings.get(context);
        remember(context);
        if (!isAvailable(settings)) {
            return;
        }
        startTimer(context);
        run(settings, (repo, appSettings) -> appSettings.setGitLastSyncInfo(
                summarize(appSettings, repo.pullOnly())), null);
    }

    public static void requestSync(final Context context, final boolean manual, final boolean silent) {
        final AppSettings settings = AppSettings.get(context);
        remember(context);
        if (!manual && !settings.isGitSyncEnabled()) {
            return;
        }
        // 用户放弃过一次合并：自动触发不再撞同一处冲突，手动一次才解除
        if (settings.isGitAutoSyncPaused() && !manual) {
            return;
        }
        warnIfAuthorMissing(settings);
        run(settings, (repo, appSettings) -> {
            final GitRepo.SyncResult result = repo.sync();
            if (result.conflict) {
                postConflict(result.conflicts, result.stage);
                return;
            }
            if (manual) {
                appSettings.setGitAutoSyncPaused(false);
            }
            appSettings.setGitLastSyncInfo(summarize(appSettings, result));
            if (!silent) {
                postMessage(summarize(appSettings, result), false);
            }
        }, silent ? null : errorPoster());
    }

    public static void requestPull(final Context context) {
        final AppSettings settings = AppSettings.get(context);
        remember(context);
        run(settings, (repo, appSettings) -> {
            final GitRepo.SyncResult result = repo.pullOnly();
            if (result.conflict) {
                postConflict(result.conflicts, result.stage);
                return;
            }
            postMessage(context.getString(result.pulled ? R.string.git_pulled : R.string.git_nothing_to_sync), false);
        }, errorPoster());
    }

    public static void requestPush(final Context context) {
        final AppSettings settings = AppSettings.get(context);
        remember(context);
        run(settings, (repo, appSettings) -> postMessage(context.getString(
                repo.pushCurrent() ? R.string.git_pushed : R.string.git_nothing_to_sync), false), errorPoster());
    }

    public static void requestCommit(final Context context) {
        requestCommit(context, false);
    }

    public static void requestCommit(final Context context, final boolean silent) {
        final AppSettings settings = AppSettings.get(context);
        remember(context);
        warnIfAuthorMissing(settings);
        run(settings, (repo, appSettings) -> postMessage(context.getString(
                repo.commitNow() ? R.string.git_committed : R.string.git_nothing_to_sync), false),
                silent ? null : errorPoster());
    }

    public static void requestStatus(final Context context, final StatusCallback callback) {
        run(AppSettings.get(context), (repo, settings) -> {
            final GitRepo.Info info = repo.status();
            final String branch = info.branch.isEmpty()
                    ? context.getString(R.string.git_status_no_branch) : info.branch;
            final String remote = repo.getRemoteNames().isEmpty() ? ""
                    : repo.getRemoteUrl(repo.getRemoteNames().get(0));
            callback.callback(context.getString(R.string.git_status_info, branch, info.ahead,
                    info.behind, info.uncommitted, info.conflicts.size())
                    + (remote.isEmpty() ? "" : "\n" + remote));
        }, errorPoster());
    }

    public static void requestAbortMerge(final Context context) {
        run(AppSettings.get(context), (repo, settings) -> {
            repo.abortMerge();
            // 放弃合并后暂停自动同步，否则每次定时都会撞回同一处冲突
            settings.setGitAutoSyncPaused(true);
            postMessage(context.getString(R.string.git_aborted_merge) + "\n"
                    + context.getString(R.string.git_sync_paused), true);
        }, errorPoster());
    }

    public static void requestResolveConflicts(final Context context, final String strategy) {
        run(AppSettings.get(context), (repo, settings) -> {
            final List<String> conflicts = new ArrayList<>(repo.status().conflicts);
            repo.resolveConflicts(strategy, conflicts);
            if (repo.completeMergeIfResolved()) {
                repo.pushCurrent();
                postMessage(context.getString(R.string.git_conflict_resolved), false);
            } else {
                postMessage(context.getString(R.string.git_conflict_message), true);
            }
        }, errorPoster());
    }

    public static void requestClone(final AppSettings settings, final String url, final File targetDir,
                                   @Nullable final String branch, final CloneCallback callback) {
        BACKGROUND.execute(() -> {
            try {
                callback.callback(GitRepo.cloneInto(settings, url, targetDir, branch).getWorkTree(), null);
            } catch (Exception e) {
                callback.callback(null, shortText(e));
            }
        });
    }

    /**
     * 一次后台任务：仓库打不开或已有任务在跑就什么都不做。
     * onError 为空时失败只记进「最近同步」摘要，不打扰用户。
     */
    private static void run(final AppSettings settings, final Task task, @Nullable final Consumer<String> onError) {
        // 手动动作只看「笔记本是不是仓库」，自动路径才受同步开关约束。
        // 这两处早退以前是彻底无声的：用户在设置页按了推送，结果既没成功提示也没失败提示。
        // 手动/非静默的请求要把原因说出来，自动请求（onError 为空）继续安静跳过。
        if (settings.getGitRepositoryDirectory() == null) {
            if (onError != null) {
                postMessage(settings.getContext().getString(R.string.git_not_a_repository), true);
            }
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            if (onError != null) {
                postMessage(settings.getContext().getString(R.string.git_sync_busy), true);
            }
            return;
        }
        BACKGROUND.execute(() -> {
            Exception failure = null;
            try {
                final GitRepo repo = openRepo(settings);
                if (repo != null) {
                    task.run(repo, settings);
                }
            } catch (Exception e) {
                failure = e;
            } finally {
                BUSY.set(false);
            }
            if (failure != null) {
                settings.setGitLastSyncInfo(shortText(failure));
                if (onError != null) {
                    onError.accept(failure.getMessage() == null ? failure.toString() : failure.getMessage());
                }
            }
        });
    }

    private static Consumer<String> errorPoster() {
        return text -> postMessage(text, true);
    }

    /**
     * 作者没填就把话说清楚。git 本身不报错（会用默认身份提交），所以这条不拦同步，
     * 只是把「忘了填作者」从一个看不见的坑变成一句能看见的提示。
     */
    private static void warnIfAuthorMissing(final AppSettings settings) {
        if (settings.isGitAuthorConfigured() || !_authorHinted.compareAndSet(false, true)) {
            return;
        }
        final String text = settings.getContext().getString(R.string.git_author_missing);
        settings.setGitLastSyncInfo(timestamp() + " · " + text);
        postMessage(text, true);
    }

    /** Worker 本身就在后台线程，这里直接跑完一次同步；返回 false 表示已有同步在跑，应该退避重试 */
    public static boolean runBackgroundSync(final AppSettings settings) throws IOException {
        if (!BUSY.compareAndSet(false, true)) {
            return false;
        }
        warnIfAuthorMissing(settings);
        try {
            final GitRepo repo = openRepo(settings);
            if (repo == null) {
                return true;
            }
            final GitRepo.SyncResult result = repo.sync();
            settings.setGitLastSyncInfo(summarize(settings, result));
            if (result.conflict) {
                postConflict(result.conflicts, result.stage);
            }
            return true;
        } finally {
            BUSY.set(false);
        }
    }

    @Nullable
    private static GitRepo openRepo(final AppSettings settings) {
        final File directory = settings.getGitRepositoryDirectory();
        if (directory == null) {
            return null;
        }
        try {
            return new GitRepo(settings, directory);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isInside(final File parent, final File file) {
        final String root = canonical(parent);
        final String path = canonical(file);
        return root.length() > 0 && (path.equals(root) || path.startsWith(root + File.separator));
    }

    private static String canonical(final File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    /** 一次同步的摘要，既用于提示，也存在设置里当最近状态 */
    private static String summarize(final AppSettings settings, final GitRepo.SyncResult result) {
        final List<String> parts = new ArrayList<>();
        if (result.committed) {
            parts.add(settings.getContext().getString(R.string.git_committed));
        }
        if (result.pulled) {
            parts.add(settings.getContext().getString(R.string.git_pulled));
        }
        if (result.resolved) {
            parts.add(settings.getContext().getString(R.string.git_conflict_resolved));
        }
        if (result.pushed) {
            parts.add(settings.getContext().getString(R.string.git_pushed));
        }
        if (parts.isEmpty()) {
            parts.add(settings.getContext().getString(R.string.git_nothing_to_sync));
        }
        return timestamp() + " · " + TextUtils.join(" · ", parts);
    }

    static String shortText(final Exception e) {
        final String text = e.getMessage() == null ? e.toString() : e.getMessage();
        final String line = text.split("\n")[0].trim();
        return timestamp() + " · " + (line.length() > 200 ? line.substring(0, 200) : line);
    }

    private static String timestamp() {
        return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(new Date());
    }

    private static void postMessage(final String text, final boolean isError) {
        MAIN.post(() -> {
            final Ui ui = _uiRef == null ? null : _uiRef.get();
            if (ui != null) {
                ui.onGitMessage(text, isError);
                return;
            }
            // 没有界面可挂（设置页、编辑器、冷启动）时用 Toast 兜底，别让结果凭空消失
            final Context context = _appContext;
            if (context != null) {
                Toast.makeText(context, isError ? context.getString(R.string.git_sync_failed, text) : text,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void postConflict(final List<String> paths, @Nullable final String stage) {
        final List<String> copy = new ArrayList<>(paths);
        MAIN.post(() -> {
            final Ui ui = _uiRef == null ? null : _uiRef.get();
            if (ui != null) {
                ui.onGitConflict(copy, stage == null ? "merge" : stage);
                return;
            }
            final Context context = _appContext;
            if (context != null) {
                Toast.makeText(context, context.getString(R.string.git_conflict_message) + "\n"
                        + TextUtils.join("\n", copy), Toast.LENGTH_LONG).show();
            }
        });
    }
}
