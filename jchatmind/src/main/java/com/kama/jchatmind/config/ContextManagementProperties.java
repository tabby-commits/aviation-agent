package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.context-management")
public class ContextManagementProperties {
    private boolean enabled = true;
    private int toolResultMaxChars = 8000;
    private int toolResultHeadChars = 3500;
    private int toolResultTailChars = 3500;
    private int summaryTriggerMessages = 16;
    private int summaryKeepRecentMessages = 8;
    private int maxPromptChars = 60000;
}
