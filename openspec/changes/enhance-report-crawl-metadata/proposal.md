## Why

当前研报批量导入脚本只稳定保留标题、来源、机构、发布日期、URL 和文件信息，东方财富等网站已有的股票代码、公司、行业、作者、页数和主题类字段没有作为标准清单字段沉淀。补充这些可选爬取元数据后，可以在不改动 OCR、切片、向量和导入后标签抽取逻辑的前提下，提高导入时的报告级标签和后续检索 metadata 质量。

## What Changes

- 扩展研报爬取/清单字段，支持 `pages`、`authors`、`tickerTags`、`companyTags`、`industryTags`、`themeTags` 等可选列。
- 东方财富或 URL 清单模式从网站/API 元数据直接读取上述字段；字段不存在时保留空值，不做 OCR 提取、正文解析、LLM 推断或 chunk 级标签抽取。
- 爬取导入的研报标题必须是网站/API 或用户显式清单提供的真实研报标题；不得用 PDF 文件名、URL 文件名、下载生成名或空标题伪造标题。
- 脚本上传时仅透传后端上传接口已支持的 `themeTags`、`industryTags`、`companyTags`、`tickerTags`。
- `authors`、`pages`、`sourceUrl` 第一阶段仅保留在脚本输入清单、运行状态和可观测输出中；不要求写入 `report_document`，不要求进入 Milvus metadata。
- 保持后端导入后链路不变：OCR、语义切片、`report_chunk_tag` 抽取、向量写入和推荐检索逻辑不在本变更中调整。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `script-report-ingest`: 扩展脚本收集和上传研报元数据的要求，支持网站直取的可选标签、作者和页数字段，并明确字段缺失时不阻断导入。
- `script-report-ingest`: 增加爬取导入标题真实性要求，禁止以文件名或生成名替代真实研报标题。

## Impact

- 影响代码：
  - `scripts/report-ingest-analysis/commands/pipeline.py`
  - `scripts/report-ingest-analysis/lib/models.py`
  - `scripts/report-ingest-analysis/lib/inputs.py`
  - `scripts/report-ingest-analysis/lib/uploader.py`
  - `scripts/report-ingest-analysis/tests/test_pipeline.py`
  - `scripts/report-ingest-analysis/README.md`
  - `scripts/report-ingest-analysis/config/*.json` 示例或清单说明
- 影响数据：
  - 新增或更新 CSV/manifest 可选列：`pages`、`authors`、`tickerTags`、`companyTags`、`industryTags`、`themeTags`。
  - 脚本运行状态中的 `input_manifest` 和 `results` 可包含更多爬取元数据。
- 不影响：
  - 后端 OCR、切片、向量化和推荐检索主链路。
  - 数据库 schema。
  - `report_chunk_tag` 和 `report_document_tag` 的导入后抽取/聚合规则。
- 外部依赖：
  - 东方财富或其他研报来源页面/API 需要提供对应字段；字段不可用时脚本必须容忍空值。
