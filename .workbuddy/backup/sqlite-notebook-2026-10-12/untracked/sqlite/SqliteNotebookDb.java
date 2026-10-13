/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * SQLite 笔记本数据库访问层。表结构、加密与读写语义均对齐 vsnote 的
 * src-tauri/src/sqlite_store.rs，使两个应用打开同一个 .db 文件都能读写。
 * <p>
 * 根是隐式的：顶层节点 parent_id IS NULL，库里没有根行。
 * md / markdown / txt 进 node_content.content (TEXT)，其余进 node_blob.data (BLOB)。
 * 设密码后只有 node.name 与两处内容被逐值加密，文件本身仍是标准 SQLite 数据库。
 */
public class SqliteNotebookDb implements AutoCloseable {

    public static final String TYPE_FOLDER = "folder";
    public static final String TYPE_FILE = "file";

    /** 进 node_content 的扩展名，其余一律进 node_blob */
    private static final String[] TEXT_EXTS = {"md", "markdown", "txt"};
    /** 与 vsnote 的遍历跳过规则一致 */
    private static final String[] SKIP_DIRS = {"node_modules", "target", ".git"};

    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    /** 记住密码时存的是 PBKDF2 派生密钥 (base64)，不是明文口令；别名前缀 */
    public static final String PASSWORD_KEY_NAME = "sqlite_notebook_key";

    /** 打开失败的原因，UI 按 kind 决定是弹密码框还是直接报错 */
    public enum Kind { PASSWORD_REQUIRED, WRONG_PASSWORD, NOT_NOTEBOOK_DB, LEGACY_DB, IO }

    public static class NotebookDbException extends IOException {
        public final Kind kind;

        public NotebookDbException(@NonNull final Kind kind, final String message) {
            super(message);
            this.kind = kind;
        }
    }

    public static class Node {
        public long id;
        @Nullable
        public Long parentId;
        public String type;
        public String name;
        public long createTime;
        public long updateTime;

        public boolean isFolder() {
            return TYPE_FOLDER.equals(type);
        }
    }

    private final SQLiteDatabase _db;
    /** null = 明文库 */
    @Nullable
    private final byte[] _key;

    private SqliteNotebookDb(@NonNull final SQLiteDatabase db, @Nullable final byte[] key) {
        _db = db;
        _key = key;
    }

    public boolean isEncrypted() {
        return _key != null;
    }

    /** @return 派生密钥的 base64，供「记住密码」缓存；明文库返回 null */
    @Nullable
    public String getDerivedKeyBase64() {
        return _key == null ? null : SqliteCrypto.encodeKey(_key);
    }

    //########################
    //## Open / close
    //########################

    /**
     * 打开一个**已存在且有内容**的笔记本数据库。绝不建库、也不把空文件初始化成库：
     * db 笔记本与文件夹笔记本是二选一，文件不在（或是 0 字节半成品）就是「没有可用的 db」。
     * 建库只发生在 {@link #createNew} —— 即用户明确执行「导出为 SQLite 数据库 / zip 转 db」。
     */
    @NonNull
    public static SqliteNotebookDb openExisting(@NonNull final File dbFile, @Nullable final char[] password,
                                                @Nullable final String cachedKeyB64) throws NotebookDbException {
        if (!dbFile.isFile()) {
            throw new NotebookDbException(Kind.NOT_NOTEBOOK_DB, "SQLite 文件不存在: " + dbFile);
        }
        if (dbFile.length() == 0) {
            throw new NotebookDbException(Kind.NOT_NOTEBOOK_DB,
                    "SQLite 文件是空的（同步未完成？），请用「导出为 SQLite 数据库」创建: " + dbFile);
        }
        return openAt(dbFile, password, cachedKeyB64);
    }

    /**
     * 新建笔记本数据库，只在用户明确执行「导出为 SQLite 数据库 / 转换为 db」时调用；
     * 除这里以外没有任何路径会创建 .db 文件。
     */
    @NonNull
    public static SqliteNotebookDb createNew(@NonNull final File dbFile, @Nullable final char[] password)
            throws NotebookDbException {
        if (dbFile.isFile() && dbFile.length() > 0) {
            throw new NotebookDbException(Kind.NOT_NOTEBOOK_DB, "目标文件已存在且非空: " + dbFile);
        }
        return openAt(dbFile, password, null);
    }

