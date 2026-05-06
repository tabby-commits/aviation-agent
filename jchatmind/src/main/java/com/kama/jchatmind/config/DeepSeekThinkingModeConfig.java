package com.kama.jchatmind.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DeepSeekThinkingModeConfig {

    @Bean
    public RestClientCustomizer deepSeekThinkingModeRestClientCustomizer(DeepSeekThinkingModeInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}
