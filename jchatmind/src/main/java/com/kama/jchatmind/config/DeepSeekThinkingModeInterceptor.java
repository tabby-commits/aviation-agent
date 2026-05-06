package com.kama.jchatmind.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DeepSeekThinkingModeInterceptor implements ClientHttpRequestInterceptor {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DEEPSEEK_V4_MODEL_PREFIX = "deepseek-v4";
    private static final String THINKING_FIELD = "thinking";
    private static final String TYPE_FIELD = "type";
    private static final String DISABLED = "disabled";

    private final ObjectMapper objectMapper;

    public DeepSeekThinkingModeInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        byte[] requestBody = shouldDisableThinking(request, body) ? withThinkingDisabled(body) : body;
        if (requestBody != body) {
            request.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
            request.getHeaders().setContentLength(requestBody.length);
        }
        return execution.execute(request, requestBody);
    }

    private boolean shouldDisableThinking(HttpRequest request, byte[] body) {
        if (!request.getURI().getPath().endsWith(CHAT_COMPLETIONS_PATH) || body.length == 0) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root instanceof ObjectNode
                    && root.path("model").asText("").startsWith(DEEPSEEK_V4_MODEL_PREFIX)
                    && !root.has(THINKING_FIELD);
        } catch (IOException ignored) {
            return false;
        }
    }

    private byte[] withThinkingDisabled(byte[] body) throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(body);
        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put(TYPE_FIELD, DISABLED);
        root.set(THINKING_FIELD, thinking);
        return objectMapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }
}
