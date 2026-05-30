## 1. 后端数据契约

- [x] 1.1 设计导入链路解释响应 DTO，包含报告摘要、整体状态、阶段列表、阶段 summary、explanation、details 和筛选定位字段。
- [x] 1.2 为 OCR 阶段定义摘要字段，覆盖 report_document、页级 OCR 数量、段落 atom 数量、清洗诊断摘要和失败解释。
- [x] 1.3 为语义切片阶段定义 PARENT-CHILD 树 DTO，覆盖 chunkUid、parentChunkUid、chunkType、索引、sectionPath、段落范围、页码范围、tokenCount、vectorStored。
- [x] 1.4 为切片诊断定义明细 DTO，能区分最终落库 chunk 与仅存在于 report_chunk_diagnostic 的过滤候选。
- [x] 1.5 为向量入库阶段定义候选 CHILD 摘要，覆盖候选数、已入库数、未入库数和可回跳到 PARENT-CHILD 树的定位信息。

## 2. 后端聚合服务与接口

- [x] 2.1 在质量查询服务中新增按 reportId 聚合导入链路解释视图的服务方法。
- [x] 2.2 聚合 report_document、report_ocr_page、report_paragraph_atom、report_ingest_stage_event，生成 OCR 阶段的前后状态和影响点。
- [x] 2.3 聚合 report_chunk 和 report_chunk_diagnostic，按 parentChunkUid 构建 PARENT-CHILD 树和过滤项分组。
- [x] 2.4 基于 CHILD 的 vectorStored 状态生成向量入库阶段摘要，并保留可筛选到对应 CHILD 的定位字段。
- [x] 2.5 新增 reportId 维度的导入链路解释接口，并保留现有 observations、ingest-stage-events、chunk-observation 接口兼容。
- [x] 2.6 处理边界场景：reportId 不存在、OCR 未完成、CHUNK 未生成、只有过滤诊断、无向量候选、阶段事件缺失。

## 3. 前端信息架构

- [x] 3.1 将研报观测入口调整为报告列表到报告详情的渐进式流程，避免默认展示所有明细。
- [x] 3.2 在报告详情顶部展示报告摘要、jobId、整体状态、失败阶段、最后错误和更新时间。
- [x] 3.3 实现链路步骤导航，支持上传、OCR、语义切片、向量入库等阶段切换。
- [x] 3.4 实现阶段解释卡，展示做了什么、执行前数据状态、执行后数据状态、读写数据、影响点和失败解释。
- [x] 3.5 实现 OCR 明细展开，展示页级 OCR 和段落 atom 摘要或分页明细，默认不铺开大文本。
- [x] 3.6 实现语义切片 PARENT-CHILD 树，支持查看 CHILD、PARENT、过滤项和诊断摘要。
- [x] 3.7 实现向量阶段候选摘要，并支持跳转或筛选到 CHUNK 阶段的候选 CHILD、未入库 CHILD。
- [x] 3.8 失败时默认聚焦失败阶段，并优先展示与失败相关的诊断入口。

## 4. 测试与验证

- [x] 4.1 增加后端单元测试：成功报告返回 OCR、CHUNK、VECTOR 阶段解释和摘要。
- [x] 4.2 增加后端单元测试：report_chunk 与 report_chunk_diagnostic 能正确构建 PARENT-CHILD 树和过滤项分组。
- [x] 4.3 增加后端单元测试：vectorStored=true/false 能正确统计向量候选、已入库和未入库 CHILD。
- [x] 4.4 增加后端单元测试：reportId 不存在、阶段事件缺失、CHUNK 未生成、无候选 CHILD 等边界场景返回可解释状态。
- [x] 4.5 增加前端验证：报告列表进入详情、阶段切换、展开明细、向量阶段跳转 CHILD 筛选符合预期。
- [x] 4.6 执行 `mvn -q test`。
- [x] 4.7 执行 `openspec validate --all --strict`。
