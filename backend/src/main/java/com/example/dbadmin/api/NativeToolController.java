package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.NativeToolsResponse;
import com.example.dbadmin.service.NativeToolLocator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/native-tools")
public class NativeToolController {
    private final NativeToolLocator locator;

    public NativeToolController(NativeToolLocator locator) {
        this.locator = locator;
    }

    @GetMapping
    public NativeToolsResponse list() {
        return new NativeToolsResponse(Instant.now().toString(), locator.detectAll());
    }
}
