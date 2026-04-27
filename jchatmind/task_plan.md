# Agentic Search Debug Task Plan

## 目标
继续调试对话级 Agentic Search 链路，确认用户复现路径属于主 Agent 工具参数、子 Agent 执行结果，还是主 Agent 消费委派结果的问题，并完成最小修复、验证与调试代码清理。

## 阶段

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 1. 基线确认 | complete | 检查 git 状态、现有调试改动、日志文件与复现入口。 |
| 2. 重新复现 | complete | 使用 Starship 综述问题生成新的 debug 日志。 |
| 3. 日志分类 | complete | 根据 instrumentation 标记判断失败分支。 |
| 4. 分支修复 | complete | 按日志证据做最小修复并补测试。 |
| 5. 验证清理 | complete | 运行目标测试，清理临时 debug instrumentation。 |

## 验收标准

- 完整综述问题能稳定进入 `delegateSearchTask` 或日志能证明失败发生在工具执行前。
- 子 Agent 空输出、自然语言输出、工具耗尽不再导致 `SEARCH_DELEGATION_PROVIDER_ERROR`。
- Tavily degraded 时最终回答说明证据不足，而不是整体搜索失败。
- 临时 debug logging 和 `debug-ffef34.log` 在验证后清理，业务修复保留。

## 错误记录

| 错误 | 尝试次数 | 处理 |
| --- | --- | --- |
| 主 Agent 生成空 `kbId` 却启用 KB 检索，导致三任务全部 `SEARCH_DELEGATION_INVALID_INPUT` | 1 | 提示层要求无 kbId 时 web-only；执行层规范化为空 kbId + Web 可用的子任务。 |
| PowerShell 将未加引号的 `-Dtest=...,...` 逗号解析为参数分隔，Maven 命令未启动 | 1 | 给 `-Dtest` 参数加引号后重跑。 |
