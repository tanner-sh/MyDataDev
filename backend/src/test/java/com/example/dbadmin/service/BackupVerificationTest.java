package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class BackupVerificationTest {
    @Test
    void computesTheSameChecksumTheBackupItselfRecorded() throws Exception {
        byte[] content = "INSERT INTO t VALUES (1);\n".repeat(50).getBytes(StandardCharsets.UTF_8);
        BackupVerification verification = new BackupVerification();

        // 分块写入：真实下载就是一块一块来的，摘要不能依赖块边界。
        for (int offset = 0; offset < content.length; offset += 7) {
            verification.write(content, offset, Math.min(7, content.length - offset));
        }

        assertThat(verification.size()).isEqualTo(content.length);
        assertThat(verification.checksum()).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
    }

    /** 整份文件可能有几个 GB，看结尾不能把它留在内存里。 */
    @Test
    void keepsOnlyTheTailNoMatterHowLargeTheFileIs() {
        BackupVerification verification = new BackupVerification();
        byte[] filler = "x".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        byte[] ending = "\nCOMMIT;\n".getBytes(StandardCharsets.UTF_8);

        verification.write(filler, 0, filler.length);
        verification.write(ending, 0, ending.length);

        assertThat(verification.tailText()).hasSizeLessThanOrEqualTo(BackupVerification.TAIL_BYTES);
        assertThat(verification.tailText()).endsWith("COMMIT;\n");
    }

    @Test
    void readsTheTailAcrossManyTinyWrites() {
        BackupVerification verification = new BackupVerification();
        for (byte value : "-- done\n".getBytes(StandardCharsets.UTF_8)) verification.write(value);

        assertThat(verification.tailText()).isEqualTo("-- done\n");
    }

    /**
     * 停在一条语句中间的文件，几乎一定是备份进程被杀或磁盘写满留下的半成品。但这只作提示：
     * 各家 dump 的收尾写法不完全一致，硬判会把好文件说成坏文件。
     */
    @Test
    void spotsAFileThatStopsMidStatement() {
        assertThat(BackupVerification.looksComplete("INSERT INTO t VALUES (1);\n")).isTrue();
        assertThat(BackupVerification.looksComplete("INSERT INTO t VALUES (1);\n\n\n")).isTrue();
        assertThat(BackupVerification.looksComplete("-- Dump completed on 2026-09-05\n")).isTrue();
        assertThat(BackupVerification.looksComplete("/* 结束 */")).isTrue();
        assertThat(BackupVerification.looksComplete("INSERT INTO t VALUES (1, 'half")).isFalse();
        assertThat(BackupVerification.looksComplete("")).isFalse();
        assertThat(BackupVerification.looksComplete(null)).isFalse();
    }

    @Test
    void handlesAnEmptyFile() {
        BackupVerification verification = new BackupVerification();

        assertThat(verification.size()).isZero();
        assertThat(verification.tailText()).isEmpty();
    }
}
