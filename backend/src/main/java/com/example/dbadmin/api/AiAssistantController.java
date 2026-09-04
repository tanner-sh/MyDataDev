package com.example.dbadmin.api;

import com.example.dbadmin.dto.AiDtos.AiAnswerResponse;
import com.example.dbadmin.dto.AiDtos.AiDiagnoseRequest;
import com.example.dbadmin.dto.AiDtos.AiDocumentRequest;
import com.example.dbadmin.dto.AiDtos.AiExplainRequest;
import com.example.dbadmin.dto.AiDtos.AiInterpretRequest;
import com.example.dbadmin.dto.AiDtos.AiReviewScriptRequest;
import com.example.dbadmin.dto.AiDtos.AiGenerateRequest;
import com.example.dbadmin.service.ai.AiAssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 问答接口。
 *
 * <p>流式入口用 POST 而不是方案里写的 GET：诊断要带上整条 SQL 与报错原文，塞进查询串既有
 * 长度上限也会被日志和反代原样记下来。代价是前端不能用 EventSource，改用 fetch 读流。</p>
 *
 * <p>这里没有任何执行入口。AI 的产出只会回到编辑器，由用户自己确认后执行 —— 那条路径上的
 * 只读拦截、生产确认、未限定范围写确认与审计一个都不少。</p>
 */
@RestController
@RequestMapping("/api/ai/sql")
public class AiAssistantController {
    private final AiAssistantService assistant;

    public AiAssistantController(AiAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/diagnose")
    public AiAnswerResponse diagnose(
            @Valid @RequestBody AiDiagnoseRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return new AiAnswerResponse(assistant.diagnose(
                request.connectionId(), request.schemaName(), request.sql(), request.errorMessage(), actor));
    }

    @PostMapping("/generate")
    public AiAnswerResponse generate(
            @Valid @RequestBody AiGenerateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return new AiAnswerResponse(assistant.generate(
                request.connectionId(), request.schemaName(), request.question(), actor));
    }

    @PostMapping("/generate/stream")
    public SseEmitter generateStream(
            @Valid @RequestBody AiGenerateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return assistant.generateStream(
                request.connectionId(), request.schemaName(), request.question(), actor);
    }

    @PostMapping("/explain-insight")
    public AiAnswerResponse explain(
            @Valid @RequestBody AiExplainRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return new AiAnswerResponse(assistant.explain(
                request.connectionId(), request.schemaName(), request.sql(), request.plan(), request.findings(), actor));
    }

    @PostMapping("/explain-insight/stream")
    public SseEmitter explainStream(
            @Valid @RequestBody AiExplainRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return assistant.explainStream(
                request.connectionId(), request.schemaName(), request.sql(), request.plan(), request.findings(), actor);
    }

    @PostMapping("/interpret/stream")
    public SseEmitter interpretStream(
            @Valid @RequestBody AiInterpretRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return assistant.interpretStream(request.connectionId(), request.schemaName(), request.sql(),
                request.preview(), request.chartCandidates(), actor);
    }

    @PostMapping("/document/stream")
    public SseEmitter documentStream(
            @Valid @RequestBody AiDocumentRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return assistant.documentStream(request.connectionId(), request.schemaName(), request.tables(), actor);
    }

    @PostMapping("/review-script/stream")
    public SseEmitter reviewScriptStream(
            @Valid @RequestBody AiReviewScriptRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return assistant.reviewScriptStream(request.connectionId(), request.script(), actor);
    }

    @PostMapping("/diagnose/stream")
    public SseEmitter diagnoseStream(
            @Valid @RequestBody AiDiagnoseRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return assistant.diagnoseStream(
                request.connectionId(), request.schemaName(), request.sql(), request.errorMessage(), actor);
    }
}
