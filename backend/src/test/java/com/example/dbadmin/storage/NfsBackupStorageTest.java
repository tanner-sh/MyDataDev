package com.example.dbadmin.storage;
import com.emc.ecs.nfsclient.nfs.io.Nfs3File;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class NfsBackupStorageTest {
    @Test void reusesExistingDirectoryWithoutInvokingNonIdempotentMkdirs() throws Exception {
        var dir = mock(Nfs3File.class); when(dir.exists()).thenReturn(true); when(dir.isDirectory()).thenReturn(true);
        NfsBackupStorage.ensureDirectory(dir); NfsBackupStorage.ensureDirectory(dir);
        verify(dir, never()).mkdirs();
    }
    @Test void acceptsConcurrentDirectoryCreationButNotPermissionOrFileErrors() throws Exception {
        var dir = mock(Nfs3File.class); when(dir.exists()).thenReturn(false, true); when(dir.isDirectory()).thenReturn(true);
        doThrow(new IOException("EEXIST")).when(dir).mkdirs();
        NfsBackupStorage.ensureDirectory(dir);
        var denied = mock(Nfs3File.class); doThrow(new IOException("permission denied")).when(denied).mkdirs();
        assertThatThrownBy(() -> NfsBackupStorage.ensureDirectory(denied)).hasMessageContaining("permission denied");
        var file = mock(Nfs3File.class); when(file.exists()).thenReturn(true);
        assertThatThrownBy(() -> NfsBackupStorage.ensureDirectory(file)).hasMessageContaining("不是目录"); verify(file, never()).mkdirs();
    }
}
