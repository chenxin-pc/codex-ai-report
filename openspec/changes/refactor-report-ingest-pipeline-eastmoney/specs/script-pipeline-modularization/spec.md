## ADDED Requirements

### Requirement: 导入分析脚本 MUST 采用单入口模块化结构

系统 MUST 保持 `commands/pipeline.py` 为唯一命令入口，并将输入采集、导入上传、运行状态管理、质量查询、Excel 导出、搜索评估和共享工具拆分为独立模块。命令入口 MUST 仅负责参数解析与流程编排，不得继续承载具体 SQL 构造和 Excel 序列化细节。

#### Scenario: 命令入口执行导入与导出

- **WHEN** 用户执行 `pipeline.py` 的导入与导出命令
- **THEN** 命令入口 MUST 调用模块化实现完成业务逻辑
- **AND** 命令入口代码 MUST 不直接实现底层数据访问和 Excel 序列化

### Requirement: 删除旧兼容脚本后 MUST 保持主流程可用

系统 MUST 删除旧兼容脚本 `import_reports_and_export_chunks.py`，并确保统一入口在导入、质量导出、搜索评估三个阶段保持可用。系统 MUST 在文档中提供统一命令用法，不再依赖旧模板命令。

#### Scenario: 旧兼容脚本移除后的流程验证

- **GIVEN** 旧兼容脚本已移除
- **WHEN** 用户按统一入口执行导入并继续执行质量导出
- **THEN** 系统 MUST 完成导入并生成符合既有规格的质量分析输出
- **AND** 文档 MUST 仅保留统一入口的命令说明
