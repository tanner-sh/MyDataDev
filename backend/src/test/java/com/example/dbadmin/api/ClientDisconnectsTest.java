package com.example.dbadmin.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ClientDisconnectsTest {
    @Test
    void recognisesSpringsOwnDisconnectedClientException() {
        assertThat(ClientDisconnects.isClientGone(
                new AsyncRequestNotUsableException("Servlet container error notification for disconnected client")))
                .isTrue();
    }

    @Test
    void recognisesAWriteThatFailedOnTheClientSocket() {
        // 现场就长这样：一个消息本地化的 IOException，栈顶是往 socket 写。中文 Windows 上
        // 消息是「你的主机中的软件中止了一个已建立的连接。」，所以判断只能看栈，不能看文本。
        IOException error = new IOException("你的主机中的软件中止了一个已建立的连接。");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("sun.nio.ch.SocketDispatcher", "write0", "SocketDispatcher.java", -2),
                new StackTraceElement("org.apache.tomcat.util.net.NioChannel", "write", "NioChannel.java", 129),
                new StackTraceElement("org.springframework.web.servlet.DispatcherServlet", "doService", "DispatcherServlet.java", 1)
        });

        assertThat(ClientDisconnects.isClientGone(error)).isTrue();
    }

    @Test
    void findsTheDisconnectThroughAWrappingException() {
        IOException brokenPipe = new IOException("Broken pipe");
        brokenPipe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("sun.nio.ch.SocketChannelImpl", "write", "SocketChannelImpl.java", 542)
        });

        assertThat(ClientDisconnects.isClientGone(new IllegalStateException("包了一层", brokenPipe))).isTrue();
    }

    @Test
    void keepsRealIoFailuresReportableAsErrors() {
        // 读备份文件失败也是 IOException，但栈里没有 socket 写。把这种也当成「对端走了」
        // 就等于把真正的故障一起藏掉，那比多几行日志糟得多。
        IOException error = new IOException("Input/output error");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.io.FileInputStream", "read", "FileInputStream.java", 1),
                new StackTraceElement("com.example.dbadmin.service.BackupService", "verifyHistory", "BackupService.java", 1)
        });

        assertThat(ClientDisconnects.isClientGone(error)).isFalse();
    }

    @Test
    void survivesACauseChainThatLoopsBackOnItself() {
        IOException outer = new IOException("外层");
        IOException inner = new IOException("内层");
        outer.initCause(inner);
        inner.initCause(outer);
        outer.setStackTrace(new StackTraceElement[0]);
        inner.setStackTrace(new StackTraceElement[0]);

        assertThat(ClientDisconnects.isClientGone(outer)).isFalse();
    }
}
