## ADDED Requirements

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
