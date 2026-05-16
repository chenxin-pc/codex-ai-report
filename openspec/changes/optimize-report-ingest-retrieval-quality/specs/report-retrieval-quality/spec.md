## ADDED Requirements

### Requirement: 向量检索 MUST 使用 Qwen embedding 和 Milvus ANN

系统 MUST 使用 `application.yml` 中配置的 Qwen embedding 模型生成向量，并使用 Milvus ANN 搜索执行相似性召回。系统 MUST 不使用绕过 Milvus 的内存相似度搜索替代主检索链路。

#### Scenario: 执行推荐搜索

- **GIVEN** 系统已配置 Qwen embedding 模型和 Milvus VectorStore
- **WHEN** 用户提交推荐 query
- **THEN** 系统 MUST 使用 Milvus ANN 执行向量召回
- **AND** 系统 MUST 基于召回证据生成推荐结果

#### Scenario: Milvus 未配置

- **GIVEN** 系统未配置可用的 Milvus VectorStore
- **WHEN** 用户提交推荐 query
- **THEN** 系统 MUST 返回明确的 VectorStore 未配置错误
- **AND** 系统 MUST 不伪造搜索结果

### Requirement: 检索文本 MUST 优先使用 CHILD chunk

系统 MUST 优先将 CHILD chunk 写入 Milvus 作为检索文档，PARENT chunk MUST 作为上下文扩展来源。写入 Milvus 的 metadata MUST 包含 reportId、chunkUid、parentChunkUid、chunkType、chunkIndex、sectionPath、tokenCount、title、source、institution 和 publishDate。

#### Scenario: CHILD chunk 入向量库

- **GIVEN** 一篇研报已生成 PARENT 和 CHILD chunk
- **WHEN** 系统执行向量入库
- **THEN** 系统 MUST 仅将符合质量要求的 CHILD chunk 写入 Milvus
- **AND** 每个向量文档 MUST 包含可追溯 metadata

#### Scenario: 推荐时扩展 PARENT 上下文

- **GIVEN** Milvus 返回的 CHILD chunk metadata 包含 parentChunkUid
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 查询对应 PARENT chunk 作为上下文扩展
- **AND** 系统 MUST 在上下文过长时按 token 上限截断

### Requirement: 搜索结果 MUST 支持初召回和最终结果分离

系统 MUST 支持配置初召回 topK 和最终返回 topK。初召回数量 MUST 可以大于最终返回数量，以便去重、阈值过滤和可选重排。

#### Scenario: 初召回大于最终结果数

- **GIVEN** 配置的初召回 topK 为 20
- **AND** 配置的最终返回 topK 为 5
- **WHEN** 系统执行搜索
- **THEN** 系统 MUST 从 Milvus 获取最多 20 个候选
- **AND** 系统 MUST 经过去重、过滤或重排后返回最多 5 个最终结果

#### Scenario: 候选结果不足

- **GIVEN** 配置的最终返回 topK 为 5
- **WHEN** Milvus 仅返回 3 个候选结果
- **THEN** 系统 MUST 返回这 3 个可用结果
- **AND** 推荐输出 MUST 基于实际可用证据说明不确定性

### Requirement: 推荐输出 MUST 严格基于召回证据

系统 MUST 将召回 chunk 和扩展上下文作为推荐生成的唯一事实依据。证据不足时，推荐 MUST 明确提示不确定性，不得编造报告中不存在的结论、数字或投资建议。

#### Scenario: 证据充分时生成建议

- **GIVEN** 检索结果包含与 query 高相关的多个 chunk
- **WHEN** 系统调用 Qwen 生成推荐
- **THEN** 推荐输出 MUST 包含 analysis、recommendation、risks 和 citations
- **AND** citations MUST 能对应到召回证据

#### Scenario: 证据不足时提示不确定性

- **GIVEN** 搜索结果为空或召回 chunk 与 query 相关性不足
- **WHEN** 系统生成推荐输出
- **THEN** recommendation MUST 明确说明证据不足
- **AND** risks MUST 包含不确定性提示

### Requirement: 搜索质量优化参数 MUST 可配置

系统 MUST 通过配置管理 OCR 渲染参数、切片 token 参数、搜索初召回 topK、最终 topK、相似度阈值和重排开关。共享默认值 MUST 放在 `application.yml`，profile 文件 MUST 只覆盖环境差异。

#### Scenario: 使用默认搜索质量配置

- **GIVEN** 环境 profile 未覆盖搜索质量参数
- **WHEN** 系统启动
- **THEN** 系统 MUST 使用 `application.yml` 中的默认配置

#### Scenario: 使用环境变量覆盖配置

- **GIVEN** 部署环境通过环境变量设置搜索 topK
- **WHEN** 系统启动
- **THEN** 系统 MUST 使用环境变量值覆盖默认配置
- **AND** 系统 MUST 不在业务代码中硬编码 endpoint、model、key 或 topK 参数
