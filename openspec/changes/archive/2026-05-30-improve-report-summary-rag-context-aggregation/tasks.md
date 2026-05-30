## 1. 配置与数据结构

- [x] 1.1 扩展推荐检索配置，新增最大 PARENT 数、总 evidence token 上限、单 PARENT token 上限、超长 PARENT 阈值和聚合开关。
- [x] 1.2 设计并实现 PARENT 证据组值对象，包含 parentChunkUid、上下文文本、命中 CHILD 列表、最高分、平均分、命中数量、截断/退化状态和展示 metadata。
- [x] 1.3 保持现有 RetrievedChunk 或响应 DTO 的兼容路径，明确同步和流式推荐如何消费聚合后的上下文。

## 2. 检索聚合逻辑

- [x] 2.1 在 Milvus CHILD 召回、分数过滤和去重后，按 parentChunkUid 聚合候选；缺少 parentChunkUid 的候选使用 CHILD 文本作为独立兜底证据。
- [x] 2.2 为每个 PARENT 组回查 parent chunk，并保留组内命中 CHILD 明细用于引用、展示和诊断。
- [x] 2.3 实现 PARENT 组排序：命中 CHILD 数量、最高相关性分数、平均相关性分数、原始召回顺序依次参与稳定排序。
- [x] 2.4 实现最大 PARENT 数和总 evidence token 预算控制，记录被排除或截断的 PARENT 组诊断信息。

## 3. 上下文选择策略

- [x] 3.1 对未超长且预算充足的 PARENT 使用完整或按单 PARENT 上限截断后的 PARENT 上下文。
- [x] 3.2 对超长 PARENT 或预算不足场景，实现命中 CHILD 覆盖窗口，优先包含命中 CHILD，并在预算内补充同父相邻 CHILD。
- [x] 3.3 在聚合上下文中标记上下文来源类型，例如 FULL_PARENT、TRUNCATED_PARENT、CHILD_WINDOW、CHILD_FALLBACK。
- [x] 3.4 保证 citations 和 evidence 展示仍能对应实际命中的 CHILD，而不是只指向 PARENT 文本。

## 4. 推荐链路接入

- [x] 4.1 调整同步推荐 Prompt 组装逻辑，使其使用聚合后的 PARENT 证据组上下文，同时保留 CHILD 引用信息。
- [x] 4.2 调整流式推荐 evidence 事件和 Prompt 组装逻辑，使其与同步推荐使用一致的聚合上下文和降级语义。
- [x] 4.3 确认主题覆盖、投研输入可分析性和证据质量护栏继续基于实际命中 CHILD 与标签 metadata 工作，不被 PARENT 聚合绕过。

## 5. 测试与验证

- [x] 5.1 增加单元测试：多个命中 CHILD 属于同一 PARENT 时只生成一个 PARENT 证据组，并保留 CHILD 明细。
- [x] 5.2 增加单元测试：PARENT 组排序按命中数量、最高分、平均分和原始顺序稳定执行。
- [x] 5.3 增加单元测试：总 token 预算和最大 PARENT 数生效，超出预算的 PARENT 被排除或截断并记录诊断。
- [x] 5.4 增加单元测试：超长 PARENT 退化为命中 CHILD 覆盖窗口，并保留引用粒度。
- [x] 5.5 增加单元测试：缺少 parentChunkUid 或 parent chunk 不存在时使用 CHILD 兜底，不伪造 PARENT 上下文。
- [x] 5.6 执行 `mvn -q test`。
- [x] 5.7 执行 `openspec validate --all --strict`。
