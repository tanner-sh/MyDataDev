package com.example.dbadmin.service.ai;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.AiDtos.AiChatRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.*;
import com.example.dbadmin.service.ai.llm.LlmClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiQueuedStreamCancellationTest {
    @RestController static class Endpoint {
        final AiSqlAgentService service;
        Endpoint(AiSqlAgentService service) { this.service = service; }
        @GetMapping("/chat") SseEmitter stream(@RequestParam String owner) {
            return service.chatStream(new AiChatRequest(1, null, null, "hello", null, null, null, null), owner, owner);
        }
    }
    @Test void twentyQueuedStreamsReachEofBeforeAnyRunningWorkerIsReleased() throws Exception {
        var properties = new AppProperties();
        var coordinator = new AiAgentCoordinator(properties, mock(ObjectProvider.class));
        var started = new CountDownLatch(4); var release = new CountDownLatch(1);
        var settings = mock(AiSettingsService.class);
        when(settings.requireEnabled(anyString())).thenReturn(new AiSettings(true, AiProvider.ANTHROPIC, "https://fixture.invalid", "fixture", null, AiEffort.HIGH));
        when(settings.requireSharedConnection(1)).thenReturn(new AiConnectionPolicy(1, AiSchemaSharing.STRUCTURE, 2));
        var connections = mock(ConnectionService.class); when(connections.require(1)).thenReturn(new DbConnection(1, "fixture", "h2", "jdbc:h2:mem:unused", "sa", "", "dev", false, java.time.Instant.now(), java.time.Instant.now()));
        var conversations = new AiConversationStore(properties);
        var service = new AiSqlAgentService(settings, connections, mock(LlmClientFactory.class), mock(AiSchemaTools.class), mock(AuditRepository.class),
                new SqlScriptSplitter(), new SqlStatementClassifier(), mock(AiSqlValidationService.class), conversations, mock(AiGlossaryService.class),
                mock(MetadataCacheService.class), coordinator, mock(AiAgentMetrics.class), properties);
        var mvc = MockMvcBuilders.standaloneSetup(new Endpoint(service)).build();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            for (int i = 0; i < 4; i++) coordinator.submit("blocker-" + i, id -> { started.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            var queued = new ArrayList<org.springframework.test.web.servlet.MvcResult>();
            for (int i = 0; i < 20; i++) queued.add(mvc.perform(get("/chat").param("owner", "owner-" + i)).andExpect(request().asyncStarted()).andReturn());
            for (int i = 0; i < 20; i++) {
                String owner = "owner-" + i;
                var result = queued.get(i);
                String data = result.getResponse().getContentAsString().lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
                var session = mapper.readTree(data);
                String id = session.path("requestId").asText();
                assertThat(coordinator.cancel(id, "intruder")).isFalse();
                assertThat(coordinator.cancel(id, owner)).isTrue();
                result.getAsyncResult(2000);
                mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("event:cancelled")));
                var next = conversations.begin(session.path("conversationId").asText(), owner, 1, null, 0, "next", null, null, null, null);
                conversations.fail(next);
            }
            assertThat(release.getCount()).isEqualTo(1);
        } finally { release.countDown(); coordinator.close(); }
    }
}
