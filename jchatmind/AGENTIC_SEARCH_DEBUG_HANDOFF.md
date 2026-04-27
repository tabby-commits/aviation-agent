# Agentic Search Debug 交接文档

更新时间：2026-04-27 13:42 UTC+8

## 背景

本轮调试针对 Agentic Search 对话级链路中的问题：

- Agentic 路由与委派链路已经能触发，例如 SSE 中出现 `AGENTIC_ROUTING`、`AGENTIC_DELEGATING`、`AGENTIC_SUBAGENT_PROGRESS`、`AGENTIC_DONE`。
- 但委派结果曾出现 `SEARCH_DELEGATION_PROVIDER_ERROR`。
- 失败原因包括：
  - `子 Agent 未输出 JSON`
  - `解析子 Agent JSON 输出失败`
  - 部分场景下主 Agent 生成的 `delegateSearchTask` arguments 本身是非法 JSON，Spring AI 在工具执行前报错。

注意：`rag_eval` 只评估 `StructuredRetrievalService.retrieve`，不经过 `SearchIntentRouter` / `delegateSearchTask`。因此本问题属于对话级 Agentic Search 链路，不属于 `rag_eval` 离线检索指标本身。

## 当前调试配置

Debug 会话信息：

- Session ID：`ffef34`
- Debug 日志文件：`debug-ffef34.log`
- 日志写入方式：Java 代码直接 append NDJSON 到 workspace 根目录下的 `debug-ffef34.log`

当前仍保留调试 instrumentation，尚未清理。涉及文件：

- `src/main/java/com/kama/jchatmind/agent/JChatMind.java`
- `src/main/java/com/kama/jchatmind/agent/search/SubAgentRuntimeFactory.java`
- `src/main/java/com/kama/jchatmind/agent/tools/SearchDelegationTool.java`

调试日志区域均使用 `// #region agent log` / `// #endregion` 包裹。

## 已确认的问题与证据

### 1. 子 Agent 曾通过 `terminate` 结束，导致没有 JSON 输出

修复前，子 Agent 工具集包含：

```text
terminate,directAnswer,webSearch
```

运行时日志显示子 Agent 多次调用 `webSearch` 失败后，最后调用了 `terminate`，导致 `runtime.getLastAssistantText()` 取到自然语言或空内容，而不是 `SubTaskResult` JSON。

典型失败输出：

```text
The search tool is consistently failing. Let me compile what I know based on available information and provide a response.
```

随后 JSON 提取失败：

```text
extractJson start=-1,end=-1
JsonParseException: Unrecognized token 'The'
```

结论：

- `H1` 确认：子 Agent 可能停在非最终 JSON 状态。
- `H2` 部分确认：子 Agent 会用工具结束或继续工具调用，而非输出 JSON。
- `H3` 确认：最终输出可能是自然语言，无法解析成 `SubTaskResult`。

### 2. 移除 `terminate` 后，子 Agent 仍可能耗尽 `maxSteps`

第一次修复后，子 Agent 工具集变为：

```text
directAnswer,webSearch
```

这证明 `terminate` 已移除。

但新的日志显示，子 Agent 仍连续调用 `webSearch` 到 `maxSteps=5`，最后一条 assistant 是空文本 + 工具调用，导致：

```json
{"hasOutput":false,"outputLength":0}
```

结论：

- 仅移除 `terminate` 不够。
- 需要在子 Agent 循环结束后，如果最后输出为空或非 JSON，补一次“无工具最终 JSON 修复轮”。

### 3. 最终 JSON 修复轮已验证对单任务用例有效

已增加 `repairJsonOutput(...)`，在以下情况触发：

- 子 Agent 最后输出为空。
- 子 Agent 最后输出非 JSON，`parseResult(extractJson(output))` 失败。

修复轮要求：

- 不调用工具。
- 仅输出一个 JSON 对象。
- 即使证据不足，也生成 `SubTaskResult` JSON，并在 `summary` 中说明证据不足。

post-fix 单任务验证结果：

```json
{
  "results": [
    {
      "taskId": "starship_test9",
      "status": "OK",
      "summary": "无法检索到关于SpaceX Starship 2025年飞行测试9（IFT-9）的有效信息...",
      "keyFindings": [
        {
          "title": "检索失败说明",
          "detail": "所有5次网络搜索均因Tavily服务错误而失败...",
          "citations": []
        }
      ],
      "citations": [],
      "stats": {
        "toolCalls": 5,
        "kbQueries": 0,
        "webQueries": 5,
        "elapsedMs": 0,
        "fallback": null
      }
    }
  ],
  "failures": []
}
```

