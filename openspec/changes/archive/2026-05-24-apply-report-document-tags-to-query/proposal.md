## Why

当前查询链路主要依赖 chunk 级标签和 Milvus metadata filter，尚未使用导入时确定的 `report_document_tag` 报告级父标签。这样会让“在哪个报告集合里找”和“哪个证据片段命中 query”混在一起，导致报告级归属无法稳定约束召回范围。

本变更用于把导入时确定的报告级父标签作为查询强约束，确保推荐检索先限定报告集合，再在集合内使用 query 锚点和 chunk 标签寻找证据。

## What Changes

- 将 `report_document_tag` 明确为导入阶段确定的报告级父标签主数据。
- 扩展 Milvus metadata 同步，使每个 CHILD chunk 的 metadata 携带对应报告级父标签摘要。
- 推荐检索构造 metadata filter 时，必须将报告级父标签约束应用到查询。
- 保留 `report_chunk_tag` 作为 chunk 级证据命中依据，避免用报告级父标签替代细粒度证据判断。
- 在 Milvus metadata 无结果或同步滞后时，支持通过 MySQL `report_document_tag` 诊断父标签主数据是否存在。

## Capabilities

### New Capabilities

- 无

### Modified Capabilities

- `structured-research-taxonomy`: 报告级标签不再只是可选诊断数据，必须在导入后作为可追溯主数据，并同步到向量 metadata。
- `report-retrieval-quality`: 推荐检索必须把报告级父标签作为查询过滤约束，与现有 query 锚点 metadata filter 协同生效。

## Impact

- 影响导入和标签链路：需要生成、覆盖或维护 `report_document_tag`。
- 影响 Milvus metadata 同步：需要增加报告级父标签字段和快照一致性判断。
- 影响推荐检索：`ReportRetrievalService` 的 metadata filter 需要组合报告级父标签和 query 锚点。
- 影响诊断和测试：需要覆盖父标签存在、缺失、metadata 滞后和无匹配结果等场景。
- 不新增外部依赖；仍以 MySQL 标签表为主数据，Milvus metadata 作为在线检索索引。
