package com.example.dbadmin.storage;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FtpBackupStorageTest {
    @Test
    void prefersExtendedPassiveModeForIpv4() {
        StorageConnection connection = new StorageConnection(1, "FTP", "127.0.0.1", 21, "", "e2e", "secret",
                null, null, null, null, null, null, "NONE", null, null, null, null, false);

        FTPClient client = new FtpBackupStorage().createClient(connection);

        assertThat(client.isUseEPSVwithIPv4()).isTrue();
    }
}
