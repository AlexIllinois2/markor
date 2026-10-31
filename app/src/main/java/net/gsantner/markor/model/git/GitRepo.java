/*#######################################################
 *
 *   Git support for notebook folders.
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.git;

import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import net.gsantner.markor.model.AppSettings;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 笔记本目录（一个 git 工作区）上的 git 操作。
 * 同步顺序固定为 commit 本地改动 → fetch → merge → push：先保住本地成果，
 * 再和远端合并，最后才推送。合并冲突不自动取舍、也不提示用户，
 * 冲突文件连同标记原样提交，保证远端已有的内容不会被覆盖掉。
 */
public class GitRepo {

    /** push 被远端拒绝时最多再走几轮 fetch+merge+commit+push */
    private static final int MAX_PUSH_ATTEMPTS = 3;

    private final Git _git;
    private final AppSettings _settings;

    public static class Info {
        public String branch = "";
        @Nullable
        public String upstream = null;
        public int ahead = 0;
        public int behind = 0;
        public int uncommitted = 0;
        public boolean merging = false;
        public boolean detached = false;
        public final List<String> conflicts = new ArrayList<>();

        public boolean isDirty() {
            return uncommitted > 0;
        }
    }

    public static class SyncResult {
        public boolean committed = false;
        public boolean pulled = false;
        public boolean pushed = false;
        public boolean conflict = false;
        public boolean noRemote = false;
        /** 本次合并遇到、并已原样提交的冲突文件 */
        public final List<String> conflicts = new ArrayList<>();
    }

    private static class MergeOutcome {
        boolean updated = false;
        boolean needsCommit = false;
        final List<String> conflicts = new ArrayList<>();
    }

