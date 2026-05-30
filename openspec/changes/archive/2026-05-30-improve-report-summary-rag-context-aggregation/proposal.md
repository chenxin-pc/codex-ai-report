## Why

当前研报推荐链路已采用 CHILD chunk 进行 Milvus ANN 召回，并通过 parentChunkUid 回查 PARENT chunk 作为上下文。对于“研报总结/推荐理由生成”这类目标，CHILD 作为召回锚点是合理的，但现有上下文组装容易按 TopK 逐条回填 PARENT，造成同一 PARENT 重复、总上下文过长、推荐理由被噪声稀释。

本变更旨在保持现有切片参数和“CHILD 入 Milvus、PARENT 做上下文”的设计前提下，改进召回后的证据聚合和生成上下文选择，使推荐理由生成更聚焦、更可控、更可解释。

## What Changes

- 将推荐生成上下文从“每个命中 CHILD 单独扩展 PARENT”调整为“先按 parentChunkUid 聚合，再选择少量高相关 PARENT 上下文”。
- 对同一 PARENT 下多个命中 CHILD 去重聚合，保留命中 CHILD 列表、最高分、平均分和命中数量，用于排序、引用和诊断。
- 引入生成上下文预算控制，包括单个 PARENT 上限、总上下文上限、最大 PARENT 数量等配置。
- 对超长 PARENT 使用混合策略：优先服务总结型生成，默认可使用 PARENT 截断；当 PARENT 过长或预算不足时，退化为命中 CHILD 覆盖窗口。
- 保留 CHILD 作为引用和证据定位粒度，避免模型生成时只看到 PARENT 而丢失实际命中来源。
- 同步推荐和流式推荐共享聚合后的证据上下文与降级语义，不改变 Milvus 主检索路径。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `report-retrieval-quality`: 推荐检索结果需要支持面向研报总结/推荐理由生成的 PARENT 级上下文聚合、去重、排序和预算控制。

## Impact

- 影响 `ReportRetrievalService`：需要在 Milvus CHILD 召回后增加 parentChunkUid 聚合、PARENT 去重排序、总 token 预算控制和超长 PARENT 处理策略。
- 影响推荐生成链路：同步和流式推荐应使用聚合后的 evidence context 进行 Prompt 组装，同时保留 CHILD 命中信息用于引用和前端展示。
- 影响配置：需要新增或调整推荐上下文聚合参数，例如最大 PARENT 数、单 PARENT token 上限、总 evidence token 上限、超长 PARENT 退化阈值。
- 影响测试：需要覆盖同一 PARENT 多 child 命中、多个 PARENT 排序、上下文预算截断、超长 PARENT 退化、无 parentChunkUid 兜底等场景。
- 不引入新的外部依赖，不改变 Qwen embedding、Milvus ANN、MySQL 主数据和现有切片入库策略。
