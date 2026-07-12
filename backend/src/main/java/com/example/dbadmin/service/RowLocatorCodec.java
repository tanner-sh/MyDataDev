package com.example.dbadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Signs row identity values so the browser never has to round-trip database
 * primary keys through JavaScript numbers or display placeholders.
 */
@Component
public class RowLocatorCodec {
    private static final int MAX_TOKEN_LENGTH = 32_768;
    private final ObjectMapper mapper;
    private final CryptoService crypto;

    public RowLocatorCodec(ObjectMapper mapper, CryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public String encode(RowLocatorState state) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(state));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.sign("row:" + payload));
            String token = payload + "." + signature;
            if (token.length() > MAX_TOKEN_LENGTH) throw new IllegalStateException("行定位字段过大，无法安全编辑");
            return token;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("无法生成行定位令牌", error);
        }
    }

    public RowLocatorState decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("行定位令牌无效，请刷新表数据后重试");
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException("行定位令牌无效，请刷新表数据后重试");
            byte[] expected = crypto.sign("row:" + parts[0]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("行定位令牌签名无效，请刷新表数据后重试");
            }
            byte[] json = Base64.getUrlDecoder().decode(parts[0].getBytes(StandardCharsets.US_ASCII));
            return mapper.readValue(json, RowLocatorState.class);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("行定位令牌无效，请刷新表数据后重试", error);
        }
    }

    public record RowLocatorState(
            int version,
            long connectionId,
            String schemaName,
            String tableName,
            List<RowLocatorValue> values
    ) {
        public RowLocatorState {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record RowLocatorValue(String column, int jdbcType, String typeName, String encodedValue) {
    }
}
