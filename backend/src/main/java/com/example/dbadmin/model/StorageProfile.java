package com.example.dbadmin.model;

import java.time.Instant;

public record StorageProfile(
        long id,
        String name,
        String type,
        String host,
        int port,
        String basePath,
        String username,
        String encryptedPassword,
        String smbShare,
        String smbDomain,
        String nfsExportPath,
        Integer nfsUid,
        Integer nfsGid,
        String nfsGroups,
        String ftpTlsMode,
        String sftpAuthMode,
        String encryptedPrivateKey,
        String encryptedPrivateKeyPassphrase,
        String serverFingerprint,
        boolean skipServerVerification,
        boolean enabled,
        String lastTestStatus,
        String lastTestMessage,
        Instant lastTestedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
