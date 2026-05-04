package com.kama.jchatmind.agent.tools;

import org.springframework.stereotype.Component;

@Component
public class TerminateTool implements Tool {

    @Override
    public String getName() {
        return "terminate";
    }

    @Override
    public String getDescription() {
        return "跳出 Agent Loop 的工具";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "terminate",
            description = """
                    结束主 Agent 循环。仅当你在「本条助手消息正文」中已经写出了用户可直接阅读的完整最终答复（满足其格式与结构要求）后才可调用。
                    禁止在只有「我准备整理」「现在可以回答了」等过渡语、尚无实质正文时调用；那种情况请先写出完整 Markdown 正文，或未调工具仅用纯文本收束时可不调用本工具。"""
    )
    public void terminate() {}
}
