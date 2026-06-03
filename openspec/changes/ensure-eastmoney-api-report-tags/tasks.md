## 1. 东方财富 API 采集

- [x] 1.1 在脚本输入配置中新增 `eastmoney_api` 模式，支持 `limit`、`beginTime`、`endTime`、分页参数和下载目录。
- [x] 1.2 实现东方财富研报列表 API 请求，设置 `Referer`、`User-Agent`、超时和基础错误摘要。
- [x] 1.3 实现分页拉取逻辑，按 `limit` 截断记录，并在 API 无数据、HTTP 失败或 JSON 异常时记录可诊断错误。
- [x] 1.4 基于 API 记录的 `infoCode` 生成 PDF 下载地址，并复用现有下载逻辑保存 PDF。

## 2. 字段归一化与准入校验

- [x] 2.1 将 `stockName` 映射为 `companyTags`，将 `stockCode` 映射为 `tickerTags`。
- [x] 2.2 将 `indvInduName` 优先映射为 `industryTags`，为空时使用 `industryName` 兜底。
- [x] 2.3 将 `researcher` 或 `author` 映射为 `authors`，将 `attachPages` 映射为 `pages`。
- [x] 2.4 支持从 API 或页面直接返回的主题、概念或同义字段映射 `themeTags`，没有则保持空值。
- [x] 2.5 新增东方财富 API 模式的必填元数据校验：标题、PDF 地址、公司、代码、行业缺失时标记采集失败并跳过下载和上传。
- [x] 2.6 保持真实标题校验，继续拒绝 URL、PDF 文件名或自动生成名作为标题。

## 3. 上传与运行状态

- [x] 3.1 确认上传逻辑继续透传非空 `companyTags`、`tickerTags`、`industryTags`、`themeTags` 和 `authors`。
- [x] 3.2 在 `state.json` 和运行结果中保存东方财富 API 模式归一化后的公司、代码、行业、主题、作者和页数字段。
- [x] 3.3 在运行摘要中统计总采集数、下载成功数、导入成功数、核心标签缺失跳过数和 `themeTags` 覆盖率。
- [x] 3.4 确保幂等逻辑仍可基于 PDF 指纹、来源 URL 或报告元数据跳过已成功导入项。

## 4. 配置与文档

- [x] 4.1 更新配置样例，增加 `eastmoney_api` 模式示例和时间范围参数。
- [x] 4.2 更新脚本 README，说明公司、代码、行业为东方财富 API 模式导入准入条件，主题为可选直取字段。
- [x] 4.3 说明主题不做 OCR、正文解析、LLM 推断或 chunk 级抽取，避免和后端标签抽取逻辑混淆。

## 5. 测试与验证

- [x] 5.1 增加脚本单测，覆盖 API 记录成功映射公司、代码、行业、作者、页数并下载 PDF。
- [x] 5.2 增加脚本单测，覆盖 `indvInduName` 为空时使用 `industryName` 兜底。
- [x] 5.3 增加脚本单测，覆盖公司、代码或行业缺失时标记失败且不下载、不上传。
- [x] 5.4 增加脚本单测，覆盖主题缺失时保持空值但不阻断导入。
- [x] 5.5 运行 `python3 -m unittest scripts/report-ingest-analysis/tests/test_pipeline.py`。
- [ ] 5.6 运行 `openspec validate --all --strict`。
- [ ] 5.7 如实现触及 Java 后端或 Mapper，运行 `mvn -q test`；否则说明本变更仅涉及脚本和 OpenSpec。
