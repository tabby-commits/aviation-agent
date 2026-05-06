package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatClientRegistryTest {

    @Test
    void resolvesStableDeepSeekProviderKey() {
        ChatClient deepSeekClient = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(Map.of("deepseek", deepSeekClient));

        assertThat(registry.get("deepseek")).isSameAs(deepSeekClient);
    }

    @Test
    void resolvesLegacyDeepSeekChatKeyToStableProvider() {
        ChatClient deepSeekClient = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(Map.of("deepseek", deepSeekClient));

        assertThat(registry.get("deepseek-chat")).isSameAs(deepSeekClient);
    }

    @Test
    void returnsNullForUnknownModelKey() {
        ChatClient deepSeekClient = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(Map.of("deepseek", deepSeekClient));

        assertThat(registry.get("unknown-model")).isNull();
    }
}
