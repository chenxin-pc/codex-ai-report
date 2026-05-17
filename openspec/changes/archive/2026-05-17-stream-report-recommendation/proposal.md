## Why

当前推荐接口需要等待 Milvus 召回和 Qwen 完整生成后一次性返回，前端在模型生成期间缺少增量反馈，用户很难判断系统是否仍在工作。新增 SSE 流式推荐可以先展示检索证据，再持续输出模型分析正文，降低长回答场景的等待感。

## What Changes

- 新增报告推荐流式输出能力，通过独立接口返回 `text/event-stream`。
- 保留现有 `POST /api/reports/recommend` 结构化 JSON 接口，不做破坏性替换。
- 流式接口不要求输出 `analysis`、`recommendation`、`risks`、`citations` 结构化字段，模型增量以自然语言分析正文呈现。
- 流式接口仍必须基于 Milvus 召回证据生成内容，证据不足或模型未配置时必须给出明确提示。
- 前端推荐分析入口改为消费新增 SSE 接口，支持状态、证据、增量正文、完成和错误事件展示。

## Capabilities

### New Capabilities

- `report-recommendation-streaming`: 定义报告推荐 SSE 流式接口、事件语义、前端消费行为和异常处理要求。

### Modified Capabilities

- `report-retrieval-quality`: 扩展“推荐输出必须基于召回证据”的要求，使新增流式自然语言输出同样遵守证据约束。

## Impact

- 后端接口：新增推荐流式接口，返回 `text/event-stream`；原同步推荐接口保持兼容。
- 后端服务：复用现有 Milvus 召回和 prompt 模板加载能力，新增 Qwen 文本流调用与 SSE 事件编排。
- Prompt：新增或调整流式推荐专用 prompt，使其输出自然语言分析正文而非结构化对象。
- 前端：推荐按钮改为通过 `fetch` POST 消费 SSE 流，增量更新分析区域，并先展示 Top5 证据。
- 外部依赖：继续依赖已配置的 DashScope 兼容 Qwen ChatModel、Milvus VectorStore 和现有 Spring AI 能力；不引入新的外部系统。
