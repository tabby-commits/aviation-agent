# JChatMind Agent Project

## Project Overview

JChatMind is a Spring Boot 3 AI Agent application using Spring AI 1.1.0, implementing the ReAct (Reasoning + Acting) pattern with tool calling, RAG support, and SSE real-time communication.

**Tech Stack:** Spring Boot 3.5.8, Spring AI 1.1.0, PostgreSQL + MyBatis, pgvector, DeepSeek + ZhiPuAI models, Ollama (bge-m3 embedding)

---

## Project Structure

```
src/main/java/com/kama/jchatmind/
├── agent/                          # CORE: AI Agent implementation
│   ├── JChatMind.java              # Main agent runtime (think-execute loop)
│   ├── JChatMindFactory.java       # Factory - creates agent instances
│   ├── AgentState.java             # Enum: IDLE/PLANNING/THINKING/EXECUTING/FINISHED/ERROR
│   ├── examples/
│   │   ├── JChatMindV1.java        # V1: basic chat with ChatMemory
│   │   └── JChatMindV2.java        # V2: adds tool calling (ReAct pattern)
│   └── tools/
│       ├── Tool.java               # Tool interface
│       ├── ToolType.java           # FIXED (required) vs OPTIONAL tools
│       ├── TerminateTool.java      # Exit agent loop (FIXED)
│       ├── DirectAnswerTool.java   # Direct response (FIXED)
│       ├── KnowledgeTools.java     # RAG similarity search (FIXED)
│       ├── DataBaseTools.java      # DB queries (OPTIONAL)
│       ├── FileSystemTools.java    # File operations (OPTIONAL)
│       ├── EmailTools.java         # Send email (OPTIONAL)
│       └── test/                   # Test tools (CityTool, DateTool, WeatherTool)
│
├── config/                         # Configuration
│   ├── MultiChatClientConfig.java  # ChatClient beans (deepseek-chat, glm-4.6)
│   ├── ChatClientRegistry.java     # Lookup ChatClient by model name
│   ├── AsyncConfig.java            # Async task config
│   └── CorsConfig.java             # CORS settings
│
├── controller/                     # REST API endpoints
│   ├── SseController.java         # SSE connect: /sse/connect/{chatSessionId}
│   ├── AgentController.java        # Agent CRUD
│   ├── ToolController.java         # GET /api/tools - list optional tools
│   ├── ChatSessionController.java  # Session management
│   ├── ChatMessageController.java  # Message management
│   ├── DocumentController.java      # Document upload/management
│   └── KnowledgeBaseController.java # Knowledge base management
│
├── service/                        # Business logic
│   ├── impl/
│   │   ├── SseServiceImpl.java     # SSE push via ConcurrentHashMap
│   │   ├── RagServiceImpl.java     # Ollama bge-m3 embedding + pgvector similarity
│   │   ├── ToolFacadeServiceImpl.java # Collects all Tool beans
│   │   ├── DocumentStorageServiceImpl.java # File storage to ./data/documents
│   │   ├── MarkdownParserServiceImpl.java  # Parse markdown to chunks
│   │   └── EmailServiceImpl.java   # SMTP email sending
│   ├── RagService.java             # interface: embed(), similaritySearch()
│   ├── ToolFacadeService.java      # interface: getAllTools(), getFixedTools(), getOptionalTools()
│   └── SseService.java             # interface: connect(), send()
│
├── model/
│   ├── entity/                     # DB entities
│   │   ├── Agent.java              # id, name, systemPrompt, model, allowedTools(KB JSON), allowedKbs(KB JSON), chatOptions
│   │   ├── ChatSession.java        # Session entity
│   │   ├── ChatMessage.java        # Message: role(SYSTEM/USER/ASSISTANT/TOOL), content, metadata(JSON)
│   │   ├── KnowledgeBase.java      # KB entity
│   │   ├── Document.java           # Doc entity: kbId, filename, filetype, metadata
│   │   └── ChunkBgeM3.java        # Embedding chunks: kbId, docId, content, embedding(pgvector)
│   ├── dto/                        # Data transfer objects
│   ├── request/                    # Create/Update request DTOs
│   ├── response/                   # API response DTOs
│   └── vo/                         # View objects for API responses
│
├── mapper/                         # MyBatis mappers (AgentMapper, ChatMessageMapper, etc.)
├── converter/                      # Entity <-> DTO converters
├── event/                          # ChatEvent system
├── exception/                      # BizException, GlobalExceptionHandler
└── message/
    └── SseMessage.java             # SSE message format: type, payload, metadata
```

---

## Agent Core Flow

```
JChatMindFactory.create(agentId, chatSessionId)
  1. loadAgent(agentId)           → Agent entity from DB
  2. loadMemory(chatSessionId)    → List<Message> from ChatMessage table
  3. resolveRuntimeKnowledgeBases() → List<KnowledgeBaseDTO>
  4. resolveRuntimeTools()        → List<Tool> (FIXED + allowed OPTIONAL)
  5. buildToolCallbacks()         → List<ToolCallback> via MethodToolCallbackProvider
  6. buildAgentRuntime()          → new JChatMind(...)

JChatMind.run()
  for i in 0..MAX_STEPS(20):
    step()
      think()                     → LLM decides tool calls
        - Prompt with system + memory + thinkPrompt + tools
        - Returns if toolCalls.isEmpty
      execute()                   → ToolCallingManager.executeToolCalls()
        - Runs tools, gets ToolResponseMessage
        - saveMessage() persists to DB
        - refreshPendingMessages() sends via SSE
        - If terminate called → FINISHED
```

---

## Key Interfaces

### Tool (agent/tools/Tool.java)
```java
public interface Tool {
    String getName();
    String getDescription();
    ToolType getType();  // FIXED or OPTIONAL
}
```

