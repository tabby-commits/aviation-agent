package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.agent.search.SearchIntentDecision;
import com.kama.jchatmind.agent.search.SearchIntentRouter;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;
    private final SearchIntentRouter searchIntentRouter;
    private final AgenticSearchProperties agenticSearchProperties;
    private final SseService sseService;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        // 创建一个 Agent 实例处理聊天事件
        SearchIntentDecision decision = searchIntentRouter.route(event.getUserInput(), null);
        emitRouting(event.getSessionId(), decision);
        String extraSystemPrompt = decision.useAgenticSearch()
                ? agenticSearchPrompt(decision)
                : null;
        JChatMind jChatMind = jChatMindFactory.create(event.getAgentId(), event.getSessionId(), extraSystemPrompt);
        jChatMind.run();
    }

    private void emitRouting(String sessionId, SearchIntentDecision decision) {
        if (!agenticSearchProperties.isEnabled()) {
            return;
        }
        try {
            sseService.send(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AGENTIC_ROUTING)
                    .payload(SseMessage.Payload.builder()
                            .stage(decision.useAgenticSearch() ? decision.reason() : "none")
                            .done(true)
                            .build())
                    .build());
        } catch (Exception ignored) {
            // Missing SSE connection must not block chat processing.
        }
    }

    private String agenticSearchPrompt(SearchIntentDecision decision) {
        return """
                【Agentic Search 路由提示】
                本轮问题已被判定为需要 Agentic Search，原因：%s。
                你应优先调用 delegateSearchTask，将问题拆成 1 到 N 个可并行检索的子任务。
                如果没有明确可用的知识库 ID，不要启用知识库检索：将 kbId 设为 null，并将 searchPolicy.allowKbSearch 设为 false。
                需要外部资料时使用 searchPolicy.allowWebSearch=true。
                子任务失败时不要编造，应在最终回答中说明对应维度材料不足。
                """.formatted(decision.reason());
    }
}