结论：

- 单任务 `starship_test9` 场景已从 `SEARCH_DELEGATION_PROVIDER_ERROR` 转为结构化结果返回。
- 即使 Tavily 全部失败，也不会再因为“未输出 JSON”导致委派失败。

## 已做代码改动

### 1. `SubAgentToolsetPolicy`

文件：

- `src/main/java/com/kama/jchatmind/agent/search/SubAgentToolsetPolicy.java`

变更：

- 子 Agent 工具集移除 `TerminateTool`。
- 保留：
  - `DirectAnswerTool`
  - 按策略添加 `SubAgentKnowledgeTool`
  - 按策略添加 `WebSearchTool`

原因：

- Runtime 证据显示 `terminate` 会让子 Agent 提前结束，且不输出 JSON。

### 2. `SubAgentRuntimeFactory`

文件：

- `src/main/java/com/kama/jchatmind/agent/search/SubAgentRuntimeFactory.java`

变更：

- 加强子 Agent system prompt：
  - 不要调用 `terminate` 或 `directAnswer` 作为最终回答。
  - 检索失败也必须输出 JSON。
  - 证据不足时 `citations` 可以为空数组。
- 新增 `repairJsonOutput(...)`：
  - 当最后输出为空或无法解析为 `SubTaskResult` 时，使用同模型做一次无工具最终 JSON 修复轮。
- 保留调试日志：
  - `runSubAgent entry`
  - `Sub-agent toolset`
  - `runSubAgent raw output`
  - `Extract JSON window`
  - `Sub-agent JSON parse failed`
  - `Repair JSON output`

### 3. `JChatMind`

文件：

- `src/main/java/com/kama/jchatmind/agent/JChatMind.java`

变更：

- 增加子 Agent 调试日志：
  - 每轮 `think` 的 assistant 文本长度、预览、工具调用数、工具名。
  - 每轮 `execute` 的工具返回摘要。
  - `run` 结束时最后 assistant 文本状态。
- 增加主 Agent 调试日志：
  - `delegateSearchTask` 调用前记录主 Agent 生成的工具 arguments。
  - 工具执行异常时记录 exception。
- 新增 `getConversationTextForRepair()`：
  - 将子 Agent 的 conversation history 压缩成文本，供最终 JSON 修复轮使用。

### 4. `SearchDelegationTool`

文件：

- `src/main/java/com/kama/jchatmind/agent/tools/SearchDelegationTool.java`

变更：

- 增加 `delegateSearchTask invoked` 日志：
  - parent session
  - model
  - taskCount
  - taskIds
- 增加 `delegateSearchTask returned` 日志：
  - resultCount
  - failureCount

目的：

- 区分问题发生在：
  - 主 Agent 参数 JSON 转换前
  - `SearchDelegationTool` 已被调用但子 Agent 失败
  - `SearchDelegationTool` 返回成功但主 Agent 后续处理失败

### 5. 测试同步

文件：

- `src/test/java/com/kama/jchatmind/agent/search/SubAgentToolsetPolicyTest.java`

变更：

- 构造函数参数移除 `TerminateTool`。
- 测试断言不再期望子 Agent 工具集包含 `terminate`。

## 当前仍未完全确认的问题

### A. 用户最新复现没有产生新的 debug log

最新一次用户说“Issue reproduced”后，读取到的 `debug-ffef34.log` 仍是上一轮单任务验证日志，没有新增 task/session。

当前后端终端也只显示上一轮单任务验证成功：

- `delegateSearchTask` 被调用。
- 子 Agent 连续 `webSearch` 失败。
- 修复轮生成 JSON。
- `delegateSearchTask` 最终返回 `results=[...]`，`failures=[]`。

因此，用户看到的“复现”很可能不是同一个子 Agent JSON 输出失败路径，可能是以下路径之一：

1. 主 Agent 生成了非法 `delegateSearchTask` arguments JSON，Spring AI 在工具执行前就失败。
2. 用户使用的是另一个会话或另一个后端进程，未命中当前 instrumentation。
3. 多任务场景中某个子任务仍失败，但未写入当前 log，需要重新复现。
4. 主 Agent 成功拿到 `results`，但最终回答仍表现为“搜索失败”或质量不好，这是主 Agent 消费结果的问题，而非子 Agent JSON 解析失败。

## 下一步计划

### 1. 重新复现用户的完整问题

操作：

1. 确认当前后端是最新重启的 8080 进程。
2. 清空 `debug-ffef34.log`。
3. 使用用户原始综述问题复现：

