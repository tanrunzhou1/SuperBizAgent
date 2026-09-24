package org.example.controller;

import lombok.Data;
import org.example.common.api.ApiResponse;
import org.example.service.AgentProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent-configs")
public class AgentProfileController {
    private final AgentProfileService profiles;

    public AgentProfileController(AgentProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(profiles.getAll()));
    }

    @GetMapping("/{profile}")
    public ResponseEntity<ApiResponse<?>> get(@PathVariable String profile) {
        return ResponseEntity.ok(ApiResponse.success(profiles.get(profile)));
    }

    @PutMapping("/{profile}/save")
    public ResponseEntity<ApiResponse<?>> save(@PathVariable String profile,
            @RequestBody SaveProfileRequest request) {
        AgentProfileService.ProfileSnapshot saved = profiles.save(profile, request.getPrompts(),
                request.toSampling());
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PostMapping("/{profile}/apply")
    public ResponseEntity<ApiResponse<?>> apply(@PathVariable String profile,
            @RequestBody(required = false) ApplyProfileRequest request) {
        Integer version = request == null ? null : request.getVersion();
        return ResponseEntity.ok(ApiResponse.success(profiles.apply(profile, version)));
    }

    @GetMapping("/{profile}/versions")
    public ResponseEntity<ApiResponse<?>> versions(@PathVariable String profile) {
        return ResponseEntity.ok(ApiResponse.success(profiles.get(profile).versions()));
    }

    @Data
    public static class SaveProfileRequest {
        private Map<String, String> prompts;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

        AgentProfileService.SamplingConfig toSampling() {
            if (temperature == null || maxTokens == null || topP == null) {
                throw new IllegalArgumentException("temperature、maxTokens、topP 均为必填项");
            }
            return new AgentProfileService.SamplingConfig(temperature, maxTokens, topP);
        }
    }

    @Data
    public static class ApplyProfileRequest {
        private Integer version;
    }
}
