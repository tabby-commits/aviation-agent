# Query Rewrite Hook Design

## Goal

在外部搜索和内部知识库检索执行前，通过共享 Hook 对查询做保守改写，提高查询独立性和检索匹配度，同时确保模型不可用、输出非法或改写为空时继续使用原查询。

## Scope

本次只实现单查询改写：

- 外部搜索：在 `SearchServiceImpl.search()` 调用 `SearchProvider` 前改写 `SearchRequest.query`。
- 内部知识库：在 `RagServiceImpl.retrieve()` 启动向量召回和 BM25 召回前改写查询，两条召回链共用改写结果。
- 离线检索评估调用 `StructuredRetrievalService.retrieve()` 时也会经过相同 Hook。
- 不实现 Multi-Query、HyDE、语义查询与关键词查询拆分、会话历史注入或重排序。

## Architecture

新增独立的 `QueryRewriteHook` 接口，输入原始查询和检索类型，输出最终查询。默认实现 `ModelQueryRewriteHook` 使用 `ChatClientRegistry` 选择配置模型，并要求模型只返回结构化 JSON。

检索服务只依赖 `QueryRewriteHook`，不直接依赖模型调用细节。Hook 必须 fail-open：任何异常、空响应、非法 JSON、空白改写或功能关闭都返回原查询。

检索类型使用枚举区分：

- `EXTERNAL_SEARCH`
- `KNOWLEDGE_BASE`

## Prompt

System Prompt：

```text
你是检索查询改写器。请把原始查询改写为一条适合向量语义检索和关键词检索的独立查询。

规则：
1. 保持原始意图，不回答问题。
2. 删除“请帮我、我想知道、能否介绍”等无检索价值表达。
3. 保留人名、产品名、类名、方法名、版本号、错误码、缩写和专业术语。
4. 不添加原查询中不存在的技术、时间、地区、对象或结论。
5. 如果原查询已经清晰，原样返回。
6. 查询保持简洁。

只输出 JSON：
{"rewrittenQuery":"改写后的查询"}
```

User Prompt：

```text
检索类型：{{retrievalType}}
原始查询：{{query}}
```

本次不传会话历史，因此模型不得尝试消解原查询之外的指代信息。

## Configuration

新增配置前缀 `jchatmind.query-rewrite`：

```yaml
jchatmind:
  query-rewrite:
    enabled: true
    model: deepseek
```

- `enabled=false` 时不调用模型。
- `model` 使用 `ChatClientRegistry` 中的逻辑键，默认 `deepseek`。

## Data Flow

外部搜索：

```text
SearchRequest
  -> QueryRewriteHook.rewrite(EXTERNAL_SEARCH, query)
  -> 使用改写后的 query 构造新 SearchRequest
  -> SearchProvider.search()
```

内部知识库：

```text
retrieve(kbId, query, topN)
  -> QueryRewriteHook.rewrite(KNOWLEDGE_BASE, query)
  -> vectorRecallDetailed(rewrittenQuery)
  -> BM25IndexManager.search(rewrittenQuery)
  -> RRF
```

`StructuredRetrievalResult.query` 保存实际执行的改写后查询，便于离线评估和问题排查。

## Error Handling

以下情况统一返回原查询：

- 功能关闭；
- 原查询为空；
- 配置模型不存在；
- 模型调用抛出异常；
- 模型返回空内容；
- JSON 无法解析；
- `rewrittenQuery` 为空白。

查询改写失败不能阻断搜索，也不额外重试模型。

## Testing

测试覆盖：

- 功能关闭时原样返回且不调用模型；
- 模型返回合法 JSON 时使用改写结果；
- 模型不存在、抛错、非法 JSON或空改写时回退原查询；
- 外部搜索 Provider 收到改写后的查询；
- 内部向量召回和 BM25 均使用改写后的查询；
- 内部检索结果记录实际执行查询。

## Non-Goals

- 不读取聊天历史；
- 不把 Hook 加入现有 `AgentHook`；
- 不持久化每次改写记录；
- 不新增查询改写重试、缓存或异步执行；
- 不调整现有召回数量和 RRF 参数。
