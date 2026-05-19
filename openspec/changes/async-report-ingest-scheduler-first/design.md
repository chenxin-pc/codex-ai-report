## Context

当前导入链路核心痛点在于：
- 上传请求承担了过多同步计算，响应慢且容易超时；
- OCR、切片、向量化失败后恢复依赖人工重跑；
- 缺少“阶段级”统一观测，难以定位慢点与错误集中区；
- 未来存在 Kafka 化诉求，但当前阶段更重视可控落地与稳定性。

## Goals / Non-Goals

**Goals**
- 以最小基础设施成本实现异步导入主链路。
- 将导入拆分为可重试、可观测、可回放的阶段状态机。
- 提供按 `report_id` 与标题检索的链路观测视图。
- 设计上保证后续可平移 Kafka。

**Non-Goals**
- 本期不引入 Kafka/RabbitMQ 等 MQ 基础设施。
- 本期不重写 OCR/Chunk/Vector 的模型实现，仅调整编排方式。
- 本期不扩展导出分析能力范围。

## High-Level Architecture

```text
Upload/API
   |
   v
ingest_job (PENDING)
   |
   +--> Scheduler: OCR Worker ----> OCR Result
   |            \---- stage event
   |
   +--> Scheduler: Chunk Worker --> Chunk Result
   |            \---- stage event
   |
   +--> Scheduler: Vector Worker -> Vector Result / Milvus
                \---- stage event

Observability UI -> query report_ingest_stage_event by report_id/title
```

## Key Decisions

### 1) 先任务表后 MQ
先使用 DB 任务表 + 定时任务轮询，避免过早引入中间件运维复杂度；阶段边界与幂等键按 MQ 友好方式设计。

### 2) 阶段独立状态机
每个阶段独立维护 `PENDING/PROCESSING/SUCCEEDED/FAILED_RETRYABLE/FAILED_FINAL`，上游成功后才触发下游。

### 3) 观测事件单独落表
通过 `report_ingest_stage_event` 记录阶段耗时、模型、输入输出摘要、错误与重试；避免仅靠日志排障。

### 4) 标题快照入观测
在事件中加入 `report_title_snapshot` 与标准化检索键，支持运维和业务按标题快速定位整条链路。

## Data Model (Conceptual)

### ingest_job
- `job_id`、`report_id`、`report_title_snapshot`、`source`
- `ocr_status`、`chunk_status`、`vector_status`
- `attempt_ocr/chunk/vector`
- `next_run_at`、`priority`、`created_at`、`updated_at`

### report_ingest_stage_event
- `id`、`job_id`、`report_id`、`report_title_snapshot`、`title_search_key`
- `stage`、`attempt`、`status`
- `model_name`、`input_size`、`output_size`
- `started_at`、`finished_at`、`duration_ms`
- `error_code`、`error_message_short`、`trace_id`

## Retry & Backoff

- `max_attempts = 3`
- 退避策略：`1m -> 5m -> 15m`
- 可重试：网络超时、429、临时 5xx
- 不可重试：参数非法、模型权限缺失、内容损坏
- 超限后转 `FAILED_FINAL`，并保留最后一次错误摘要

## Kafka Migration Seam

保持以下不变：
- 阶段定义与状态机语义
- 幂等键（`job_id + stage + attempt`）
- 阶段事件表结构与观测查询接口

仅替换触发方式：
- 当前：Scheduler 轮询 `PENDING`
- 未来：上游成功后投递下游 Topic，Worker 消费执行

## Risks / Trade-offs

- 轮询存在 DB 压力：通过批量拉取 + 索引 + 并发上限缓解。
- 幂等不充分会导致重复写入：严格使用阶段幂等键与唯一约束。
- 事件记录过多：按保留期做分区/归档策略。

## Open Questions

- Vector 阶段是否需要独立资源池（与 Chunk 隔离）以降低相互抢占。
- 观测页是否需支持“单报告 Gantt 视图”与“批量失败 TopN”双视角。
- 任务优先级是否按来源（东方财富/手动上传）差异化。
