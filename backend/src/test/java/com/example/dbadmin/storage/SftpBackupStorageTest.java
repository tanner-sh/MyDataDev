package com.example.dbadmin.storage;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SftpBackupStorageTest {
    private static final String TEMPORARY = "/backups/task.sql.part-1";
    private static final String TARGET = "/backups/task.sql";

    @Test
    void renamesWithoutCopyOptionsForSftpV3() throws Exception {
        SftpClient sftp = mock(SftpClient.class);
        when(sftp.getVersion()).thenReturn(SftpConstants.SFTP_V3);
        when(sftp.stat(TARGET)).thenThrow(new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "missing"));

        SftpBackupStorage.moveIntoPlace(sftp, TEMPORARY, TARGET);

        verify(sftp).rename(TEMPORARY, TARGET);
        verify(sftp, never()).remove(TARGET);
    }

    @Test
    void removesExistingTargetBeforeRenamingForSftpV3() throws Exception {
        SftpClient sftp = mock(SftpClient.class);
        when(sftp.getVersion()).thenReturn(SftpConstants.SFTP_V3);
        when(sftp.stat(TARGET)).thenReturn(new SftpClient.Attributes());

        SftpBackupStorage.moveIntoPlace(sftp, TEMPORARY, TARGET);

        verify(sftp).remove(TARGET);
        verify(sftp).rename(TEMPORARY, TARGET);
    }

    @Test
    void usesOverwriteRenameWhenProtocolSupportsCopyOptions() throws Exception {
        SftpClient sftp = mock(SftpClient.class);
        when(sftp.getVersion()).thenReturn(SftpConstants.SFTP_V5);

        SftpBackupStorage.moveIntoPlace(sftp, TEMPORARY, TARGET);

        verify(sftp).rename(TEMPORARY, TARGET, SftpClient.CopyMode.Overwrite);
        verify(sftp, never()).stat(TARGET);
        verify(sftp, never()).remove(TARGET);
    }

    @Test
    void preservesUnexpectedStatFailure() throws Exception {
        SftpClient sftp = mock(SftpClient.class);
        when(sftp.getVersion()).thenReturn(SftpConstants.SFTP_V3);
        when(sftp.stat(TARGET)).thenThrow(new SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "denied"));

        assertThatThrownBy(() -> SftpBackupStorage.moveIntoPlace(sftp, TEMPORARY, TARGET))
                .isInstanceOf(SftpException.class)
                .hasMessageContaining("denied");

        verify(sftp, never()).rename(TEMPORARY, TARGET);
    }
}
