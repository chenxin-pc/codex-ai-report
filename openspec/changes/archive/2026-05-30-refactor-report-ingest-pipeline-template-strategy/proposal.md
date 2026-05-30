## Why

当前异步入库链路把阶段调度、状态推进、重试退避、事件记录和具体 OCR/切片/向量动作集中在同一服务中，`ReportIngestService` 也同时承担切片过滤、落库、诊断、向量文档构建等职责。随着 OCR、语义切片、噪声过滤、标签抽取和 Milvus metadata 同步逐步增加，继续在单类中扩展会提高回归风险，也会让事务边界和失败诊断变得难以判断。

本变更希望用克制的模板方法与策略拆分优化入库链路：只抽稳定生命周期和真实变化点，不引入过度框架化的状态机，保持开发者仍能按“上传 -> OCR -> CHUNK -> VECTOR”顺序读懂主流程。

## What Changes

- 将异步阶段处理的通用生命周期整理为显式阶段执行模板，统一处理任务占用、尝试次数、成功收尾、失败重试、退避时间和阶段事件写入。
- 将 OCR、CHUNK、VECTOR 的具体动作拆为阶段处理组件，保留现有三阶段调度语义与对外查询接口。
- 引入轻量阶段描述能力，用于集中表达阶段编码、状态字段、attempt 字段、前置依赖和模型名，避免散落的字符串分支。
- 将 chunk 过滤判断拆为可单测的策略或 policy 组件，保留 segmentType、sectionPath、低语义质量和财务表格豁免的现有语义。
- 将向量文档构建和向量批量写入边界显式化，保留结构化 metadata 服务存在时优先构建增强 metadata 的行为。
- 优化异步阶段状态流的事务边界设计，避免长耗时 OCR、LLM 或 Milvus 调用与任务状态占用/完成更新混在同一个长事务中。
- 不修改 Controller、DTO、数据库表结构、公开接口 URL、Prompt 或外部依赖。
- 不引入复杂注册表、完整 GoF State 类层级或通用工作流引擎。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `report-ingest-quality`: 明确入库链路内部重构后 MUST 保持 OCR、CHUNK、VECTOR 阶段语义、失败诊断、幂等行为和质量数据可追溯性不变，并 SHOULD 通过可测试的阶段模板与策略边界降低分支复杂度。

## Impact

- 主要影响代码：
  - `src/main/java/com/example/aimilvusweb/service/ReportIngestAsyncService.java`
  - `src/main/java/com/example/aimilvusweb/service/ReportIngestService.java`
  - 新增入库内部阶段执行、阶段处理、过滤策略和向量写入相关组件
  - `src/test/java/com/example/aimilvusweb/service/ReportIngestAsyncServiceTests.java`
  - `src/test/java/com/example/aimilvusweb/service/ReportIngestServiceTests.java`
  - 新增阶段执行模板、阶段 handler、chunk 过滤 policy 和向量阶段单元测试
- 不影响项：
  - 不修改 `schema.sql`
  - 不修改上传、状态查询、质量观测接口的请求/响应 DTO
  - 不修改 OCR、切片、推荐 Prompt
  - 不新增外部依赖
- 验证要求：
  - `mvn -q test`
  - `openspec validate --all --strict`
