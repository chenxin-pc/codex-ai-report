## MODIFIED Requirements

### Requirement: 推荐输出 MUST 严格基于召回证据

系统 MUST 将召回 chunk 和扩展上下文作为推荐生成的唯一事实依据。同步结构化推荐和流式自然语言推荐均 MUST 不编造报告中不存在的结论、数字或投资建议。证据不足时，推荐 MUST 明确提示不确定性。

#### Scenario: 证据充分时生成同步建议

- **GIVEN** 检索结果包含与 query 高相关的多个 chunk
- **WHEN** 系统调用 Qwen 生成同步推荐
- **THEN** 推荐输出 MUST 包含 analysis、recommendation、risks 和 citations
- **AND** citations MUST 能对应到召回证据

#### Scenario: 证据充分时生成流式建议

- **GIVEN** 检索结果包含与 query 高相关的多个 chunk
- **WHEN** 系统调用 Qwen 生成流式推荐
- **THEN** 流式正文 MUST 仅基于召回证据进行分析
- **AND** 流式正文 MUST 引用或指向可识别的 Chunk 编号、标题或证据来源

#### Scenario: 证据不足时提示不确定性

- **GIVEN** 搜索结果为空或召回 chunk 与 query 相关性不足
- **WHEN** 系统生成推荐输出
- **THEN** recommendation 或流式正文 MUST 明确说明证据不足
- **AND** risks 或流式正文 MUST 包含不确定性提示
