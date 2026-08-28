package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServiceBinaryTest {
    @Test
    void stopsProbingBinaryStreamsAfterOneMegabyte() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[2 * 1024 * 1024]);

        assertThat(SqlService.describeBinaryStream(input)).isEqualTo("<BINARY > 1 MB>");
        assertThat(input.available()).isPositive();
    }

    @Test
    void reportsExactLengthForSmallBinaryStreams() throws Exception {
        assertThat(SqlService.describeBinaryStream(new ByteArrayInputStream(new byte[17])))
                .isEqualTo("<BINARY 17 bytes>");
    }
}
