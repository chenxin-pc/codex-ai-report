## Why

当前观测页能查询研报列表、阶段事件和切片前后对照，但它更像分散的日志与数据表入口，普通开发很难直接理解“每个导入环节做了什么、执行前后数据状态如何变化、会影响后续什么”。随着 OCR、语义切片、PARENT/CHILD、过滤诊断和向量入库链路变复杂，需要一个面向开发者的导入链路解释视图，把现有质量数据组织成可读的阶段说明和可展开明细。

## What Changes

- 新增面向开发者的报告导入链路解释视图，以 reportId 为主线展示上传、OCR、语义切片、向量入库等阶段。
- 每个阶段展示“做了什么、执行前数据状态、执行后数据状态、读写数据、影响点、失败解释”，而不是只展示阶段事件字段。
- 语义切片阶段以 PARENT-CHILD 树展示 `report_chunk` 最终落库结构，并结合 `report_chunk_diagnostic` 展示保留、过滤、原因和诊断。
- 向量入库阶段展示来自语义切片阶段的可入库 CHILD 候选摘要、已入库数量、未入库数量，并可跳转或筛选到对应 PARENT-CHILD 明细。
- OCR 阶段展示报告主档、页级 OCR、段落 atom 的摘要，并按需展开页文本和段落 atom 明细。
- 保留现有研报列表、阶段事件、切片对照能力，但将它们重组为渐进式页面：总览、阶段解释、按需明细。
- 不引入新的外部依赖，不改变 OCR、切片、向量入库主处理逻辑。

## Capabilities

### New Capabilities
- `developer-ingest-chain-observation`: 面向开发者展示报告导入链路解释视图，按阶段组织操作说明、前后数据状态、影响点和可展开明细。

### Modified Capabilities
- `report-ingest-quality`: 质量数据查询从“可按 reportId 查询阶段事件和质量数据”扩展为“可支撑按阶段解释导入链路和关联数据状态”。

## Impact

- 影响后端观测查询接口：需要新增或扩展按 reportId 返回链路解释视图的数据结构。
- 影响质量查询服务：需要聚合 `report_document`、`report_ocr_page`、`report_paragraph_atom`、`report_chunk`、`report_chunk_diagnostic`、阶段事件和向量状态。
- 影响前端观测页：需要从分散列表改为报告详情、链路步骤导航、阶段解释卡和按需展开明细。
- 影响测试：需要覆盖成功链路、失败链路、无切片数据、过滤 chunk、未向量化 CHILD、标题定位后进入 reportId 链路等场景。
- 不影响推荐检索接口和 RAG 生成逻辑。
