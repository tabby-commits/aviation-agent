package com.kama.jchatmind.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekThinkingModeInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeepSeekThinkingModeInterceptor interceptor = new DeepSeekThinkingModeInterceptor(objectMapper);

    @Test
    void disablesThinkingForDeepSeekV4ChatCompletionRequests() throws Exception {
        CaptureExecution execution = new CaptureExecution();
        MockClientHttpRequest request = request();
        byte[] body = """
                {"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hello"}]}
                """.getBytes();

        interceptor.intercept(request, body, execution);

        JsonNode updated = objectMapper.readTree(execution.body);
        assertThat(updated.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(updated.path("model").asText()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void leavesExplicitThinkingOptionsUntouched() throws Exception {
        CaptureExecution execution = new CaptureExecution();
        byte[] body = """
                {"model":"deepseek-v4-flash","thinking":{"type":"enabled"},"messages":[]}
                """.getBytes();

        interceptor.intercept(request(), body, execution);

        JsonNode updated = objectMapper.readTree(execution.body);
        assertThat(updated.path("thinking").path("type").asText()).isEqualTo("enabled");
    }

    @Test
    void leavesNonV4ModelsUntouched() throws Exception {
        CaptureExecution execution = new CaptureExecution();
        byte[] body = """
                {"model":"deepseek-chat","messages":[]}
                """.getBytes();

        interceptor.intercept(request(), body, execution);

        JsonNode updated = objectMapper.readTree(execution.body);
        assertThat(updated.has("thinking")).isFalse();
    }

    private MockClientHttpRequest request() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.deepseek.com/chat/completions"));
        request.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        return request;
    }

    private static class CaptureExecution implements ClientHttpRequestExecution {
        private byte[] body;

        @Override
        public ClientHttpResponse execute(org.springframework.http.HttpRequest request, byte[] body) throws IOException {
            this.body = body;
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        }
    }
}
