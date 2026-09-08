package com.example.dbadmin.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;

/**
 * 建 SSH 客户端的唯一入口。
 *
 * <p>SSH 的认证材料只能来自本应用保存的配置，不能隐式继承运行账户的 {@code ~/.ssh}。
 * {@code SshClient.setUpDefaultClient()} 默认挂着读 {@code ~/.ssh/config} 的解析器，
 * {@code start()} 里还会给没设过身份提供者的客户端装上 {@code ~/.ssh/id_*} 的监视器 ——
 * 两件事都发生在看不见的地方。</p>
 *
 * <p>留着它们，同一份配置换台部署机就可能连向不同主机（{@code HostName}、{@code Port}、
 * {@code User}、{@code ProxyJump} 都会被改写），或者拿服务器账户自己的私钥通过认证。后一种
 * 更糟：配置里的凭据早就作废了，隧道却还连得上，而这条隧道后面是业务库。</p>
 *
 * <p>身份提供者必须在 {@code start()} 之前设好 —— 设成空的提供者才是「明确不要默认身份」，
 * 留 null 会被 {@code start()} 当成「还没配」而补上那个监视器。</p>
 */
public final class SshClients {
    private SshClients() {
    }

    /** 一个不读 {@code ~/.ssh/config}、不带默认私钥的客户端；调用方自己装身份与校验器。 */
    public static SshClient newIsolatedClient() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        return client;
    }
}
