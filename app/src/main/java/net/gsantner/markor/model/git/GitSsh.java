/*#######################################################
 *
 *   SSH transport (public key auth) for the notebook git sync.
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.git;

import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import net.gsantner.markor.model.AppSettings;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.util.FS;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSH 公钥认证：私钥文件 + 可选口令都来自设置，host key 按 accept-new 语义存到应用私有目录。
 * 22 端口被墙时走 GitHub 的 ssh://git@ssh.github.com:443/user/repo.git，JGit 原生支持带端口
 * 的 SSH URL，这里无需特殊处理。
 */
final class GitSsh {

    private static final String KNOWN_HOSTS_FILE = "git_known_hosts";

    /** 记住已安装工厂的签名，密钥配置没变就不反复重建（JSch 身份缓存跟工厂实例绑定） */
    @Nullable
    private static String installedSignature = null;

    private GitSsh() {
    }

    /** 在 git 操作前调用；没配置私钥就不动默认工厂，SSH URL 会以明确错误失败 */
    static void configure(final AppSettings settings) {
        final String keyPath = settings.getGitSshKeyPath();
        if (TextUtils.isEmpty(keyPath)) {
            return;
        }
        final String passphrase = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? settings.getGitSshPassphrase() : "";
        final String signature = keyPath + "\n" + passphrase;
        if (signature.equals(installedSignature)) {
            return;
        }
        installedSignature = signature;
        final File knownHosts = new File(settings.getContext().getFilesDir(), KNOWN_HOSTS_FILE);
        SshSessionFactory.setInstance(new Factory(keyPath, passphrase, knownHosts));
    }

    private static final class Factory extends JschConfigSessionFactory {
        private final String keyPath;
        private final byte[] passphrase;
        private final File knownHostsFile;

        Factory(final String keyPath, final String passphrase, final File knownHostsFile) {
            this.keyPath = keyPath;
            // JSch 的口令参数就是 byte[]；没设口令给空数组，对无口令密钥也无影响
            this.passphrase = passphrase == null ? new byte[0] : passphrase.getBytes(StandardCharsets.UTF_8);
            this.knownHostsFile = knownHostsFile;
        }

        @Override
        protected JSch createDefaultJSch(final FS fs) throws JSchException {
            final JSch jsch = new JSch();
            // Android 上没有 ~/.ssh/known_hosts，也不想让 JSch 自己去读
            jsch.addIdentity(keyPath, passphrase);
            return jsch;
        }

        @Override
        protected void configure(final OpenSshConfig.Host host, final Session session) {
            // 密钥已在 createDefaultJSch 装好；限定只用公钥，避免 JSch 回退到会卡死的交互路径
            session.setConfig("PreferredAuthentications", "publickey");
            session.setHostKeyRepository(new AcceptNewKnownHosts(knownHostsFile));
        }
    }

    /**
     * accept-new：首次连接自动记录 host key，之后 key 变了就拒绝（防中间人）。
     * JSch 传进来的 host 已带端口格式（[ssh.github.com]:443），文件格式与 OpenSSH known_hosts 兼容。
     */
    private static final class AcceptNewKnownHosts implements HostKeyRepository {
        private final File file;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();

        AcceptNewKnownHosts(final File file) {
            this.file = file;
            load();
        }

        @Override
        public int check(final String host, final byte[] key) {
            final byte[] known = entries.get(host);
            if (known == null) {
                entries.put(host, key.clone());
                save(host, key);
                return OK;
            }
            return Arrays.equals(known, key) ? OK : CHANGED;
        }

        @Override
        public void add(final HostKey hostkey, final UserInfo ui) {
            // accept-new 路径在 check 里已经入库；JSch 只在用户交互确认后才会走到这里
        }

        @Override
        public void remove(final String host, final String type) {
        }

        @Override
        public void remove(final String host, final String type, final byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return file.getAbsolutePath();
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(final String host, final String type) {
            return new HostKey[0];
        }

        private void load() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String[] parts = line.trim().split("\\s+");
                    if (parts.length != 3) {
                        continue;
                    }
                    try {
                        entries.put(parts[0], Base64.getDecoder().decode(parts[2]));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            } catch (IOException ignored) {
                // 文件还不存在：首次连接时写入
            }
        }

        private void save(final String host, final byte[] key) {
            final String line = host + " " + keyTypeOf(key) + " " + Base64.getEncoder().encodeToString(key);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            } catch (IOException ignored) {
                // 记不进盘只影响下次重新 accept，连接本身不受影响
            }
        }

        /** key blob 开头是 [长度][算法名]，算法名即 known_hosts 里的 keytype */
        private static String keyTypeOf(final byte[] key) {
            if (key.length > 4) {
                final int len = ((key[0] & 0xff) << 24) | ((key[1] & 0xff) << 16)
                        | ((key[2] & 0xff) << 8) | (key[3] & 0xff);
                if (len > 0 && 4 + len <= key.length) {
                    return new String(key, 4, len, StandardCharsets.UTF_8);
                }
            }
            return "unknown";
        }
    }
}
