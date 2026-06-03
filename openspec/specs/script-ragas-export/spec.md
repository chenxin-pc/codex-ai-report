# script-ragas-export Specification

## Purpose
TBD - created by archiving change add-report-rag-evaluation-dataset. Update Purpose after archive.
## Requirements
### Requirement: 脚本 MUST 导出 Ragas 可消费数据集

脚本 MUST 能将 eval run 结果导出为 Ragas 可消费的 JSONL 或等价结构化文件。每条记录 MUST 至少包含 user_input、retrieved_contexts、response、reference、reference_contexts、retrieved_context_ids、reference_context_ids 和 metadata。

#### Scenario: 导出成功

- **GIVEN** eval run 已完成至少一个成功 case run
- **WHEN** 用户执行 Ragas 导出命令
- **THEN** 脚本 MUST 生成 JSONL 文件
- **AND** 每一行 MUST 包含 query 映射出的 user_input
- **AND** 每一行 MUST 包含 FINAL retrieved context 映射出的 retrieved_contexts
- **AND** 每一行 MUST 包含 eval case 标准答案和标准证据映射出的 reference 与 reference_contexts

#### Scenario: Case 缺少 reference answer

- **GIVEN** 某个 eval case 没有 referenceAnswer
- **WHEN** 脚本导出 Ragas 数据
- **THEN** 脚本 MUST 使用 requiredClaims 的可读摘要作为 reference
- **AND** 若 requiredClaims 也为空，脚本 MUST 标记该记录不适合 answer correctness 指标

### Requirement: Ragas 导出 MUST 保留项目内评测元数据

Ragas 导出文件 MUST 在 metadata 中保留 caseId、caseType、expectedIntent、expectedOutputLevel、expectedDegradationReasons、actualIntent、actualOutputLevel、degradationReasons、evidenceQuality、corpusVersion、runId、模型、prompt hash 和检索配置摘要。

#### Scenario: 导出元数据

- **GIVEN** eval case run 包含运行快照和自动判分
- **WHEN** 脚本生成 Ragas JSONL
- **THEN** metadata MUST 包含 caseId、runId 和 corpus 标识
- **AND** metadata MUST 包含项目内自动判分结果
- **AND** metadata MUST 包含模型和 prompt 快照

#### Scenario: 导出不可分析输入 case

- **GIVEN** eval case 类型为不可分析输入
- **WHEN** 脚本导出 Ragas 数据
- **THEN** metadata MUST 标记该记录不适合 context recall 或 faithfulness 指标
- **AND** 脚本 MUST 保留该记录用于项目内降级行为评测

### Requirement: 脚本 MUST 输出导出摘要和跳过原因

脚本 MUST 在导出后输出摘要，包含总 case 数、导出记录数、跳过记录数、缺少 reference 的记录数、缺少 retrieved context 的记录数和输出文件路径。

#### Scenario: 部分记录被跳过

- **GIVEN** eval run 中存在失败 case run
- **WHEN** 脚本执行 Ragas 导出
- **THEN** 脚本 MUST 跳过失败 case run 或按配置包含错误记录
- **AND** 脚本 MUST 在摘要中记录跳过原因和数量

#### Scenario: 无可导出记录

- **GIVEN** eval run 不包含任何成功 case run
- **WHEN** 脚本执行 Ragas 导出
- **THEN** 脚本 MUST 返回明确错误
- **AND** 脚本 MUST NOT 生成空白成功文件伪装为有效数据集
