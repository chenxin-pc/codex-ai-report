# report-recommendation-streaming Specification

## Purpose
TBD - created by archiving change stream-report-recommendation. Update Purpose after archive.
## Requirements
### Requirement: 流式推荐接口 SHALL 返回 SSE 事件

系统 SHALL 提供独立的报告推荐流式接口，接收投研 query 并以 `text/event-stream` 返回状态、证据、模型增量、完成和错误事件。该接口 MUST 不替换现有同步推荐接口。

#### Scenario: 提交有效 query

- **GIVEN** 用户提交非空投研 query
- **WHEN** 前端调用流式推荐接口
- **THEN** 系统 MUST 返回 `text/event-stream` 响应
- **AND** 系统 MUST 至少发送 `status`、`evidence`、`delta` 和 `done` 事件

#### Scenario: 保留同步接口

- **GIVEN** 系统已提供 `POST /api/reports/recommend`
- **WHEN** 新增流式推荐接口
- **THEN** 同步推荐接口 MUST 继续返回结构化 `RecommendRespDTO`
- **AND** 同步推荐接口的缓存行为 MUST 不因流式接口而失效

### Requirement: 流式推荐接口 SHALL 先返回检索证据

系统 SHALL 复用现有 Milvus ANN 召回链路构建 Top5 证据，并在模型正文增量之前通过 `evidence` 事件发送证据列表。

#### Scenario: 检索到证据

- **GIVEN** Milvus 返回与 query 相关的候选 chunk
- **WHEN** 系统开始流式推荐
- **THEN** 系统 MUST 先发送表示检索阶段的 `status` 事件
- **AND** 系统 MUST 在首个 `delta` 事件之前发送包含 Top5 证据的 `evidence` 事件

#### Scenario: 未检索到证据

- **GIVEN** Milvus 未返回可用候选 chunk
- **WHEN** 系统开始流式推荐
- **THEN** 系统 MUST 发送空 Top5 的 `evidence` 事件
- **AND** 后续模型提示或错误提示 MUST 明确说明证据不足

### Requirement: 流式模型输出 SHALL 使用自然语言增量

系统 SHALL 使用流式推荐专用 prompt 调用 Qwen 文本流能力，并通过 `delta` 事件推送自然语言分析正文。流式正文 MUST 不要求解析为 `analysis`、`recommendation`、`risks`、`citations` 字段。

#### Scenario: 模型正常流式输出

- **GIVEN** Qwen ChatModel 已配置且可用
- **WHEN** 模型生成推荐分析
- **THEN** 系统 MUST 将模型文本增量包装为 `delta` 事件
- **AND** 前端 MUST 将 `delta` 文本追加到分析正文区域

#### Scenario: 模型未配置

- **GIVEN** Qwen ChatModel 未配置或不可用
- **WHEN** 用户调用流式推荐接口
- **THEN** 系统 MUST 返回已检索到的证据
- **AND** 系统 MUST 通过流事件说明模型未配置
- **AND** 系统 MUST 发送 `done` 事件结束响应

### Requirement: 前端 SHALL 消费并展示 SSE 推荐流

前端 SHALL 使用 `fetch` POST 调用流式推荐接口，解析 SSE 帧并按事件类型更新页面状态、证据列表、分析正文和错误提示。

#### Scenario: 增量展示推荐正文

- **GIVEN** 用户点击开始分析
- **WHEN** 前端收到连续 `delta` 事件
- **THEN** 前端 MUST 按接收顺序追加展示模型正文
- **AND** 前端 MUST 在收到 `done` 后解除分析中状态

#### Scenario: 流中错误

- **GIVEN** 流式响应已经建立
- **WHEN** 前端收到 `error` 事件
- **THEN** 前端 MUST 展示错误信息
- **AND** 前端 MUST 解除分析中状态

#### Scenario: 用户重新发起分析

- **GIVEN** 一次流式推荐仍在进行
- **WHEN** 用户清空结果或重新发起分析
- **THEN** 前端 MUST 取消或隔离旧请求
- **AND** 旧请求的后续事件 MUST 不覆盖新结果

### Requirement: 推荐响应 MUST 暴露主题覆盖和结构化锚点状态

同步推荐和流式推荐 MUST 在响应中暴露结构化锚点、主题覆盖状态和降级原因。前端 MUST 能区分“无相关主题证据”和“有证据但正在生成”。

#### Scenario: 流式事件包含主题覆盖状态

- **GIVEN** 用户提交主题类 query
- **WHEN** 系统发送 `status` 或 `evidence` 流式事件
- **THEN** 事件数据 MUST 包含输入意图、输出等级、降级原因
- **AND** 事件数据 SHOULD 包含主题覆盖状态和结构化锚点摘要

#### Scenario: 同步响应包含主题覆盖状态

- **GIVEN** 用户调用同步推荐接口
- **WHEN** 系统完成证据质量校验
- **THEN** 响应 MUST 包含 evidence quality
- **AND** evidence quality SHOULD 表达主题覆盖是否通过

### Requirement: 无主题覆盖时 MUST 不进入实质主题生成

当主题类 query 的召回证据未通过主题覆盖校验时，系统 MUST 返回 L1 降级说明。系统 MUST NOT 输出相关公司候选、产业链外推、投资建议或貌似完整的主题研究结论。

#### Scenario: 储能无覆盖时流式降级

- **GIVEN** 用户提交“哪些研报看好储能板块，核心逻辑和风险是什么？”
- **AND** 召回证据未覆盖储能主题
- **WHEN** 系统开始流式推荐
- **THEN** 系统 MUST 发送降级原因 `LOW_THEME_COVERAGE`
- **AND** 系统 MUST 输出“未检索到储能相关研报证据”之类的证据不足说明
- **AND** 系统 MUST NOT 输出候选公司或储能产业链推断

#### Scenario: 储能无覆盖时同步降级

- **GIVEN** 用户调用同步推荐接口查询储能板块
- **AND** 主题覆盖校验失败
- **WHEN** 系统构建同步响应
- **THEN** recommendation MUST 是证据不足或主题无覆盖说明
- **AND** risks MUST 包含 `LOW_THEME_COVERAGE` 或等价降级原因

### Requirement: 低相关候选 MUST 作为诊断信息而非推荐证据

当系统召回到低相关候选但主题覆盖失败时，系统 MUST 将低相关候选用于诊断或调试展示，而不是默认作为推荐证据展示给用户。

#### Scenario: 低相关候选不作为推荐 Top5

- **GIVEN** 用户查询储能板块
- **AND** 系统低相关候选包含港口、算力和军工证据
- **WHEN** 主题覆盖校验失败
- **THEN** 默认推荐证据列表 MUST 为空或标记为低相关
- **AND** 系统 SHOULD 说明这些候选已被过滤或仅用于诊断

#### Scenario: 调试模式展示低相关候选

- **GIVEN** 系统启用检索诊断展示
- **AND** 主题覆盖校验失败
- **WHEN** 前端展示结果
- **THEN** 前端 MAY 展示被过滤候选及过滤原因
- **AND** 前端 MUST 不将这些候选标记为可支撑结论的证据

