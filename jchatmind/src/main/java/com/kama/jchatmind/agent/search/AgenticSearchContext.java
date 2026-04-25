package com.kama.jchatmind.agent.search;

/**
 * Per-thread context exposed while a main JChatMind instance executes tools.
 * SearchDelegationTool uses it to inherit the parent chat session and model.
 */
public final class AgenticSearchContext {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private AgenticSearchContext() {
    }

    public static void set(String chatSessionId, String model) {
        CURRENT.set(new Context(chatSessionId, model));
    }

    public static Context get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Context(String chatSessionId, String model) {
    }
}
