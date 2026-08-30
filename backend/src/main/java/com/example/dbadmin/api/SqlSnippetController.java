package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.SqlSnippetRequest;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetResponse;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.service.SqlSnippetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sql-snippets")
public class SqlSnippetController {
    private final SqlSnippetService service;

    public SqlSnippetController(SqlSnippetService service) {
        this.service = service;
    }

    @GetMapping
    public List<SqlSnippetResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dbType,
            Authentication authentication
    ) {
        return service.list(keyword, dbType, identity(authentication));
    }

    @PostMapping
    public SqlSnippetResponse create(
            @Valid @RequestBody SqlSnippetRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            Authentication authentication
    ) {
        return service.create(request, identity(authentication), actor);
    }

    @PutMapping("/{id}")
    public SqlSnippetResponse update(
            @PathVariable long id,
            @Valid @RequestBody SqlSnippetRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            Authentication authentication
    ) {
        return service.update(id, request, identity(authentication), actor);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable long id,
            @RequestHeader(value = "X-User", required = false) String actor,
            Authentication authentication
    ) {
        service.delete(id, identity(authentication), actor);
        return Map.of("ok", true);
    }

    /** 插入到编辑器时上报一次使用。 */
    @PostMapping("/{id}/use")
    public SqlSnippetResponse recordUse(@PathVariable long id, Authentication authentication) {
        return service.recordUse(id, identity(authentication));
    }

    private WebIdentity identity(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof WebIdentity identity ? identity : null;
    }
}
