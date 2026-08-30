package com.example.dbadmin.service;

import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveEnvelope;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 配置归档的口令加密。
 *
 * <p>不能复用 {@link CryptoService}：它的密钥来自本机的 {@code app.crypto-key}，而导出的整个
 * 意义就是把配置搬到另一台装机上，那边的密钥必然不同。所以归档用调用者自己给的口令派生密钥。</p>
 *
 * <p>口令是人记的，熵远低于随机密钥，因此必须过 KDF 而不是直接哈希：PBKDF2-HMAC-SHA256，
 * 每次导出新生成盐，迭代次数写进文件头，将来调高不会让旧文件解不开。载荷用 AES-256-GCM，
 * 并把文件头作为附加认证数据绑进去 —— 改动迭代次数或盐会直接导致解密失败，而不是悄悄
 * 用一个错误的参数去尝试。</p>
 */
@Service
public class ConfigArchiveCrypto {
    public static final String FORMAT = "mydatadev-config-archive";
    public static final int VERSION = 1;
    /** OWASP 对 PBKDF2-HMAC-SHA256 的推荐下限量级；导出导入是低频操作，付得起这个时间。 */
    public static final int DEFAULT_ITERATIONS = 210_000;
    /** 允许解开的最小迭代次数：低于这个数的文件不是本程序生成的，就是被人改小了。 */
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 5_000_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    /** 口令下限与 Web 管理员初始密码一致，避免这里成为整套凭据的薄弱环节。 */
    public static final int MIN_PASSPHRASE_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public ArchiveEnvelope seal(String plaintext, char[] passphrase) {
        requirePassphrase(passphrase);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        ArchiveEnvelope header = new ArchiveEnvelope(FORMAT, VERSION, "PBKDF2WithHmacSHA256", DEFAULT_ITERATIONS,
                encode(salt), "AES/GCM/NoPadding", encode(iv), null);
        byte[] key = deriveKey(passphrase, salt, DEFAULT_ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(header));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new ArchiveEnvelope(header.format(), header.version(), header.kdf(), header.iterations(),
                    header.salt(), header.cipher(), header.iv(), encode(sealed));
        } catch (Exception error) {
            throw new IllegalStateException("配置归档加密失败", error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public String open(ArchiveEnvelope envelope, char[] passphrase) {
        requirePassphrase(passphrase);
        validate(envelope);
        byte[] salt = decode(envelope.salt(), "salt");
        byte[] iv = decode(envelope.iv(), "iv");
        byte[] key = deriveKey(passphrase, salt, envelope.iterations());
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(envelope));
            return new String(cipher.doFinal(decode(envelope.payload(), "payload")), StandardCharsets.UTF_8);
        } catch (Exception error) {
            // GCM 校验失败分不清「口令错」和「文件被改过」，也不该分：两种情况的处置一样。
            throw new IllegalArgumentException("口令不正确，或归档文件已损坏。");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void validate(ArchiveEnvelope envelope) {
        if (envelope == null) throw new IllegalArgumentException("归档内容为空。");
        if (!FORMAT.equals(envelope.format())) {
            throw new IllegalArgumentException("这不是 MyDataDev 配置归档文件。");
        }
        if (envelope.version() != VERSION) {
            throw new IllegalArgumentException("归档格式版本不支持：" + envelope.version() + "，当前支持 " + VERSION + "。");
        }
        if (!"PBKDF2WithHmacSHA256".equals(envelope.kdf()) || !"AES/GCM/NoPadding".equals(envelope.cipher())) {
            throw new IllegalArgumentException("归档使用了不支持的加密算法。");
        }
        if (envelope.iterations() < MIN_ITERATIONS || envelope.iterations() > MAX_ITERATIONS) {
            throw new IllegalArgumentException("归档的密钥派生参数不在允许范围内。");
        }
    }

    private void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw new IllegalArgumentException("归档口令至少需要 " + MIN_PASSPHRASE_LENGTH + " 位。");
        }
    }

    /** 文件头进 AAD：盐和迭代次数被改动时解密直接失败，不会用错参数默默重试。 */
    private static byte[] associatedData(ArchiveEnvelope header) {
        return (header.format() + "|" + header.version() + "|" + header.kdf() + "|"
                + header.iterations() + "|" + header.salt() + "|" + header.cipher() + "|" + header.iv())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("无法从口令派生密钥", error);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] decode(String value, String field) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("归档文件的 " + field + " 字段不是合法的 Base64。");
        }
    }
}
