# 可选：Agentic Search 对话级回归（rag_eval 不覆盖）

`rag_eval` 仅测 `StructuredRetrievalService.retrieve`，不经过 `SearchIntentRouter` 与 `delegateSearchTask`。要验证 [AGENTIC_SEARCH_PLAN.md](../AGENTIC_SEARCH_PLAN.md) §11 中的路由与委派行为，可按下述做**手工或小脚本**检查。

## 1. 短问答 / 事实型：不应强行走 Agentic

- 在 UI 或聊天 API 发送一条**窄事实问题**（与航天新闻 KB 相关、无需综述）。
- 观察 SSE 或日志：**不应**为主路径反复出现 `AGENTIC_DELEGATING` 或子任务流（具体类型名以 `SseMessage.Type` 为准）。
- 可选：在 `application.yaml` 中暂时关闭 `jchatmind.agentic-search.enabled` 或 `router.enabled`，对同一问题对比回答结构是否一致（仅作 A/B，不作为检索评测）。

## 2. 综述 / 多源型：可触发委派（依提示词与路由）

- 发送明显「跨时间段对比 / 多源综合」类问题，观察是否出现委派相关 SSE 阶段事件。
- 若 Tavily 未配置，确认文档所述 **web→KB 回退** 行为：回答仍应可依赖 KB，且无未处理异常链。

## 3. 与 rag_eval 的关系

- 本节通过即**不保证** `Recall@5` 变化；通过 rag_eval 即**不保证**意图路由正确。二者互补。
