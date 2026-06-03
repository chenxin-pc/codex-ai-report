## Why

当前批量导入使用的东方财富 PDF URL 清单只能稳定提供标题、机构、发布日期、页数和 PDF 地址，无法保证导入后的研报具备公司、股票代码和行业标签。后续检索、筛选和推荐需要这些标签作为结构化入口，因此需要从东方财富研报 API 直接采集并在导入前校验必备元数据。

## What Changes

- 新增或扩展东方财富 API 采集模式，直接调用东方财富研报列表 API 获取研报元数据和 PDF 标识。
- 将东方财富 API 字段归一化为现有脚本字段：`stockName -> companyTags`、`stockCode -> tickerTags`、`indvInduName/industryName -> industryTags`、`researcher/author -> authors`、`attachPages -> pages`。
- 对东方财富 API 采集结果新增导入准入校验：标题、PDF 地址、公司、代码、行业必须存在，否则该条记录标记为采集失败并跳过上传。
- `themeTags` 只使用 API 或页面直接返回的主题/概念字段；没有则保持为空，不阻断下载或导入，不做 OCR、正文解析、LLM 推断或 chunk 级抽取。
- 运行结果需要统计公司、代码、行业缺失导致的跳过数量，以及主题字段覆盖率，便于每次爬取后确认元数据质量。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `script-report-ingest`: 扩展东方财富来源采集要求，保证通过东方财富 API 成功导入的研报具备公司、代码和行业标签，并保留主题标签可选直取策略。

## Impact

- 影响脚本输入采集模块：`scripts/report-ingest-analysis/lib/inputs.py` 与主入口配置解析。
- 影响脚本模型和状态输出：`ReportInput`、`ReportResult`、`state.json`、运行摘要需要保存并统计必备标签状态。
- 影响上传请求：继续复用现有 multipart 字段 `companyTags`、`tickerTags`、`industryTags`、`themeTags`，并在 `authors` 非空时提交作者字段。
- 影响配置样例和 README：新增东方财富 API 模式示例、时间范围、limit 和必备元数据校验说明。
- 依赖东方财富公开研报 API `https://reportapi.eastmoney.com/report/list`，需要设置合适的 `Referer`、`User-Agent`、分页、时间范围和失败重试策略。
