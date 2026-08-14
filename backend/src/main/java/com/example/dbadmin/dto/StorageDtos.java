package com.example.dbadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class StorageDtos {
    private StorageDtos() {
    }

    public record StorageProfileRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 20) String type,
            @NotBlank @Size(max = 500) String host,
            @Min(1) @Max(65535) Integer port,
            @Size(max = 1000) String basePath,
            @Size(max = 500) String username,
            @Size(max = 20_000) String password,
            @Size(max = 500) String smbShare,
            @Size(max = 500) String smbDomain,
            @Size(max = 1000) String nfsExportPath,
            @Min(0) Integer nfsUid,
            @Min(0) Integer nfsGid,
            List<@Min(0) Integer> nfsGroups,
            @Size(max = 20) String ftpTlsMode,
            @Size(max = 20) String sftpAuthMode,
            @Size(max = 100_000) String privateKey,
            @Size(max = 20_000) String privateKeyPassphrase,
            @Size(max = 200) String serverFingerprint,
            boolean skipServerVerification,
            boolean enabled
    ) {
    }

    public record StorageProfileResponse(
            long id,
            String name,
            String type,
            String host,
            int port,
            String basePath,
            String username,
            boolean passwordConfigured,
            String smbShare,
            String smbDomain,
            String nfsExportPath,
            Integer nfsUid,
            Integer nfsGid,
            List<Integer> nfsGroups,
            String ftpTlsMode,
            String sftpAuthMode,
            boolean privateKeyConfigured,
            boolean privateKeyPassphraseConfigured,
            String serverFingerprint,
            boolean skipServerVerification,
            boolean enabled,
            String lastTestStatus,
            String lastTestMessage,
            Instant lastTestedAt,
            int taskReferences,
            int historyReferences
    ) {
    }

    public record StorageTestResponse(boolean ok, String message) {
    }
}
