package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import com.example.dbadmin.dto.StorageDtos.StorageProfileRequest;
import com.example.dbadmin.dto.StorageDtos.StorageProfileResponse;
import com.example.dbadmin.dto.StorageDtos.StorageTestResponse;
import com.example.dbadmin.service.StorageProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/storage-profiles")
public class StorageProfileController {
    private final StorageProfileService service;

    public StorageProfileController(StorageProfileService service) { this.service = service; }

    @GetMapping public List<StorageProfileResponse> list() { return service.list(); }

    @PostMapping public StorageProfileResponse create(@Valid @RequestBody StorageProfileRequest request,
                                                       @RequestHeader(value = "X-User", required = false) String actor) {
        return service.create(request, actor);
    }

    @PutMapping("/{id}") public StorageProfileResponse update(@PathVariable long id, @Valid @RequestBody StorageProfileRequest request,
                                                               @RequestHeader(value = "X-User", required = false) String actor) {
        return service.update(id, request, actor);
    }

    @DeleteMapping("/{id}") public MessageResponse delete(@PathVariable long id,
                                                           @RequestHeader(value = "X-User", required = false) String actor) {
        service.delete(id, actor);
        return new MessageResponse(true, "Storage profile deleted");
    }

    @PostMapping("/test") public StorageTestResponse testDraft(@Valid @RequestBody StorageProfileRequest request) throws Exception {
        return service.testDraft(request);
    }

    @PostMapping("/{id}/test") public StorageTestResponse test(@PathVariable long id,
                                                                @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.test(id, actor);
    }
}
