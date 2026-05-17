# script-quality-export Specification

## Purpose
TBD - created by archiving change script-report-ingest-analysis-pipeline. Update Purpose after archive.
## Requirements
### Requirement: 脚本 MUST 从数据库拉取导入质量数据

导入完成后，脚本 MUST 能基于 runId、reportId 列表或导入时间范围从 MySQL 拉取报告元数据、OCR 原始结果、清洗结果、PARENT/CHILD chunk、过滤诊断和向量入库状态。脚本 MUST 不依赖在线服务在导入主流程中自动生成 Excel。

#### Scenario: 按 runId 拉取质量数据

- **GIVEN** 一次脚本运行已经完成多篇研报导入
- **WHEN** 用户执行质量导出脚本
- **THEN** 脚本 MUST 根据 runId 定位本次运行的 reportId 列表
- **AND** 脚本 MUST 从数据库拉取对应 OCR、chunk 和诊断数据

#### Scenario: 按 reportId 列表拉取质量数据

- **GIVEN** 用户提供了一组 reportId
- **WHEN** 用户执行质量导出脚本
- **THEN** 脚本 MUST 只导出这些报告对应的质量数据

### Requirement: 脚本 MUST 生成 OCR 和切片分析 Excel

脚本 MUST 生成 Excel 文件展示导入质量，文件 MUST 至少包含 reports、ocr_pages、chunks、filtered_chunks、summary 这些 sheet。Excel MUST 包含足够字段用于人工判断 OCR 和切片质量。

#### Scenario: 生成导入质量 Excel

- **GIVEN** 脚本已拉取到报告、OCR 和 chunk 数据
- **WHEN** 脚本生成 Excel
- **THEN** reports sheet MUST 包含报告标题、来源、机构、发布日期和导入状态
- **AND** ocr_pages sheet MUST 包含 reportId、页码、OCR 原文、清洗文本和诊断信息
- **AND** chunks sheet MUST 包含 chunkUid、parentChunkUid、chunkType、sectionPath、tokenCount、页码范围和 chunkText
- **AND** filtered_chunks sheet MUST 包含被过滤 chunk、过滤原因和诊断指标
- **AND** summary sheet MUST 包含本次运行的统计结果

#### Scenario: 没有可导出数据

- **GIVEN** 用户提供的 runId 或 reportId 没有关联任何成功导入报告
- **WHEN** 用户执行质量导出脚本
- **THEN** 脚本 MUST 返回明确的无可导出数据提示
- **AND** 脚本 MUST 不生成误导性的空白质量报告

### Requirement: 导出脚本 MUST 保护敏感信息

导出脚本 MUST 不在 Excel、日志或错误文件中输出 API Key、Token、数据库密码、完整外部认证头或其他敏感配置。长文本字段 MUST 支持截断或摘要展示，并保留主键用于回查完整数据。

#### Scenario: 配置包含数据库密码

- **GIVEN** 脚本通过环境变量读取数据库密码
- **WHEN** 脚本生成日志和 Excel
- **THEN** 日志和 Excel MUST 不包含该数据库密码

#### Scenario: 文本过长

- **GIVEN** OCR 原文或 chunkText 超过 Excel 单元格适合展示的长度
- **WHEN** 脚本写入 Excel
- **THEN** 脚本 MUST 按配置截断展示文本或写入摘要
- **AND** 脚本 MUST 保留 reportId、pageNumber 或 chunkUid 用于追溯完整文本

