package com.kama.jchatmind.agent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIntentRouterTest {

    @Test
    void disabledRouterDoesNotUseAgenticSearch() {
        AgenticSearchProperties properties = new AgenticSearchProperties();
        properties.setEnabled(false);
        SearchIntentRouter router = new SearchIntentRouter(new ChatClientRegistry(Map.of()), properties, new ObjectMapper());

        SearchIntentDecision decision = router.route("一句简单事实问答", null);

        assertThat(decision.useAgenticSearch()).isFalse();
        assertThat(decision.reason()).isEqualTo("disabled");
    }

    @Test
    void missingRouterModelFallsBackToNormalAgent() {
        AgenticSearchProperties properties = new AgenticSearchProperties();
        properties.setEnabled(true);
        SearchIntentRouter router = new SearchIntentRouter(new ChatClientRegistry(Map.of()), properties, new ObjectMapper());

        SearchIntentDecision decision = router.route("请综述 2025 年 AI 搜索趋势", null);

        assertThat(decision.useAgenticSearch()).isFalse();
        assertThat(decision.reason()).isEqualTo("router_model_missing");
    }
}
