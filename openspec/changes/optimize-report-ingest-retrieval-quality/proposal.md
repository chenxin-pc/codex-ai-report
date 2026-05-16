## Why

当前研报核心链路中 OCR 识别质量、OCR 后语义切片质量和 Milvus 向量搜索近似性都不稳定，导致最终推荐结果难以持续给客户提供高质量建议。该变更聚焦在线核心能力本身：沉淀可分析的 OCR/切片/检索质量数据，并优化 OCR、切片和检索推荐链路；批量导入、Excel 导出和离线分析编排由 `script-report-ingest-analysis-pipeline` 承接。

## What Changes

- 优化 OCR 链路：保留页级 OCR 结果和诊断信息，增加 OCR 文本清洗、噪声识别和异常暴露能力。
- 优化语义切片链路：保留段落、页码、章节、切片边界修复和过滤原因，提升 CHILD chunk 对 embedding 检索的适配性。
- 优化搜索和推荐链路：扩大初召回、增强上下文扩展、预留重排策略，并确保推荐严格基于召回证据生成。
- 新增或调整必要的持久化结构、配置项、Prompt 和测试，保证脚本可以从数据库拉取 OCR、切片和检索质量数据进行分析。
- 移除在线导入流程中的批量导出和自动评估职责；每 10 篇研报导入、Excel 生成、搜索评估编排由脚本化流水线处理。

## Capabilities

### New Capabilities

- `report-ingest-quality`: 定义研报导入过程中的 OCR、清洗、切片、过滤和质量诊断数据沉淀要求。
- `report-retrieval-quality`: 定义 OCR、语义切片、Milvus ANN 搜索和推荐生成的质量优化要求。

### Modified Capabilities

- 无。当前 `openspec/specs` 下尚无既有能力规格，本次以新增能力规格承接需求。

## Impact

- 影响服务层：`ReportIngestService`、`ReportOcrParseService`、`ReportSemanticChunkService`、`ReportRecommendService` 及必要的质量数据查询/持久化服务。
- 影响通用集成：`common.ocr`、Prompt 加载、Milvus VectorStore 调用。
- 影响持久化：可能新增 OCR 页级结果、段落 atom、chunk 诊断、过滤原因等表或字段，并同步更新 `src/main/resources/schema.sql` 与 MyBatis XML。
- 影响配置：新增 OCR 渲染/清洗、切片参数、搜索 topK、重排开关等配置，默认放在 `application.yml`，环境差异由 profile 覆盖。
- 影响 Prompt：切片边界规划和推荐生成 Prompt 需要继续存放在 `src/main/resources/prompts`，并通过模板服务加载。
- 外部依赖：继续依赖 DashScope 兼容端点/Qwen、OCR 服务、MySQL、Redis、Milvus；Excel 生成和批量脚本不在本变更实现。
