/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.sqlite;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.gsantner.markor.model.AppSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 文件夹 / 压缩包(zip) / SQLite 数据库 之间的相互转换。
 * <p>
 * 库文件的表结构与加密方式跟 vsnote 一致：md/markdown/txt 进 node_content，其它进 node_blob，
 * 口令只加密节点名与内容 (db_meta 里存 salt 与校验值)，文件本身仍是标准 SQLite 数据库。
 */
public final class SqliteNotebookUtils {

    private static final String[] SQLITE_EXTENSIONS = {".db", ".sqlite", ".sqlite3", ".vsdb"};
    private static final String ZIP_EXTENSION = ".zip";

    private SqliteNotebookUtils() {
    }

    public static boolean isSqliteNotebookFile(final File file) {
        if (file == null) {
            return false;
        }
        final String name = file.getName().toLowerCase(java.util.Locale.US);
        if (name.startsWith(".")) {
            return false;
        }
        for (final String ext : SQLITE_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isZipFile(final File file) {
        return file != null && file.isFile() && file.getName().toLowerCase(java.util.Locale.US)
                .endsWith(ZIP_EXTENSION);
    }

    /**
     * 打开这个库还需不需要问用户要口令。加密库的文件头和明文库一样是标准 SQLite，
     * 所以只能读 db_meta.enc_salt 判定 (不能按文件头猜)；本机已记住且仍有效的派生密钥算「不需要」。
     * 此处不做 KDF，只是探一下表。
     */
    public static boolean requiresPassword(@NonNull final Context context, @NonNull final File dbFile) {
        try (SqliteNotebookDb db = openForReading(context, dbFile, null)) {
            return false;
        } catch (SqliteNotebookDb.NotebookDbException e) {
            return e.kind == SqliteNotebookDb.Kind.PASSWORD_REQUIRED || e.kind == SqliteNotebookDb.Kind.WRONG_PASSWORD;
        } catch (Exception e) {
            return false;
        }
    }

    //########################
    //## Folder <-> db
    //########################

    /**
     * 将文件夹导出为 SQLite 数据库。密码非空时逐值加密 (与 vsnote 通用)。
     */
    public static void exportFolderToDb(@NonNull final File srcDir, @NonNull final File destDb,
                                        @Nullable final char[] password) throws Exception {
        if (!srcDir.isDirectory()) {
            throw new IOException("Source is not a folder: " + srcDir);
        }
        deleteDatabaseFile(destDb);
        try (SqliteNotebookDb db = SqliteNotebookDb.createNew(destDb, password)) {
            db.exportFolderContents(srcDir, destDb);
        } catch (Exception e) {
            // 留下半成品空库, 下次导出会撞「目标文件已存在」—— 直接删掉
            deleteDatabaseFile(destDb);
            throw e;
        }
    }

    /**
     * 将 SQLite 数据库还原为文件夹
     */
    public static void extractDbToFolder(@NonNull final Context context, @NonNull final File dbFile,
                                         @Nullable final char[] password, @NonNull final File destDir)
            throws Exception {
        try (SqliteNotebookDb db = openForReading(context, dbFile, password)) {
            db.extractContentsToFolder(destDir);
        }
    }

    //########################
    //## Zip <-> db
    //########################

    public static void importZipToDb(@NonNull final Context context, @NonNull final File zipFile,
                                     @NonNull final File destDb, @Nullable final char[] password) throws Exception {
        final File tmpDir = new File(context.getCacheDir(), "zip2db_" + System.currentTimeMillis());
        try {
            unzip(zipFile, tmpDir);
            exportFolderToDb(tmpDir, destDb, password);
        } finally {
            SqliteNotebookDb.deleteRecursively(tmpDir);
        }
    }

    public static void exportDbToZip(@NonNull final Context context, @NonNull final File dbFile,
                                     @Nullable final char[] password, @NonNull final File destZip) throws Exception {
        final File tmpDir = new File(context.getCacheDir(), "db2zip_" + System.currentTimeMillis());
        try {
            extractDbToFolder(context, dbFile, password, tmpDir);
            zipFolder(tmpDir, destZip);
        } finally {
            SqliteNotebookDb.deleteRecursively(tmpDir);
        }
    }

    /** 转换用的打开入口：优先用本次输入的口令，其次用本机为该库记住的派生密钥 */
    @NonNull
    private static SqliteNotebookDb openForReading(@NonNull final Context context, @NonNull final File dbFile,
                                                   @Nullable final char[] password)
            throws SqliteNotebookDb.NotebookDbException {
        return SqliteNotebookDb.openExisting(dbFile, password,
                AppSettings.get(context).getSqliteNotebookKey(dbFile));
    }

    //########################
    //## Zip helpers
    //########################

    private static void unzip(final File zipFile, final File destDir) throws IOException {
        destDir.mkdirs();
        final byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final File outFile = new File(destDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)
                        && !outFile.getCanonicalPath().equals(destDir.getCanonicalPath())) {
                    continue; // 防 zip path traversal
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                    if (entry.getTime() > 0) {
                        //noinspection ResultOfMethodCallIgnored
                        outFile.setLastModified(entry.getTime());
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void zipFolder(final File srcDir, final File destZip) throws IOException {
        final byte[] buf = new byte[8192];
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(destZip))) {
            zipRecursive(zos, srcDir, "", buf);
        }
    }

    private static void zipRecursive(final ZipOutputStream zos, final File dir, final String relParent,
                                     final byte[] buf) throws IOException {
        final File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        final List<File> children = new ArrayList<>();
        // 这里的输入是已解包的 db 内容，按名字跳过会漏掉 . 开头的节点，所以全量打包
        Collections.addAll(children, list);
        Collections.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (final File f : children) {
            final String relPath = SqliteNotebookDb.joinPath(relParent, f.getName());
            if (f.isDirectory()) {
                final ZipEntry entry = new ZipEntry(relPath + "/");
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                zos.closeEntry();
                zipRecursive(zos, f, relPath, buf);
            } else {
                final ZipEntry entry = new ZipEntry(relPath);
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(f)) {
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    //########################
    //## File helpers
    //########################

    private static void deleteDatabaseFile(final File dbFile) {
        //noinspection ResultOfMethodCallIgnored
        dbFile.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getAbsolutePath() + "-journal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getAbsolutePath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getAbsolutePath() + "-shm").delete();
    }
}
