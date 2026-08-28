package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.ActiveOperations;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackgroundTaskStreamTest {
    @Test
    void pushesASnapshotAsSoonAsAClientSubscribes() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter emitter = mock(SseEmitter.class);

        fixture.stream.register(7L, emitter);

        // 界面不该为了拿到第一屏内容而等一整个推送周期。
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(fixture.stream.subscriberCount()).isEqualTo(1);
    }

    @Test
    void onlyPushesWhenTheSnapshotChanges() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter emitter = mock(SseEmitter.class);
        fixture.stream.register(7L, emitter);

        fixture.stream.publish();
        fixture.stream.publish();
        // 初次订阅那一次之外没有新内容，就不该再发。
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        fixture.active(List.of(running()));
        fixture.stream.publish();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void queriesOnceForSubscribersWatchingTheSameConnection() {
        Fixture fixture = new Fixture();
        fixture.stream.register(7L, mock(SseEmitter.class));
        fixture.stream.register(7L, mock(SseEmitter.class));
        fixture.stream.register(9L, mock(SseEmitter.class));

        fixture.stream.publish();

        // 三次订阅各查了一次，随后的一轮推送里 7 号连接只查一次。
        verify(fixture.histories, times(3)).findActive(7L);
        verify(fixture.histories, times(2)).findActive(9L);
    }

    @Test
    void doesNothingWithoutSubscribers() {
        Fixture fixture = new Fixture();

        fixture.stream.publish();

        verify(fixture.histories, never()).findActive(any());
    }

    @Test
    void dropsSubscribersWhoseChannelIsGone() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter emitter = mock(SseEmitter.class);
        fixture.stream.register(7L, emitter);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        fixture.active(List.of(running()));
        fixture.stream.publish();

        assertThat(fixture.stream.subscriberCount()).isZero();
    }

    private static BackupHistory running() {
        return new BackupHistory(1L, 2L, 7L, "RUNNING", "正在导出", null, null, Instant.now(), null);
    }

    /** 三个协作者都很重，测试只关心「快照读了几次、推了几次」。 */
    private static final class Fixture {
        private final RestoreService restores = mock(RestoreService.class);
        private final BackupHistoryRepository histories = mock(BackupHistoryRepository.class);
        private final SqlFileExecutionRepository sqlFiles = mock(SqlFileExecutionRepository.class);
        private final BackgroundTaskStream stream;

        private Fixture() {
            when(histories.findActive(any())).thenReturn(List.of());
            when(sqlFiles.findActive(any())).thenReturn(List.of());
            active(List.of());
            // 任务模型里有 Instant：不注册 JSR-310 模块的话序列化会直接失败，那测的就不是推送逻辑了。
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            stream = new BackgroundTaskStream(restores, histories, sqlFiles, mapper, new AppProperties());
        }

        private void active(List<BackupHistory> backups) {
            when(restores.active(any(), any(), any())).thenReturn(new ActiveOperations(backups, List.of(), List.of()));
        }
    }
}
