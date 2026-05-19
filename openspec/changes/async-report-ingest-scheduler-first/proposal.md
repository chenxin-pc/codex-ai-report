## Why

当前导入链路在同步执行时，用户等待长、失败恢复成本高、阶段观测不足。团队已达成方向：先采用“库表 + 定时任务”实现异步化，在稳定后按需平移到 Kafka，而不是一步到位引入消息中间件复杂度。

## What Changes

- 引入导入异步编排：Upload 仅落任务表，不再同步串行执行 OCR/Chunk/Vector 全流程。
- 增加阶段化任务执行（OCR、Chunk、Vector）与可重试状态机。
- 增加阶段事件观测表，记录耗时、模型、输入输出摘要、错误与重试信息。
- 在观测检索中加入研报标题快照字段，支持按标题搜索全链路阶段记录。
- 预留 Kafka 平移边界：阶段定义、幂等键、状态流转语义保持不变。

## Capabilities

### New Capabilities
- `report-ingest-async-scheduler`: 基于数据库任务表与定时任务的异步导入执行能力。
- `report-ingest-stage-observability`: 基于阶段事件表的导入链路观测与检索能力。

### Modified Capabilities
- `script-report-ingest`: CLI/脚本触发行为调整为“提交任务 + 查询状态”为主。
- `report-ingest-quality`: 增加异步阶段状态前置校验与失败可观测要求。

## Impact

- 受影响模块：上传入口、OCR/Chunk/Vector 调用编排、任务调度器、观测查询接口与页面入口。
- 数据层影响：新增任务主表与阶段事件表，补充标题检索字段与必要索引。
- 运维影响：新增定时任务并发、重试、退避和积压治理配置。
- 迁移影响：本期不引入 Kafka；后续若迁移，保持业务语义与数据模型兼容。
