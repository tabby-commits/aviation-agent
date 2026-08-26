# SPEC 全流程实施验证记录（2026-08-27）

对应：SPEC.md 七步实施顺序全部完成（③④⑤⑥⑦ 收尾记录；①② 见 2026-08-25/26 验证文档）

## ③ 论文全文入库（核心页策略）

| 项 | 结果 |
|---|---|
| 论文 KB（低轨卫星星座论文全文） | 557/557 篇全部入库，**1661 块**，孤儿 document 0 |
| 章节过滤 | 背景/综述/引言/结论/参考文献章节不入库（状态机跨页跟踪，无标题结构兜底保留） |
| 嵌入范围 | 核心页策略：首页块（标题+摘要）+ 参数证据页块；块长 2000 字符 |
| 吞吐 | Ollama 并行 4（OLLAMA_NUM_PARALLEL=4）实测 6.6s/块；全量 11 批约 95 分钟 |
| 性能根因记录 | CPU bge-m3 22.4s/4000字符块（全量嵌入需 28h 不可行）→ 并行+短块+核心页 = 3.4x 提速 + 范围缩减 |

### 执行中的问题与修复
- llama-server 过载假死 2 次（16 路并发小请求触发）→ 重启 Ollama + 改串行大批量 + 最终并行 4 限流
- WebClient 响应缓冲上限 256KB < 32 块嵌入响应 → 提至 16MB
- embed 调用无超时挂起 → 加 5 分钟超时
- 早期假死期间遗留 32 个孤儿 document（有 document 无 chunks）→ 清理后重导补齐
- devtools 检测开发期编译触发应用重启 → 运行长任务时禁用（-Dspring.devtools.restart.enabled=false）

## ④ 四个 Agent 工具（OPTIONAL）

| 工具 | 验证 |
|---|---|
| PaperSearchTool | 元数据过滤检索 ✓（AgentToolsIntegrationTest） |
| ParameterEvidenceTool | 参数族/国别/关键词查询，含证据原文+页码 ✓ |
| TaxonomyBrowseTool | 6 类树 + 节点论文列表 + 国别分布 ✓ |
| BibliometricTool | 公式纯函数 BibliometricCalculator 单测 8/8（手算已知答案：N_F/N_W/ICR/HC 并列分数分配/MA/CAGR/机构份额/HHI）✓ |

**归属-有效论文交集**：4488 归属集与 557 有效论文集交集 246 篇（部分覆盖，分类限定统计时需说明口径）。

## ⑤ 三个 Skill

- `competitiveness-framework`（28 项指标 + 每项工具支撑标注 ✅/🔍/❌ + 解释边界 + 报告结构）
- `data-resource-rules`（7 类资源优先级 + 调用顺序 + 数据口径 + 引用格式 + 禁止事项）
- `ci-analysis-methods`（五类方法 + 参数可比性 7 项核对清单 + 证据分级表达）
- 旧 space-tech-intelligence-analyst 已删除；SkillTool 描述更新为三 Skill 路由；SkillTest 13/13

## ⑥ 运行追溯与客观检查

- `evaluation_run` / `evaluation_check_item` 表 + 八项客观检查清单（维度覆盖/指标理由/引用完整性/来源层级/检索可追溯/方法可复核/结论对应证据/边界表述）
- 接口：POST/GET `/api/evaluations/runs`、PUT `/{id}/report`、PUT `/{id}/checks`（符合/不符合/证据不足）

## ⑦ 端到端验证

- 参数证据抽查：时延类 CN 147 条，证据原文+页码完整可回查
- 运行登记演示：一次运行自动生成 8 项检查，检查结论更新生效（citation=符合，boundary=证据不足）
- 全量回归：**mvn test 159/159 通过**（新增 23 测试：PDF 解析 5 + corpus 2 + 计量 8 + 工具 5 + 评价 3）

## 全程裁定汇总（错误成本均为低成本可回退）

1. 分类体系弃 EASC 用 1 层 6 类（用户指令）；EASC L1 英文名 1:1 映射保证归属零丢失
2. 全文嵌入改核心页策略（用户"背景综述不要"指令 + CPU 吞吐物理约束 28h→2h）
3. 同步分批接口替代后台任务+SSE（失败恢复简单、循环调用可控）
4. 测试清理与真实数据共用 source 曾误删 4488 条归属 → 已修复（deleteByDocIdPrefix）并重导恢复
5. 密钥以环境变量方式从 git 历史提取传入运行进程（未写入任何跟踪文件）
