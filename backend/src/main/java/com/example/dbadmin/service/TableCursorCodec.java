package com.example.dbadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

@Component
public class TableCursorCodec {
    private static final int MAX_CURSOR_LENGTH = 8_192;
    private final ObjectMapper mapper;
    private final CryptoService crypto;

    public TableCursorCodec(ObjectMapper mapper, CryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public String encode(CursorState state) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(state));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.sign("cursor:" + payload));
            String token = payload + "." + signature;
            if (token.length() > MAX_CURSOR_LENGTH) throw new IllegalStateException("分页键过大，无法生成安全游标");
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法生成分页游标", e);
        }
    }

    public CursorState decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException("分页游标无效");
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException("分页游标无效");
            byte[] expected = crypto.sign("cursor:" + parts[0]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("分页游标签名无效");
            byte[] json = Base64.getUrlDecoder().decode(parts[0].getBytes(StandardCharsets.US_ASCII));
            return mapper.readValue(json, CursorState.class);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("分页游标无效", e);
        }
    }

    public record CursorState(
            int version,
            String mode,
            long connectionId,
            String schemaName,
            String tableName,
            List<String> keyColumns,
            List<String> keyValues,
            long offset
    ) {
        public CursorState {
            keyColumns = keyColumns == null ? List.of() : List.copyOf(keyColumns);
            keyValues = keyValues == null ? List.of() : List.copyOf(keyValues);
        }
    }
}
