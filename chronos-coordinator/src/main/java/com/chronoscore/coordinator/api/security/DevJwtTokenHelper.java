package com.chronoscore.coordinator.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Utility for issuing signed HMAC-SHA256 JWT bearer tokens for development,
 * local environments, and integration tests.
 */
@Component
public class DevJwtTokenHelper {

    private final JwtSecurityProperties securityProperties;

    public DevJwtTokenHelper(JwtSecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /**
     * Generates a signed JWT bearer token.
     *
     * @param subject  user/service identifier.
     * @param tenantId tenant identifier claim (e.g. "tenant-a", "tenant-b").
     * @param roles    list of roles without ROLE_ prefix (e.g. "ADMIN", "OPERATOR", "VIEWER").
     * @return signed compact JWT string.
     */
    public String generateToken(String subject, String tenantId, List<String> roles) {
        return generateToken(subject, tenantId, roles, securityProperties.getTokenValiditySeconds());
    }

    /**
     * Generates a signed JWT bearer token with custom validity duration.
     *
     * @param subject         user/service identifier.
     * @param tenantId        tenant identifier claim.
     * @param roles           list of roles (e.g. "ADMIN", "OPERATOR", "VIEWER").
     * @param validitySeconds expiration offset in seconds (can be negative for expired token tests).
     * @return signed compact JWT string.
     */
    public String generateToken(String subject, String tenantId, List<String> roles, long validitySeconds) {
        try {
            Instant now = Instant.now();
            Date issuedAt = Date.from(now);
            Date expiresAt = Date.from(now.plusSeconds(validitySeconds));

            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(securityProperties.getIssuer())
                    .issueTime(issuedAt)
                    .expirationTime(expiresAt)
                    .claim("tenant_id", tenantId)
                    .claim("roles", roles)
                    .claim("scope", String.join(" ", roles))
                    .build();

            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256),
                    claimsSet
            );

            byte[] secretBytes = securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
            MACSigner signer = new MACSigner(secretBytes);
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test JWT token: " + e.getMessage(), e);
        }
    }
}
