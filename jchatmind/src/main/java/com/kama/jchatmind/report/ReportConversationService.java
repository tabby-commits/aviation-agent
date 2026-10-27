package com.kama.jchatmind.report;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Every nonempty request starts a new research run and a new report version. */
@Service
public class ReportConversationService {
    private static final Logger log = LoggerFactory.getLogger(ReportConversationService.class);
    private final QuestionReportPipeline pipeline;
    private final ChatMessageFacadeService messages;
    private final SseService sse;

    public ReportConversationService(QuestionReportPipeline pipeline,
                                     ChatMessageFacadeService messages, SseService sse) {
        this.pipeline = pipeline;
        this.messages = messages;
        this.sse = sse;
    }

    public boolean handleIfResearch(ChatEvent event) {
        String question = event.getUserInput();
        if (question == null || question.isBlank()) return false;
        try {
            ReportDocument report = pipeline.run(event.getSessionId(), question,
                    text -> stage(event.getSessionId(), text));
            publish(event.getSessionId(), "《" + report.title() + "》第 " + report.version()
                    + " 版已根据本轮问题与新获取的证据生成。点击角标查看原网页，Word 可下载。", report.id());
        } catch (Exception e) {
            log.error("Research report failed for session {}", event.getSessionId(), e);
            publish(event.getSessionId(), "本轮分析未生成报告：" + e.getMessage(), null);
        }
        return true;
    }

    private void stage(String sessionId, String text) {
        try {
            sse.send(sessionId, SseMessage.builder().type(SseMessage.Type.REPORT_STAGE)
                    .payload(SseMessage.Payload.builder().stage("report").statusText(text).done(false).build())
                    .build());
        } catch (Exception ignored) { /* Disconnected browser does not change the run. */ }
    }

    private void publish(String sessionId, String content, String reportId) {
        ChatMessageDTO.MetaData metadata = reportId == null ? null
                : ChatMessageDTO.MetaData.builder().reportId(reportId).build();
        ChatMessageDTO dto = ChatMessageDTO.builder().sessionId(sessionId)
                .role(ChatMessageDTO.RoleType.ASSISTANT).content(content).metadata(metadata).build();
        String id = messages.createChatMessage(dto).getChatMessageId();
        ChatMessageVO visible = ChatMessageVO.builder().id(id).sessionId(sessionId)
                .role(ChatMessageDTO.RoleType.ASSISTANT).content(content).metadata(metadata).build();
        try {
            sse.send(sessionId, SseMessage.builder().type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder().message(visible).build()).build());
            sse.send(sessionId, SseMessage.builder().type(SseMessage.Type.AI_DONE)
                    .payload(SseMessage.Payload.builder().done(true).build()).build());
        } catch (Exception ignored) { /* Message is persisted for reconnect. */ }
    }
}
