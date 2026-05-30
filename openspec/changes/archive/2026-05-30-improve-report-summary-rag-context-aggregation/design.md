## Context

当前研报 RAG 链路已经形成“CHILD 入 Milvus、PARENT 回查上下文”的基本结构。现有参数更适合研报总结/推荐理由生成：CHILD 约 700-1100 tokens，适合作为小论点召回锚点；PARENT 约 3000-5200 tokens，适合作为完整论证链的语义容器。

问题不在切片参数本身，而在召回后的上下文组装方式。当前链路容易对 Milvus TopK 中每个 CHILD 分别回填 PARENT，导致同一 PARENT 重复进入 Prompt，多个大 PARENT 同时进入生成上下文，并且缺少总 token 预算与 PARENT 级排序。

本设计保持现有主路径约束：继续使用 Qwen embedding、Milvus ANN、CHILD 向量文档和 MySQL PARENT 回查；不引入新的外部检索系统。

## Goals / Non-Goals

**Goals:**

- 将召回候选从 CHILD 列表聚合为 PARENT 级证据组，适配研报总结/推荐理由生成。
- 同一 PARENT 在生成上下文中最多出现一次，同时保留命中的 CHILD 明细用于引用、展示和诊断。
- 引入可配置的上下文预算：最大 PARENT 数、单 PARENT token 上限、总 evidence token 上限和超长 PARENT 退化阈值。
- 对超长 PARENT 使用混合策略，在总结型上下文完整性和噪声控制之间取得平衡。
- 保持同步推荐和流式推荐使用一致的 evidence context 语义。

**Non-Goals:**

- 不重写 OCR、LLM 语义切片、chunk 入库或 Milvus metadata 同步主链路。
- 不大幅调整现有 CHILD/PARENT token 默认参数。
- 不将 PARENT 写入 Milvus 作为主 ANN 检索文档。
- 不引入 MySQL 大文本 `LIKE` 作为正式检索路径。
- 不改变主题覆盖、投研输入可分析性和输出降级的既有语义。

## Decisions

### 1. 先按 parentChunkUid 聚合，再选择生成上下文

Milvus 仍返回 CHILD 候选。检索服务在分数过滤、去重后，以 `parentChunkUid` 为主键构建 PARENT 证据组；缺少 `parentChunkUid` 的候选作为单独 CHILD 证据组处理。

每个 PARENT 证据组记录：

- parentChunkUid
- parent chunkText 或退化上下文
- 命中的 child 列表
- 最高 relevanceScore
- 平均 relevanceScore
- 命中 child 数量
- sectionPath、title、source 等展示 metadata
- 是否发生截断或退化

备选方案是继续返回平铺 CHILD 列表并在推荐服务中拼接上下文。该方案会让聚合逻辑散落到同步和流式推荐中，难以保证一致性，因此不采用。

### 2. PARENT 组排序优先服务总结型生成

PARENT 证据组排序采用稳定的组合规则：

1. 命中 child 数量多的 PARENT 优先。
2. 最高 relevanceScore 更高的 PARENT 优先。
3. 平均 relevanceScore 更高的 PARENT 优先。
4. 原始召回排名更靠前的 PARENT 优先。

这使“多个 child 命中同一 PARENT”的情况被视为该语义单元整体相关，更符合推荐理由生成目标。

备选方案是只按最高分排序。该方案对单个高分 child 过敏，可能让覆盖面更强的 PARENT 被排到后面，因此不作为主规则。

### 3. 使用总预算而不是只依赖单 PARENT 上限

现有 `max-parent-context-tokens` 只限制单条扩展上下文，但 `finalTopK=5` 时仍可能累计到很大的 Prompt。新增上下文聚合预算：

- `maxParentCount`：默认建议 3。
- `maxParentContextTokens`：单 PARENT 上限，可沿用或调整现有值，建议 3000-4500。
- `totalEvidenceContextTokens`：总 evidence 上限，建议 9000-12000。
- `largeParentTokenThreshold`：超长 PARENT 判断阈值，建议 3500-4500。

系统先按 PARENT 排序，再在总预算内逐个选择上下文。无法纳入预算的 PARENT 不进入生成上下文，但可保留为被截断或未纳入的诊断信息。

### 4. 超长 PARENT 使用混合策略

默认情况下，PARENT 是总结型生成的主要上下文来源；当 PARENT 超过超长阈值或总预算不足时，系统退化为命中 CHILD 覆盖窗口。

覆盖窗口优先包含：

1. 所有命中的 CHILD。
2. 同父相邻 CHILD，用于补足前后语义。
3. 必要的 PARENT 头部或 sectionPath 作为主题提示。

备选方案是总是整段 PARENT 截断。该方案简单，但容易截断到命中 CHILD 之前或之后，导致实际证据点不在 Prompt 中，因此只作为 PARENT 不超长时的默认策略。

### 5. 引用粒度继续以 CHILD 为准

生成上下文可以是 PARENT 级，但引用和证据定位仍以命中 CHILD 为准。同步响应、流式 evidence 事件和调试信息应能展示 PARENT 组及其命中 CHILD 明细，避免用户只看到大段 PARENT 而无法定位真正的召回来源。

## Risks / Trade-offs

- [Risk] PARENT 聚合后最终 evidence 条目数少于原 TopK，前端看起来“证据变少”。→ Mitigation：展示 PARENT 组内命中 CHILD 数量和命中明细，表达证据覆盖更集中。
- [Risk] 组合排序参数不当导致高分单点被多命中 PARENT 压过。→ Mitigation：保留最高分、平均分和原始排名作为排序因子，并通过单元测试覆盖排序稳定性。
- [Risk] 超长 PARENT 退化为 CHILD 窗口后，推荐总结可能缺少完整章节背景。→ Mitigation：窗口包含同父相邻 CHILD，并在预算允许时补 PARENT 头部或 sectionPath。
- [Risk] 同步和流式推荐上下文不一致。→ Mitigation：在检索服务或共享上下文组装服务中统一聚合结果，推荐层只消费同一数据结构。
- [Risk] 新增上下文预算配置过多，运维不易理解。→ Mitigation：提供保守默认值，并保持现有 `max-parent-context-tokens` 兼容迁移。

## Migration Plan

1. 增加上下文聚合配置，默认值保持当前体验接近：继续允许 PARENT 上下文，但增加最大 PARENT 数和总预算。
2. 在检索服务中引入 PARENT 证据组数据结构，并让现有 RetrievedChunk 输出保持兼容或提供适配字段。
3. 同步推荐和流式推荐切换到聚合后的 evidence context。
4. 增加单元测试覆盖聚合、排序、预算、超长退化和缺失 parentChunkUid 兜底。
5. 执行 `mvn -q test` 与 `openspec validate --all --strict`。

回滚策略：保留旧的逐 CHILD 扩展 PARENT 逻辑作为可恢复路径；若聚合策略出现问题，可通过配置关闭 PARENT 聚合并恢复平铺 evidence context。

## Open Questions

- 聚合后的 PARENT 组是否需要新增前端展示结构，还是只在后端 Prompt 组装中使用？
- `totalEvidenceContextTokens` 默认值应更偏保守的 9000，还是更适合总结型生成的 12000？
- 超长 PARENT 退化窗口应默认取命中 CHILD 前后 1 个，还是在预算允许时前后 2 个？
