package com.example.dbadmin.desktop;

import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("desktop")
@RequestMapping("/internal/desktop")
public class DesktopControlController {
    public static final String CONTROL_HEADER = "X-MyDataDev-Desktop-Token";

    private final DesktopLifecycleService lifecycle;

    public DesktopControlController(DesktopLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/shutdown")
    public MessageResponse shutdown(@RequestHeader(value = CONTROL_HEADER, required = false) String token) {
        if (!lifecycle.authorized(token)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        lifecycle.requestShutdown();
        return new MessageResponse(true, "MyDataDev desktop backend is shutting down");
    }
}
