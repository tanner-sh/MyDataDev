package com.example.dbadmin.service;

import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SshTunnelSettings;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真的起一台 SSH 服务和一台 H2 TCP 服务，验证「隧道 + 连接池」这条完整路径。
 *
 * <p>SSH 隧道是纯集成型的功能：URL 改写、端口转发、连接池与隧道的生命周期绑定，任何一环
 * 单独 mock 都证明不了它真的连得上。</p>
 */
class SshTunnelIntegrationTest {
    private static final String SSH_USER = "ops";
    private static final String SSH_PASSWORD = "secret";

    private static Server h2;
    private static SshServer sshd;
    private static String jdbcUrl;

    private RemoteDataSourceRegistry registry;

    @BeforeAll
    static void startServers() throws Exception {
        h2 = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        jdbcUrl = "jdbc:h2:tcp://127.0.0.1:" + h2.getPort() + "/mem:ssh-tunnel-test;DB_CLOSE_DELAY=-1";

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshd.setPasswordAuthenticator((username, password, session) ->
                SSH_USER.equals(username) && SSH_PASSWORD.equals(password));
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.start();
    }

    @AfterAll
    static void stopServers() throws Exception {
        if (sshd != null) sshd.stop(true);
        if (h2 != null) h2.stop();
    }

    @AfterEach
    void closeRegistry() {
        if (registry != null) registry.close();
    }

    @Test
    void opensADatabaseConnectionThroughTheTunnel() throws Exception {
        registry = new RemoteDataSourceRegistry();

        try (Connection connection = registry.open(connection(), "", spec(SSH_PASSWORD));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
        // 隧道跟着池走：第二次借用应当复用同一个池，而不是再开一条 SSH 会话。
        assertThat(registry.size()).isEqualTo(1);
        try (Connection second = registry.open(connection(), "", spec(SSH_PASSWORD))) {
            assertThat(second.isValid(2)).isTrue();
        }
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void testConnectionGoesThroughTheTunnelToo() throws Exception {
        registry = new RemoteDataSourceRegistry();
        registry.test(jdbcUrl, "sa", "", spec(SSH_PASSWORD));
        // 测试连接不应留下常驻的池或隧道。
        assertThat(registry.size()).isZero();
    }

    @Test
    void reportsBastionAuthenticationFailures() {
        registry = new RemoteDataSourceRegistry();

        assertThatThrownBy(() -> registry.open(connection(), "", spec("wrong-password")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法建立到跳板机");
        assertThat(registry.size()).isZero();
    }

    @Test
    void evictingTheConnectionClosesTheTunnel() throws Exception {
        registry = new RemoteDataSourceRegistry();

        try (Connection ignored = registry.open(connection(), "", spec(SSH_PASSWORD))) {
            assertThat(sshd.getActiveSessions()).isNotEmpty();
        }
        registry.evict(connection().id());

        assertThat(registry.size()).isZero();
        // 会话关闭是异步的，给它一点时间收尾。
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !sshd.getActiveSessions().isEmpty()) {
            Thread.sleep(50);
        }
        assertThat(sshd.getActiveSessions()).isEmpty();
    }

    private static DbConnection connection() {
        return new DbConnection(1L, "tunnel", "h2", jdbcUrl, "sa", null, "dev", false,
                null, null, null, null, null, SshTunnelSettings.disabled(), Instant.now(), Instant.now());
    }

    private static SshTunnelSpec spec(String password) {
        return new SshTunnelSpec("127.0.0.1", sshd.getPort(), SSH_USER, SshTunnelSpec.AUTH_PASSWORD,
                password, null, null, null, true);
    }
}
