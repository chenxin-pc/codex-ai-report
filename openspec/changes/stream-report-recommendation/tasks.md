## 1. 后端流式接口

- [x] 1.1 在 `QwenClient` 中新增文本流调用能力，未配置 ChatClient 时返回可识别的空流或状态。
- [x] 1.2 在推荐服务中抽取可复用的检索证据构建逻辑，供同步接口和流式接口共享。
- [x] 1.3 新增流式推荐专用 prompt，要求自然语言正文严格基于召回证据输出。
- [x] 1.4 新增 `POST /api/reports/recommend/stream` 接口，返回 `text/event-stream`。
- [x] 1.5 按 `status`、`evidence`、`delta`、`done`、`error` 事件编排流式响应。
- [x] 1.6 保持 `POST /api/reports/recommend` 结构化响应和缓存行为不变。

## 2. 前端流式消费

- [x] 2.1 将推荐分析入口改为调用新增流式接口。
- [x] 2.2 实现 `fetch` POST 读取 `ReadableStream` 并解析 SSE 帧。
- [x] 2.3 按事件类型更新状态、Top5 证据、增量分析正文和错误提示。
- [x] 2.4 支持清空或重新分析时取消或隔离旧请求，避免旧事件覆盖新结果。
- [x] 2.5 保持页面在移动端和桌面端的布局稳定，分析正文增量更新时不造成明显错位。

## 3. 测试与校验

- [x] 3.1 补充后端单元测试，覆盖流式事件顺序、模型未配置、证据为空和流中异常。
- [x] 3.2 补充或更新前端构建校验，确认 SSE 解析逻辑可通过生产构建。
- [x] 3.3 执行 `openspec validate --all --strict`。
- [x] 3.4 执行 `mvn -q test`。
- [x] 3.5 如涉及前端构建，执行 `npm run build`。
