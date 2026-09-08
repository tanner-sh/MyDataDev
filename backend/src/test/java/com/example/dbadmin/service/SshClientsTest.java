package com.example.dbadmin.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SshClientsTest {
    /**
     * {@code start()} 才是装上默认身份的地方，所以断言必须跨过它 —— 只看构造完的客户端，
     * 换成留 null 的写法也照样能通过。
     */
    @Test
    void doesNotInheritTheRuntimeUsersSshConfigurationOrKeys() throws IOException {
        SshClient client = SshClients.newIsolatedClient();

        assertThat(client.getHostConfigEntryResolver()).isSameAs(HostConfigEntryResolver.EMPTY);
        assertThat(client.getKeyIdentityProvider()).isSameAs(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);

        client.start();
        try {
            assertThat(client.getHostConfigEntryResolver()).isSameAs(HostConfigEntryResolver.EMPTY);
            assertThat(client.getKeyIdentityProvider()).isSameAs(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        } finally {
            client.stop();
        }
    }

    /**
     * 隔离只有在每个客户端都从这里建出来时才成立，而漏掉一处不会有任何症状：
     * 开发机上 {@code ~/.ssh} 里恰好有能用的配置和私钥，跑起来一切正常，换到部署环境才出事。
     */
    @Test
    void everySshClientIsBuiltThroughThisFactory() throws IOException {
        Path sources = Path.of("src", "main", "java");
        assumeTrue(Files.exists(sources), "后端源码不在预期位置，跳过约定检查");

        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                if (file.getFileName().toString().equals("SshClients.java")) continue;
                if (Files.readString(file, StandardCharsets.UTF_8).contains("SshClient.setUpDefaultClient(")) {
                    offenders.add(sources.relativize(file).toString());
                }
            }
        }

        assertThat(offenders)
                .as("这些文件绕开了 SshClients.newIsolatedClient()，会继承运行账户的 ~/.ssh 配置与私钥")
                .isEmpty();
    }
}
