package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.SchemaDiffRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffResponse;
import com.example.dbadmin.service.SchemaDiffService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema-diff")
public class SchemaDiffController {
    private final SchemaDiffService service;

    public SchemaDiffController(SchemaDiffService service) {
        this.service = service;
    }

    /**
     * 用 POST 而不是 GET：请求体里可能带上百个表名，而且这是一次代价不小的读操作，
     * 不适合被浏览器或中间层当成可缓存的 GET。
     */
    @PostMapping
    public SchemaDiffResponse compare(@Valid @RequestBody SchemaDiffRequest request,
                                      @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.compare(request, actor);
    }
}
