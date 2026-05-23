# script-report-ingest Specification

## Purpose
TBD - created by archiving change script-report-ingest-analysis-pipeline. Update Purpose after archive.
## Requirements
### Requirement: 脚本 MUST 支持每次收集 10 篇研报

系统 MUST 提供脚本化入口，用于从配置来源收集最多 10 篇待导入研报。脚本 MUST 支持本地目录、URL 清单和东方财富来源三种输入模式，并为每篇研报记录来源、标题、机构、发布日期、本地文件路径和采集状态。

#### Scenario: 从本地目录收集研报

- **GIVEN** 用户配置了本地研报目录
- **WHEN** 用户运行批量导入脚本
- **THEN** 脚本 MUST 扫描目录中的 PDF 文件
- **AND** 脚本 MUST 选择最多 10 篇待导入研报生成输入清单

#### Scenario: 从 URL 清单收集研报

- **GIVEN** 用户配置了包含研报下载地址的 URL 清单文件
- **WHEN** 用户运行批量导入脚本
- **THEN** 脚本 MUST 下载最多 10 篇研报到本地工作目录
- **AND** 脚本 MUST 记录每篇研报的来源 URL 和下载状态

#### Scenario: 从东方财富来源收集研报

- **GIVEN** 用户配置了东方财富来源参数
- **WHEN** 用户运行批量导入脚本
- **THEN** 脚本 MUST 拉取最多 10 篇研报并保存为本地 PDF 文件
- **AND** 脚本 MUST 记录每篇研报的东方财富来源信息和拉取状态

#### Scenario: 可用研报少于 10 篇

- **GIVEN** 配置来源中只有 6 篇可用研报
- **WHEN** 用户运行批量导入脚本
- **THEN** 脚本 MUST 导入这 6 篇可用研报
- **AND** 脚本 MUST 在运行结果中记录未满 10 篇的状态

### Requirement: 脚本 MUST 通过后端导入能力完成入库和向量化

脚本 MUST 通过受控入口提交导入任务，并由后端异步完成 OCR、语义切片、MySQL 入库和 Milvus 向量化。脚本或调用方 MUST 能查询任务状态与阶段结果，不得绕过后端主链路直接写入 Milvus。

#### Scenario: 提交任务后异步执行成功

- **GIVEN** 脚本输入清单包含待导入 PDF
- **AND** 后端导入入口可用
- **WHEN** 脚本提交研报文件和元数据
- **THEN** 系统 MUST 返回 `jobId` 并记录任务为待执行或执行中
- **AND** 系统 MUST 在后续异步阶段完成 OCR、切片、MySQL 入库和 Milvus 向量化
- **AND** 调用方 MUST 可查询到最终成功状态

#### Scenario: 单阶段失败并重试后成功

- **GIVEN** 某研报任务在 OCR、切片或向量化阶段出现可重试错误
- **WHEN** 定时任务按退避策略进行重试
- **THEN** 系统 MUST 记录每次重试事件
- **AND** 当重试成功时 MUST 将该阶段状态更新为成功并推进下游阶段

#### Scenario: 阶段失败达到上限

- **GIVEN** 某研报任务在同一阶段连续失败且超过最大重试次数
- **WHEN** 系统执行最终失败判定
- **THEN** 系统 MUST 将该阶段标记为最终失败
- **AND** 系统 MUST 记录失败阶段和错误摘要供后续查询

### Requirement: 脚本运行 MUST 支持幂等和重试

脚本 MUST 为每次运行生成 runId，并记录输入清单、成功项、失败项和输出文件。重复运行时，脚本 MUST 能基于文件指纹、来源 URL 或报告元数据跳过已成功导入项，除非用户显式要求强制重跑。

#### Scenario: 重复运行跳过已导入文件

- **GIVEN** 某篇研报在上一次脚本运行中已成功导入
- **WHEN** 用户再次运行脚本且未启用强制重跑
- **THEN** 脚本 MUST 跳过该研报
- **AND** 脚本 MUST 在运行结果中记录跳过原因

#### Scenario: 强制重跑已导入文件

- **GIVEN** 某篇研报已存在成功导入记录
- **WHEN** 用户使用强制重跑参数运行脚本
- **THEN** 脚本 MUST 按配置重新提交该研报
- **AND** 脚本 MUST 记录此次重跑与原导入记录的关系

