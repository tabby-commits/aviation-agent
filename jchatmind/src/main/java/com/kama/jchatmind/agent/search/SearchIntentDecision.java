package com.kama.jchatmind.agent.search;

public record SearchIntentDecision(
        boolean useAgenticSearch,
        String reason,
        boolean needClarification
) {
    public static SearchIntentDecision disabled(String reason) {
        return new SearchIntentDecision(false, reason, false);
    }
}
