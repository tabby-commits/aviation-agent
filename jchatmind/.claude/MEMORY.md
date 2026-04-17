# Memory Index

- [project_overview](project_overview.md) — JChatMind Agent项目概述：Spring Boot 3 + Spring AI ReAct模式

---

## 本次对话要点（2026-04-15）

### 批量上传接口实现
- 使用 CountDownLatch + 线程池实现并发等待
- SSE 推送进度（batch_progress / batch_complete）
- 10分钟超时兜底机制
- 线程安全：CopyOnWriteArrayList + AtomicInteger

### 技术细节
- ThreadPoolTaskExecutor vs ThreadPoolExecutor：前者是Spring封装的适配器，支持生命周期管理
- setQueueCapacity(2000) → LinkedBlockingQueue（双链表）
- 不调 setQueueCapacity → SynchronousQueue（无队列）

### 测试相关
- @SpringBootTest 默认事务回滚，加 @Transactional 可保留数据
- 测试前需确保 knowledge_base 表有数据（testKbId 动态获取）
- Mapper XML 需配置到 test/application.yaml 的 mybatis.mapper-locations
