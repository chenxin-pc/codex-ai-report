## Why

当前 `ReportRetrievalService` 同时承担召回编排、候选映射、分数过滤、去重、重排、PARENT 上下文扩展和诊断 fallback 等职责，文件体量和分支复杂度已经影响召回质量调参、单元测试和后续策略扩展。

本变更拟在不改变推荐接口、数据库字段和现有召回语义的前提下，将召回链路重构为显式 Pipeline 与策略组件，让过滤、去重、重排和证据上下文构建成为可独立测试、可替换的内部边界。

## What Changes

- 将 `ReportRetrievalService` 调整为对外召回入口 Facade，保留 `retrieve(String query)` 的调用方式和返回语义。
- 新增 retrieval 内部包，用于承载召回 Pipeline、候选过滤、去重、重排策略和证据上下文策略。
- 将现有 `rerankEnabled` 行为抽象为 `RerankStrategy`，保留关闭时原顺序、开启时 query overlap 重排的行为。
- 将现有 `parentAggregationEnabled` 行为抽象为 `EvidenceContextStrategy`，保留 PARENT 聚合和 CHILD 扩展两种上下文构建语义。
- 保持 Milvus ANN 主召回、metadata scalar filter、分数标准化、低分过滤、双重去重、TopK 截断、无结果诊断和 PARENT fallback 行为不变。
- 不新增数据库字段，不修改 Controller、前端、推荐响应 DTO 或公开 API。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `report-retrieval-quality`: 明确推荐召回链路在内部 Pipeline 重构后仍 MUST 按稳定顺序应用候选处理步骤，并 MUST 保持重排与证据上下文策略的配置开关兼容。

## Impact

- 主要影响代码：
  - `src/main/java/com/example/aimilvusweb/service/ReportRetrievalService.java`
  - 新增 `src/main/java/com/example/aimilvusweb/service/retrieval/` 下的内部 Pipeline 与策略类
  - `src/test/java/com/example/aimilvusweb/service/ReportRetrievalServiceTests.java`
  - 新增 `src/test/java/com/example/aimilvusweb/service/retrieval/` 下的策略与 Pipeline 单元测试
- 不影响项：
  - 不修改 `schema.sql`
  - 不修改推荐接口 URL、请求 DTO、响应 DTO
  - 不修改前端调用方式
  - 不引入新的外部依赖
- 验证要求：
  - `mvn -q test`
  - `openspec validate --all --strict`