```text
请综合比较 2025 年 SpaceX Starship 试飞进展、主要失败原因和后续改进方向，要求多来源检索后给出结构化综述。
```

预期日志：

- 如果进入主 Agent 工具执行前，应出现：
  - `Main agent delegate tool call before execution`
- 如果 `delegateSearchTask` 被成功调用，应出现：
  - `delegateSearchTask invoked`
  - `delegateSearchTask returned`
- 如果进入子 Agent，应出现：
  - `runSubAgent entry`
  - `Sub-agent toolset`
  - `Sub-agent think output`
  - `Repair JSON output` 或正常 JSON parse

### 2. 根据日志分支处理

#### 分支 1：主 Agent arguments 非法

证据：

- `Main agent delegate tool call before execution` 有 `delegateArgs`
- 随后 `Main agent tool execution failed`
- 异常类似：

```text
Conversion from JSON to java.util.Map<java.lang.String, java.lang.Object> failed
JsonParseException: Unexpected character ...
```

下一步：

- 优化 `SearchDelegationTool` 的 `@Tool(description=...)`，明确 JSON schema 示例。
- 考虑把主 Agent system prompt 中的 `delegateSearchTask` 调用格式写得更严格。
- 这属于“主 Agent 工具参数生成不稳定”，不是子 Agent 输出 JSON 问题。

#### 分支 2：子 Agent failures 仍非空

证据：

- `delegateSearchTask invoked` 出现。
- `delegateSearchTask returned` 的 `failureCount > 0`。
- 子 Agent 日志中有 `Sub-agent JSON parse failed` 或没有 `Repair JSON output`。

下一步：

- 对具体子任务检查 `allowKb/allowWeb/maxSteps/fallback`。
- 检查 repair 轮输出是否仍无法解析。
- 如果 repair 轮输出 Markdown 代码块或多余文本，增强 `extractJson` 或 repair prompt。

#### 分支 3：delegateSearchTask 成功，但最终回答质量差

证据：

- `delegateSearchTask returned` 的 `resultCount > 0` 且 `failureCount=0`。
- 主 Agent 最终仍回答“搜索失败”或没有正确吸收结构化结果。

下一步：

- 优化主 Agent 在收到 `DelegationResult` 后的 system prompt。
- 要求主 Agent：
  - 如果 `results` 非空，必须基于 `results.summary/keyFindings/citations` 回答。
  - 如果 `failures` 非空，只说明对应维度材料不足，不要整体否定搜索。

### 3. 验证通过后清理 instrumentation

验证通过条件：

- 重新复现完整综述问题。
- `debug-ffef34.log` 证明：
  - 主 Agent 工具参数合法。
  - `delegateSearchTask invoked`。
  - 每个子任务都有结构化 `SubTaskResult` 或可解释的 fallback JSON。
  - `delegateSearchTask returned` 中 `failureCount=0`，或 failures 被主 Agent 正确说明。
- 用户确认前端/对话结果符合预期。

清理项：

- 删除 `JChatMind.java` 中 debug logging 代码。
- 删除 `SubAgentRuntimeFactory.java` 中 debug logging 代码。
- 删除 `SearchDelegationTool.java` 中 debug logging 代码。
- 删除 `debug-ffef34.log`。
- 保留业务修复：
  - 子 Agent 不暴露 `terminate`。
  - 子 Agent 最终 JSON repair 轮。
  - 子 Agent prompt 强化。

## 当前建议接手顺序

1. 先按用户原始问题重新复现一次，确保日志真的更新。
2. 读取 `debug-ffef34.log`，先看是否出现 `Main agent tool execution failed`。
3. 若有主 Agent JSON 参数错误，优先修主 Agent 工具调用格式。
4. 若没有主 Agent JSON 参数错误，再看 `delegateSearchTask returned` 的 `failureCount`。
5. 如果 `failureCount=0`，不要继续修子 Agent，转而检查主 Agent 最终回答消费结果的问题。

## 重要注意事项

- 当前 `debug-ffef34.log` 可能不存在或为空，这是正常的；只有命中 instrumentation 后才会创建。
- 复现对话必须先有 SSE 连接，否则主 Agent 可能因为 `No client found for chatSessionId` 中断，无法进入 Agentic 子任务。
- Tavily 当前不稳定，很多查询会返回：

```json
{"hits":[],"provider":"tavily","degraded":true,"errorMessage":"Tavily search failed"}
```

业务逻辑应该能把这种情况结构化为“证据不足”，而不是抛 `SEARCH_DELEGATION_PROVIDER_ERROR`。

