## Why

当前研报推荐检索主要依赖 Milvus dense 向量召回与少量 metadata scalar filter，面对股票代码、公司名、章节词、财务指标和研报专有术语时容易出现语义相似但实体或章节不准确的问题。Milvus 已升级到 2.6，具备服务端 BM25 full-text 与 hybrid search 的版本基础，可以直接重建检索索引以提升召回覆盖和排序聚焦度。

本变更明确允许删除全部历史导入研报数据，包括 MySQL 导入链路数据和旧 Milvus collection，因此不提供旧数据迁移、旧 schema 兼容或旧 VectorStore collection 双读。

## What Changes

- **BREAKING** 删除并重建研报导入域数据：历史上传研报、OCR 页、段落 atom、chunk、chunk diagnostic、导入任务、阶段事件、标签任务、报告/切片标签、向量 metadata sync job 和旧 Milvus collection 均可清空。
- **BREAKING** 停止把旧 Milvus dense-only collection 作为推荐检索索引；新上传研报必须写入 Milvus 2.6 BM25+dense hybrid collection。
- 新增或扩展 MySQL 研报元数据存储，保存导入阶段解析或录入的研报作者信息，并作为 Milvus author metadata 的事实来源。
- 新建 Milvus hybrid collection schema，包含 analyzer text field、dense embedding field、BM25 sparse field、BM25 function、结构化 scalar metadata（含从 MySQL 同步的研报作者）和对应索引。
- 将向量入库阶段从单纯 `VectorStore.add` 升级为写入 Milvus hybrid collection；Qwen embedding 仍作为 dense 向量来源。
- 将推荐召回从单路 dense ANN 升级为 dense 向量检索 + BM25 full-text 检索 + hybrid ranker 融合。
- 增强 metadata 过滤与排序信号：支持多值 OR，明确 ticker/company 强约束，author/theme/industry/reportTheme/section intent 作为可配置过滤或排序加权信号。
- 保持现有 PARENT 上下文聚合、证据护栏、输入可分析性判定和推荐输出降级语义。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `report-retrieval-quality`: 推荐检索从 Milvus dense ANN 主链路升级为 Milvus 2.6 BM25+dense hybrid retrieval，并调整 metadata filter、排序融合和分数标准化要求。
- `report-ingest-quality`: 向量入库阶段必须写入新的 hybrid collection schema，并允许清空历史导入域数据后重新导入研报。

## Impact

- 影响代码：
  - `src/main/java/com/example/aimilvusweb/service/retrieval/`
  - `src/main/java/com/example/aimilvusweb/service/ingest/`
  - `src/main/java/com/example/aimilvusweb/service/ReportRetrievalService.java`
  - `src/main/java/com/example/aimilvusweb/service/ReportIngestService.java`
  - `src/main/java/com/example/aimilvusweb/service/ReportVectorMetadataSyncJobService.java`
  - `src/main/resources/application.yml`
  - 相关单元测试与集成测试
- 影响数据：
  - 清空历史研报导入相关 MySQL 表。
  - 新导入研报需要在 MySQL 保存作者等结构化元数据，并同步写入 Milvus hybrid collection。
  - 删除旧 Milvus collection，并创建新 hybrid collection。
  - 用户需要重新上传或重新导入研报数据。
- 外部依赖：
  - Milvus 2.6 full text search、BM25 function、analyzer、sparse vector、hybrid search 和 ranker 能力。
  - Qwen embedding 配置继续作为 dense embedding 来源。
- 不影响：
  - 结构化投研词库和 taxonomy 主数据。
  - Prompt 文件和推荐 API 对外响应结构。
  - OCR、语义切片和 PARENT 上下文聚合的业务目标。
