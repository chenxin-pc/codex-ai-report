# 研报导入分析流水线

本目录存放 `script-report-ingest-analysis-pipeline` OpenSpec 变更对应的离线脚本。

## 文件地图

| 路径 | 角色 | 职责 | 常见修改场景 |
| --- | --- | --- | --- |
| `README.md` | 说明入口 | 维护文件地图、使用命令、测试方式和修改指引。 | 新增文件、命令、配置项或维护约定时修改。 |
| `commands/pipeline.py` | 主入口 | 串联研报收集、上传导入、质量数据导出、搜索评估、运行状态记录和 Excel 生成。命令入口包括 `ingest`、`export-quality`、`evaluate-search`、`run-all`、`summary`。 | 新增流水线阶段、调整导入/导出逻辑、增加命令行参数、修改 Excel 输出结构。 |
| `config/config.example.json` | 配置样例 | 说明输入来源、API 路径、数据库连接、输出目录、评估问题和运行策略。真实账号密码通过环境变量注入。 | 新增配置项、调整默认输出目录、增加评估 query 示例。 |
| `tests/test_pipeline.py` | 脚本级测试 | 覆盖本地文件收集、URL 清单读取、失败处理、跳过重复导入、Excel sheet 结构、SQL 输入校验和上传响应处理。 | 修改 `commands/pipeline.py` 行为、增加输出 sheet、改变异常处理或命令语义。 |

统一使用 `commands/pipeline.py`。本目录不再维护旧批处理兼容脚本。

## 常用命令（只导入）

仅执行导入，不触发质量导出与搜索评估：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json ingest
```

## 准备配置

复制配置样例，并通过环境变量提供账号和密码，不要把真实密钥或密码写入配置文件。

```bash
cp scripts/report-ingest-analysis/config/config.example.json scripts/report-ingest-analysis/config/config.local.json
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=123456
```

## 本地目录模式

将 PDF 放入配置项 `input.local_dir` 指向的目录。也可以在同一目录添加 `manifest.csv`，用于补充标题、来源、机构、发布日期等元数据。标题必须是真实研报标题，不能用 PDF 文件名、下载生成名或 URL 文件名伪造：

```csv
fileName,title,source,institution,publishDate,url,pages,authors,tickerTags,companyTags,industryTags,themeTags
report.pdf,Report Title,Local,Institution,2026-05-01,,12,Analyst A,000001.SZ,Company A,Bank,
```

只执行导入：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json ingest
```

执行完整流水线：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json run-all
```

## URL 清单模式

将 `input.mode` 设置为 `url_manifest`，并让 `input.url_manifest` 指向 URL 清单文件。推荐使用 CSV/TSV 文件显式提供真实标题和网站直取元数据：

```csv
url,title,source,institution,publishDate,pages,authors,tickerTags,companyTags,industryTags,themeTags
https://example.test/report.pdf,Report Title,Example,Institution,2026-05-01,12,Analyst A,000001.SZ,Company A,Bank,
```

脚本最多下载 `input.limit` 篇 PDF，记录下载失败原因，并根据 `runtime.continue_on_error` 决定遇错继续还是停止。`pages`、`authors`、`tickerTags`、`companyTags`、`industryTags`、`themeTags` 均为可选字段，网站或清单拿不到就留空；脚本不会从 PDF、OCR 文本或 chunk 文本中提取作者或主题。

真实标题是硬约束：如果清单只有 PDF 地址，或者标题看起来是 `H3_AP202605171822381467_1`、`report-01.pdf` 等文件名/生成名，脚本会把该条记录标记为采集失败并跳过上传，避免伪造标题污染后端 `report_document.title`。

## 东方财富模式

将 `input.mode` 设置为 `eastmoney`。可通过本地清单 `input.eastmoney_manifest` 或远端清单 `input.eastmoney_manifest_url` 提供东方财富 PDF 地址与元数据（字段与 URL 清单模式一致）。脚本会下载最多 `input.limit` 篇并继续执行导入、质量导出与搜索评估。

东方财富来源同样只使用网站/API 已提供的作者、页数、股票代码、公司、行业和主题字段；不存在的字段保持为空，不进行 OCR 或推断补齐。

## 东方财富 API 模式

将 `input.mode` 设置为 `eastmoney_api` 时，脚本会直接请求东方财富研报列表 API，并从 API 记录中归一化导入元数据：

| API 字段 | 脚本字段 | 导入策略 |
| --- | --- | --- |
| `stockName` | `companyTags` | 必填，缺失则跳过该条 |
| `stockCode` | `tickerTags` | 必填，缺失则跳过该条 |
| `indvInduName` / `industryName` | `industryTags` | 必填，优先 `indvInduName`，缺失则跳过该条 |
| `researcher` / `author` | `authors` | 有值则上传 |
| `attachPages` | `pages` | 可选记录 |
| 主题、概念或同义字段 | `themeTags` | 有就上传，没有就留空 |

示例配置：

```json
{
  "input": {
    "mode": "eastmoney_api",
    "limit": 20,
    "beginTime": "2026-05-01",
    "endTime": "2026-06-02",
    "pageSize": 50,
    "download_dir": "./outputs/downloads"
  }
}
```

该模式下，成功上传的研报必须具备公司、代码和行业；作者有值时会随上传接口提交；主题只使用东方财富 API 或页面直接返回的字段。脚本不会从 PDF、OCR 文本、标题、chunk 文本中提取或推断主题，也不会调用 LLM 补齐主题。

运行摘要会额外展示 `input`、`downloaded`、`coreMetadataMissing` 和 `themeTagsPresent`，用于每次爬取后检查标签覆盖情况。

推荐用于“只导入”的配置：

- `scripts/report-ingest-analysis/config/config.eastmoney.local.json`：东方财富清单下载 + 导入。
- `scripts/report-ingest-analysis/config/config.eastmoney.localdir.json`：先本地准备 PDF + `manifest.csv`，再按本地目录导入（更稳定）。

仅执行东方财富导入：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.eastmoney.local.json ingest
```

如果网络波动导致下载失败，建议改用本地目录配置重试：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.eastmoney.localdir.json ingest
```

## 重试与后续命令

查看某次运行摘要和输出文件位置：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json summary --run-id <runId>
```

基于已有 `runId` 重新导出质量分析 Excel，不重复导入研报：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json export-quality --run-id <runId>
```

基于已有 `runId` 执行搜索评估：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json evaluate-search --run-id <runId>
```

强制重新导入此前已成功导入过的文件：

```bash
python3 scripts/report-ingest-analysis/commands/pipeline.py --config scripts/report-ingest-analysis/config/config.local.json ingest --force
```

输出文件写入 `output.dir/runs/<runId>/`，包括 `state.json`、导入质量 Excel 和搜索评估 Excel。

## 测试

脚本级回归测试：

```bash
python3 -m unittest scripts/report-ingest-analysis/tests/test_pipeline.py
```

## 修改指引

- 新增输入来源：修改 `commands/pipeline.py` 的输入收集函数，并在 `config/config.example.json` 增加示例配置。
- 新增流水线命令：修改 `commands/pipeline.py` 的命令函数和 `build_parser()`，并在本文档补充命令示例。
- 新增质量导出字段或 sheet：修改 `commands/pipeline.py` 的查询、归一化和 `build_quality_workbook()`，同步更新 `tests/test_pipeline.py`。
- 新增搜索评估输出：修改 `evaluate_queries()` 或 `build_search_workbook()`，同步更新 `tests/test_pipeline.py`。
- 修改配置键：同时更新 `config/config.example.json`、本文档命令说明和相关测试。
