## MODIFIED Requirements

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