    /**
     * 加密方式是「值级」的，所以任何情况下都用系统 SQLite 打开文件；只有口令错误或表结构不符时才报错。
     *
     * @param password     明文口令；打开明文库时误传的口令会被忽略
     * @param cachedKeyB64 本机记住的派生密钥，命中时跳过 60 万次 KDF 迭代
     */
    @NonNull
    private static SqliteNotebookDb openAt(@NonNull final File dbFile, @Nullable final char[] password,
                                           @Nullable final String cachedKeyB64) throws NotebookDbException {
        final boolean isNew = !dbFile.isFile() || dbFile.length() == 0;
        if (!isNew && !hasSqliteHeader(dbFile)) {
            // 加密库的文件头与明文库一样是标准 SQLite；不是的话多半是旧版 SQLCipher 库
            throw new NotebookDbException(Kind.LEGACY_DB,
                    "不是标准 SQLite 文件（可能是旧版 SQLCipher 笔记本库）: " + dbFile);
        }

        final SQLiteDatabase db;
        try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
            // vsnote 的删除依赖 FK 级联，SQLite 默认关闭，按连接打开
            db.execSQL("PRAGMA foreign_keys = ON");
        } catch (Exception e) {
            throw new NotebookDbException(Kind.IO, "cannot open " + dbFile + ": " + e);
        }

