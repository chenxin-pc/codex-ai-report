## Why

当前 `scripts/report-ingest-analysis/commands/pipeline.py` 已承担输入采集、导入上传、质量导出、搜索评估、Excel 生成等多类职责，文件规模和耦合度持续上升。与此同时，后续仅需要东方财富拉取能力，不再需要兼容旧批处理模板，适合在同一变更内完成能力收敛与结构重构。

## What Changes

- 移除旧兼容脚本 `import_reports_and_export_chunks.py`，统一使用 `commands/pipeline.py` 作为唯一入口。
- 在导入输入侧新增东方财富来源模式，支持从东方财富来源拉取研报并进入现有导入链路。
- 将 `pipeline.py` 拆分为按职责分层的模块（输入采集、导入客户端、状态管理、质量查询、Excel 输出、搜索评估、共享工具）。
- 保持现有导入质量导出与搜索评估能力可用，确保拉取后数据可正常导入并导出分析。
- **BREAKING**：删除旧批处理兼容命令及其旧参数/旧输出模板路径。

## Capabilities

### New Capabilities
- `script-pipeline-modularization`: 规范脚本入口与模块边界，要求单入口编排与可复用模块实现，避免继续在单文件累积复杂度。

### Modified Capabilities
- `script-report-ingest`: 扩展输入来源要求，新增东方财富拉取模式，并移除对旧兼容脚本路径的依赖。

## Impact

- 受影响代码：`scripts/report-ingest-analysis/commands/pipeline.py`、`scripts/report-ingest-analysis/README.md`、`scripts/report-ingest-analysis/tests/*`，以及新增的模块目录文件。
- 受影响接口：研报上传 API 保持不变；输入采集逻辑新增东方财富来源分支。
- 受影响运行方式：批量导入统一走 `pipeline.py` 子命令；旧兼容脚本命令不再可用。
- 外部依赖：东方财富拉取可能依赖 HTTP 下载与页面/清单解析能力（基于现有标准库实现，不引入强制新三方依赖）。
