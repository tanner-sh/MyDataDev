package com.example.dbadmin.service.ai;

import com.example.dbadmin.dto.AiDtos.AiSettingsUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSettingsProfileTest {
    private static final UnaryOperator<String> ENCRYPT = value -> "cipher(" + value + ")";

    @Test
    void keepsStoredKeyWhenSubmittedValueIsTheMask() {
        AiSettings existing = new AiSettings(true, AiProvider.ANTHROPIC, null, "claude-opus-5", "cipher(old)", AiEffort.HIGH);
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                true, "ANTHROPIC", null, "claude-opus-5", AiSettingsProfile.SECRET_MASK, "LOW");

        AiSettings settings = AiSettingsProfile.toSettings(request, existing, ENCRYPT);

        assertThat(settings.apiKeyCipher()).isEqualTo("cipher(old)");
        assertThat(settings.effort()).isEqualTo(AiEffort.LOW);
    }

    @Test
    void clearsStoredKeyWhenSubmittedValueIsEmpty() {
        AiSettings existing = new AiSettings(false, AiProvider.ANTHROPIC, null, "claude-opus-5", "cipher(old)", AiEffort.HIGH);
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(false, "ANTHROPIC", null, "claude-opus-5", "", "HIGH");

        assertThat(AiSettingsProfile.toSettings(request, existing, ENCRYPT).apiKeyCipher()).isNull();
    }

    @Test
    void encryptsAndTrimsANewKey() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                true, "ANTHROPIC", null, null, "  sk-ant-example\n", null);

        AiSettings settings = AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT);

        assertThat(settings.apiKeyCipher()).isEqualTo("cipher(sk-ant-example)");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
        assertThat(settings.effort()).isEqualTo(AiEffort.HIGH);
    }

    /** 换服务商时沿用旧 Key 等于拿 Claude 的 Key 去打自建网关，只会得到一个看不懂的 401。 */
    @Test
    void dropsStoredKeyWhenProviderChanges() {
        AiSettings existing = new AiSettings(true, AiProvider.ANTHROPIC, null, "claude-opus-5", "cipher(old)", AiEffort.HIGH);
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                false, "OPENAI_COMPATIBLE", "http://127.0.0.1:11434/v1", "qwen2.5", AiSettingsProfile.SECRET_MASK, "HIGH");

        assertThat(AiSettingsProfile.toSettings(request, existing, ENCRYPT).apiKeyCipher()).isNull();
    }

    @Test
    void requiresAnApiKeyBeforeEnablingTheOfficialApi() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(true, "ANTHROPIC", null, "claude-opus-5", null, "HIGH");

        assertThatThrownBy(() -> AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API Key");
    }

    /** 本地模型通常没有 Key 的概念，强制要求会让离线部署直接用不了。 */
    @Test
    void allowsEnablingACompatibleEndpointWithoutAKey() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                true, "OPENAI_COMPATIBLE", "http://127.0.0.1:11434/v1/", "qwen2.5", null, "HIGH");

        AiSettings settings = AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.apiKeyCipher()).isNull();
        assertThat(settings.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
    }

    @Test
    void rejectsACompatibleEndpointWithoutABaseUrl() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(false, "OPENAI_COMPATIBLE", " ", "qwen2.5", null, "HIGH");

        assertThatThrownBy(() -> AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("接口地址");
    }

    @Test
    void rejectsANonHttpBaseUrl() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                false, "OPENAI_COMPATIBLE", "ftp://models.internal/v1", "qwen2.5", null, "HIGH");

        assertThatThrownBy(() -> AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http://");
    }

    /** 官方 API 的地址由 SDK 决定；留着一个填了不生效的值只会误导管理员。 */
    @Test
    void clearsBaseUrlForTheOfficialApi() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                false, "ANTHROPIC", "https://example.invalid", "claude-opus-5", "sk-ant", "HIGH");

        assertThat(AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT).baseUrl()).isNull();
    }

    @Test
    void requiresAModelWhenTheProviderHasNoDefault() {
        AiSettingsUpdateRequest request = new AiSettingsUpdateRequest(
                false, "OPENAI_COMPATIBLE", "http://127.0.0.1:11434/v1", "  ", null, "HIGH");

        assertThatThrownBy(() -> AiSettingsProfile.toSettings(request, AiSettings.disabled(), ENCRYPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型名称");
    }

    @Test
    void summaryNeverLeaksTheCiphertext() {
        AiSettings settings = new AiSettings(true, AiProvider.ANTHROPIC, null, "claude-opus-5", "cipher(secret)", AiEffort.MAX);

        var response = AiSettingsProfile.summarize(settings);

        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("cipher(");
        assertThat(response.effort()).isEqualTo("MAX");
    }
}
