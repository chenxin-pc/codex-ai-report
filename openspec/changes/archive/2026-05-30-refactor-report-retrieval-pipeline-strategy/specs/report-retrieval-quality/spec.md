## ADDED Requirements

### Requirement: 推荐检索 MUST 按稳定流水线应用候选处理策略

系统 MUST 在推荐召回内部按稳定顺序应用结构化锚点抽取、Milvus ANN 搜索、候选映射、相关性分数过滤、候选去重、可选重排、证据上下文构建和最终 TopK 截断。内部实现 MAY 使用 Pipeline 和策略组件重构，但 MUST 保持现有配置开关、召回结果语义和降级行为兼容。

#### Scenario: 按固定顺序处理召回候选

- **GIVEN** Milvus 返回多个召回候选
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 先将 Milvus 文档映射为候选证据并标准化相关性分数
- **AND** 系统 MUST 在重排和证据上下文构建前完成低分过滤与候选去重
- **AND** 系统 MUST 在证据上下文构建后按最终 topK 返回结果

#### Scenario: 重排关闭时保持候选顺序

- **GIVEN** `rerankEnabled=false`
- **AND** Milvus 返回多个通过过滤和去重的候选
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 保持过滤和去重后的候选顺序
- **AND** 系统 MUST NOT 使用 query overlap 重排改变候选次序

#### Scenario: 重排开启时使用 query overlap 策略

- **GIVEN** `rerankEnabled=true`
- **AND** Milvus 返回多个通过过滤和去重的候选
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 使用 query 与候选子切片文本的 overlap 分数执行重排
- **AND** 系统 MUST 保持重排结果继续进入后续证据上下文构建

#### Scenario: PARENT 聚合关闭时逐 CHILD 扩展上下文

- **GIVEN** `parentAggregationEnabled=false`
- **AND** 候选 metadata 包含 `parentChunkUid`
- **WHEN** 系统构建推荐证据上下文
- **THEN** 系统 MUST 按 CHILD 候选逐条扩展对应 PARENT 上下文
- **AND** 系统 MUST 在 PARENT 缺失或超出预算时保持现有 fallback 与截断语义

#### Scenario: PARENT 聚合开启时按 PARENT 分组构建证据

- **GIVEN** `parentAggregationEnabled=true`
- **AND** 多个候选命中同一 `parentChunkUid`
- **WHEN** 系统构建推荐证据上下文
- **THEN** 系统 MUST 按 PARENT 聚合命中 CHILD
- **AND** 系统 MUST 依据命中数量、相关性分数和召回顺序排序证据组
- **AND** 系统 MUST 遵守总 evidence token 预算和每组 PARENT 上下文预算

#### Scenario: Milvus 无结果时保持诊断和降级语义

- **GIVEN** Milvus ANN 搜索返回空结果
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 返回空证据列表
- **AND** 系统 MUST 保持 metadata fallback 诊断行为
- **AND** 系统 MUST NOT 绕过 Milvus 伪造推荐证据