### RagService (service/RagService.java)
```java
public interface RagService {
    float[] embed(String text);                    // Ollama bge-m3
    List<String> similaritySearch(String kbId, String title); // pgvector top-3
}
```

### SseService (service/SseService.java)
```java
public interface SseService {
    SseEmitter connect(String chatSessionId);      // 30min timeout
    void send(String chatSessionId, SseMessage message);
}
```

---

## Database Tables

- **agent** - Agent configurations (systemPrompt, model, allowedTools/JSON, allowedKbs/JSON)
- **chat_session** - Conversation sessions
- **chat_message** - Messages with role, content, metadata(JSON with toolCalls/toolResponse)
- **knowledge_base** - Knowledge bases
- **document** - Uploaded documents (metadata JSON)
- **chunk_bge_m3** - Text chunks with pgvector embedding

---

## Configuration (application.yaml)

- **datasource** - PostgreSQL: localhost:5432/jchatmind
- **ai.deepseek** - DeepSeek API (deepseek-chat model)
- **ai.zhipuai** - ZhiPuAI API (glm-4.6 model)
- **document.storage.base-path** - ./data/documents
- **RagServiceImpl** - Ollama at localhost:11434, model bge-m3

---

## Key Design Patterns

1. **ReAct Pattern** - think() decides, execute() acts, max 20 steps
2. **Tool Facade** - All Tools collected via `@Autowired List<Tool> tools`
3. **Factory Pattern** - JChatMindFactory assembles agent from DB config
4. **SSE Real-time** - ConcurrentHashMap<String, SseEmitter> per chatSessionId
5. **RAG Pipeline** - Ollama embed → pgvector similarity search → LLM context

---

## Important Files for Reference

| Purpose | File |
|---------|------|
| Agent runtime | `agent/JChatMind.java` |
| Agent factory | `agent/JChatMindFactory.java` |
| Tool base interface | `agent/tools/Tool.java` |
| RAG embedding | `service/impl/RagServiceImpl.java` |
| SSE messaging | `service/impl/SseServiceImpl.java` |
| Tool registry | `service/impl/ToolFacadeServiceImpl.java` |
| Agent entity | `model/entity/Agent.java` |
| SSE message format | `message/SseMessage.java` |

---

## Batch Upload Feature（2026-04-15 新增）

**接口：** `POST /api/documents/upload/batch?kbId=xxx`

**核心设计：**
```
Controller → CountDownLatch(参与处理的文件数)
  → docProcessExecutor.execute() 并发处理每个md文件
  → processMarkdownDocument() 复用现有逻辑
  → SSE 推送 batch_progress / batch_complete
  → latch.await(10分钟) 超时兜底
```

**CountDownLatch 要点：**
- latch 数 = 实际处理的文件数（非总文件数）
- `countDown()` 必须放 `finally` 块
- `await(timeout)` 加超时保底

**线程安全：**
- 结果收集：`CopyOnWriteArrayList<FileResult>`
- 计数器：`AtomicInteger`

**新增文件：**
- `config/DocumentThreadPoolConfig.java` — 专用线程池（core=16, max=32, queue=2000）
- `model/response/BatchSubmitResponse.java`
- `model/response/BatchResultResponse.java`
- `model/response/FileResult.java`
- `test/.../DocumentBatchUploadTest.java` — 4个测试用例全通过

---

## 论文与新闻竞争力分析数据层（2026-08 新增，对应 SPEC.md）

**数据表：** paper（元数据+筛选结论）、taxonomy_node（1层6类分类体系）、paper_taxonomy（论文归属）、parameter_evidence（参数证据）、evaluation_run / evaluation_check_item（运行追溯）、chunk_bge_m3 扩展（论文全文 KB）

**导入接口（幂等，均为 POST）：**
- `/api/papers/import/metadata?source=WOS|CNKI` — WoS CSV / CNKI RefWorks 元数据
- `/api/papers/import/screening` — 筛选结论（国别判定/纳入状态）
- `/api/papers/import/taxonomy-memberships` — EASC 一级类目映射导入归属
- `/api/papers/import/parameters` — 参数证据（decision_id 幂等+年份富化）
- `/api/papers/import/corpus?pdfDir=&limit=` — 论文全文（两阶段流水：并发解析→串行大批量嵌入→并发落库；章节过滤：背景/综述/参考文献不入库）

**查询接口：** `/api/papers`（分页过滤）、`/api/papers/{docId}`、`/api/papers/import/stats`、`/api/taxonomy`、`/api/taxonomy/{code}/papers`（中美分布）、`/api/parameters`（参数族/国别/关键词）、`/api/evaluations/runs`（+`/{id}`、`/{id}/report`、`/{id}/checks` 八项客观检查）

**Agent 工具（OPTIONAL）：** PaperSearchTool、ParameterEvidenceTool、TaxonomyBrowseTool、BibliometricTool（指标9-12/16公式计算：完全/分数计数、高被引前10%分数分配、三年移动平均、CAGR、ICR、机构HHI）

**三个专用 Skill：** competitiveness-framework（28项指标）、data-resource-rules（数据源规则+引用格式）、ci-analysis-methods（五类方法+口径核对）；旧 space-tech-intelligence-analyst 已退役

**关键教训：**
- 集成测试清理严禁 deleteBySource 等与真实数据共用的条件（曾误删4488条归属）
- 测试断言不得依赖全库计数（真实导入后必挂），用测试数据专属 keyword/family 圈定
- Ollama CPU 嵌入：大批量单请求（32块/次）远优于并发小请求；llama-server 过载会假死需重启
- Windows 控制台中文路径需 python urllib.parse.quote 编码后传参