        try {
            final byte[] key = resolveKey(db, isNew, password, cachedKeyB64);
            return new SqliteNotebookDb(db, key);
        } catch (NotebookDbException | RuntimeException e) {
            db.close();
            throw e instanceof NotebookDbException ? (NotebookDbException) e
                    : new NotebookDbException(Kind.IO, String.valueOf(e));
        }
    }

    /** 空文件或不存在 = 新建；新建时给了口令才加密 */
    @Nullable
    private static byte[] resolveKey(@NonNull final SQLiteDatabase db, final boolean isNew,
                                     @Nullable final char[] password, @Nullable final String cachedKeyB64)
            throws NotebookDbException {
        if (isNew) {
            ensureSchema(db, true);
            if (password == null || password.length == 0) {
                return null;
            }
            final byte[] salt = SqliteCrypto.randomSalt();
            final byte[] key = SqliteCrypto.deriveKey(password, salt);
            setMeta(db, SqliteCrypto.META_ENC_SALT, SqliteCrypto.encodeBase64(salt));
            setMeta(db, SqliteCrypto.META_ENC_CHECK,
                    SqliteCrypto.encodeBase64(SqliteCrypto.seal(key, SqliteCrypto.ENC_CHECK_PLAIN)));
            return key;
        }

        if (!hasTable(db, "node")) {
            throw new NotebookDbException(Kind.NOT_NOTEBOOK_DB, "不是有效的笔记本数据库（缺少 node 表）");
        }
        if (isLegacyMarkorSchema(db)) {
            throw new NotebookDbException(Kind.LEGACY_DB,
                    "旧版 Markor 笔记本库，请先解包为文件夹再用当前版本重建");
        }
        // 老版本 vsnote 的库没有 db_meta / node_blob，这里补齐附属表 (不动 node 表)
        ensureSchema(db, false);

        final String saltB64 = getMeta(db, SqliteCrypto.META_ENC_SALT);
        if (saltB64 == null) {
            return null; // 明文库
        }
        final byte[] salt = SqliteCrypto.decodeBase64(saltB64);
        if (salt == null || salt.length != SqliteCrypto.SALT_BYTES) {
            throw new NotebookDbException(Kind.IO, "db_meta.enc_salt 已损坏");
        }
        final byte[] cachedKey = SqliteCrypto.decodeKey(cachedKeyB64);
        if (cachedKey != null && keyMatches(db, cachedKey)) {
            return cachedKey;
        }
        if (password == null || password.length == 0) {
            throw new NotebookDbException(Kind.PASSWORD_REQUIRED, "该 SQLite db 已加密，需要密码");
        }
        final byte[] key = SqliteCrypto.deriveKey(password, salt);
        if (!keyMatches(db, key)) {
            throw new NotebookDbException(Kind.WRONG_PASSWORD, "密码错误");
        }
        return key;
    }

    private static boolean keyMatches(@NonNull final SQLiteDatabase db, @NonNull final byte[] key) {
        final String check = getMeta(db, SqliteCrypto.META_ENC_CHECK);
        if (check == null) {
            return false;
        }
        final byte[] sealed = SqliteCrypto.decodeBase64(check);
        final byte[] plain = sealed == null ? null : SqliteCrypto.unseal(key, sealed);
        return plain != null && Arrays.equals(plain, SqliteCrypto.ENC_CHECK_PLAIN);
    }

    private static boolean hasSqliteHeader(@NonNull final File file) {
        final byte[] buf = new byte[SQLITE_MAGIC.length];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = 0;
            while (read < buf.length) {
                final int r = fis.read(buf, read, buf.length - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
            return read == buf.length && Arrays.equals(buf, SQLITE_MAGIC);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            _db.close();
        } catch (Exception ignored) {
        }
    }

    public static String md5Hex(final String text) {
        try {
            final MessageDigest md = MessageDigest.getInstance("MD5");
            final byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (final byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "workspace";
        }
    }

    /** 每个数据库文件对应一个稳定的应用内工作区目录, 用作笔记本根文件夹 */
    public static File getWorkspaceDir(@NonNull final Context context, @NonNull final File dbFile) {
        return new File(new File(context.getFilesDir(), "sqlite_notebook"), md5Hex(dbFile.getAbsolutePath()));
    }

    //########################
    //## Schema
    //########################

    private static final String SQL_NODE = "CREATE TABLE IF NOT EXISTS node (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "parent_id INTEGER REFERENCES node(id) ON DELETE CASCADE," +
            "type TEXT NOT NULL CHECK (type IN ('folder', 'file'))," +
            "name TEXT NOT NULL," +
            "create_time INTEGER NOT NULL," +
            "update_time INTEGER NOT NULL," +
            "deleted INTEGER NOT NULL DEFAULT 0)";
    private static final String SQL_NODE_CONTENT = "CREATE TABLE IF NOT EXISTS node_content (" +
            "node_id INTEGER PRIMARY KEY REFERENCES node(id) ON DELETE CASCADE," +
            "content TEXT NOT NULL)";
    private static final String SQL_NODE_BLOB = "CREATE TABLE IF NOT EXISTS node_blob (" +
            "node_id INTEGER PRIMARY KEY REFERENCES node(id) ON DELETE CASCADE," +
            "data BLOB NOT NULL)";
    /** key 必须是 PRIMARY KEY，写 enc_salt / enc_check 才走得到覆盖旧值 */
    private static final String SQL_DB_META = "CREATE TABLE IF NOT EXISTS db_meta (" +
            "key TEXT PRIMARY KEY," +
            "value TEXT NOT NULL)";
    private static final String SQL_INDEX_PARENT = "CREATE INDEX IF NOT EXISTS idx_node_parent ON node(parent_id)";

    /** @param createNode true = 新建库，连 node 一起建；false = 已存在的库，只补齐附属表 */
    private static void ensureSchema(@NonNull final SQLiteDatabase db, final boolean createNode) {
        if (createNode) {
            db.execSQL(SQL_NODE);
        }
        db.execSQL(SQL_NODE_CONTENT);
        db.execSQL(SQL_NODE_BLOB);
        db.execSQL(SQL_DB_META);
        db.execSQL(SQL_INDEX_PARENT);
    }

    private static boolean hasTable(@NonNull final SQLiteDatabase db, @NonNull final String table) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                new String[]{table})) {
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /** 旧版 Markor 的库：node 多一个 size 列，且用 sync_state 表存快照 */
    private static boolean isLegacyMarkorSchema(@NonNull final SQLiteDatabase db) {
        return hasTable(db, "sync_state") || hasColumn(db, "node", "size");
    }

    private static boolean hasColumn(@NonNull final SQLiteDatabase db, @NonNull final String table,
                                     @NonNull final String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(c.getString(1))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    //########################
    //## db_meta (加密参数本身是明文)
    //########################

    @Nullable
    private static String getMeta(@NonNull final SQLiteDatabase db, @NonNull final String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM db_meta WHERE key = ?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void setMeta(@NonNull final SQLiteDatabase db, @NonNull final String key,
                                @NonNull final String value) {
        final ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        // 不用 ON CONFLICT DO UPDATE：那是 SQLite 3.24+ 语法，老 Android 自带的 sqlite 更旧
        db.insertWithOnConflict("db_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    //########################
    //## Node CRUD
    //########################

    private static final String NODE_COLS = "id, parent_id, type, name, create_time, update_time";

    /** name 列存的是密文，解密失败 (口令不符 / 值损坏) 的节点直接丢弃，与 vsnote 一致 */
    @Nullable
    private Node readNode(@NonNull final Cursor c) {
        final Node n = new Node();
        n.id = c.getLong(0);
        n.parentId = c.isNull(1) ? null : c.getLong(1);
        n.type = c.getString(2);
        n.name = SqliteCrypto.unsealText(_key, c.getString(3));
        n.createTime = c.getLong(4);
        n.updateTime = c.getLong(5);
        return n.name == null ? null : n;
    }

    /** 顶层节点用 parent_id IS NULL，其余按 id 比较 */
    @NonNull
    private static String parentClause(@Nullable final Long parentId) {
        return parentId == null ? "parent_id IS NULL" : "parent_id = ?";
    }

    @Nullable
    public Node getNode(final long nodeId) {
        try (Cursor c = _db.rawQuery("SELECT " + NODE_COLS + " FROM node WHERE id = ? AND deleted = 0",
                new String[]{String.valueOf(nodeId)})) {
            return c.moveToFirst() ? readNode(c) : null;
        }
    }

    /**
     * 按名字找直接子节点。加密库里 name 是密文，不能 WHERE name = ?，
     * 只能列出同级节点逐个解密比对 (对齐 vsnote 的 find_child)。
     */
    @Nullable
    public Node getChild(@Nullable final Long parentId, @NonNull final String name) {
        for (final Node n : listChildrenRaw(parentId)) {
            if (name.equals(n.name)) {
                return n;
            }
        }
        return null;
    }

    /** 同级节点，已解密、未排序 */
    @NonNull
    public List<Node> listChildrenRaw(@Nullable final Long parentId) {
        final List<Node> list = new ArrayList<>();
        final String clause = parentClause(parentId);
        final String[] args = parentId == null ? null : new String[]{String.valueOf(parentId)};
        try (Cursor c = _db.rawQuery(
                "SELECT " + NODE_COLS + " FROM node WHERE " + clause + " AND deleted = 0", args)) {
            while (c.moveToNext()) {
                final Node n = readNode(c);
                if (n != null) {
                    list.add(n);
                }
            }
        }
        return list;
    }

    /** 与文件系统列表一致：文件夹在前，名称不区分大小写升序 */
    @NonNull
    public List<Node> listChildren(@Nullable final Long parentId) {
        final List<Node> list = listChildrenRaw(parentId);
        Collections.sort(list, (a, b) -> {
            if (a.isFolder() != b.isFolder()) {
                return a.isFolder() ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    @Nullable
    public Node getNodeByPath(@NonNull final String relPath) {
        Node node = null;
        for (final String part : splitPath(relPath)) {
            node = getChild(node == null ? null : node.id, part);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** 节点名合法性：非空、不含路径分隔符、不是 . 或 .. */
    public static boolean validName(@NonNull final String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && !name.contains("\\");
    }

    /** @return 新节点 id；名字非法或同级重名时返回 -1 */
    public long insertNode(@Nullable final Long parentId, final boolean isFolder, @NonNull final String name,
                           final long createTime, final long updateTime) {
        if (!validName(name) || siblingExists(parentId, name, null)) {
            return -1;
        }
        final ContentValues cv = new ContentValues();
        cv.put("parent_id", parentId);
        cv.put("type", isFolder ? TYPE_FOLDER : TYPE_FILE);
        cv.put("name", SqliteCrypto.sealText(_key, name));
        cv.put("create_time", createTime);
        cv.put("update_time", updateTime);
        cv.put("deleted", 0);
        return _db.insertWithOnConflict("node", null, cv, SQLiteDatabase.CONFLICT_ABORT);
    }

    private boolean siblingExists(@Nullable final Long parentId, @NonNull final String name,
                                  @Nullable final Long excludeId) {
        final long exclude = excludeId == null ? -1 : excludeId;
        for (final Node n : listChildrenRaw(parentId)) {
            if (n.id != exclude && name.equals(n.name)) {
                return true;
            }
        }
        return false;
    }

    /** 逐级创建文件夹链，返回最末一级的父 id (null = 挂在隐式根下) */
    @Nullable
    public Long ensureChain(@NonNull final List<String> comps, final long time) throws IOException {
        Long parent = null;
        for (final String comp : comps) {
            final Node existing = getChild(parent, comp);
            if (existing != null) {
                if (!existing.isFolder()) {
                    throw new IOException("同名文件已存在，无法创建文件夹: " + comp);
                }
                parent = existing.id;
            } else {
                final long id = insertNode(parent, true, comp, time, time);
                if (id < 0) {
                    throw new IOException("cannot create folder: " + comp);
                }
                parent = id;
            }
        }
        return parent;
    }

    /** 把工作区文件的当前内容写进节点 (文件系统 -> 数据库) */
    public void writeFile(@NonNull final File srcFile, @NonNull final Node node, final long updateTime)
            throws IOException {
        updateFileNode(node, updateTime, readFileBytes(srcFile));
    }

    /** 写入或覆盖节点内容，按扩展名分流，并清掉另一种类型的内容 (对齐 vsnote 的 write_file) */
    public void putContent(final long nodeId, @NonNull final String name, @NonNull final byte[] content) {
        _db.execSQL("DELETE FROM node_content WHERE node_id = ?", new Object[]{nodeId});
        _db.execSQL("DELETE FROM node_blob WHERE node_id = ?", new Object[]{nodeId});
        final ContentValues cv = new ContentValues();
        cv.put("node_id", nodeId);
        if (isTextName(name)) {
            cv.put("content", SqliteCrypto.sealText(_key, decodeBytes(content)));
            _db.insertWithOnConflict("node_content", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } else {
            cv.put("data", SqliteCrypto.sealBlob(_key, content));
            _db.insertWithOnConflict("node_blob", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /** 更新文件节点：内容非 null 时重写内容，同时触碰祖先的 update_time */
    public void updateFileNode(@NonNull final Node node, final long updateTime, @Nullable final byte[] content) {
        _db.beginTransaction();
        try {
            _db.execSQL("UPDATE node SET update_time = ? WHERE id = ?", new Object[]{updateTime, node.id});
            if (content != null) {
                putContent(node.id, node.name, content);
            }
            touchAncestors(node.parentId, updateTime);
            _db.setTransactionSuccessful();
        } finally {
            _db.endTransaction();
        }
    }

    /** @return 节点内容的明文字节 (文本按 UTF-8 还原)；文件夹或无内容时为空数组 */
    @NonNull
    public byte[] readContent(@NonNull final Node node) {
        if (node.isFolder()) {
            return new byte[0];
        }
        final String id = String.valueOf(node.id);
        // 优先 blob，其次文本 (与 vsnote 的 read_file_bytes 同序)
        try (Cursor c = _db.rawQuery("SELECT data FROM node_blob WHERE node_id = ?", new String[]{id})) {
            if (c.moveToFirst()) {
                final byte[] plain = SqliteCrypto.unsealBlob(_key, c.getBlob(0));
                if (plain != null) {
                    return plain;
                }
            }
        }
        try (Cursor c = _db.rawQuery("SELECT content FROM node_content WHERE node_id = ?", new String[]{id})) {
            if (c.moveToFirst()) {
                final String text = SqliteCrypto.unsealText(_key, c.getString(0));
                if (text != null) {
                    return text.getBytes(StandardCharsets.UTF_8);
                }
            }
        }
        return new byte[0];
    }

    /** 触碰节点及各祖先的 update_time (对齐 vsnote 的 touch_ancestors) */
    public void touchAncestors(@Nullable Long id, final long time) {
        while (id != null) {
            _db.execSQL("UPDATE node SET update_time = ? WHERE id = ?", new Object[]{time, id});
            Long parent = null;
            try (Cursor c = _db.rawQuery("SELECT parent_id FROM node WHERE id = ?",
                    new String[]{String.valueOf(id)})) {
                if (c.moveToFirst() && !c.isNull(0)) {
                    parent = c.getLong(0);
                }
            } catch (Exception ignored) {
            }
            id = parent;
        }
    }

    public boolean renameNode(@NonNull final Node node, @NonNull final String newName) {
        if (!validName(newName) || siblingExists(node.parentId, newName, node.id)) {
            return false;
        }
        final long time = System.currentTimeMillis();
        _db.execSQL("UPDATE node SET name = ?, update_time = ? WHERE id = ?",
                new Object[]{SqliteCrypto.sealText(_key, newName), time, node.id});
        touchAncestors(node.parentId, time);
        return true;
    }

    /** 硬删除节点及其子孙；FK 级联已打开，仍显式删内容表以免级联未生效 */
    public void deleteNodeCascade(final long nodeId) {
        final List<Long> allIds = collectSubtree(nodeId);
        _db.beginTransaction();
        try {
            for (final Long id : allIds) {
                _db.execSQL("DELETE FROM node_content WHERE node_id = ?", new Object[]{id});
                _db.execSQL("DELETE FROM node_blob WHERE node_id = ?", new Object[]{id});
                _db.execSQL("DELETE FROM node WHERE id = ?", new Object[]{id});
            }
            _db.setTransactionSuccessful();
        } finally {
            _db.endTransaction();
        }
    }

    @NonNull
    private List<Long> collectSubtree(final long nodeId) {
        final List<Long> allIds = new ArrayList<>();
        List<Long> level = new ArrayList<>();
        level.add(nodeId);
        while (!level.isEmpty()) {
            allIds.addAll(level);
            final List<Long> next = new ArrayList<>();
            for (final Long id : level) {
                try (Cursor c = _db.rawQuery("SELECT id FROM node WHERE parent_id = ?",
                        new String[]{String.valueOf(id)})) {
                    while (c.moveToNext()) {
                        next.add(c.getLong(0));
                    }
                }
            }
            level = next;
        }
        return allIds;
    }

    //########################
    //## Tree
    //########################

    /** 加载全部未删除节点，返回 相对路径 -> 节点。根是隐式的，不在结果里。 */
    @NonNull
    public Map<String, Node> loadTree() {
        final Map<Long, List<Node>> childrenMap = new HashMap<>();
        final List<Node> roots = new ArrayList<>();
        try (Cursor c = _db.rawQuery("SELECT " + NODE_COLS + " FROM node WHERE deleted = 0 ORDER BY id", null)) {
            while (c.moveToNext()) {
                final Node n = readNode(c);
                if (n == null) {
                    continue;
                }
                if (n.parentId == null) {
                    roots.add(n);
                } else {
                    // 不用 Map.computeIfAbsent：那是 API 24+，本工程 minSdk 为 18
                    List<Node> siblings = childrenMap.get(n.parentId);
                    if (siblings == null) {
                        siblings = new ArrayList<>();
                        childrenMap.put(n.parentId, siblings);
                    }
                    siblings.add(n);
                }
            }
        }

        final Map<String, Node> tree = new TreeMap<>();
        final Map<Long, String> paths = new HashMap<>();
        final List<Node> queue = new ArrayList<>(roots);
        while (!queue.isEmpty()) {
            final Node n = queue.remove(queue.size() - 1);
            final String parentPath = n.parentId == null ? null : paths.get(n.parentId);
            if (n.parentId != null && parentPath == null) {
                continue; // 父节点不在树中, 跳过
            }
            // 必须带上完整相对层级；只拼根会丢中间层，后续解析不到节点
            final String path = n.parentId == null ? "" : joinPath(parentPath, n.name);
            paths.put(n.id, path);
            if (n.parentId != null) {
                tree.put(path, n);
            }
            final List<Node> children = childrenMap.get(n.id);
            if (children != null) {
                queue.addAll(children);
            }
        }
        return tree;
    }

    //########################
    //## Folder <-> db
    //########################

    /** 把一个文件夹的内容写入当前数据库 (挂在隐式根下)，用于导出 */
    public void exportFolderContents(@NonNull final File srcDir, @NonNull final File destDb) throws IOException {
        _db.beginTransaction();
        try {
            exportRecursive(this, srcDir, null, destDb);
            _db.setTransactionSuccessful();
        } finally {
            _db.endTransaction();
        }
    }

    private static void exportRecursive(@NonNull final SqliteNotebookDb db, @NonNull final File dir,
                                        @Nullable final Long parentId, @NonNull final File destDb)
            throws IOException {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        final List<File> sorted = new ArrayList<>(Arrays.asList(children));
        Collections.sort(sorted, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (final File f : sorted) {
            final String name = f.getName();
            if (f.equals(destDb) || isSkippedName(name)) {
                continue; // 目标库自身 / 点开头 / 依赖目录都不入库
            }
            final long mtime = f.lastModified();
            if (f.isDirectory()) {
                final long id = db.insertNode(parentId, true, name, mtime, mtime);
                if (id >= 0) {
                    exportRecursive(db, f, id, destDb);
                }
            } else if (f.isFile()) {
                final long id = db.insertNode(parentId, false, name, mtime, mtime);
                if (id >= 0) {
                    db.putContent(id, name, readFileBytes(f));
                }
            }
        }
    }

    /** 把当前数据库的全部节点还原为文件夹，用于解包 */
    public void extractContentsToFolder(@NonNull final File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Cannot create folder: " + destDir);
        }
        final List<Map.Entry<String, Node>> entries = new ArrayList<>(loadTree().entrySet());
        // 父目录先建，否则写子文件时父路径还不存在
        Collections.sort(entries, (a, b) -> splitPath(a.getKey()).size() - splitPath(b.getKey()).size());
        for (final Map.Entry<String, Node> e : entries) {
            if (e.getValue().isFolder()) {
                //noinspection ResultOfMethodCallIgnored
                new File(destDir, e.getKey()).mkdirs();
            }
        }
        for (final Map.Entry<String, Node> e : entries) {
            final Node node = e.getValue();
            if (node.isFolder()) {
                continue;
            }
            final File f = new File(destDir, e.getKey());
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            writeFileBytes(f, readContent(node));
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(node.updateTime);
        }
    }

    //########################
    //## File helpers
    //########################

    /** 读磁盘文件；失败时抛出，让调用方中止 (避免把空内容写进节点) */
    @NonNull
    public static byte[] readFileBytes(@NonNull final File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            final long length = Math.max(0, file.length());
            final byte[] buf = new byte[(int) length];
            int off = 0;
            while (off < buf.length) {
                final int r = fis.read(buf, off, buf.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return off == buf.length ? buf : Arrays.copyOf(buf, off);
        }
    }

    public static void writeFileBytes(@NonNull final File file, @NonNull final byte[] content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content);
        }
    }

    //########################
    //## Text / binary split, encoding
    //########################

    /** 与 vsnote 的 Path::extension 同语义：".gitignore" 视为无扩展名，"a.tar.gz" 取 gz */
    @Nullable
    public static String extensionOf(@NonNull final String name) {
        final int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean isTextName(@NonNull final String name) {
        final String ext = extensionOf(name);
        return ext != null && Arrays.asList(TEXT_EXTS).contains(ext);
    }

    /** 点开头的名字与依赖/构建目录不作为笔记本节点 (与 vsnote 的遍历规则一致) */
    public static boolean isSkippedName(@NonNull final String name) {
        return name.startsWith(".") || Arrays.asList(SKIP_DIRS).contains(name);
    }

    /**
     * 与 vsnote 的 decode_bytes 一致：去掉 UTF-8 BOM，先严格按 UTF-8 解，
     * 解不动再按 GB18030，最后兜底 ISO-8859-1 (不会失败，但中文可能读乱)。
     */
    @NonNull
    public static String decodeBytes(@NonNull final byte[] raw) {
        final byte[] bytes;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            bytes = Arrays.copyOfRange(raw, 3, raw.length);
        } else {
            bytes = raw;
        }
        try {
            final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            // fall through
        }
        try {
            return new String(bytes, charsetOrFallback("GB18030"));
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static Charset charsetOrFallback(@NonNull final String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    //########################
    //## Path helpers
    //########################

    @NonNull
    public static List<String> splitPath(@NonNull final String relPath) {
        final List<String> parts = new ArrayList<>();
        if (relPath.isEmpty()) {
            return parts;
        }
        for (final String p : relPath.split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return parts;
    }

    @NonNull
    public static String joinPath(@NonNull final String parent, @NonNull final String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    public static void deleteRecursively(final File file) {
        if (file == null) {
            return;
        }
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
