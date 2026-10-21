package com.chronoscore.coordinator.api.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Development token controller allowing local developers and test suites
 * to issue valid signed JWT bearer tokens without requiring a real external identity provider.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class DevAuthController {

    private final DevJwtTokenHelper tokenHelper;

    public DevAuthController(DevJwtTokenHelper tokenHelper) {
        this.tokenHelper = tokenHelper;
    }

    public record TokenRequest(
            String username,
            String tenantId,
            List<String> roles
    ) {}

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn
    ) {}

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> issueToken(@RequestBody TokenRequest request) {
        String username = request.username() != null && !request.username().isBlank() ? request.username() : "dev-user";
        String tenantId = request.tenantId() != null && !request.tenantId().isBlank() ? request.tenantId() : "default";
        List<String> roles = request.roles() != null && !request.roles().isEmpty() ? request.roles() : List.of("VIEWER");

        String token = tokenHelper.generateToken(username, tenantId, roles);
        return ResponseEntity.ok(new TokenResponse(token, "Bearer", 3600));
    }
}
