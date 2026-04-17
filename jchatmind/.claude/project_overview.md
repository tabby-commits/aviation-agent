---
name: JChatMind Agent Project Overview
description: Spring Boot 3 AI Agent项目，核心是JChatMind类实现ReAct模式的think-execute循环
type: reference
---

# JChatMind 项目概述

## 技术栈
- Spring Boot 3.5.8 + Spring AI 1.1.0
- PostgreSQL + MyBatis + pgvector (向量数据库)
- DeepSeek + ZhiPuAI (双模型支持)
- Ollama bge-m3 (本地embedding)
- SSE (Server-Sent Events) 实时通信

## 核心模块

### 1. Agent模块 (agent/)
- `JChatMind.java` — Agent运行时，核心是think-execute循环（最多20步）
- `JChatMindFactory.java` — 从数据库加载配置，创建Agent实例
- `AgentState.java` — 状态机：IDLE→THINKING→EXECUTING→FINISHED/ERROR
- `examples/` — V1(基础聊天)→V2(添加工具调用)的演进示例

### 2. Tools模块 (agent/tools/)
- FIXED工具（所有Agent必须有）：TerminateTool, DirectAnswerTool, KnowledgeTools
- OPTIONAL工具：DataBaseTools, FileSystemTools, EmailTools

### 3. RAG管道
- `RagServiceImpl.java` — 调用本地Ollama bge-m3生成向量
- `ChunkBgeM3` entity — 存储文本块+pgvector向量
- `KnowledgeTools` — Agent调用RAG的工具接口

### 4. SSE实时通信
- `SseServiceImpl` — ConcurrentHashMap管理连接，30分钟超时
- `SseMessage` — 消息格式：type + payload + metadata，新增BATCH_PROGRESS/BATCH_COMPLETE类型

### 5. 数据库实体
- Agent: systemPrompt, model, allowedTools(JSON), allowedKbs(JSON)
- ChatMessage: role(SYSTEM/USER/ASSISTANT/TOOL), content, metadata(JSON存toolCalls)
- ChunkBgeM3: kbId, docId, content, embedding(pgvector)

## Agent执行流程
```
think() → LLM决定是否调用工具
  └─ 有工具调用 → execute() → 执 行工具 → 检查terminate → 继续循环
  └─ 无工具调用 → FINISHED
```

## 关键配置
- Ollama: localhost:11434
- PostgreSQL: localhost:5432/jchatmind
- DeepSeek API: api.deepseek.com
- ZhiPuAI API: open.bigmodel.cn

---

## 本次新增功能（2026-04-15）

### 批量上传文档接口

**新增文件：**
- `config/DocumentThreadPoolConfig.java` — 文档处理专用线程池
  - core=16, max=32, queue=2000, CallerRunsPolicy
  - 使用 ThreadPoolTaskExecutor（Spring封装，支持生命周期管理）
  - 实际队列是 LinkedBlockingQueue（双链表，有界2000）
- `model/response/BatchSubmitResponse.java` — 提交响应
- `model/response/BatchResultResponse.java` — 最终结果
- `model/response/FileResult.java` — 单文件处理结果
- `test/.../DocumentBatchUploadTest.java` — 集成测试（4个用例全通过）

**修改文件：**
- `controller/DocumentController.java` — 新增 POST /documents/upload/batch
- `service/DocumentFacadeService.java` — 新增 uploadDocumentsBatch 接口
- `service/impl/DocumentFacadeServiceImpl.java` — 实现批量逻辑
- `message/SseMessage.java` — 新增BATCH_PROGRESS/BATCH_COMPLETE类型
- `mapper/ChunkBgeM3Mapper.java/.xml` — 新增 countByKbId

**接口设计：**
```
POST /api/documents/upload/batch?kbId=xxx
  → 立即返回 batchId + PROCESSING
  → 线程池并发处理每文件，CountDownLatch 同步
  → 每50条/完成时 SSE 推送进度
  → 主线程 latch.await(10min) 超时兜底
```

**CountDownLatch 用法要点：**
1. latch 数 = 实际参与处理的文件数（非总文件数）
2. countDown() 必须放 finally 块（无论成功/异常都要减）
3. await() 要加超时保底
4. 结果收集用 CopyOnWriteArrayList + AtomicInteger

**批量处理流程：**
```
Controller → 提交任务到 docProcessExecutor
  → 每个任务: processFile() → latch.countDown()
  → 主线程 latch.await(10分钟)
  → 返回响应 + SSE 推送最终状态
```
