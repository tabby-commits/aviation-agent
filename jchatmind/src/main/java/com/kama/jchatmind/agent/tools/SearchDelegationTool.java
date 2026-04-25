package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.agent.search.AgenticSearchContext;
import com.kama.jchatmind.agent.search.SubAgentExecutionService;
import com.kama.jchatmind.agent.search.model.DelegationResult;
import com.kama.jchatmind.agent.search.model.GlobalPolicy;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SearchDelegationTool implements Tool {

    private final SubAgentExecutionService executionService;

    public SearchDelegationTool(SubAgentExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String getName() {
        return "delegateSearchTask";
    }

    @Override
    public String getDescription() {
        return "将综述、广搜、跨源分析型问题拆成 1 到 N 个可并行执行的子检索任务。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "delegateSearchTask",
            description = "派发并行子任务进行 Agentic Search。每个子任务可独立启用知识库宽召回和 Web 搜索，返回结构化摘要、关键发现、引用与 failures。"
    )
    public DelegationResult delegate(List<SubTaskSpec> tasks, GlobalPolicy policy) {
        AgenticSearchContext.Context context = AgenticSearchContext.get();
        String parentSessionId = context == null ? null : context.chatSessionId();
        String model = context == null ? null : context.model();
        return executionService.execute(tasks, policy, parentSessionId, model);
    }
}
