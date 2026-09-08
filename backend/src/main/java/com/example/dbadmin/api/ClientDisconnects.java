package com.example.dbadmin.api;

/**
 * 判断一个异常是不是「对端先走了」。
 *
 * <p>浏览器刷新一下、关掉标签页、切换连接让 SSE 重连，甚至中间的反向代理掐掉一条空闲长连接 ——
 * 服务端都会在写响应时撞上一个 I/O 异常。这不是故障，可它此前一路冒到 {@code ApiExceptionHandler}
 * 的兜底分支，被记成一条带完整栈的 ERROR。用户报的现象就是这个：应用起着不动，日志定期刷错误。</p>
 *
 * <p><b>不能按异常消息判断。</b>底层消息由操作系统给，本身是本地化的 —— Linux 上是
 * {@code Broken pipe}，中文 Windows 上是「你的主机中的软件中止了一个已建立的连接。」，
 * 匹配文本等于只在开发者自己的机器上生效。这里改看两件与语言无关的事：异常类型
 * （Spring 的 {@code AsyncRequestNotUsableException}、Tomcat 的 {@code ClientAbortException}
 * 都是专门表示这件事的），以及栈里有没有往客户端 socket 写的帧。</p>
 *
 * <p>反过来说得同样清楚：读备份文件失败、写导出临时文件失败也是 {@code IOException}，
 * 它们的栈里没有 socket 写，必须照旧记成错误。把两者混为一谈就等于把真正的故障也一起藏了。</p>
 */
public final class ClientDisconnects {
    /** 表示「这条连接已经不能再用了」的异常类型，按全限定名比对，免得为 Tomcat 内部类加编译期依赖。 */
    private static final String[] CLIENT_GONE_TYPES = {
            "org.springframework.web.context.request.async.AsyncRequestNotUsableException",
            "org.apache.catalina.connector.ClientAbortException",
            "org.eclipse.jetty.io.EofException"
    };
    /** 往客户端 socket 写数据的帧：出现它就说明失败发生在「发给浏览器」这一段。 */
    private static final String[] SOCKET_WRITE_FRAMES = {
            "org.apache.tomcat.util.net.",
            "sun.nio.ch.SocketDispatcher",
            "sun.nio.ch.SocketChannelImpl"
    };

    private ClientDisconnects() {
    }

    /** cause 链自己指回自己是有的，限一个深度免得在这里转不出去。 */
    private static final int MAX_CAUSE_DEPTH = 12;

    public static boolean isClientGone(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (matchesType(current) || writesToClientSocket(current)) return true;
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean matchesType(Throwable error) {
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : CLIENT_GONE_TYPES) {
                if (name.equals(type.getName())) return true;
            }
        }
        return false;
    }

    private static boolean writesToClientSocket(Throwable error) {
        for (StackTraceElement frame : error.getStackTrace()) {
            for (String prefix : SOCKET_WRITE_FRAMES) {
                if (frame.getClassName().startsWith(prefix)) return true;
            }
        }
        return false;
    }
}
