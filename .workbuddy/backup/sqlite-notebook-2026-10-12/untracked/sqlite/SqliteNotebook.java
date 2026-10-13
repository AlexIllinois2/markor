/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.sqlite;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite 笔记本会话: 把数据库内容映射到应用内工作区文件夹, 监听工作区变化并与数据库双向同步。
 * 应用内其他部分只感知到普通文件夹, 体验与普通笔记本一致。
 * <p>
 * 同步快照存在应用私有目录的侧文件里而不是数据库内 —— 库文件要与 vsnote 通用，
 * 不能塞进 Markor 专有的表 (加密库里明文路径表还会泄露树形结构)。
 */
public final class SqliteNotebook {

    public enum State { OPENING, OPEN, FAILED }

    public enum ActivateResult { OK, PASSWORD_REQUIRED, WRONG_PASSWORD, NOT_NOTEBOOK_DB, LEGACY_DB, IO_ERROR }

    private static final long DEBOUNCE_MS = 800;
    private static final int DIR_STATE_MTIME = 0;
    private static final int DIR_STATE_SIZE = -1;

    private static final Object ACTIVATE_LOCK = new Object();
    private static volatile SqliteNotebook sActive;

    private final Context _appContext;
    private final File _dbFile;
    private final File _workspace;
    private final ExecutorService _executor = Executors.newSingleThreadExecutor();
    private final Handler _mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, FileObserver> _watchers = new HashMap<>();
    private final Runnable _reconcileNow = this::queueReconcile;

    private volatile SqliteNotebookDb _db;
    private volatile State _state = State.OPENING;
    private volatile boolean _closed = false;

    private SqliteNotebook(@NonNull final Context appContext, @NonNull final File dbFile) {
        _appContext = appContext;
        _dbFile = dbFile;
        _workspace = SqliteNotebookDb.getWorkspaceDir(appContext, dbFile);
    }

    //########################
    //## Session control
    //########################

    /** 打开 AppSettings 里配置的笔记本 (应用启动 / 主界面恢复时用) */
    public static void activate(@NonNull final Context context,
                                @Nullable final GsCallback.a1<ActivateResult> onDone) {
        final AppSettings as = AppSettings.get(context.getApplicationContext());
        open(context, as.getSqliteNotebookFile(), null, onDone);
    }

    /**
     * 打开指定的 db 文件并成为当前会话。成功与否都由 onDone 回调原因，
     * 调用方只在 OK 之后才把它写进 AppSettings (失败的库不该占着笔记本位置)。
     *
     * @param password 本次输入的口令；null 时只用本机记住的派生密钥
     */
    public static void open(@NonNull final Context context, @Nullable final File dbFile,
                            @Nullable final char[] password,
                            @Nullable final GsCallback.a1<ActivateResult> onDone) {
        final Context app = context.getApplicationContext();
        synchronized (ACTIVATE_LOCK) {
            if (dbFile == null) {
                closeInternal();
                notifyDone(onDone, ActivateResult.OK);
                return;
            }
            if (password == null && sActive != null && !sActive._closed && sActive._dbFile.equals(dbFile)
                    && sActive._state != State.FAILED) {
                notifyDone(onDone, ActivateResult.OK);
                return;
            }
            closeInternal();
            sActive = new SqliteNotebook(app, dbFile);
            sActive.start(password, onDone);
        }
    }

    public static void deactivate() {
        synchronized (ACTIVATE_LOCK) {
            closeInternal();
        }
    }

    private static void closeInternal() {
        final SqliteNotebook sn = sActive;
        sActive = null;
        if (sn != null) {
            sn.shutdown();
        }
    }

