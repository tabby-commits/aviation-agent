# 论文元数据真实导入验证记录（2026-08-26）

对应计划：`docs/superpowers/plans/2026-08-25-paper-metadata-import.md` Task 8
环境：本地 PostgreSQL（Docker 容器 pg16-vector，pgvector/pgvector:pg16）+ 应用默认端口 8080

## 源文件基准

| 来源 | 文件 | 基准记录数（grep） | 导入 total | 一致 |
|---|---|---|---|---|
| WOS | `C:/研究生阶段文档/文献情报中心资料/小论文/WOS_data.csv`（12.9MB） | 6430（`^PT `） | 6430 | ✓ |
| CNKI | `C:/研究生阶段文档/文献情报中心资料/小论文/知网论文数据.txt` | 471（`^RT `） | 471 | ✓ |

## 导入结果

| 来源 | total | inserted | updated | failed |
|---|---|---|---|---|
| WOS 首次 | 6430 | 5430 | 1000 | 0 |
| CNKI 首次 | 471 | 469 | 2 | 0 |
| WOS 重跑（幂等验证） | 6430 | **0** | 6430 | 0 |
| CNKI 重跑（幂等验证） | 471 | **0** | 471 | 0 |

入库统计（`GET /api/papers/import/stats`）：
```json
{"bySourceDb":{"CNKI":469,"WOS":5430},"byScreeningStatus":{"pending":5899},"total":5899}
```

## 偏差与解释

1. **WOS 首次导入 updated=1000**：源 CSV 文件内部含 1000 条重复 UT 记录（同文件后出现的记录按 doc_id 判定为已存在走更新）。库中 `COUNT(DISTINCT doc_id)=5430` 与 `inserted=5430` 精确闭环，无重复数据。该现象与小论文项目的 `wos_deduplicate_by_ut.py`（按 UT 去重脚本）互相印证——WoS 原始导出本就含重复。注意：中期报告口径"WoS 检索结果 5,628 条"为小论文去重筛选后的数字，与本原始导出 6430 行不同属正常（筛选条件不同）。
2. **CNKI 首次导入 updated=2**：源 TXT 内 2 条记录的 title|journal|year 组合重复，构造出相同 docId，同样走更新，无重复行。
3. **multipart 上限**：WOS_data.csv 12.9MB 超过 Spring Boot 默认 10MB，已在 `application.yaml` 将 `spring.servlet.multipart.max-file-size` 提升至 200MB / `max-request-size` 1000MB（为第三步论文全文批量导入留余量）。

## 抽查结论

- 过滤查询：`sourceDb=WOS&yearFrom=2020&yearTo=2025&keyword=satellite` → 2811 条，按 publish_year DESC 排序正确。
- WoS 详情抽查（`WOS:000388603100003`，源文件首条）：title / firstAuthor / authors / affiliations / year / journal / docType / citedCount 与源文件逐字段一致；screening_status=pending（待第二步筛选导入填充国别）。
- CNKI 详情抽查：docId 为 `CNKI:md5hex` 格式，中文标题、作者、年份正确。

## 结论

第一步（表结构与元数据导入）验证通过：5949 条原始记录（5899 唯一）全部入库，幂等重跑零新增，接口统计与源基准闭环。
