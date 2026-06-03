## 1. 数据模型与迁移

- [x] 1.1 设计并新增 `eval_corpus`、`eval_corpus_report`、`eval_case`、`eval_case_anchor`、`eval_reference_context`、`eval_case_reference_context`、`eval_case_forbidden_context` 表结构。
- [x] 1.2 设计并新增 `eval_run`、`eval_case_run`、`eval_retrieved_context` 以及必要的自动判分/人工复核字段。
- [x] 1.3 更新 `schema.sql` 和 MyBatis mapper XML，保证评测表可初始化、查询和写入。
- [x] 1.4 增加评测实体、DTO 和 mapper 接口，字段命名与现有 Java/MyBatis 风格一致。

## 2. 评测数据集服务

- [x] 2.1 实现 corpus 创建与查询能力，支持从已导入报告生成 corpus report 快照。
- [x] 2.2 实现 reference context 创建能力，支持从业务 chunk 复制 chunkUid、parentChunkUid、sectionPath、页码、标签和 referenceText 快照。
- [x] 2.3 实现 eval case 创建与查询能力，支持 expectedIntent、expectedAnchors、expectedOutputLevel、expectedDegradationReasons、requiredClaims 和 forbiddenClaims。
- [x] 2.4 实现 eval case 与 reference context、forbidden context 的关联查询能力，用于后续运行和导出。

## 3. 评测运行服务

- [x] 3.1 实现 eval run 创建逻辑，记录 appCommit、prompt hash、模型、词典版本和检索配置快照。
- [x] 3.2 实现 eval case run 执行逻辑，复用真实推荐链路或受控评测入口获取 actualIntent、top results、evidenceQuality 和生成输出。
- [x] 3.3 实现 retrieved context 持久化，至少保存 FINAL 阶段，并预留 INITIAL、FILTERED、DIAGNOSTIC 阶段字段。
- [x] 3.4 实现项目内自动判分，覆盖 intentMatch、anchorMatch、outputLevelMatch、degradationReasonMatch、referenceContextHit、forbiddenContextHit、forbiddenClaimHit 和 needsManualReview。
- [x] 3.5 实现失败 case run 的错误记录和继续/停止策略。

## 4. 脚本与导出

- [x] 4.1 扩展评估脚本配置，支持 eval corpus、eval case 文件或数据库数据源。
- [x] 4.2 扩展搜索评估执行流程，使用 eval case 驱动推荐调用并保存完整 JSON 结果。
- [x] 4.3 扩展 Excel 输出，新增 eval case 标准字段、自动判分 sheet，并保留 manual_review。
- [x] 4.4 实现 Ragas JSONL 导出，映射 user_input、retrieved_contexts、response、reference、reference_contexts、context ids 和 metadata。
- [x] 4.5 输出导出摘要，包含总数、成功数、跳过数、缺少 reference 数、缺少 retrieved context 数和文件路径。

## 5. 测试与验证

- [x] 5.1 增加 mapper 和服务层单元测试，覆盖 corpus、case、reference context、run 和 retrieved context 的核心读写。
- [x] 5.2 增加自动判分测试，覆盖命中标准证据、漏召回、命中禁止证据、命中禁用结论和不可计算指标。
- [x] 5.3 增加脚本测试，覆盖 eval case 数据源、完整 JSON 输出、Excel sheet 和 Ragas JSONL 导出。
- [x] 5.4 执行 `mvn -q test`，确认 Java 测试通过。
- [x] 5.5 执行脚本级测试，确认评测脚本行为通过。
- [x] 5.6 执行 `openspec validate --all --strict`，确认 OpenSpec 规格通过严格校验。
