package com.example.dbadmin.service;

import java.util.Locale;
import java.util.Map;

/**
 * 从 JDBC 地址里定位数据库主机与端口，并支持把它替换成另一个地址。
 *
 * <p>SSH 隧道建立后，驱动要连的是本地转发端口而不是原始主机，所以必须把地址里的
 * {@code host:port} 换掉。</p>
 *
 * <p>这里没有做进 {@code DatabaseDialect}：URL 语法属于驱动而不是方言 —— 同一个
 * {@code jdbc:mysql://} 语法既服务 MySQL 方言也服务 OceanBase MySQL 模式，而 Oracle 一个
 * scheme 下有两种写法。按 scheme 解析比按方言解析更贴近事实，也免得每加一个方言都要重写
 * 一遍同样的 URL 拆分。</p>
 */
public final class JdbcEndpoints {
    /** scheme 省略端口时驱动使用的默认端口。 */
    private static final Map<String, Integer> DEFAULT_PORTS = Map.ofEntries(
            Map.entry("mysql", 3306),
            Map.entry("mariadb", 3306),
            Map.entry("postgresql", 5432),
            Map.entry("sqlserver", 1433),
            Map.entry("clickhouse", 8123),
            Map.entry("oceanbase", 2881),
            Map.entry("dm", 5236),
            Map.entry("oracle", 1521),
            Map.entry("h2", 9092)
    );

    private JdbcEndpoints() {
    }

    /**
     * 地址里的主机与端口，外加它在原串中的位置。
     *
     * <p>位置用于原样替换：JDBC 地址后面常挂着一长串驱动参数，重新拼装整个 URL 比替换一个
     * 片段更容易改错。</p>
     *
     * @param start 主机名在原串中的起始下标
     * @param end   主机（含端口，若原串写了端口）在原串中的结束下标（不含）
     */
    public record Endpoint(String host, int port, int start, int end) {
    }

    /** 定位地址中的主机与端口；无法定位时抛出带原因的 {@link IllegalArgumentException}。 */
    public static Endpoint locate(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.trim();
        if (!url.regionMatches(true, 0, "jdbc:", 0, 5)) {
            throw new IllegalArgumentException("数据库地址必须以 jdbc: 开头。");
        }
        String scheme = scheme(url);
        if ("oracle".equals(scheme)) return locateOracle(url);
        int authorityStart = url.indexOf("://");
        if (authorityStart < 0) {
            throw new IllegalArgumentException(
                    "该数据库地址不是「主机:端口」形式（例如本地文件数据库），无法通过 SSH 隧道访问。");
        }
        return locateAuthority(url, authorityStart + 3, scheme);
    }

    /** 把地址中的主机与端口替换成隧道的本地监听地址。 */
    public static String rewrite(String jdbcUrl, String host, int port) {
        Endpoint endpoint = locate(jdbcUrl);
        String url = jdbcUrl.trim();
        return url.substring(0, endpoint.start()) + host + ":" + port + url.substring(endpoint.end());
    }

    private static String scheme(String url) {
        int start = 5;
        int end = url.indexOf(':', start);
        if (end < 0) end = url.length();
        return url.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static Endpoint locateAuthority(String url, int start, String scheme) {
        int end = url.length();
        for (int index = start; index < url.length(); index++) {
            char c = url.charAt(index);
            if (c == '/' || c == '?' || c == ';' || c == '#') {
                end = index;
                break;
            }
        }
        // user:password@host 形式：取最后一个 @ 之后的部分，密码里也可能有 @。
        int at = url.lastIndexOf('@', end - 1);
        if (at >= start) start = at + 1;
        String authority = url.substring(start, end);
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("数据库地址里没有主机名，无法通过 SSH 隧道访问。");
        }
        if (authority.indexOf(',') >= 0) {
            throw new IllegalArgumentException("数据库地址配置了多个主机，SSH 隧道暂不支持多主机地址。");
        }
        // SQL Server 的命名实例（host\instance）要靠 SQL Browser 的 UDP 1434 端口解析真实端口，
        // 隧道转发的是 TCP，转发过去也连不上，所以要求显式写出端口。
        int backslash = authority.indexOf('\\');
        if (backslash >= 0 && authority.indexOf(':') < 0) {
            throw new IllegalArgumentException(
                    "SQL Server 命名实例需要显式写出端口（例如 jdbc:sqlserver://host:1433;instanceName=SQLEXPRESS）才能通过 SSH 隧道访问。");
        }
        return parseHostPort(url, start, end, scheme);
    }

    private static Endpoint locateOracle(String url) {
        int at = url.indexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("Oracle 地址缺少 @ 主机部分，无法通过 SSH 隧道访问。");
        }
        int start = at + 1;
        if (start < url.length() && url.charAt(start) == '(') {
            throw new IllegalArgumentException(
                    "Oracle TNS 描述符地址暂不支持 SSH 隧道，请改用 jdbc:oracle:thin:@//主机:端口/服务名 形式。");
        }
        if (url.startsWith("//", start)) start += 2;
        // @host:port/service 到 '/' 为止；@host:port:sid 到第二个 ':' 为止。
        int end = url.length();
        int colons = 0;
        for (int index = start; index < url.length(); index++) {
            char c = url.charAt(index);
            if (c == '/' || c == '?') {
                end = index;
                break;
            }
            if (c == ':' && ++colons == 2) {
                end = index;
                break;
            }
        }
        return parseHostPort(url, start, end, "oracle");
    }

    private static Endpoint parseHostPort(String url, int start, int end, String scheme) {
        String value = url.substring(start, end);
        String host;
        int port;
        int hostEnd;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("数据库地址中的 IPv6 主机缺少右方括号。");
            host = value.substring(1, close);
            hostEnd = close + 1;
        } else {
            int colon = value.indexOf(':');
            int separator = colon < 0 ? value.length() : colon;
            int backslash = value.indexOf('\\');
            if (backslash >= 0 && backslash < separator) separator = backslash;
            host = value.substring(0, separator);
            hostEnd = separator;
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("数据库地址里没有主机名，无法通过 SSH 隧道访问。");
        }
        if (hostEnd < value.length() && value.charAt(hostEnd) == ':') {
            String portText = value.substring(hostEnd + 1);
            // SQL Server 允许 host:port\instance 之外的写法，端口后可能还跟着别的片段。
            int portEnd = 0;
            while (portEnd < portText.length() && Character.isDigit(portText.charAt(portEnd))) portEnd++;
            if (portEnd == 0) {
                throw new IllegalArgumentException("数据库地址中的端口不是数字：" + portText);
            }
            port = Integer.parseInt(portText.substring(0, portEnd));
            end = start + hostEnd + 1 + portEnd;
        } else {
            Integer defaultPort = DEFAULT_PORTS.get(scheme);
            if (defaultPort == null) {
                throw new IllegalArgumentException("数据库地址没有写端口，且无法推断 " + scheme + " 的默认端口，请补全端口后再启用 SSH 隧道。");
            }
            port = defaultPort;
            end = start + hostEnd;
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("数据库地址中的端口超出范围：" + port);
        }
        return new Endpoint(host, port, start, end);
    }
}
