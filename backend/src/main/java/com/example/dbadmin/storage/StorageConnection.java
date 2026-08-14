package com.example.dbadmin.storage;

import java.util.List;

public record StorageConnection(
        long id,
        String type,
        String host,
        int port,
        String basePath,
        String username,
        String password,
        String smbShare,
        String smbDomain,
        String nfsExportPath,
        Integer nfsUid,
        Integer nfsGid,
        List<Integer> nfsGroups,
        String ftpTlsMode,
        String sftpAuthMode,
        String privateKey,
        String privateKeyPassphrase,
        String serverFingerprint,
        boolean skipServerVerification
) {
}
