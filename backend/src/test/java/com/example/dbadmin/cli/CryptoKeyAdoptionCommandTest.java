package com.example.dbadmin.cli;

import com.example.dbadmin.service.CryptoKeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoKeyAdoptionCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOnlyAPendingKeyUntilNormalStartupValidatesIt() throws Exception {
        Path keyFile = temporaryDirectory.resolve("secrets/master.key");
        ArrayDeque<char[]> inputs = new ArrayDeque<>();
        inputs.add("existing-key".toCharArray());
        inputs.add("existing-key".toCharArray());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int result = CryptoKeyAdoptionCommand.run(
                new String[]{"crypto-key", "adopt", "--key-file", keyFile.toString()},
                ignored -> inputs.removeFirst(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result).isZero();
        assertThat(keyFile).doesNotExist();
        assertThat(CryptoKeyStore.pendingPath(keyFile)).hasContent("existing-key\n");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("待验证").doesNotContain("existing-key");
    }

    @Test
    void mismatchedConfirmationDoesNotWriteAKey() {
        Path keyFile = temporaryDirectory.resolve("master.key");
        ArrayDeque<char[]> inputs = new ArrayDeque<>();
        inputs.add("first-key".toCharArray());
        inputs.add("second-key".toCharArray());
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = CryptoKeyAdoptionCommand.run(
                new String[]{"crypto-key", "adopt", "--key-file=" + keyFile},
                ignored -> inputs.removeFirst(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8)
        );

        assertThat(result).isEqualTo(2);
        assertThat(keyFile).doesNotExist();
        assertThat(CryptoKeyStore.pendingPath(keyFile)).doesNotExist();
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("不一致");
    }
}
