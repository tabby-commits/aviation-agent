# 第二步真实导入验证记录（2026-08-26）

对应计划：`docs/superpowers/plans/2026-08-26-step2-import-plan.md`

## 分类体系（1 层 6 类，弃用 EASC 多层树，按用户指令简化）

| code | 中文名 | 论文数（level2 正式归属） |
|---|---|---|
| sat-ground-integration | 星地融合网络 | 1325 |
| inter-satellite-networking | 星间组网与网络协议 | 1163 |
| space-computing | 空间计算与星载智能 | 1017 |
| optical-isl | 星间激光通信 | 467 |
| interference-mitigation | 干扰抑制 | 373 |
| constellation-design | 星座构型与轨道设计 | 143 |
| **合计** | | **4488** ✓ 与导入数闭环 |

## 导入结果

| 接口 | 源文件 | total | 结果 |
|---|---|---|---|
| POST /api/papers/import/screening | document_screen.csv | 708 | updated=708, skipped=0, failed=0 ✓ |
| POST /api/papers/import/taxonomy-memberships | paper_members.csv | 12180 | imported=4488, candidateSkipped=7692, unmapped=0, noPaper=0 ✓ |
| POST /api/papers/import/parameters | confirmed_parameters_final.csv | 1443 | inserted=21+1413, updated=1422（重导后）, failed=0 ✓ |

## 入库状态核验（docker exec psql）

- paper 表：included=557（与筛选表 include=True=557 精确一致；CN 465 / US 92）
- parameter_evidence：1443 条；publish_year 富化 1413 条（30 条对应论文无年份或未入元数据表）
- 参数族 top：latency and delay(172)、throughput and data rate(91)、classification and prediction quality(83)、cost(64)、bit error rate(45)——与中期报告表 4 参数热点对应（原文为英文族名）

## 偏差与修复

1. **comparator 列宽**：真实数据个别 comparator 值超 VARCHAR(16)，首次导入 21 条失败；DDL 改 VARCHAR(64) 并 ALTER 存量表后重导，failed=0。
2. **种子中文乱码**：ScriptUtils 按平台默认 GBK 读 SQL 导致 taxonomy 种子中文损坏；改用 EncodedResource(UTF-8) 并清除乱码行重插。
3. **应用启动密钥**：main 的 70e713c 将 API key 改为环境变量后本地未设置；从 git 历史（515ef85）提取旧值以环境变量方式传给 spring-boot:run（未写入任何 git 跟踪文件）。建议后续将真实 key 放入 application-local.yaml。
4. **测试隔离**（3 处）：真实数据入库后 PaperMapperQueryTest 2 处与 ParameterImportServiceTest 1 处断言被真实数据污染，均加测试数据专属条件（keyword/family/docId）圈定。

## 全量回归

mvn test：**136/136 通过**（新增 10 测试：筛选导入 3 + 分类体系 3 + 归属导入 2 + 参数导入查询 2）。
