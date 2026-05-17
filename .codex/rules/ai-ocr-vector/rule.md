# AI / OCR / 向量链路规则

## OCR 与切片主链路
- 报告上传主链路：OCR 结果 -> 语义切片 -> MySQL 入库 / Milvus 向量化。
- 大模型切片只做语义边界规划，不改写原文、不生成切片正文。
- 切片边界必须做合法性校验：连续、不重叠、不遗漏、不越界。
- 切片失败不得静默伪装成功，必须抛出明确异常或诊断。

## Prompt 与模型
- Prompt 文本放在 `src/main/resources/prompts`，通过 `PromptTemplateService` 加载。
- 向量检索必须使用 Milvus ANN。
- Embedding 模型必须来自 `application.yml` 的 Qwen embedding 配置。
- 入 Milvus 优先 CHILD chunk，必要上下文通过 `parentChunkUid` 回查 PARENT chunk。

## 安全与输入治理
- 上传文件、OCR 返回、用户 query、日期/分页参数都必须校验。
- 文件上传不能信任文件名和 Content-Type，需处理空文件、异常文件、超大文件。
- 禁止将用户输入直接拼入 SQL、Prompt 控制指令、日志敏感字段或外部 URL。
- 外部 HTTP 调用必须有配置入口与失败处理，禁止硬编码生产地址和密钥。
