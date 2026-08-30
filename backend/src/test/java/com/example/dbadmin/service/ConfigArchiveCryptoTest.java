package com.example.dbadmin.service;

import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 归档口令加密。
 *
 * <p>这是整个导出功能的安全核心：文件里装着全部连接的明文密码，一旦这层能被绕开，
 * 导出就等于把凭据以明文散出去。</p>
 */
class ConfigArchiveCryptoTest {
    private final ConfigArchiveCrypto crypto = new ConfigArchiveCrypto();
    private static final char[] PASSPHRASE = "correct-horse-battery-staple".toCharArray();

    @Test
    void roundTripsThroughThePassphrase() {
        ArchiveEnvelope sealed = crypto.seal("{\"secret\":\"p@ss\"}", PASSPHRASE);

        assertThat(crypto.open(sealed, PASSPHRASE)).isEqualTo("{\"secret\":\"p@ss\"}");
        assertThat(sealed.format()).isEqualTo(ConfigArchiveCrypto.FORMAT);
        assertThat(sealed.iterations()).isEqualTo(ConfigArchiveCrypto.DEFAULT_ITERATIONS);
    }

    @Test
    void sealedPayloadLeaksNoPlaintext() {
        ArchiveEnvelope sealed = crypto.seal("{\"password\":\"super-secret-value\"}", PASSPHRASE);

        assertThat(sealed.payload()).doesNotContain("super-secret-value").doesNotContain("password");
    }

    @Test
    void everyExportUsesFreshSaltAndIv() {
        ArchiveEnvelope first = crypto.seal("same content", PASSPHRASE);
        ArchiveEnvelope second = crypto.seal("same content", PASSPHRASE);

        // 盐或 IV 复用会让两份归档的密文出现可比对的结构。
        assertThat(first.salt()).isNotEqualTo(second.salt());
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.payload()).isNotEqualTo(second.payload());
    }

    @Test
    void wrongPassphraseIsRejected() {
        ArchiveEnvelope sealed = crypto.seal("payload", PASSPHRASE);

        assertThatThrownBy(() -> crypto.open(sealed, "wrong-passphrase-here".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("口令不正确");
    }

    @Test
    void tamperedPayloadIsRejected() {
        ArchiveEnvelope sealed = crypto.seal("payload", PASSPHRASE);
        byte[] bytes = Base64.getDecoder().decode(sealed.payload());
        bytes[0] ^= 0x01;
        ArchiveEnvelope tampered = new ArchiveEnvelope(sealed.format(), sealed.version(), sealed.kdf(),
                sealed.iterations(), sealed.salt(), sealed.cipher(), sealed.iv(), Base64.getEncoder().encodeToString(bytes));

        assertThatThrownBy(() -> crypto.open(tampered, PASSPHRASE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headerIsAuthenticatedSoKdfParametersCannotBeDowngraded() {
        ArchiveEnvelope sealed = crypto.seal("payload", PASSPHRASE);
        // 文件头进了 AAD：把迭代次数改小会直接解密失败，而不是用错误参数去派生一个弱密钥。
        ArchiveEnvelope downgraded = new ArchiveEnvelope(sealed.format(), sealed.version(), sealed.kdf(),
                150_000, sealed.salt(), sealed.cipher(), sealed.iv(), sealed.payload());

        assertThatThrownBy(() -> crypto.open(downgraded, PASSPHRASE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesWeakPassphrases() {
        assertThatThrownBy(() -> crypto.seal("payload", "short".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 12 位");
        assertThatThrownBy(() -> crypto.seal("payload", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesForeignOrUnsupportedArchives() {
        ArchiveEnvelope sealed = crypto.seal("payload", PASSPHRASE);

        assertThatThrownBy(() -> crypto.open(withFormat(sealed, "some-other-tool"), PASSPHRASE))
                .hasMessageContaining("不是 MyDataDev 配置归档文件");
        assertThatThrownBy(() -> crypto.open(withVersion(sealed, 99), PASSPHRASE))
                .hasMessageContaining("版本不支持");
        // 迭代次数低到不可能是本程序生成的，说明文件被人改过。
        assertThatThrownBy(() -> crypto.open(withIterations(sealed, 1), PASSPHRASE))
                .hasMessageContaining("密钥派生参数");
        assertThatThrownBy(() -> crypto.open(null, PASSPHRASE)).hasMessageContaining("归档内容为空");
    }

    private static ArchiveEnvelope withFormat(ArchiveEnvelope envelope, String format) {
        return new ArchiveEnvelope(format, envelope.version(), envelope.kdf(), envelope.iterations(),
                envelope.salt(), envelope.cipher(), envelope.iv(), envelope.payload());
    }

    private static ArchiveEnvelope withVersion(ArchiveEnvelope envelope, int version) {
        return new ArchiveEnvelope(envelope.format(), version, envelope.kdf(), envelope.iterations(),
                envelope.salt(), envelope.cipher(), envelope.iv(), envelope.payload());
    }

    private static ArchiveEnvelope withIterations(ArchiveEnvelope envelope, int iterations) {
        return new ArchiveEnvelope(envelope.format(), envelope.version(), envelope.kdf(), iterations,
                envelope.salt(), envelope.cipher(), envelope.iv(), envelope.payload());
    }
}
