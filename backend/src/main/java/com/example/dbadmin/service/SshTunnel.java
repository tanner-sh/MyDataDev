package com.example.dbadmin.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.session.SessionHeartbeatController.HeartbeatType;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;

/**
 * 一条到跳板机的 SSH 会话，外加一个转发到目标数据库的本地端口。
 *
 * <p>隧道的生命周期跟着远程连接池走：池在，隧道就得在，因为池里的物理连接连的正是这个本地
 * 端口。{@link RemoteDataSourceRegistry} 负责建池时开、淘汰池时关。</p>
 *
 * <p>每条隧道自带一个 {@link SshClient}。共享客户端能省一点内存，但也会让一条连接的隧道故障
 * 波及其他连接 —— 数据库连接之间本来就该互不影响。</p>
 */
public final class SshTunnel implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SshTunnel.class);
    /** 本地转发只绑回环地址：隧道口不能变成局域网里的一个数据库入口。 */
    private static final String LOCAL_HOST = "127.0.0.1";

    private final SshClient client;
    private final ClientSession session;
    private final SshdSocketAddress local;
    private final String describedTarget;

    private SshTunnel(SshClient client, ClientSession session, SshdSocketAddress local, String describedTarget) {
        this.client = client;
        this.session = session;
        this.local = local;
        this.describedTarget = describedTarget;
    }

    /**
     * 建立隧道：连跳板机、认证、把 {@code targetHost:targetPort} 转发到一个随机本地端口。
     *
     * @param timeouts 连接与认证的等待上限，超时按不可达处理
     */
    public static SshTunnel open(SshTunnelSpec spec, String targetHost, int targetPort, Timeouts timeouts) throws Exception {
        validate(spec);
        SshClient client = SshClient.setUpDefaultClient();
        client.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        client.setServerKeyVerifier(spec.skipHostKeyCheck()
                ? AcceptAllServerKeyVerifier.INSTANCE
                : (verified, remoteAddress, serverKey) -> KeyUtils.checkFingerPrint(spec.serverFingerprint(), serverKey).getKey());
        client.start();
        ClientSession session = null;
        try {
            session = client.connect(spec.username(), spec.host(), spec.port())
                    .verify(timeouts.connect()).getSession();
            authenticate(session, spec, timeouts);
            // 空闲的隧道会被跳板机或中间设备掐断，而连接池里的连接可以闲置很久。
            session.setSessionHeartbeat(HeartbeatType.IGNORE, timeouts.heartbeat());
            SshdSocketAddress bound = session.startLocalPortForwarding(
                    new SshdSocketAddress(LOCAL_HOST, 0), new SshdSocketAddress(targetHost, targetPort));
            log.debug("SSH 隧道已建立：{}:{} 转发到 {}:{}（经由 {}@{}）",
                    LOCAL_HOST, bound.getPort(), targetHost, targetPort, spec.username(), spec.host());
            return new SshTunnel(client, session, bound, targetHost + ":" + targetPort);
        } catch (Exception error) {
            closeQuietly(session);
            client.stop();
            throw new IllegalStateException("无法建立到跳板机 " + spec.username() + "@" + spec.host() + ":" + spec.port()
                    + " 的 SSH 隧道：" + rootMessage(error), error);
        }
    }

    public String localHost() {
        return LOCAL_HOST;
    }

    public int localPort() {
        return local.getPort();
    }

    /** 会话断了要让调用方能发现：池里的连接这时已经全部失效。 */
    public boolean isOpen() {
        return session.isOpen() && client.isStarted();
    }

    @Override
    public void close() {
        try {
            session.stopLocalPortForwarding(local);
        } catch (Exception error) {
            log.debug("关闭本地转发端口 {} 失败", local.getPort(), error);
        }
        closeQuietly(session);
        try {
            client.stop();
        } catch (Exception error) {
            log.debug("停止 SSH 客户端失败（目标 {}）", describedTarget, error);
        }
    }

    private static void validate(SshTunnelSpec spec) {
        if (spec.host() == null || spec.host().isBlank()) {
            throw new IllegalArgumentException("SSH 隧道缺少跳板机地址。");
        }
        if (spec.username() == null || spec.username().isBlank()) {
            throw new IllegalArgumentException("SSH 隧道缺少登录用户名。");
        }
        if (spec.usesPrivateKey() && (spec.privateKey() == null || spec.privateKey().isBlank())) {
            throw new IllegalArgumentException("SSH 隧道选择了密钥认证，但没有配置私钥。");
        }
        // 不校验主机密钥等于把中间人攻击的门留着，所以要求用户显式选择「跳过校验」，
        // 而不是因为忘填指纹就默默降级。
        if (!spec.skipHostKeyCheck() && (spec.serverFingerprint() == null || spec.serverFingerprint().isBlank())) {
            throw new IllegalArgumentException(
                    "SSH 隧道未配置跳板机主机指纹。请填写指纹（例如 SHA256:xxxx），或在明确风险后勾选「跳过主机密钥校验」。");
        }
    }

    private static void authenticate(ClientSession session, SshTunnelSpec spec, Timeouts timeouts) throws Exception {
        if (spec.usesPrivateKey()) {
            FilePasswordProvider passphrase = FilePasswordProvider.of(
                    spec.passphrase() == null ? "" : spec.passphrase());
            Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(
                    session,
                    NamedResource.ofName("ssh-tunnel-" + spec.host()),
                    new ByteArrayInputStream(spec.privateKey().getBytes(StandardCharsets.UTF_8)),
                    passphrase);
            if (keys == null || !keys.iterator().hasNext()) {
                throw new IllegalArgumentException("无法解析 SSH 私钥，请确认粘贴的是完整的 PEM/OpenSSH 私钥。");
            }
            for (KeyPair key : keys) session.addPublicKeyIdentity(key);
        } else if (spec.password() != null && !spec.password().isEmpty()) {
            session.addPasswordIdentity(spec.password());
        }
        session.auth().verify(timeouts.auth());
    }

    private static void closeQuietly(ClientSession session) {
        if (session == null) return;
        try {
            session.close(false);
        } catch (Exception error) {
            log.debug("关闭 SSH 会话失败", error);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /** 隧道相关的等待上限，来自 {@code app.ssh.*}。 */
    public record Timeouts(Duration connect, Duration auth, Duration heartbeat) {
        public static final Timeouts DEFAULTS =
                new Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30));
    }
}
