# Agentic Search Debug Findings

## 2026-04-27 基线发现

- `AGENTIC_SEARCH_DEBUG_HANDOFF.md` 说明当前问题属于对话级 Agentic Search 链路，不属于 `rag_eval` 离线检索。
- 已有未提交变更包含三类内容：子 Agent 移除 `terminate`、最终 JSON repair 轮、临时 debug instrumentation。
- 当前没有发现新的 `debug-ffef34.log`，需要重新复现才能判断最新失败分支。
- 相关调试点位：
  - `JChatMind.java`：主 Agent 工具调用前参数、工具执行异常、子 Agent 每轮输出。
  - `SearchDelegationTool.java`：`delegateSearchTask invoked/returned`。
  - `SubAgentRuntimeFactory.java`：子 Agent toolset、raw output、JSON 解析、repair 输出。
- SSE 连接入口是 `/sse/connect/{chatSessionId}`；交接文档提示复现时必须保持 SSE 连接，否则主 Agent 可能因无客户端中断。

## 2026-04-27 完整综述复现

- 新会话 `dac9d247-9382-4b2f-8936-b626df3544e2` 成功触发 `AGENTIC_ROUTING`，路由阶段为 `survey|multi_source|comparison|time_series`。
- 主 Agent 生成的 `delegateSearchTask` arguments 是合法 JSON，并成功进入 `SearchDelegationTool.delegate(...)`。
- `delegateSearchTask returned` 显示 `resultCount=0`、`failureCount=3`。
- 三个子任务 `flights_2025`、`failure_analysis`、`improvements_roadmap` 都因为 `allowKbSearch=true` 且 `kbId=""` 返回 `SEARCH_DELEGATION_INVALID_INPUT`，错误消息为“启用知识库检索时 kbId 不能为空”。
- 本次失败不属于子 Agent JSON 输出失败；子 Agent 没有真正运行到 `runSubAgent entry`。
