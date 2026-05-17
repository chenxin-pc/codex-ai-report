## Context

当前推荐链路由前端 `POST /api/reports/recommend` 发起，后端完成 Milvus 召回、证据拼装和 Qwen 结构化输出后一次性返回 `RecommendRespDTO`。这个接口适合程序化消费和缓存，但在 Qwen 生成耗时较长时，前端只能显示“分析中”，无法展示已完成的检索证据或模型增量内容。

项目已经使用 Spring Boot Web、Spring AI ChatClient、Milvus VectorStore 和 Vue 前端。新增能力需要沿用现有检索链路、证据约束和 prompt 管理方式，不引入新的外部服务。

## Goals / Non-Goals

**Goals:**

- 新增 `POST /api/reports/recommend/stream`，使用 `text/event-stream` 返回推荐分析进度和模型增量文本。
- 保留现有同步推荐接口，兼容依赖结构化 `RecommendRespDTO` 的调用方。
- 流式输出先返回检索状态和 Top5 证据，再持续推送自然语言分析正文。
- 流式模型输出不要求结构化字段，但必须严格基于召回证据。
- 前端推荐入口消费新增 SSE 接口，支持增量展示、完成状态和错误提示。

**Non-Goals:**

- 不引入 WebSocket、多轮会话或服务端会话存储。
- 不要求流式接口复用同步接口的 Redis 结果缓存。
- 不要求将流式输出解析回 `analysis`、`recommendation`、`risks`、`citations` 字段。
- 不改变上传、OCR、切片、入库和 Milvus 检索的主链路。

## Decisions

### 使用 POST + fetch 消费 SSE

新增接口使用 `POST` 接收现有 `RecommendReqDTO` JSON 请求体，并返回 `text/event-stream`。前端使用 `fetch` 读取 `ReadableStream` 并解析 SSE 帧。

选择原因：投研问题可能较长，继续使用 JSON body 能避免 `EventSource` 的 GET query 长度、编码和隐私暴露问题，也能复用当前请求校验模型。

备选方案：`EventSource` + GET 更简单，但不适合长 query 和 JSON body；WebSocket 支持双向交互，但对当前单次推荐输出偏重。

### 保留同步接口并新增流式接口

`POST /api/reports/recommend` 保持结构化返回和缓存行为，新增 `/stream` 专注用户界面的低等待体验。

选择原因：同步接口仍适合测试、脚本和程序消费；流式接口可以牺牲结构化字段，换取更顺畅的阅读体验。

### 流式事件使用稳定事件类型

服务端按阶段发送以下事件：

- `status`：阶段状态，例如 `retrieving`、`generating`。
- `evidence`：检索完成后的 Top5 证据列表。
- `delta`：Qwen 生成的自然语言文本增量。
- `done`：流式输出完成。
- `error`：流建立后的业务或模型异常。

选择原因：事件类型清晰可测，前端可以按事件类型更新不同区域，后续也能扩展进度或统计信息。

### 流式 prompt 与结构化 prompt 分离

新增流式推荐专用 prompt，要求模型输出面向投研用户的自然语言分析正文，并继续强调只能使用证据片段中的事实、数字、判断和风险。

选择原因：同步 prompt 当前要求结构化对象，直接流式输出会暴露半截 JSON，阅读体验差；分离 prompt 可以避免两个接口互相牵制。

### 模型未配置时仍返回可结束的事件流

当 Qwen ChatClient 未配置或不可用时，服务端仍返回检索证据，并通过 `delta` 或 `error` 事件说明模型未配置，随后发送 `done`。

选择原因：这与现有同步接口“未配置模型时仍返回检索结果和提示”的体验一致，也避免前端长时间停留在 loading 状态。

## Risks / Trade-offs

- [Risk] `fetch` 解析 SSE 需要前端维护缓冲和帧解析逻辑 → Mitigation：只支持标准 `event:` / `data:` 帧，后端事件 payload 始终为单行 JSON。
- [Risk] 流式接口无法直接复用同步接口结构化缓存 → Mitigation：MVP 不缓存流式模型输出，保留同步接口缓存能力。
- [Risk] 代理或浏览器缓冲可能影响实时性 → Mitigation：接口显式返回 `text/event-stream`，必要时增加禁用缓冲的响应头。
- [Risk] 流中异常无法再依赖统一 JSON 异常响应 → Mitigation：流建立后的异常统一转换为 `error` 事件，并以 `done` 结束。
- [Risk] 自然语言输出弱化字段化引用 → Mitigation：流式 prompt 要求正文引用 Chunk 编号或标题；证据事件先行展示 Top5 来源。
