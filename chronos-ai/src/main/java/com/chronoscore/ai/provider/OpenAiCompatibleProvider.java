package com.chronoscore.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI Provider connecting to any OpenAI-compatible HTTP chat completions endpoint.
 * Works with OpenAI, Azure OpenAI, vLLM, Ollama, LocalAI, and similar engines.
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this(baseUrl, apiKey, modelName, timeout, new ObjectMapper());
    }

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, String modelName, Duration timeout, ObjectMapper objectMapper) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.apiKey = apiKey != null ? apiKey : "";
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");

        // Java 21 Virtual-thread friendly HTTP client with bounded timeouts
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(this.timeout);

        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl.endsWith("/") ? this.baseUrl.substring(0, this.baseUrl.length() - 1) : this.baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String getProviderName() {
        return "openai-compatible";
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check health or simple status endpoint if available
            return true;
        } catch (Exception e) {
            log.warn("OpenAI-compatible provider availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public AiCompletionResponse generate(AiCompletionRequest request) {
        Instant start = Instant.now();
        Map<String, Object> payload = buildRequestBody(request);

        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/chat/completions")
                    .body(payload);

            if (!apiKey.isBlank()) {
                requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }

            String responseBody = requestSpec.retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new AiProviderException(getProviderName(), 502, "Received empty response from AI provider");
            }

            return parseResponse(responseBody, Duration.between(start, Instant.now()));
        } catch (RestClientResponseException ex) {
            log.error("AI Provider error response: HTTP {} - {}", ex.getStatusCode().value(), ex.getStatusText());
            throw new AiProviderException(getProviderName(), ex.getStatusCode().value(),
                    "AI provider returned error: " + ex.getStatusText());
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("AI Provider request failed: {}", ex.getMessage());
            throw new AiProviderException(getProviderName(), "Communication failure with AI provider: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildRequestBody(AiCompletionRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());

        // Structured JSON mode
        Map<String, String> responseFormat = new HashMap<>();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())
        );
        body.put("messages", messages);

        return body;
    }

    private AiCompletionResponse parseResponse(String rawJson, Duration duration) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                throw new AiProviderException(getProviderName(), 502, "Malformed AI response: 'choices' array missing or empty");
            }

            JsonNode firstChoice = choices.get(0);
            String content = firstChoice.path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new AiProviderException(getProviderName(), 502, "Malformed AI response: message content is blank");
            }

            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);

            return new AiCompletionResponse(content, modelName, promptTokens, completionTokens, duration);
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiProviderException(getProviderName(), "Failed to parse AI provider JSON response: " + ex.getMessage(), ex);
        }
    }
}
