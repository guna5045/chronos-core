package com.chronoscore.ai.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StubAiProviderTest {

    private StubAiProvider provider;

    @BeforeEach
    void setUp() {
        provider = new StubAiProvider();
    }

    @Test
    @DisplayName("Returns enqueued custom response")
    void shouldReturnEnqueuedResponse() {
        provider.enqueueResponse("{\"custom\": true}");

        AiCompletionRequest request = AiCompletionRequest.builder()
                .systemPrompt("test")
                .userPrompt("test")
                .build();

        AiCompletionResponse response = provider.generate(request);
        assertEquals("{\"custom\": true}", response.content());
    }

    @Test
    @DisplayName("Simulates failure when configured or prompt requested")
    void shouldSimulateFailure() {
        provider.setSimulateFailure(true);

        AiCompletionRequest request = AiCompletionRequest.builder()
                .systemPrompt("test")
                .userPrompt("test")
                .build();

        AiProviderException ex = assertThrows(AiProviderException.class, () -> provider.generate(request));
        assertEquals(500, ex.getStatusCode());
    }

    @Test
    @DisplayName("Simulates timeout when configured or prompt requested")
    void shouldSimulateTimeout() {
        provider.setSimulateTimeout(true);

        AiCompletionRequest request = AiCompletionRequest.builder()
                .systemPrompt("test")
                .userPrompt("test")
                .build();

        AiProviderException ex = assertThrows(AiProviderException.class, () -> provider.generate(request));
        assertEquals(504, ex.getStatusCode());
    }

    @Test
    @DisplayName("Throws when marked unavailable")
    void shouldThrowWhenUnavailable() {
        provider.setAvailable(false);

        AiCompletionRequest request = AiCompletionRequest.builder()
                .systemPrompt("test")
                .userPrompt("test")
                .build();

        AiProviderException ex = assertThrows(AiProviderException.class, () -> provider.generate(request));
        assertEquals(503, ex.getStatusCode());
    }
}
