## 1. 元数据模型与解析

- [x] 1.1 扩展脚本 `ReportInput` 和 `ReportResult`，增加 `pages`、`authors`、`theme_tags`、`industry_tags`、`company_tags`、`ticker_tags` 字段
- [x] 1.2 扩展清单行归一化逻辑，支持读取 `pages`、`authors/author/researcher`、`themeTags/theme_tags`、`industryTags/industry_tags`、`companyTags/company_tags`、`tickerTags/ticker_tags/code` 等列名
- [x] 1.3 增加真实标题校验，禁止用 PDF 文件名、URL 文件名、下载生成名或空标题作为研报标题
- [x] 1.4 更新 URL 清单和东方财富清单收集流程，保留网站/API 直取元数据；真实标题缺失时标记采集失败或跳过上传
- [x] 1.5 移除或避免启用标题兜底主题、机构兜底公司等推断式标签补齐逻辑

## 2. 上传与运行状态

- [x] 2.1 扩展上传逻辑，将非空 `themeTags`、`industryTags`、`companyTags`、`tickerTags` 作为 multipart 表单字段提交
- [x] 2.2 确认 `authors` 非空时上传，`pages`、`sourceUrl` 仅保留在脚本清单和运行状态中，不作为后端必填字段上传
- [x] 2.3 更新运行状态保存和摘要展示，确保新增可选字段可在 `state.json` 中追溯
- [x] 2.4 确认导入后 OCR、CHUNK、VECTOR、chunk 标签抽取和检索逻辑无代码改动

## 3. 文档与配置样例

- [x] 3.1 更新 `scripts/report-ingest-analysis/README.md`，说明标准 CSV 列和可选字段空值策略
- [x] 3.2 更新配置样例或示例清单说明，展示 `url,title,source,institution,publishDate,pages,authors,tickerTags,companyTags,industryTags,themeTags`
- [x] 3.3 明确标题真实性要求：爬取导入必须使用网站/API 或清单显式真实标题，不能用文件名伪造
- [x] 3.4 明确文档边界：作者和主题不从 PDF/OCR/chunk 提取，网站/API 拿不到则留空

## 4. 测试与验证

- [x] 4.1 增加脚本测试，覆盖 URL 清单完整可选字段可被读取并写入输入清单
- [x] 4.2 增加脚本测试，覆盖可选字段缺失时保持空值且不判定采集失败
- [x] 4.3 增加脚本测试，覆盖真实标题缺失或标题为生成文件名时不得上传导入
- [x] 4.4 增加上传测试，确认四类导入标签和非空 `authors` 会被提交，`pages` 不作为必填上传字段
- [x] 4.5 运行 `python3 -m unittest scripts/report-ingest-analysis/tests/test_pipeline.py`
- [x] 4.6 执行 `openspec validate --all --strict`
