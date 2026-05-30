## ADDED Requirements

### Requirement: 推荐检索 MUST 按 PARENT 聚合生成上下文

系统 MUST 继续使用 CHILD chunk 作为 Milvus ANN 主召回单元，但在构建研报总结或推荐理由生成上下文时，MUST 按 `parentChunkUid` 将命中的 CHILD 聚合为 PARENT 证据组。同一 PARENT 在生成上下文中 MUST 最多出现一次，且系统 MUST 保留该 PARENT 下所有命中 CHILD 的明细用于引用、展示和诊断。

#### Scenario: 多个命中 CHILD 属于同一 PARENT

- **GIVEN** Milvus 返回多个 CHILD 召回结果
- **AND** 其中至少两个 CHILD 具有相同 `parentChunkUid`
- **WHEN** 系统构建推荐理由生成上下文
- **THEN** 系统 MUST 将这些 CHILD 聚合到同一个 PARENT 证据组
- **AND** 该 PARENT 上下文 MUST 最多进入生成上下文一次
- **AND** 系统 MUST 保留聚合组内的命中 CHILD 列表、分数和 chunkUid

#### Scenario: 命中 CHILD 缺少 parentChunkUid

- **GIVEN** Milvus 返回的 CHILD 候选缺少 `parentChunkUid`
- **WHEN** 系统构建推荐理由生成上下文
- **THEN** 系统 MUST 使用该 CHILD 文本作为兜底证据上下文
- **AND** 系统 MUST 不因缺少 PARENT 而丢弃该候选
- **AND** 系统 MUST 标记该证据未完成 PARENT 聚合

### Requirement: PARENT 证据组 MUST 按相关性和覆盖度排序

系统 MUST 对 PARENT 证据组进行稳定排序，以支持研报总结和推荐理由生成。排序 MUST 至少考虑命中 CHILD 数量、最高相关性分数、平均相关性分数和原始召回顺序。系统 MUST 优先选择覆盖度更高且相关性更强的 PARENT 证据组进入生成上下文。

#### Scenario: 同一 PARENT 命中多个 CHILD

- **GIVEN** PARENT A 命中 3 个 CHILD
- **AND** PARENT B 命中 1 个 CHILD
- **AND** 两个 PARENT 的最高相关性分数接近
- **WHEN** 系统排序 PARENT 证据组
- **THEN** PARENT A MUST 优先于 PARENT B
- **AND** 排序结果 MUST 可通过命中数量和相关性分数解释

#### Scenario: 命中数量相同但分数不同

- **GIVEN** PARENT A 和 PARENT B 命中 CHILD 数量相同
- **AND** PARENT A 的最高相关性分数高于 PARENT B
- **WHEN** 系统排序 PARENT 证据组
- **THEN** PARENT A MUST 优先于 PARENT B

### Requirement: 推荐生成上下文 MUST 受总 token 预算控制

系统 MUST 支持推荐 evidence context 的总 token 预算、单 PARENT token 上限和最大 PARENT 数量配置。系统构建生成上下文时 MUST 同时遵守这些预算，不得仅依赖单条 PARENT 截断上限导致多个大 PARENT 同时进入 Prompt。

#### Scenario: 聚合后的 PARENT 总上下文超过预算

- **GIVEN** 聚合后的 PARENT 证据组有 5 个
- **AND** 它们的上下文 token 总数超过配置的总 evidence token 上限
- **WHEN** 系统选择生成上下文
- **THEN** 系统 MUST 按 PARENT 证据组排序结果选择预算内的上下文
- **AND** 系统 MUST 不把所有 PARENT 无限制加入 Prompt
- **AND** 系统 MUST 记录被预算排除或截断的证据组

#### Scenario: PARENT 数量超过最大数量

- **GIVEN** 聚合后的 PARENT 证据组数量超过配置的最大 PARENT 数量
- **WHEN** 系统构建推荐理由生成上下文
- **THEN** 系统 MUST 只选择排序靠前且数量不超过配置上限的 PARENT 证据组
- **AND** 未选中的 PARENT 证据组 MAY 作为诊断信息保留

### Requirement: 超长 PARENT MUST 使用混合上下文策略

当 PARENT token 数超过配置的超长阈值或无法纳入总预算时，系统 MUST 不盲目使用整段 PARENT。系统 MUST 优先构建包含命中 CHILD 和同父相邻 CHILD 的覆盖窗口；预算允许时 MAY 补充 PARENT 的章节路径、标题或前部上下文，以保持总结型生成所需语义。

#### Scenario: PARENT 超过超长阈值

- **GIVEN** 某个 PARENT 的 token 数超过配置的超长阈值
- **AND** 该 PARENT 下有一个或多个命中 CHILD
- **WHEN** 系统构建该 PARENT 的生成上下文
- **THEN** 系统 MUST 优先包含命中的 CHILD
- **AND** 系统 MUST 在预算内补充同父相邻 CHILD
- **AND** 系统 MUST 标记该上下文由整段 PARENT 退化为 CHILD 覆盖窗口

#### Scenario: 小 PARENT 可直接使用

- **GIVEN** 某个 PARENT 的 token 数未超过单 PARENT 上限
- **AND** 总 evidence token 预算仍足够
- **WHEN** 系统构建该 PARENT 的生成上下文
- **THEN** 系统 MAY 使用完整 PARENT 作为上下文
- **AND** 系统 MUST 仍保留命中 CHILD 作为引用和定位依据

### Requirement: 推荐证据引用 MUST 保留 CHILD 粒度

系统使用 PARENT 证据组进行推荐理由生成时，MUST 保留命中 CHILD 的 chunkUid、sectionPath、页码或段落范围等可追溯信息。推荐输出和流式 evidence 事件 MUST 能指向实际命中的 CHILD，而不是只暴露被聚合后的 PARENT 文本。

#### Scenario: 生成上下文使用 PARENT

- **GIVEN** 系统选择完整 PARENT 作为生成上下文
- **AND** 该 PARENT 下有多个命中 CHILD
- **WHEN** 系统返回推荐证据或构建 citations
- **THEN** 系统 MUST 使用命中 CHILD 信息作为引用依据
- **AND** 系统 MUST 能展示 PARENT 与命中 CHILD 的对应关系

#### Scenario: 生成上下文使用 CHILD 覆盖窗口

- **GIVEN** 系统因 PARENT 过长使用 CHILD 覆盖窗口
- **WHEN** 系统返回推荐证据或构建 citations
- **THEN** 系统 MUST 标记上下文来源为 CHILD 窗口
- **AND** citations MUST 能对应到实际命中的 CHILD
