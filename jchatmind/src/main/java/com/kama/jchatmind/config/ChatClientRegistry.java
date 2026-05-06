package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChatClientRegistry {

    private static final String DEEPSEEK_PROVIDER_KEY = "deepseek";
    private static final String LEGACY_DEEPSEEK_MODEL_KEY = "deepseek-chat";

    private final Map<String, ChatClient> chatClients;

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = chatClients;
    }

    public ChatClient get(String key) {
        if (LEGACY_DEEPSEEK_MODEL_KEY.equals(key)) {
            return chatClients.get(DEEPSEEK_PROVIDER_KEY);
        }
        return chatClients.get(key);
    }
}
