package com.chronoscore.coordinator.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>
 * Strictly disabled in production via {@code chronos.security.dev-auth.enabled=false}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(
        name = "chronos.security.dev-auth.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class DevAuthController {

    private final DevJwtTokenHelper tokenHelper;

    public DevAuthController(DevJwtTokenHelper tokenHelper) {
        this.tokenHelper = tokenHelper;
    }

    public record TokenRequest(
            String username,
            String subject,
            String tenantId,
            List<String> roles
    ) {
        public String effectiveUsername() {
            if (subject != null && !subject.isBlank()) {
                return subject.trim();
            }
            if (username != null && !username.isBlank()) {
                return username.trim();
            }
            return "dev-user";
        }
    }

    public record TokenResponse(
            String accessToken,
            String token,
            String tokenType,
            long expiresIn,
            long expiresInSeconds,
            List<String> roles,
            String tenantId
    ) {
        public TokenResponse(String token, String tokenType, long expiresIn, List<String> roles, String tenantId) {
            this(token, token, tokenType, expiresIn, expiresIn, roles, tenantId);
        }
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> issueToken(@RequestBody(required = false) TokenRequest request) {
        String username = request != null ? request.effectiveUsername() : "dev-user";
        String tenantId = request != null && request.tenantId() != null && !request.tenantId().isBlank()
                ? request.tenantId().trim()
                : "default";
        List<String> roles = request != null && request.roles() != null && !request.roles().isEmpty()
                ? request.roles()
                : List.of("VIEWER");

        String token = tokenHelper.generateToken(username, tenantId, roles);
        return ResponseEntity.ok(new TokenResponse(token, "Bearer", 86400, roles, tenantId));
    }
}
