package com.example.dbadmin.api;

import com.example.dbadmin.auth.WebAuthenticationService;
import com.example.dbadmin.dto.AccessTokenDtos.SystemInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemInfoController {
    public static final int API_VERSION = 1;

    private final WebAuthenticationService authenticationService;
    private final Environment environment;
    private final String version;

    public SystemInfoController(
            WebAuthenticationService authenticationService,
            Environment environment,
            @Value("${app.version:${spring.ai.mcp.server.version:0.0.0}}") String version
    ) {
        this.authenticationService = authenticationService;
        this.environment = environment;
        this.version = version;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        String mode = environment.acceptsProfiles(Profiles.of("desktop")) ? "desktop" : "web";
        return new SystemInfoResponse(
                "MyDataDev",
                version,
                API_VERSION,
                mode,
                authenticationService.providerId()
        );
    }
}
