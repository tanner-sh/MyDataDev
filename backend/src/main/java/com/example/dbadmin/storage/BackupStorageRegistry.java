package com.example.dbadmin.storage;

import com.example.dbadmin.model.StorageProfile;
import com.example.dbadmin.repo.StorageProfileRepository;
import com.example.dbadmin.service.CryptoService;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

@Component
public class BackupStorageRegistry {
    private final StorageProfileRepository profiles;
    private final CryptoService crypto;
    private final Map<String, BackupStorage> adapters;

    public BackupStorageRegistry(StorageProfileRepository profiles, CryptoService crypto, List<BackupStorage> adapters) {
        this.profiles = profiles;
        this.crypto = crypto;
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(value -> value.type().toUpperCase(Locale.ROOT), value -> value));
    }

    public StorageProfile requireProfile(long id) {
        return profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("文件服务配置不存在：" + id));
    }

    public StorageConnection connection(StorageProfile profile) {
        List<Integer> groups = profile.nfsGroups() == null || profile.nfsGroups().isBlank() ? List.of()
                : Arrays.stream(profile.nfsGroups().split(",")).filter(value -> !value.isBlank()).map(Integer::valueOf).toList();
        return new StorageConnection(profile.id(), profile.type(), profile.host(), profile.port(), profile.basePath(),
                profile.username(), crypto.decrypt(profile.encryptedPassword()), profile.smbShare(), profile.smbDomain(),
                profile.nfsExportPath(), profile.nfsUid(), profile.nfsGid(), groups, profile.ftpTlsMode(), profile.sftpAuthMode(),
                crypto.decrypt(profile.encryptedPrivateKey()), crypto.decrypt(profile.encryptedPrivateKeyPassphrase()),
                profile.serverFingerprint(), profile.skipServerVerification());
    }

    public void test(StorageProfile profile) throws Exception {
        adapter(profile).test(connection(profile));
    }

    public void upload(StorageProfile profile, Path source, String objectKey, LongConsumer progress) throws Exception {
        adapter(profile).upload(connection(profile), source, objectKey, progress);
    }

    public void download(StorageProfile profile, String objectKey, OutputStream output, LongConsumer progress) throws Exception {
        adapter(profile).download(connection(profile), objectKey, output, progress);
    }

    public long size(StorageProfile profile, String objectKey) throws Exception {
        return adapter(profile).size(connection(profile), objectKey);
    }

    public void delete(StorageProfile profile, String objectKey) throws Exception {
        adapter(profile).delete(connection(profile), objectKey);
    }

    private BackupStorage adapter(StorageProfile profile) {
        BackupStorage adapter = adapters.get(profile.type().toUpperCase(Locale.ROOT));
        if (adapter == null) throw new IllegalArgumentException("不支持的文件服务类型：" + profile.type());
        return adapter;
    }
}
