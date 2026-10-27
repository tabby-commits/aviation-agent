package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import com.kama.jchatmind.agent.hook.RecoveryBudget;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JChatMindFinalSynthesisGuardTest {

    @Test
    void longPlanningMonologueStillRequiresFinalAnswerSynthesis() throws Exception {
        String planning = """
                The skills confirm what I've found. Now I have all the data needed to compose the final answer.
                Let me confirm one thing before writing it. I have enough data. Let me now write the comprehensive
                final answer. Let me summarize the dimensions and verify exact trending data. I should cite docIds
                and page numbers, distinguish fact from inference, and report zero comparable pairs. Let me write it
                comprehensively. I have sufficient evidence. Let me now write the full final answer and terminate.
                """;

        assertTrue(needsSynthesis(planning));
    }

    @Test
    void substantiveMarkdownReportDoesNotRequireAnotherSynthesis() throws Exception {
        String report = """
                # 分析报告

                ## 数据范围与方法

                本报告限定于冻结样本，说明统计单位、分母、去重规则和证据缺口。以下结果只描述公开证据。

                ## 结果

                | 指标 | 中国 | 美国 |
                |---|---:|---:|
                | 参数论文数 | 154 | 21 |

                ## 结论边界

                当前为 0 组严格可比对，因此不得形成技术性能国别排名，也不得外推至整个航天领域。
                报告率只表示冻结论文中存在至少一条结构化参数证据的比例，不能解释为国家透明度、技术成熟度、
                技术优势或工程能力。参数族分布只用于描述公开证据覆盖，不对数值最大值、均值或记录密度作优劣判断。
                所有关键参数仍需保留技术对象、单位、条件、结果形式、证据原文和页码，以便独立复核。
                """;

        assertFalse(needsSynthesis(report));
    }

    @Test
    void chineseDraftNotesWithoutMarkdownReportRequireSynthesis() throws Exception {
        String draft = """
                我已经收集了非常丰富的信息。让我梳理已有的成果。我注意到两个中国星座的数据，
                以及美国两大星座的详细数据。需要说明的一点是知识库中没有精确累计在轨总数，
                但我有足够事实支撑分析。对于投资维度我会如实说明未公开。为审慎起见，
                我在报告中会注明按批次整理。现在准备输出完整报告。
                """;

        assertTrue(needsSynthesis(draft));
    }

    @Test
    void finalAnswerRecoveryAllowsOneValidationRetry() {
        assertEquals(2, RecoveryBudget.defaults().maxFinalAnswerSyntheses());
    }

    @Test
    void singleStrongProcessMarkerWithoutAnyHeadingRequiresSynthesis() throws Exception {
        String planning = """
                所有类别的 SpaceNews 事件均返回 confirmed=0。按数据规则硬闸门，必须停止数值汇总，
                不得改用数据库自由查询或模型记忆绕过闸门。由于本轮约束明确要求不得把候选提及数
                当事件数，本轮应输出 AWAITING_HUMAN_REVIEW 预审报告。因此不进行任何主体事件、
                应用事件或产能行动的数值汇总。指标5、23、26均依赖已确认新闻事件，当前冻结状态下
                不可计算。我按框架输出完整预审报告。现在撰写完整的 Markdown 预审报告。
                """ + "补充过程说明。".repeat(20);

        assertTrue(needsSynthesis(planning));
    }

    private boolean needsSynthesis(String content) throws Exception {
        JChatMind runtime = new JChatMind();
        Method method = JChatMind.class.getDeclaredMethod(
                "needsMainAgentFinalAnswerSynthesis", AssistantMessage.class);
        method.setAccessible(true);
        return (boolean) method.invoke(runtime, new AssistantMessage(content));
    }
}
