package com.chronoscore.coordinator.api.security;

import com.chronoscore.coordinator.api.dto.response.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security configuration for Chronos Core REST API.
 * <p>
 * Key Features:
 * <ul>
 *   <li>Stateless JWT authentication via OAuth2 Resource Server.</li>
 *   <li>Role-Based Access Control (RBAC): ADMIN, OPERATOR, VIEWER.</li>
 *   <li>Strict error handling: JSON formatted 401 and 403 responses without stack traces.</li>
 *   <li>Controlled CORS policy for local development and dashboard control plane.</li>
 *   <li>Security response headers (frame options DENY, nosniff content-type).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless REST API with token authentication: disable CSRF
                .csrf(AbstractHttpConfigurer::disable)
                // Controlled CORS configuration
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Security headers
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(HeadersConfigurer.ContentTypeOptionsConfig::disable)
                )
                // Stateless session management
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Endpoint authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Safe Actuator health/readiness endpoints
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Dev token endpoint for testing/local setups
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // ADMIN only operations
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflows").hasRole(ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflows/*/versions").hasRole(ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflows/*/versions/*/publish").hasRole(ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflows/*/versions/*/deprecate").hasRole(ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tenants/*/policy").hasRole(ChronosRoles.ROLE_ADMIN)

                        // OPERATOR + ADMIN mutating operations
                        .requestMatchers(HttpMethod.POST, "/api/v1/tasks").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/tasks/*/retry").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/tasks/*/cancel").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflows/*/execute").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflow-executions/*/cancel").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/dlq/*/replay").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/dlq/*/archive").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)

                        // AI operations
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/workflows/generate").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/workflows/*/approve").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/workflows/*/reject").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/incidents/analyze").hasAnyRole(ChronosRoles.ROLE_VIEWER, ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/actions/*/approve").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/actions/*/reject").hasAnyRole(ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)

                        // VIEWER + OPERATOR + ADMIN read operations
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(ChronosRoles.ROLE_VIEWER, ChronosRoles.ROLE_OPERATOR, ChronosRoles.ROLE_ADMIN)

                        // All other /api/v1 requests must be authenticated
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                // OAuth2 Resource Server JWT configuration
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] secretBytes = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }

    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();

            // 1. Check "roles" claim (List<String>)
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                for (String role : roles) {
                    String formatted = role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase();
                    authorities.add(new SimpleGrantedAuthority(formatted));
                }
            }

            // 2. Fall back to "scope" or "scp" if roles claim is absent
            if (authorities.isEmpty()) {
                String scope = jwt.getClaimAsString("scope");
                if (scope != null) {
                    for (String s : scope.split("\\s+")) {
                        String formatted = s.startsWith("ROLE_") ? s : "ROLE_" + s.toUpperCase();
                        authorities.add(new SimpleGrantedAuthority(formatted));
                    }
                }
            }

            return new JwtAuthenticationToken(jwt, authorities);
        };
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiErrorResponse error = ApiErrorResponse.of(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized",
                    "UNAUTHENTICATED",
                    authException.getMessage() != null ? authException.getMessage() : "Authentication token missing or invalid",
                    request.getRequestURI()
            );
            objectMapper.writeValue(response.getOutputStream(), error);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiErrorResponse error = ApiErrorResponse.of(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Forbidden",
                    "ACCESS_DENIED",
                    accessDeniedException.getMessage() != null ? accessDeniedException.getMessage() : "Access denied for this resource",
                    request.getRequestURI()
            );
            objectMapper.writeValue(response.getOutputStream(), error);
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With"));
        config.setExposedHeaders(List.of("Location", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