    public GitRepo(final AppSettings settings, final File workingDirectory) throws IOException {
        try {
            _git = Git.open(workingDirectory);
        } catch (IOException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
        _settings = settings;
        GitSsh.configure(settings);
        applyAndroidRepositoryDefaults(_git.getRepository());
    }

    public static boolean isGitWorkingDirectory(@Nullable final File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        final File dotGit = new File(dir, Constants.DOT_GIT);
        if (dotGit.isDirectory()) {
            return true;
        }
        // 子模块与链接工作区的 .git 是一行 gitdir: 文本
        return dotGit.isFile() && readFirstLine(dotGit).startsWith("gitdir:");
    }

    /**
     * sdcardfs / FUSE 上的权限位与 mtime 不可信，会被读成满屏假改动；
     * JGit 的自动 gc 走 java.lang.management，在 Android 上直接抛异常。
     */
    private static void applyAndroidRepositoryDefaults(final Repository repository) {
        try {
            final StoredConfig config = repository.getConfig();
            boolean changed = false;
            if (!"false".equals(config.getString("core", null, "fileMode"))) {
                config.setBoolean("core", null, "fileMode", false);
                changed = true;
            }
            if (!"0".equals(config.getString("gc", null, "auto"))) {
                config.setInt("gc", null, "auto", 0);
                changed = true;
            }
            if (changed) {
                config.save();
            }
        } catch (IOException ignored) {
        }
    }

    public File getWorkTree() {
        return _git.getRepository().getWorkTree();
    }

    public String getRemoteUrl(@Nullable final String remote) {
        try {
            final StoredConfig config = _git.getRepository().getConfig();
            final String value = config.getString("remote", remote, "url");
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    public List<String> getRemoteNames() {
        try {
            final Set<String> names = _git.getRepository().getRemoteNames();
            return new ArrayList<>(names);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Info status() throws IOException {
        final Info info = new Info();
        final Repository repository = _git.getRepository();
        try {
            info.branch = repository.getBranch() == null ? "" : repository.getBranch();
            info.detached = info.branch.startsWith("(");
            final org.eclipse.jgit.api.Status status = _git.status().call();
            final Set<String> changed = new LinkedHashSet<>();
            changed.addAll(status.getAdded());
            changed.addAll(status.getChanged());
            changed.addAll(status.getModified());
            changed.addAll(status.getRemoved());
            changed.addAll(status.getMissing());
            changed.addAll(status.getUntracked());
            info.conflicts.addAll(status.getConflicting());
            info.uncommitted = changed.size();
            info.merging = isMerging(repository);
            info.upstream = resolveUpstream(info.branch);
            if (info.upstream != null) {
                final ObjectId head = repository.resolve(Constants.HEAD);
                final ObjectId remoteHead = repository.resolve(info.upstream);
                if (head != null && remoteHead != null) {
                    final int[] counts = countAheadBehind(head, remoteHead);
                    info.ahead = counts[0];
                    info.behind = counts[1];
                }
            }
        } catch (GitAPIException e) {
            throw toIo(e);
        }
        return info;
    }

    private static boolean isMerging(final Repository repository) {
        final RepositoryState state = repository.getRepositoryState();
        return state == RepositoryState.MERGING || state == RepositoryState.MERGING_RESOLVED;
    }

    @Nullable
    private String resolveUpstream(@Nullable final String branch) throws IOException {
        if (branch == null || branch.isEmpty() || branch.startsWith("(")) {
            return null;
        }
        final Repository repository = _git.getRepository();
        final String tracking = new BranchConfig(repository.getConfig(), branch).getTrackingBranch();
        if (tracking == null || repository.resolve(tracking) == null) {
            return null;
        }
        return tracking;
    }

    private int[] countAheadBehind(final ObjectId head, final ObjectId upstream) throws IOException {
        final Repository repository = _git.getRepository();
        final int[] result = new int[]{0, 0};
        try (RevWalk walk = new RevWalk(repository)) {
            final RevCommit local = walk.parseCommit(head);
            final RevCommit remote = walk.parseCommit(upstream);
            walk.markStart(local);
            walk.markUninteresting(remote);
            for (RevCommit ignored : walk) {
                result[0]++;
            }
            walk.reset();
            walk.markStart(remote);
            walk.markUninteresting(local);
            for (RevCommit ignored : walk) {
                result[1]++;
            }
        }
        return result;
    }

    /**
     * 完整同步，顺序固定：commit 本地 → fetch → merge → push。
     * 合并冲突不自动取舍、也不弹提示：冲突文件带着标记原样提交，
     * 然后继续推送；如果推送被远端拒绝（期间远端又前进了），
     * 就再 fetch+merge 一轮，冲突依旧原样保留，直到推送成功或达到重试上限。
     */
    public SyncResult sync() throws IOException {
        final SyncResult result = new SyncResult();
        final String remote = effectiveRemote();
        result.noRemote = remote == null;

        // ❶ 本地改动先提交：保住本地成果；上次遗留的冲突现场也原样收进索引
        result.committed = commitAllChanges(renderMessage(_settings.getGitCommitMessageTemplate()));

        if (remote == null) {
            return result;
        }

        for (int attempt = 0; attempt < MAX_PUSH_ATTEMPTS; attempt++) {
            // ❷ fetch 必须先做；拿不到远端就中止，不在过期状态上合并/推送
            fetch(remote);

            // ❸ merge：冲突保留现状，不替用户选边
            final MergeOutcome merge = mergeUpstream(remote);
            if (!merge.conflicts.isEmpty()) {
                result.conflict = true;
                result.conflicts.addAll(merge.conflicts);
            }
            if (merge.updated) {
                result.pulled = true;
            }
            if (merge.needsCommit) {
                // 冲突文件也一起 add+commit：标记内容原样进入提交，远端内容不会被覆盖
                result.committed = commitAllChanges(mergeMessage(remote)) || result.committed;
            }

            // ❹ push；被拒绝说明远端又前进了，回到 fetch/merge 再来一轮
            try {
                result.pushed = push(remote);
                return result;
            } catch (PushRejectedException e) {
                if (attempt == MAX_PUSH_ATTEMPTS - 1) {
                    throw e;
                }
            }
        }
        return result;
    }

    /** 只拉取：有本地未提交改动时先提交（安全顺序），冲突同样原样保留并提交，不提示、不取舍 */
    public SyncResult pullOnly() throws IOException {
        final SyncResult result = new SyncResult();
        final String remote = effectiveRemote();
        result.noRemote = remote == null;
        if (remote == null) {
            return result;
        }

        Info info = status();
        if (!info.conflicts.isEmpty() || isMerging(_git.getRepository())) {
            // 上次遗留的冲突现场：原样提交，避免仓库一直停在 MERGING
            result.conflict = true;
            result.conflicts.addAll(info.conflicts);
            result.committed = commitAllChanges(mergeMessage(remote));
            info = status();
        }
        if (info.isDirty()) {
            return result;
        }

        fetch(remote);
        final MergeOutcome merge = mergeUpstream(remote);
        if (!merge.conflicts.isEmpty()) {
            result.conflict = true;
            result.conflicts.addAll(merge.conflicts);
        }
        if (merge.needsCommit) {
            result.committed = commitAllChanges(mergeMessage(remote)) || result.committed;
        }
        result.pulled = merge.updated;
        return result;
    }

    public boolean commitNow() throws IOException {
        // 手动提交走同一条路径：先收新增/修改，再收删除
        return commitAllChanges(renderMessage(_settings.getGitCommitMessageTemplate()));
    }

    /** 克隆到 targetDir 并把结果仓库交给调用方；targetDir 必须不存在或为空目录 */
    public static GitRepo cloneInto(final AppSettings settings, final String url, final File targetDir,
                                    @Nullable final String branch) throws IOException {
        GitSsh.configure(settings);
        final CloneCommand command = Git.cloneRepository()
                .setURI(url).setDirectory(targetDir).setTimeout(60)
                .setCredentialsProvider(staticCredentials(settings));
        if (branch != null && !branch.trim().isEmpty()) {
            command.setBranch(branch.trim());
        }
        try {
            return new GitRepo(settings, command.call().getRepository().getWorkTree());
        } catch (GitAPIException e) {
            throw toIo(e);
        }
    }

    @Nullable
    private static CredentialsProvider staticCredentials(final AppSettings settings) {
        final String user = settings.getGitUsername();
        final String token = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? settings.getGitAccessToken() : "";
        // 没令牌就返回 null：公开仓库匿名可读，而 Android 上没有控制台，JGit 的交互式追问只会卡住线程
        return TextUtils.isEmpty(token) ? null
                : new UsernamePasswordCredentialsProvider(user.isEmpty() ? "oauth2" : user, token);
    }

    private String effectiveRemote() {
        final List<String> remotes = getRemoteNames();
        if (remotes.isEmpty()) {
            return null;
        }
        final String configured = _settings.getGitRemoteName();
        return remotes.contains(configured) ? configured : remotes.get(0);
    }

    private boolean commitAllChanges(final String message) throws IOException {
        try {
            // git add -A：JGit 的 add 不收删除，得再走一遍 update 模式
            _git.add().addFilepattern(".").call();
            _git.add().setUpdate(true).addFilepattern(".").call();
            // 普通提交不接受空提交；合并提交即使树没有变化也要落下来，否则 MERGE_HEAD 会一直留着
            final boolean merging = isMerging(_git.getRepository());
            _git.commit().setMessage(message)
                    .setAllowEmpty(merging)
                    .setAuthor(_settings.getGitAuthorName(), _settings.getGitAuthorEmail())
                    .setCommitter(_settings.getGitAuthorName(), _settings.getGitAuthorEmail())
                    .call();
            return true;
        } catch (EmptyCommitException e) {
            return false;
        } catch (GitAPIException e) {
            throw toIo(e);
        }
    }

    private void fetch(final String remote) throws IOException {
        try {
            _git.fetch().setRemote(remote).setTimeout(30)
                    .setCredentialsProvider(credentials()).call();
        } catch (GitAPIException e) {
            throw toIo(e);
        }
    }

    /**
     * fetch 之后的合并。setCommit(false)：合并结果留在索引里，
     * 由 commitAllChanges 统一提交——这样冲突和普通合并走同一条路径，
     * 冲突文件（含标准标记）也就原样进入提交，不做任何取舍。
     */
    private MergeOutcome mergeUpstream(final String remote) throws IOException {
        final MergeOutcome outcome = new MergeOutcome();
        final Repository repository = _git.getRepository();
        final String branch = repository.getBranch();
        if (branch == null || branch.isEmpty() || branch.startsWith("(")) {
            return outcome;
        }
        final String target = resolveMergeTarget(remote, branch);
        final ObjectId targetId = target == null ? null : repository.resolve(target);
        if (targetId == null) {
            return outcome;
        }
        try {
            final MergeResult merged = _git.merge().setCommit(false).include(targetId).call();
            final MergeResult.MergeStatus mergeStatus = merged.getMergeStatus();
            if (mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                outcome.needsCommit = true;
                // 冲突路径直接从索引取，交给 commitAllChanges 原样提交
                outcome.conflicts.addAll(_git.status().call().getConflicting());
                return outcome;
            }
            if (mergeStatus == MergeResult.MergeStatus.FAILED
                    || mergeStatus == MergeResult.MergeStatus.ABORTED
                    || mergeStatus == MergeResult.MergeStatus.NOT_SUPPORTED
                    || mergeStatus == MergeResult.MergeStatus.CHECKOUT_CONFLICT) {
                throw new IOException("merge " + mergeStatus);
            }
            if (mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                return outcome;
            }
            outcome.updated = true;
            // 快进已经移动了 HEAD；其余情况（MERGED_NOT_COMMITTED 等）还需要一条提交
            outcome.needsCommit = mergeStatus != MergeResult.MergeStatus.FAST_FORWARD;
            return outcome;
        } catch (GitAPIException e) {
            throw toIo(e);
        }
    }

    @Nullable
    private String resolveMergeTarget(final String remote, final String branch) throws IOException {
        final Repository repository = _git.getRepository();
        final String tracking = new BranchConfig(repository.getConfig(), branch).getTrackingBranch();
        if (tracking != null && repository.resolve(tracking) != null) {
            return tracking;
        }
        final String name = Constants.R_REMOTES + remote + "/" + branch;
        return repository.resolve(name) == null ? null : name;
    }

    private String mergeMessage(final String remote) {
        return "markor: merge " + remote;
    }

    private boolean push(final String remote) throws IOException {
        final Repository repository = _git.getRepository();
        final String branch = repository.getBranch();
        if (branch == null || branch.isEmpty() || branch.startsWith("(")) {
            return false;
        }
        final boolean hadUpstream = resolveUpstream(branch) != null;
        try {
            final PushCommand command = _git.push().setRemote(remote)
                    .setTimeout(30).setCredentialsProvider(credentials());
            if (!hadUpstream) {
                command.setRefSpecs(new RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch));
            }
            for (PushResult result : command.call()) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    final RemoteRefUpdate.Status status = update.getStatus();
                    if (status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE) {
                        continue;
                    }
                    final String message = "push " + status
                            + (update.getMessage() == null ? "" : ": " + update.getMessage());
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                            || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        throw new PushRejectedException(message);
                    }
                    throw new IOException(message);
                }
            }
        } catch (GitAPIException e) {
            throw toIo(e);
        }
        if (!hadUpstream) {
            setUpstream(remote, branch);
        }
        return true;
    }

    /** 远端在 fetch 之后又前进了：值得重新 fetch+merge 后再推一次 */
    private static class PushRejectedException extends IOException {
        PushRejectedException(final String message) {
            super(message);
        }
    }

    /** JGit 的 push 没有 -u，首推成功后把上游写回配置，下次 pull/push 才知道推哪儿 */
    private void setUpstream(final String remote, final String branch) {
        try {
            final StoredConfig config = _git.getRepository().getConfig();
            config.setString("branch", branch, "remote", remote);
            config.setString("branch", branch, "merge", Constants.R_HEADS + branch);
            config.save();
        } catch (IOException ignored) {
        }
    }

    @Nullable
    private CredentialsProvider credentials() {
        return staticCredentials(_settings);
    }

    public static String renderMessage(final String template) {
        final String now = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(new Date());
        return template.replace("{now}", now);
    }

    private static String readFirstLine(final File file) {
        try (InputStream in = new FileInputStream(file)) {
            final byte[] buffer = new byte[256];
            final int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).split("\n")[0].trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static IOException toIo(final GitAPIException e) {
        return new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }
}
