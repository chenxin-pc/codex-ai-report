## ADDED Requirements

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
