/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.sqlite;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SQLite 笔记本的值级加密原语, 与 vsnote 的 src-tauri/src/sqlite_store.rs 逐字节兼容。
 * <p>
 * 未设密码时数据库是完全标准的 SQLite 文件; 设密码后只有 node.name / node_content.content /
 * node_blob.data 三处的值被加密, 表结构与时间戳仍为明文。
 */
public final class SqliteCrypto {

    /** PBKDF2-HMAC-SHA256 轮数, 与 vsnote 的 KDF_ITERS 一致 */
    public static final int KDF_ITERATIONS = 600_000;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** seal 后附加的固定长度开销: nonce + gcm tag */
    public static final int SEALED_OVERHEAD_BYTES = NONCE_BYTES + (TAG_BITS / 8);

    public static final String META_ENC_SALT = "enc_salt";
    public static final String META_ENC_CHECK = "enc_check";
    /** 解密后等于该值即密码正确 (与 vsnote 的 ENC_CHECK_PLAIN 一致) */
    public static final byte[] ENC_CHECK_PLAIN = "vsnote-db-ok".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();

    private SqliteCrypto() {
    }

    //########################
    //## KDF
    //########################

    /**
     * 纯 Java 实现的 PBKDF2-HMAC-SHA256。
     * 不用 SecretKeyFactory("PBKDF2WithHmacSHA256") 是因为该算法名要 API 26 才有,
     * 而本包 minSdk 为 18; Mac("HmacSHA256") 全版本可用, 输出与标准实现一致。
     */
    public static byte[] deriveKey(@NonNull final char[] password, @NonNull final byte[] salt) {
        final byte[] passwordBytes = new String(password).getBytes(StandardCharsets.UTF_8);
        final Mac mac = hmac(passwordBytes);
        final int hashBytes = 32;
        final int blocks = (KEY_BYTES + hashBytes - 1) / hashBytes;
        final byte[] out = new byte[KEY_BYTES];
        final byte[] block = new byte[hashBytes];
        final byte[] firstInput = new byte[salt.length + 4];
        System.arraycopy(salt, 0, firstInput, 0, salt.length);
        for (int i = 1; i <= blocks; i++) {
            firstInput[salt.length] = (byte) (i >>> 24);
            firstInput[salt.length + 1] = (byte) (i >>> 16);
            firstInput[salt.length + 2] = (byte) (i >>> 8);
            firstInput[salt.length + 3] = (byte) i;
            byte[] previous = mac.doFinal(firstInput);
            System.arraycopy(previous, 0, block, 0, hashBytes);
            for (int round = 1; round < KDF_ITERATIONS; round++) {
                previous = mac.doFinal(previous);
                for (int j = 0; j < hashBytes; j++) {
                    block[j] ^= previous[j];
                }
            }
            System.arraycopy(block, 0, out, (i - 1) * hashBytes,
                    Math.min(hashBytes, KEY_BYTES - (i - 1) * hashBytes));
        }
        return out;
    }

    @NonNull
    public static byte[] randomSalt() {
        final byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    private static Mac hmac(final byte[] key) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    //########################
    //## AES-256-GCM
    //########################

    /** @return nonce(12B) || ciphertext || tag(16B) */
    @NonNull
    public static byte[] seal(@NonNull final byte[] key, @NonNull final byte[] plain) {
        final byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        final byte[] body;
        try {
            body = gcm(Cipher.ENCRYPT_MODE, key, nonce, plain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM encrypt failed", e);
        }
        final byte[] out = new byte[nonce.length + body.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(body, 0, out, nonce.length, body.length);
        return out;
    }

    /** @return 明文; 密钥不符或数据损坏时返回 null */
    @Nullable
    public static byte[] unseal(@NonNull final byte[] key, @Nullable final byte[] sealed) {
        if (sealed == null || sealed.length < SEALED_OVERHEAD_BYTES) {
            return null;
        }
        try {
            return gcm(Cipher.DECRYPT_MODE, key,
                    Arrays.copyOfRange(sealed, 0, NONCE_BYTES),
                    Arrays.copyOfRange(sealed, NONCE_BYTES, sealed.length));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 解密失败 = 密码错误 / 值被改动 / 不是本格式的密文, 与 vsnote 一样按 None 处理
            return null;
        }
    }

    private static byte[] gcm(final int mode, final byte[] key, final byte[] nonce, final byte[] input)
            throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return cipher.doFinal(input);
    }

    //########################
    //## 列级封装 (key == null 表示明文库, 值原样存取)
    //########################

    public static String sealText(@Nullable final byte[] key, final String text) {
        return key == null ? text : encodeBase64(seal(key, text.getBytes(StandardCharsets.UTF_8)));
    }

    @Nullable
    public static String unsealText(@Nullable final byte[] key, @Nullable final String stored) {
        if (stored == null) {
            return null;
        }
        if (key == null) {
            return stored;
        }
        final byte[] sealed = decodeBase64(stored);
        if (sealed == null) {
            return null;
        }
        final byte[] plain = unseal(key, sealed);
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }

    public static byte[] sealBlob(@Nullable final byte[] key, final byte[] data) {
        return key == null ? data : seal(key, data);
    }

    @Nullable
    public static byte[] unsealBlob(@Nullable final byte[] key, @Nullable final byte[] stored) {
        if (stored == null) {
            return null;
        }
        return key == null ? stored : unseal(key, stored);
    }

    //########################
    //## base64 / 密钥序列化
    //########################

    public static String encodeBase64(@NonNull final byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    /** @return 解码结果; 输入非法 base64 时返回 null (与 vsnote 的 .ok()? 语义一致) */
    @Nullable
    public static byte[] decodeBase64(@Nullable final String text) {
        if (text == null) {
            return null;
        }
        try {
            return Base64.decode(text.trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String encodeKey(@NonNull final byte[] key) {
        return encodeBase64(key);
    }

    /** 长度不对或 base64 损坏一律按“未记住”处理, 避免用错密钥解密 */
    @Nullable
    public static byte[] decodeKey(@Nullable final String encoded) {
        final byte[] key = decodeBase64(encoded);
        return (key == null || key.length != KEY_BYTES) ? null : key;
    }
}
