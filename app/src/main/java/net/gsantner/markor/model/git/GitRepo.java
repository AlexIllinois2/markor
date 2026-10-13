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
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
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
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 笔记本目录（一个 git 工作区）上的 git 操作。
 * 同步顺序与冲突语义跟随 vsnote 的 git_auto_sync，让两个端能操作同一个仓库：
 * commit 本地改动 → pull（merge，不是 rebase）→ push；冲突按策略处理，处理不了就留现场。
 */
public class GitRepo {

    public static final List<String> CONFLICT_STRATEGIES =
            Collections.unmodifiableList(Arrays.asList("markers", "union", "ours", "theirs"));

    private static final String MARKER_OURS = "<<<<<<<";
    private static final String MARKER_BASE = "|||||||";
    private static final String MARKER_SEP = "=======";
    private static final String MARKER_THEIRS = ">>>>>>>";

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

        public boolean hasUpstream() {
            return upstream != null;
        }

        public boolean isDirty() {
            return uncommitted > 0;
        }
    }

    public static class SyncResult {
        public boolean committed = false;
        public boolean pulled = false;
        public boolean pushed = false;
        public boolean resolved = false;
        public boolean conflict = false;
        public boolean noRemote = false;
        @Nullable
        public String stage = null;
        public final List<String> conflicts = new ArrayList<>();
    }

    private enum PullKind {CLEAN, NO_UPSTREAM, AUTO_RESOLVED, CONFLICT}

    private static class PullOutcome {
        PullKind kind = PullKind.CLEAN;
        boolean updated = false;
        final List<String> conflicts = new ArrayList<>();
    }

    public GitRepo(final AppSettings settings, final File workingDirectory) throws IOException {
        try {
            _git = Git.open(workingDirectory);
        } catch (IOException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
        _settings = settings;
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
     * 完整同步。返回的 SyncResult 描述这一步实际做了什么，冲突时 stage 说明是本次合并造成
     * 还是上次遗留，UI 据此决定弹哪种提示。
     */
    public SyncResult sync() throws IOException {
        final SyncResult result = new SyncResult();
        // 上次遗留的冲突：用户已在编辑器里改好（文件内不再有标记）的，算解决并补合并提交
        completeMergeIfResolved();
        Info info = status();
        if (!info.conflicts.isEmpty()) {
            result.conflict = true;
            result.stage = "unresolved";
            result.conflicts.addAll(info.conflicts);
            return result;
        }

        final String remote = effectiveRemote();
        result.noRemote = remote == null;

        // ❶ 本地改动先提交：保住本地成果，也给后面的合并一个干净基线
        result.committed = commitAllChanges(renderMessage(_settings.getGitCommitMessageTemplate()));

        if (!result.noRemote) {
            // ❷ 先拉后推：顺序反了会被远端以 non-fast-forward 拒绝
            final PullOutcome pull = pullMerge(remote, _settings.getGitConflictStrategy());
            if (pull.kind == PullKind.CONFLICT) {
                info = status();
                result.conflict = true;
                result.stage = "merge";
                result.conflicts.addAll(info.conflicts.isEmpty() ? pull.conflicts : info.conflicts);
                return result;
            }
            result.pulled = pull.kind == PullKind.CLEAN || pull.kind == PullKind.AUTO_RESOLVED;
            result.resolved = pull.kind == PullKind.AUTO_RESOLVED;

            // ❸ 定时同步空转时不空推，省一次网络往返与凭据询问
            info = status();
            if (info.ahead > 0 || !info.hasUpstream()) {
                result.pushed = push(remote);
            }
        }
        return result;
    }

    /** 只拉取：工作区脏就先跳过，交给同步先提交再拉（与 vsnote 的 git_auto_pull 同语义） */
    public SyncResult pullOnly() throws IOException {
        final SyncResult result = new SyncResult();
        final String remote = effectiveRemote();
        result.noRemote = remote == null;
        if (result.noRemote) {
            return result;
        }
        Info info = status();
        if (!info.conflicts.isEmpty()) {
            result.conflict = true;
            result.stage = "unresolved";
            result.conflicts.addAll(info.conflicts);
            return result;
        }
        if (info.isDirty()) {
            return result;
        }
        final PullOutcome pull = pullMerge(remote, _settings.getGitConflictStrategy());
        if (pull.kind == PullKind.CONFLICT) {
            info = status();
            result.conflict = true;
            result.stage = "merge";
            result.conflicts.addAll(info.conflicts.isEmpty() ? pull.conflicts : info.conflicts);
            return result;
        }
        result.pulled = pull.updated;
        result.resolved = pull.kind == PullKind.AUTO_RESOLVED;
        return result;
    }

    public boolean pushCurrent() throws IOException {
        final String remote = effectiveRemote();
        return remote != null && push(remote);
    }

    public boolean commitNow() throws IOException {
        // 手动提交走同一条路径：先收新增/修改，再收删除
        return commitAllChanges(renderMessage(_settings.getGitCommitMessageTemplate()));
    }

    /** 克隆到 targetDir 并把结果仓库交给调用方；targetDir 必须不存在或为空目录 */
    public static GitRepo cloneInto(final AppSettings settings, final String url, final File targetDir,
                                    @Nullable final String branch) throws IOException {
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
    }    private String effectiveRemote() {
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
            _git.commit().setMessage(message)
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

    private PullOutcome pullMerge(final String remote, final String strategy) throws IOException {
        final PullOutcome outcome = new PullOutcome();
        final CredentialsProvider credentials = credentials();
        try {
            // fetch 失败不致命（离线场景），最终结论交给 pull
            _git.fetch().setRemote(remote).setTimeout(30)
                    .setCredentialsProvider(credentials).call();
        } catch (Exception ignored) {
        }

        final Info info = status();
        if (info.branch.isEmpty() || info.detached) {
            outcome.kind = PullKind.NO_UPSTREAM;
            return outcome;
        }
        try {
            final PullCommand command = _git.pull().setRebase(false).setTimeout(30)
                    .setCredentialsProvider(credentials);
            if (!info.hasUpstream()) {
                command.setRemote(remote).setRemoteBranchName(info.branch);
            }
            final PullResult pulled = command.call();
            final MergeResult merged = pulled.getMergeResult();
            final MergeResult.MergeStatus mergeStatus = merged == null ? null : merged.getMergeStatus();
            if (mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                outcome.conflicts.addAll(status().conflicts);
                if (!"markers".equals(strategy) && resolveConflicts(strategy, outcome.conflicts) > 0
                        && completeMergeIfResolved()) {
                    outcome.kind = PullKind.AUTO_RESOLVED;
                    outcome.updated = true;
                    return outcome;
                }
                outcome.kind = PullKind.CONFLICT;
                return outcome;
            }
            outcome.updated = mergeStatus != null
                    && mergeStatus != MergeResult.MergeStatus.ALREADY_UP_TO_DATE
                    && mergeStatus != MergeResult.MergeStatus.FAILED
                    && mergeStatus != MergeResult.MergeStatus.ABORTED
                    && mergeStatus != MergeResult.MergeStatus.NOT_SUPPORTED
                    && mergeStatus != MergeResult.MergeStatus.CHECKOUT_CONFLICT;
            return outcome;
        } catch (RefNotFoundException | org.eclipse.jgit.api.errors.RefNotAdvertisedException e) {
            outcome.kind = PullKind.NO_UPSTREAM;
            return outcome;
        } catch (GitAPIException e) {
            throw toIo(e);
        }
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
                    if (update.getStatus() != RemoteRefUpdate.Status.OK
                            && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new IOException("push " + update.getStatus()
                                + (update.getMessage() == null ? "" : ": " + update.getMessage()));
                    }
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

    /**
     * 冲突文件已经不再含冲突标记（用户在编辑器里改好并保存了）就把它收进索引；
     * 合并状态清干净后补一条合并提交，与 vsnote「保存即完成合并」的约定一致。
     */
    public boolean completeMergeIfResolved() throws IOException {
        final List<String> conflicts = status().conflicts;
        if (conflicts.isEmpty() && !isMerging(_git.getRepository())) {
            return false;
        }
        for (final String path : conflicts) {
            final File file = new File(getWorkTree(), path);
            if (!file.exists() || !containsConflictMarkers(readFile(file))) {
                stageResolution(path, file.exists());
            }
        }
        if (!status().conflicts.isEmpty() || !isMerging(_git.getRepository())) {
            return false;
        }
        try {
            _git.commit().setMessage("markor: merge " + remoteOrDefault())
                    .setAuthor(_settings.getGitAuthorName(), _settings.getGitAuthorEmail())
                    .setCommitter(_settings.getGitAuthorName(), _settings.getGitAuthorEmail())
                    .call();
            return true;
        } catch (GitAPIException e) {
            return false;
        }
    }

    private String remoteOrDefault() {
        final String remote = effectiveRemote();
        return remote == null ? Constants.DEFAULT_REMOTE_NAME : remote;
    }

    /** 按策略解决冲突文件；markers 只认「用户已经把标记改掉了」的文件 */
    public int resolveConflicts(final String strategy, final List<String> paths) throws IOException {
        int resolved = 0;
        for (final String path : paths) {
            final File file = new File(getWorkTree(), path);
            if ("markers".equals(strategy)) {
                if (file.exists() && !containsConflictMarkers(readFile(file))) {
                    stageResolution(path, true);
                    resolved++;
                }
                continue;
            }
            final byte[] ours = stageBlob(path, 2);
            final byte[] theirs = stageBlob(path, 3);
            final byte[] content;
            if ("ours".equals(strategy)) {
                content = ours;
            } else if ("theirs".equals(strategy)) {
                content = theirs;
            } else {
                content = unionContent(file, ours, theirs);
            }
            if (content == null) {
                deleteFile(file);
                stageResolution(path, false);
            } else {
                writeFile(file, content);
                stageResolution(path, true);
            }
            resolved++;
        }
        return resolved;
    }

    /** union：优先剥掉 JGit 已写好的冲突标记（保留双方），没有标记才拼两侧 */
    private byte[] unionContent(final File file, @Nullable final byte[] ours, @Nullable final byte[] theirs)
            throws IOException {
        if (file.exists()) {
            final String text = readFile(file);
            if (containsConflictMarkers(text)) {
                return conflictSide(text, true, true).getBytes(StandardCharsets.UTF_8);
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (ours == null || theirs == null) {
            return ours != null ? ours : theirs;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ours, 0, ours.length);
        out.write(theirs, 0, theirs.length);
        return out.toByteArray();
    }

    /** 放弃本次合并：索引回 HEAD，冲突文件取回我们这一侧，本地已提交的内容不丢 */
    public void abortMerge() throws IOException {
        final Repository repository = _git.getRepository();
        final List<String> conflicts = status().conflicts;
        if (!isMerging(repository) && conflicts.isEmpty()) {
            return;
        }
        try {
            _git.reset().setRef(Constants.HEAD).setMode(ResetCommand.ResetType.MIXED).call();
        } catch (GitAPIException e) {
            throw toIo(e);
        }
        for (final String path : conflicts) {
            restoreFromHead(path);
        }
        for (final String stateFile : new String[]{"MERGE_HEAD", "MERGE_MSG", "MERGE_MODE"}) {
            // JGit 没有 merge --abort，状态文件得自己清，否则仓库一直停在 MERGING
            deleteFile(new File(repository.getDirectory(), stateFile));
        }
    }

    private void restoreFromHead(final String path) throws IOException {
        final Repository repository = _git.getRepository();
        final File file = new File(getWorkTree(), path);
        final ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null) {
            deleteFile(file);
            return;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            final RevCommit commit = walk.parseCommit(head);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (treeWalk == null) {
                    deleteFile(file);
                    return;
                }
                writeFile(file, readBlob(treeWalk.getObjectId(0)));
            }
        }
    }

    private void stageResolution(final String path, final boolean exists) throws IOException {
        try {
            if (exists) {
                _git.add().addFilepattern(path).call();
            } else {
                _git.rm().setCached(true).addFilepattern(path).call();
            }
        } catch (GitAPIException e) {
            throw toIo(e);
        }
    }

    @Nullable
    private byte[] stageBlob(final String path, final int stage) throws IOException {
        final DirCache cache = DirCache.read(_git.getRepository());
        for (final DirCacheEntry entry : cache.getEntriesWithin(path)) {
            if (entry.getStage() == stage && path.equals(entry.getPathString())) {
                return readBlob(entry.getObjectId());
            }
        }
        return null;
    }

    private byte[] readBlob(final ObjectId id) throws IOException {
        final ObjectLoader loader = _git.getRepository().open(id);
        if (loader.isLarge()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            loader.copyTo(out);
            return out.toByteArray();
        }
        return loader.getBytes();
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

    public static boolean containsConflictMarkers(final String text) {
        for (final String line : text.split("\n")) {
            final String value = trimCr(line);
            if (value.startsWith(MARKER_OURS) || value.startsWith(MARKER_THEIRS)) {
                return true;
            }
        }
        return false;
    }

    /** 取出冲突区块的某侧内容；两侧都要就是 union */
    static String conflictSide(final String text, final boolean keepOurs, final boolean keepTheirs) {
        final StringBuilder out = new StringBuilder();
        boolean inConflict = false;
        boolean inOurs = true;
        boolean inBase = false;
        for (final String raw : text.split("\n", -1)) {
            final String line = trimCr(raw);
            if (line.startsWith(MARKER_OURS)) {
                inConflict = true;
                inOurs = true;
                inBase = false;
                continue;
            }
            if (inConflict && line.startsWith(MARKER_BASE)) {
                inOurs = false;
                inBase = true;
                continue;
            }
            if (inConflict && line.equals(MARKER_SEP)) {
                inOurs = false;
                inBase = false;
                continue;
            }
            if (inConflict && line.startsWith(MARKER_THEIRS)) {
                inConflict = false;
                inOurs = true;
                inBase = false;
                continue;
            }
            if (!inConflict || (!inBase && (inOurs ? keepOurs : keepTheirs))) {
                out.append(raw).append('\n');
            }
        }
        if (!text.endsWith("\n") && out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static String trimCr(final String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
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

    private static String readFile(final File file) throws IOException {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(final File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void writeFile(final File file, final byte[] content) throws IOException {
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    private static void deleteFile(final File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static IOException toIo(final GitAPIException e) {
        return new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }
}
