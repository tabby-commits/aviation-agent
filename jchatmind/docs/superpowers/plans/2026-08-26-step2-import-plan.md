# 第二步实施计划：筛选结论 / 分类体系 / 论文归属 / 参数证据导入（2026-08-26）

对应 SPEC.md 实施顺序 ②。按用户指令调整：**不使用 EASC 多层分类树**，改为自行设计的 1 层 6 类可解释航天分类体系（依据：完整稿件.md 4.1 节系统层次 + NASA Technology Taxonomy / EASC 一级类目精神 + 数据中 EASC L1 的实际分布）。

## 分类体系定义（taxonomy 种子，code 为稳定标识）

| code | name_en（=EASC L1 原名，1:1 映射） | name_cn | 说明（对齐完整稿件 4.1 节） |
|---|---|---|---|
| constellation-design | Constellation Design Technologies | 星座构型与轨道设计 | 星座构型、轨道设计、覆盖与部署策略，对应星座网络空间段布局 |
| optical-isl | Inter-Satellite Optical Communication Technologies | 星间激光通信 | 激光星间链路的信道、捕获跟瞄、调制编码与光传输 |
| inter-satellite-networking | Inter-Satellite Networking Technologies | 星间组网与网络协议 | 星间链路组网、路由、切换、拥塞控制与传输协议 |
| space-computing | Space Computing Technologies | 空间计算与星载智能 | 星载计算、在轨数据处理、联邦学习与任务卸载 |
| sat-ground-integration | Satellite-Terrestrial Integrated Networking Technologies | 星地融合网络 | 星地一体化组网、地面系统、NTN 融合与用户接入 |
| interference-mitigation | Interference Mitigation Technologies | 干扰抑制 | 频谱共存、干扰协调、抗干扰与资源规避 |

## 数据源（只读导入）

1. 筛选：`final_results/02_parameter_extraction/document_screen.csv`（708 行；include True/False；国别 CN 588 / US 120；confidence 全 1.0）
2. 归属：`final_results/01_taxonomy/final_tree/paper_members.csv`（12180 行；只导 membership_type=level2_assignment 的 4488 条；first_level 按上表 1:1 映射）
3. 参数：`final_results/02_parameter_extraction/confirmed_parameters_final.csv`（1443 行 57 列，选 23 列入库）

## 任务（TDD，每接口独立测试）

- **Task A** POST /api/papers/import/screening —— ScreeningCsvParser + PaperMapper.updateScreening（更新 screening_status/国别/证据/置信度/文件名/排除原因；不存在的 doc_id 计 skipped）
- **Task B** 分类体系内置 —— taxonomy_node 表 + 幂等种子 + GET /api/taxonomy（paperCount 实时聚合）
- **Task C** POST /api/papers/import/taxonomy-memberships —— PaperTaxonomyMapper（唯一约束 doc_id+code+type，冲突忽略）+ EASC L1→code 内置映射（未匹配计 unmappedSkipped）
- **Task D** POST /api/papers/import/parameters + GET /api/parameters —— parameter_evidence 表（decision_id 幂等）+ publish_year 从 paper 表富化 + 分页过滤查询（参数族/国别/关键词）
- **Task E** 全量单测回归（全部接口）
- **Task F** 合并回 main 并推送

## 新表 DDL 概要

- `taxonomy_node(code UNIQUE, parent_code, name_en, name_cn, description, level, created_at)`
- `paper_taxonomy(doc_id, taxonomy_code, membership_type, source, UNIQUE(doc_id,taxonomy_code,membership_type))`
- `parameter_evidence(decision_id UNIQUE, doc_id, 参数名/数值/单位/条件/证据原文/页码/章节/参数族/审核状态/国别/年份...)`
