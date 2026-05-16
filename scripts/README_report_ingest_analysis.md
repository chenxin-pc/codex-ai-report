# 研报导入分析流水线

本目录存放 `script-report-ingest-analysis-pipeline` OpenSpec 变更对应的离线脚本。

## 准备配置

复制配置样例，并通过环境变量提供账号和密码，不要把真实密钥或密码写入配置文件。

```bash
cp scripts/report_ingest_analysis_config.example.json scripts/report_ingest_analysis_config.local.json
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=123456
```

## 本地目录模式

将 PDF 放入配置项 `input.local_dir` 指向的目录。也可以在同一目录添加 `manifest.csv`，用于补充标题、来源、机构、发布日期等元数据：

```csv
fileName,title,source,institution,publishDate,url
report.pdf,Report Title,Local,Institution,2026-05-01,
```

只执行导入：

```bash
python3 scripts/report_ingest_analysis_pipeline.py --config scripts/report_ingest_analysis_config.local.json ingest
```

执行完整流水线：

```bash
python3 scripts/report_ingest_analysis_pipeline.py --config scripts/report_ingest_analysis_config.local.json run-all
```

## URL 清单模式

将 `input.mode` 设置为 `url_manifest`，并让 `input.url_manifest` 指向 URL 清单文件。清单可以是每行一个 PDF 地址的纯文本文件，也可以是包含以下字段的 CSV/TSV 文件：

```csv
url,title,source,institution,publishDate
https://example.test/report.pdf,Report Title,Example,Institution,2026-05-01
```

脚本最多下载 `input.limit` 篇 PDF，记录下载失败原因，并根据 `runtime.continue_on_error` 决定遇错继续还是停止。

## 重试与后续命令

查看某次运行摘要和输出文件位置：

```bash
python3 scripts/report_ingest_analysis_pipeline.py --config scripts/report_ingest_analysis_config.local.json summary --run-id <runId>
```

基于已有 `runId` 重新导出质量分析 Excel，不重复导入研报：

```bash
python3 scripts/report_ingest_analysis_pipeline.py --config scripts/report_ingest_analysis_config.local.json export-quality --run-id <runId>
```

基于已有 `runId` 执行搜索评估：

```bash
python3 scripts/report_ingest_analysis_pipeline.py --config scripts/report_ingest_analysis_config.local.json evaluate-search --run-id <runId>
```

强制重新导入此前已成功导入过的文件：

```bash
python3 scripts/report_ingest_analysis_pipeline.py --config scripts/report_ingest_analysis_config.local.json ingest --force
```

输出文件写入 `output.dir/runs/<runId>/`，包括 `state.json`、导入质量 Excel 和搜索评估 Excel。
