package com.kama.jchatmind.model.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDTOModelTypeTest {

    @Test
    void resolvesStableDeepSeekProviderKey() {
        assertThat(AgentDTO.ModelType.fromModelName("deepseek"))
                .isEqualTo(AgentDTO.ModelType.DEEPSEEK);
    }

    @Test
    void resolvesLegacyDeepSeekChatKeyToDeepSeekProvider() {
        assertThat(AgentDTO.ModelType.fromModelName("deepseek-chat"))
                .isEqualTo(AgentDTO.ModelType.DEEPSEEK);
    }
}