    private static void notifyDone(@Nullable final GsCallback.a1<ActivateResult> onDone,
                                   @NonNull final ActivateResult result) {
        if (onDone == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                onDone.callback(result);
            } catch (Exception ignored) {
            }
        });
    }

    private void start(@Nullable final char[] password, @Nullable final GsCallback.a1<ActivateResult> onDone) {
        _executor.execute(() -> {
            final AppSettings as = AppSettings.get(_appContext);
            // Not final: javac forbids assigning a blank final in a catch block
            // when the try block also assigns it (definite-unassignment rule).
            SqliteNotebookDb db = null;
            ActivateResult result = ActivateResult.IO_ERROR;
            try {
                db = SqliteNotebookDb.openExisting(_dbFile, password, as.getSqliteNotebookKey(_dbFile));
                result = ActivateResult.OK;
            } catch (SqliteNotebookDb.NotebookDbException e) {
                Log.e(SqliteNotebook.class.getName(), "open notebook db failed: " + e, e);
                // 记住的密钥解不开 (口令改过 / 库换过) 就丢弃缓存, 回到重新输入
                if (e.kind == SqliteNotebookDb.Kind.PASSWORD_REQUIRED
                        || e.kind == SqliteNotebookDb.Kind.WRONG_PASSWORD) {
                    as.setSqliteNotebookKey(_dbFile, null);
                }
                db = null;
                result = toActivateResult(e.kind);
            } catch (Exception e) {
                Log.e(SqliteNotebook.class.getName(), "open notebook db failed: " + e, e);
                db = null;
                result = ActivateResult.IO_ERROR;
            }
            _db = db;
            _state = db == null ? State.FAILED : State.OPEN;
            if (db != null) {
                // 工作区目录只在库真的打开后才有：打不开就不该留下一个幻影笔记本
                //noinspection ResultOfMethodCallIgnored
                _workspace.mkdirs();
                final String derivedKey = db.getDerivedKeyBase64();
                if (derivedKey != null) {
                    as.setSqliteNotebookKey(_dbFile, derivedKey);
                }
                reconcileSafe();
                startWatchers();
            }
            notifyDone(onDone, result);
        });
    }

    @NonNull
    private static ActivateResult toActivateResult(@NonNull final SqliteNotebookDb.Kind kind) {
        switch (kind) {
            case PASSWORD_REQUIRED:
                return ActivateResult.PASSWORD_REQUIRED;
            case WRONG_PASSWORD:
                return ActivateResult.WRONG_PASSWORD;
            case NOT_NOTEBOOK_DB:
                return ActivateResult.NOT_NOTEBOOK_DB;
            case LEGACY_DB:
                return ActivateResult.LEGACY_DB;
            default:
                return ActivateResult.IO_ERROR;
        }
    }

    private void shutdown() {
        if (_closed) {
            return;
        }
        _closed = true;
        _mainHandler.removeCallbacks(_reconcileNow);
        try {
            // 收尾同步, 确保最近的修改写入数据库
            _executor.submit(this::reconcileSafe).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        stopWatchers();
        final SqliteNotebookDb db = _db;
        _db = null;
        if (db != null) {
            db.close();
        }
        _executor.shutdown();
    }

    //########################
    //## File watching
    //########################

    private static final int WATCH_MASK = FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE | FileObserver.MODIFY
            | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;

    private void startWatchers() {
        addWatcher(_workspace);
    }

    private void addWatcher(final File dir) {
        if (_closed || dir == null || !dir.isDirectory()) {
            return;
        }
        final String path = dir.getAbsolutePath();
        synchronized (_watchers) {
            if (_watchers.containsKey(path)) {
                return;
            }
        }
        try {
            final FileObserver observer = new FileObserver(path, WATCH_MASK) {
                @Override
                public void onEvent(final int event, @Nullable final String p) {
                    if (_closed) {
                        return;
                    }
                    if (p != null) {
                        final File child = new File(dir, p);
                        if ((event & FileObserver.CREATE) != 0 && child.isDirectory()) {
                            addWatcher(child);
                        }
                        if ((event & (FileObserver.DELETE | FileObserver.MOVED_FROM
                                | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0) {
                            removeWatcher(child.getAbsolutePath());
                        }
                    }
                    scheduleReconcile();
                }
            };
            observer.startWatching();
            synchronized (_watchers) {
                _watchers.put(path, observer);
            }
        } catch (Exception ignored) {
        }
    }

    private void removeWatcher(final String path) {
        final FileObserver observer;
        synchronized (_watchers) {
            observer = _watchers.remove(path);
        }
        if (observer != null) {
            try {
                observer.stopWatching();
            } catch (Exception ignored) {
            }
        }
    }

    private void stopWatchers() {
        synchronized (_watchers) {
            for (final FileObserver o : _watchers.values()) {
                try {
                    o.stopWatching();
                } catch (Exception ignored) {
                }
            }
            _watchers.clear();
        }
    }

    private void scheduleReconcile() {
        _mainHandler.removeCallbacks(_reconcileNow);
        _mainHandler.postDelayed(_reconcileNow, DEBOUNCE_MS);
    }

    private void queueReconcile() {
        if (!_closed) {
            _executor.execute(this::reconcileSafe);
        }
    }

    private void reconcileSafe() {
        if (_closed) {
            return;
        }
        final SqliteNotebookDb db = _db;
        if (db == null) {
            return;
        }
        try {
            reconcile(db);
        } catch (Exception e) {
            Log.e(SqliteNotebook.class.getName(), "reconcile failed: " + e, e);
        }
    }

    //########################
    //## Two-way sync
    //########################

    private void reconcile(final SqliteNotebookDb db) {
        final Map<String, SqliteNotebookDb.Node> dbTree = db.loadTree();
        final Map<String, long[]> fsState = new HashMap<>();
        scanWorkspace(_workspace, "", fsState);
        final Map<String, long[]> snap = loadSnapshot();

        final Set<String> allPaths = new HashSet<>();
        allPaths.addAll(dbTree.keySet());
        allPaths.addAll(fsState.keySet());
        allPaths.addAll(snap.keySet());
        final List<String> paths = new ArrayList<>(allPaths);
        Collections.sort(paths, (a, b) -> {
            final int da = depth(a), dbd = depth(b);
            if (da != dbd) {
                return da - dbd; // 浅路径优先, 保证父先于子
            }
            return a.compareTo(b);
        });

        final Map<String, long[]> newSnap = new HashMap<>();
        for (final String path : paths) {
            final SqliteNotebookDb.Node node = dbTree.get(path);
            final long[] fs = fsState.get(path);
            final long[] sn = snap.get(path);
            try {
                syncPath(db, path, node, fs, sn, newSnap);
            } catch (Exception e) {
                Log.e(SqliteNotebook.class.getName(), "sync path failed: " + path + " " + e);
            }
        }
        saveSnapshot(newSnap);
        syncWatchersWithWorkspace();
    }

    private void syncPath(final SqliteNotebookDb db, final String path,
                          @Nullable final SqliteNotebookDb.Node node, @Nullable final long[] fs,
                          @Nullable final long[] sn, final Map<String, long[]> newSnap) throws IOException {
        final boolean isDirFs = fs != null && fs[1] == DIR_STATE_SIZE;

        if (node == null && fs == null) {
            return; // 双侧皆无
        }

        if (node == null) {
            if (sn == null || fs[0] != sn[0] || fs[1] != sn[1]) {
                // 文件系统新增, 或数据库已删除但文件又被改动 -> 以文件系统为准
                pushToDb(db, path, fs);
                newSnap.put(path, stateOfFs(path, fs));
            } else {
                // 数据库已删除 -> 删除镜像文件
                SqliteNotebookDb.deleteRecursively(new File(_workspace, path));
                newSnap.remove(path);
            }
            return;
        }

        if (fs == null) {
            final boolean dbChanged = !node.isFolder() && sn != null && node.updateTime != sn[0];
            if (sn == null || dbChanged) {
                // 数据库新增, 或文件系统删除后数据库又有改动 -> 落盘
                newSnap.put(path, materialize(db, path, node));
            } else {
                // 文件系统已删除 -> 删除数据库节点
                db.deleteNodeCascade(node.id);
                newSnap.remove(path);
            }
            return;
        }

        // 两侧都存在
        final boolean isDirNode = node.isFolder();
        if (isDirNode != isDirFs) {
            // 类型变化, 以文件系统为准
            db.deleteNodeCascade(node.id);
            SqliteNotebookDb.deleteRecursively(new File(_workspace, path));
            pushToDb(db, path, fs);
            newSnap.put(path, stateOfFs(path, fs));
            return;
        }

        if (isDirNode) {
            newSnap.put(path, dirState());
            return;
        }

        final boolean fsChanged = sn == null || fs[0] != sn[0] || fs[1] != sn[1];
        final boolean dbChanged = sn == null || node.updateTime != sn[0];
        if (!fsChanged && !dbChanged) {
            newSnap.put(path, new long[]{sn[0], sn[1]});
            return;
        }

        if (fsChanged && dbChanged && fs[0] < node.updateTime) {
            // 两侧同时改动, 数据库较新 -> 落盘
            newSnap.put(path, materialize(db, path, node));
        } else {
            // 以文件系统为准写入数据库 (同时改动时文件系统较新或相等)
            db.writeFile(new File(_workspace, path), node, fs[0]);
            newSnap.put(path, new long[]{fs[0], fs[1]});
        }
    }

    private static long[] dirState() {
        return new long[]{DIR_STATE_MTIME, DIR_STATE_SIZE};
    }

    private long[] stateOfFs(final String path, @NonNull final long[] fs) {
        return fs[1] == DIR_STATE_SIZE ? dirState() : new long[]{fs[0], fs[1]};
    }

    private static int depth(final String path) {
        int d = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                d++;
            }
        }
        return d;
    }

    /**
     * 文件系统 -> 数据库 (路径不存在时新建, 需要时补齐父目录节点)
     */
    private void pushToDb(final SqliteNotebookDb db, final String path, final long[] fs) throws IOException {
        final List<String> parts = SqliteNotebookDb.splitPath(path);
        if (parts.isEmpty()) {
            return;
        }
        final File file = new File(_workspace, path);
        final long mtime = file.lastModified();
        final Long parent = db.ensureChain(parts.subList(0, parts.size() - 1), mtime);
        final String name = parts.get(parts.size() - 1);
        final SqliteNotebookDb.Node existing = db.getChild(parent, name);

        if (fs[1] == DIR_STATE_SIZE) {
            if (existing == null) {
                final long id = db.insertNode(parent, true, name, mtime, mtime);
                if (id >= 0) {
                    addWatcher(new File(_workspace, path));
                }
            }
            return;
        }
        if (existing == null) {
            final long id = db.insertNode(parent, false, name, mtime, mtime);
            if (id >= 0) {
                db.putContent(id, name, SqliteNotebookDb.readFileBytes(file));
            }
        } else {
            db.writeFile(file, existing, mtime);
        }
    }

    /**
     * 数据库 -> 文件系统 (落盘)。返回落盘后应记录的快照 (与文件系统实际状态一致, 避免反复覆盖)
     */
    private long[] materialize(final SqliteNotebookDb db, final String path,
                               @NonNull final SqliteNotebookDb.Node node) {
        final File outFile = new File(_workspace, path);
        if (node.isFolder()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.mkdirs();
            addWatcher(outFile);
            return dirState();
        }
        //noinspection ResultOfMethodCallIgnored
        if (outFile.getParentFile() != null) {
            //noinspection ResultOfMethodCallIgnored
            outFile.getParentFile().mkdirs();
        }
        try {
            SqliteNotebookDb.writeFileBytes(outFile, db.readContent(node));
            if (!outFile.setLastModified(node.updateTime)) {
                // 文件系统不支持改 mtime 时, 让数据库迁就它, 避免每轮都判定为有改动
                db.updateFileNode(node, outFile.lastModified(), null);
            }
        } catch (Exception e) {
            Log.e(SqliteNotebook.class.getName(), "materialize failed: " + path + " " + e);
        }
        return new long[]{outFile.lastModified(), outFile.length()};
    }

    //########################
    //## Workspace scan
    //########################

    private void scanWorkspace(final File dir, final String relParent, final Map<String, long[]> out) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (final File f : children) {
            // 工作区是应用私有目录，只放落盘出来的节点：这里不能按名字跳过，
            // 否则名字以点开头的节点会被当成「文件系统已删」而从数据库里删掉
            final String rel = SqliteNotebookDb.joinPath(relParent, f.getName());
            if (f.isDirectory()) {
                out.put(rel, dirState());
                scanWorkspace(f, rel, out);
            } else if (f.isFile()) {
                out.put(rel, new long[]{f.lastModified(), f.length()});
            }
        }
    }

    private void syncWatchersWithWorkspace() {
        if (_closed) {
            return;
        }
        synchronized (_watchers) {
            for (final String watched : new ArrayList<>(_watchers.keySet())) {
                if (!new File(watched).isDirectory()) {
                    removeWatcher(watched);
                }
            }
        }
        addWatcherRecursive(_workspace, new HashSet<>());
    }

    private void addWatcherRecursive(final File dir, final Set<String> visited) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        final String path;
        try {
            path = dir.getCanonicalPath();
        } catch (Exception e) {
            return;
        }
        if (!visited.add(path)) {
            return;
        }
        addWatcher(dir);
        final File[] children = dir.listFiles();
        if (children != null) {
            for (final File f : children) {
                if (f.isDirectory()) {
                    addWatcherRecursive(f, visited);
                }
            }
        }
    }

    //########################
    //## Sync snapshot (sidecar file, not inside the db)
    //########################

    @NonNull
    private File snapshotFile() {
        return new File(_workspace.getParentFile(), _workspace.getName() + ".sync");
    }

    @NonNull
    private Map<String, long[]> loadSnapshot() {
        final Map<String, long[]> state = new HashMap<>();
        final File file = snapshotFile();
        if (!file.isFile()) {
            return state;
        }
        try {
            final String text = new String(SqliteNotebookDb.readFileBytes(file), "UTF-8");
            for (final String line : text.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                final String[] cols = line.split("\t");
                if (cols.length == 3) {
                    state.put(unescape(cols[2]), new long[]{Long.parseLong(cols[0]), Long.parseLong(cols[1])});
                }
            }
        } catch (Exception e) {
            Log.e(SqliteNotebook.class.getName(), "load snapshot failed: " + e, e);
        }
        return state;
    }

    private void saveSnapshot(@NonNull final Map<String, long[]> state) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, long[]> e : state.entrySet()) {
            sb.append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\t')
                    .append(escape(e.getKey())).append('\n');
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            _workspace.getParentFile().mkdirs();
            SqliteNotebookDb.writeFileBytes(snapshotFile(), sb.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(SqliteNotebook.class.getName(), "save snapshot failed: " + e, e);
        }
    }

    private static String escape(final String path) {
        return path.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String unescape(final String text) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                final char next = text.charAt(++i);
                sb.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
