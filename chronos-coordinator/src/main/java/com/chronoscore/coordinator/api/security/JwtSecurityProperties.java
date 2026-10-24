package com.chronoscore.coordinator.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for Chronos Core REST Security and OAuth2 Resource Server.
 */
@Component
@ConfigurationProperties(prefix = "chronos.security")
public class JwtSecurityProperties {

    /**
     * Whether REST security is enabled.
     */
    private boolean enabled = true;

    /**
     * Secret key used for signing/verifying HMAC-SHA256 JWT tokens.
     * Minimum 256 bits (32 bytes). In development/test, defaults to a standard test key.
     */
    private String jwtSecret = "chronos-core-distributed-orchestration-dev-secret-key-32bytes-minimum";

    /**
     * Expected JWT issuer claim (iss).
     */
    private String issuer = "chronos-core";

    /**
     * Token validity duration in seconds (default 1 hour).
     */
    private long tokenValiditySeconds = 3600;

    /**
     * Allowed CORS origins for browser control plane.
     */
    private List<String> corsAllowedOrigins = List.of(
            "http://localhost:5173",
            "http://localhost:3000",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:3000"
    );

    /**
     * Development authentication configuration.
     */
    private DevAuth devAuth = new DevAuth();

    public static class DevAuth {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DevAuth getDevAuth() {
        return devAuth;
    }

    public void setDevAuth(DevAuth devAuth) {
        this.devAuth = devAuth;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getTokenValiditySeconds() {
        return tokenValiditySeconds;
    }

    public void setTokenValiditySeconds(long tokenValiditySeconds) {
        this.tokenValiditySeconds = tokenValiditySeconds;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
