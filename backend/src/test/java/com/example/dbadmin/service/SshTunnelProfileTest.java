package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SshTunnelRequest;
import com.example.dbadmin.model.SshTunnelSettings;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshTunnelProfileTest {
    /** 测试里用可逆的假加密，好让断言直接读到明文。 */
    private static final UnaryOperator<String> ENCRYPT = value -> "enc:" + value;
    private static final UnaryOperator<String> DECRYPT = value -> value.substring("enc:".length());

    @Test
    void disabledRequestClearsEverything() {
        SshTunnelSettings settings = SshTunnelProfile.toSettings(
                new SshTunnelRequest(false, "bastion", 22, "ops", "PASSWORD", "secret", null, null, null, true),
                null, ENCRYPT);

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.host()).isNull();
        assertThat(settings.encryptedPassword()).isNull();
        assertThat(SshTunnelProfile.toSpec(settings, DECRYPT)).isNull();
    }

    @Test
    void missingRequestMeansNoTunnel() {
        assertThat(SshTunnelProfile.toSettings(null, null, ENCRYPT).enabled()).isFalse();
        assertThat(SshTunnelProfile.toSpec(null, DECRYPT)).isNull();
    }

    @Test
    void encryptsSecretsAndDefaultsPortAndAuthMode() {
        SshTunnelSettings settings = SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, " bastion.internal ", null, " ops ", null, "secret", null, null, "SHA256:abc", false),
                null, ENCRYPT);

        assertThat(settings.host()).isEqualTo("bastion.internal");
        assertThat(settings.username()).isEqualTo("ops");
        assertThat(settings.port()).isEqualTo(22);
        assertThat(settings.authMode()).isEqualTo(SshTunnelSpec.AUTH_PASSWORD);
        assertThat(settings.encryptedPassword()).isEqualTo("enc:secret");

        SshTunnelSpec spec = SshTunnelProfile.toSpec(settings, DECRYPT);
        assertThat(spec.password()).isEqualTo("secret");
        assertThat(spec.usesPrivateKey()).isFalse();
    }

    @Test
    void maskKeepsStoredSecretsAndEmptyStringClearsThem() {
        SshTunnelSettings stored = new SshTunnelSettings(true, "bastion", 2222, "ops", "PRIVATE_KEY",
                "enc:old-password", "enc:old-key", "enc:old-passphrase", "SHA256:abc", false);

        SshTunnelSettings kept = SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 2222, "ops", "PRIVATE_KEY",
                        SshTunnelProfile.SECRET_MASK, SshTunnelProfile.SECRET_MASK, SshTunnelProfile.SECRET_MASK,
                        "SHA256:abc", false),
                stored, ENCRYPT);
        assertThat(kept.encryptedPrivateKey()).isEqualTo("enc:old-key");
        assertThat(kept.encryptedPassphrase()).isEqualTo("enc:old-passphrase");

        SshTunnelSettings cleared = SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 2222, "ops", "PRIVATE_KEY",
                        "", SshTunnelProfile.SECRET_MASK, "", "SHA256:abc", false),
                stored, ENCRYPT);
        assertThat(cleared.encryptedPassword()).isNull();
        assertThat(cleared.encryptedPassphrase()).isNull();
        assertThat(cleared.encryptedPrivateKey()).isEqualTo("enc:old-key");
    }

    @Test
    void rejectsIncompleteConfigurations() {
        assertThatThrownBy(() -> SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, " ", 22, "ops", "PASSWORD", "secret", null, null, null, true), null, ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("跳板机地址");

        assertThatThrownBy(() -> SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 22, null, "PASSWORD", "secret", null, null, null, true), null, ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名");

        assertThatThrownBy(() -> SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 22, "ops", "PRIVATE_KEY", null, null, null, null, true), null, ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("私钥");

        assertThatThrownBy(() -> SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 70000, "ops", "PASSWORD", "secret", null, null, null, true), null, ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口");

        assertThatThrownBy(() -> SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 22, "ops", "TOTP", "secret", null, null, null, true), null, ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("认证方式");
    }

    @Test
    void requiresFingerprintUnlessHostKeyCheckIsExplicitlySkipped() {
        assertThatThrownBy(() -> SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 22, "ops", "PASSWORD", "secret", null, null, null, false), null, ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机指纹");

        assertThat(SshTunnelProfile.toSettings(
                new SshTunnelRequest(true, "bastion", 22, "ops", "PASSWORD", "secret", null, null, null, true), null, ENCRYPT)
                .skipHostKeyCheck()).isTrue();
    }

    @Test
    void summaryReportsWhichSecretsExistWithoutLeakingThem() {
        SshTunnelSettings settings = new SshTunnelSettings(true, "bastion", 2222, "ops", "PRIVATE_KEY",
                null, "enc:key", "enc:passphrase", "SHA256:abc", false);

        var summary = SshTunnelProfile.summarize(settings);
        assertThat(summary.enabled()).isTrue();
        assertThat(summary.hasPassword()).isFalse();
        assertThat(summary.hasPrivateKey()).isTrue();
        assertThat(summary.hasPassphrase()).isTrue();
        assertThat(summary.serverFingerprint()).isEqualTo("SHA256:abc");
        assertThat(summary.toString()).doesNotContain("enc:");

        var empty = SshTunnelProfile.summarize(null);
        assertThat(empty.enabled()).isFalse();
        assertThat(empty.port()).isEqualTo(SshTunnelSettings.DEFAULT_PORT);
    }
}
