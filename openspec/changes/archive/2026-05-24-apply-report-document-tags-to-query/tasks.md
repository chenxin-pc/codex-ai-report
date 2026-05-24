## 1. 报告级标签主数据

- [x] 1.1 新增或补全 `ReportDocumentTag` 实体、Mapper 和 XML SQL，支持按 reportId 查询、按版本覆盖写入和按标签诊断计数。
- [x] 1.2 为 `report_document_tag` 写入逻辑补充服务层封装，统一处理标签去重、词库版本、来源、置信度和更新时间。
- [x] 1.3 补充 Mapper 或服务测试，覆盖新增、覆盖重算、空标签和重复标签场景。

## 2. 导入与标签聚合

- [x] 2.1 在导入或标签抽取完成链路中生成报告级父标签，优先复用 ACTIVE 词库和 chunk 标签聚合结果。
- [x] 2.2 确保新导入报告在 chunk 标签处理完成后写入或更新 `report_document_tag`。
- [x] 2.3 为历史数据或重打标场景补充父标签重算入口，保证标签变化后能触发后续 metadata 同步。

## 3. Milvus Metadata 同步

- [x] 3.1 扩展标签 metadata 聚合服务，使其同时读取 `report_chunk_tag` 和 `report_document_tag`。
- [x] 3.2 扩展向量 Document metadata 字段，写入报告级父标签的 primary 字段和可解释列表字段。
- [x] 3.3 将报告级父标签纳入 `tagSnapshotHash`，确保父标签变化会创建或更新 metadata 同步任务。
- [x] 3.4 调整 metadata sync job，使报告级父标签变化时能同步受影响报告下的 CHILD chunk。

## 4. 查询过滤与诊断

- [x] 4.1 扩展 query 或检索上下文，明确查询时需要应用的报告级父标签约束。
- [x] 4.2 修改 Milvus metadata filter 构造逻辑，将父标签约束与现有 query 锚点过滤组合，并确保父标签不会被 OR 条件绕过。
- [x] 4.3 在 Milvus filter 无结果时增加 `report_document_tag` 主数据诊断，区分父标签主数据缺失、metadata 滞后和确实无证据。
- [x] 4.4 保持主题覆盖校验以 chunk 级证据为准，避免仅凭报告级父标签通过 `themeCovered`。

## 5. 测试与校验

- [x] 5.1 为 metadata 构建补充单元测试，确认报告级父标签字段、chunk 标签字段和快照哈希都正确生成。
- [x] 5.2 为检索 filter 构造补充单元测试，覆盖父标签强约束、query 锚点 OR 组合和无锚点场景。
- [x] 5.3 为推荐链路补充服务测试，覆盖父标签命中、metadata 滞后、无父标签和父标签不能单独通过主题覆盖。
- [x] 5.4 执行 `mvn -q test`。
- [x] 5.5 执行 `openspec validate --all --strict`。
