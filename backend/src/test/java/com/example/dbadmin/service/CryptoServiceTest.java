package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {
    @Test
    void rejectsStartupWithoutAnExplicitEncryptionKey() {
        assertThatThrownBy(() -> new CryptoService(new AppProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_ADMIN_CRYPTO_KEY");
    }
}
