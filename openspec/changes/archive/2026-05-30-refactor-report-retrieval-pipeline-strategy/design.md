## Context

`ReportRetrievalService.retrieve` 当前集中编排推荐召回的完整链路：获取 VectorStore、抽取 query 锚点、构造 Milvus `SearchRequest`、执行 ANN 搜索、映射候选、标准化分数、低分过滤、去重、可选重排、可选 PARENT 聚合以及 CHILD fallback 上下文扩展。

这些步骤本身已经构成稳定 Pipeline，但目前都耦合在一个服务类中。随着 metadata filter、主题覆盖、PARENT 聚合、诊断 fallback 和后续可能的重排策略增加，继续在单类中扩展会让召回质量调参和回归测试变得困难。

本设计将召回链路拆为显式 Pipeline 与策略组件，同时保持外部行为兼容：不改数据库、不改推荐接口、不改 DTO、不引入新依赖。

## Goals / Non-Goals

**Goals:**

- 保留 `ReportRetrievalService.retrieve(String query)` 作为对外入口，降低调用方改动。
- 将候选过滤、双重去重、重排和证据上下文构建拆为独立组件，提升单元测试粒度。
- 通过 `RerankStrategy` 保持 `rerankEnabled` 开关行为：关闭时保持顺序，开启时使用 query overlap 重排。
- 通过 `EvidenceContextStrategy` 保持 `parentAggregationEnabled` 开关行为：关闭时逐 CHILD 扩展，开启时按 PARENT 聚合并执行预算与 fallback。
- 保持 Milvus ANN 主召回、metadata scalar filter、分数标准化、TopK、PARENT fallback 和无结果诊断语义不变。

**Non-Goals:**

- 不新增召回算法、LLM rerank、hybrid search 或新的 metadata 字段。
- 不修改 `schema.sql`、实体字段、推荐请求/响应 DTO 或前端展示。
- 不改变输入可分析性判定、证据护栏、推荐生成 Prompt 或输出等级规则。
- 不将 `RetrievedChunk`、`RetrievedChild`、`EvidenceContextType` 在第一阶段迁移为公开顶层类型，除非实现中确有必要。

## Decisions

### Decision 1: 使用 Facade + Pipeline，而不是让调用方直接依赖 Pipeline

`ReportRetrievalService` 继续作为推荐召回 Facade。`ReportRecommendService`、`RecommendationEvidenceGuardrailService` 等调用方仍通过既有服务类型和 `RetrievedChunk` 结果消费召回证据。

选择该方案是为了把变更范围限制在召回内部，避免一次重构同时影响推荐编排、证据护栏和测试夹具。替代方案是直接把 `RetrievalPipeline` 暴露给调用方，但这会扩大迁移面，不适合作为第一版结构重构。

### Decision 2: 第一阶段保留现有嵌套结果类型

`ReportRetrievalService.RetrievedChunk`、`RetrievedChild`、`EvidenceContextType` 可以先保留在原类中，新组件通过这些类型协作。这样可以避免同步修改多个调用方 import 与测试断言。

替代方案是立即迁移到 `service.retrieval` 包下的顶层 record。该方案架构更干净，但会提高第一阶段风险。若后续 retrieval 包稳定，再单独做类型迁移更稳。

### Decision 3: 策略选择保持配置驱动，暂不引入复杂注册表

`rerankEnabled` 和 `parentAggregationEnabled` 仍由 `ReportQualityProperties.Retrieval` 控制。实现上可以通过简单选择器或 Spring 注入策略列表完成，但第一版不需要引入通用策略注册框架。

选择该方案是因为当前只有两组二元策略：`NoopRerankStrategy` / `QueryOverlapRerankStrategy`，以及 `ChildExpansionEvidenceContextStrategy` / `ParentAggregationEvidenceContextStrategy`。过早抽象注册表会增加理解成本。

### Decision 4: 先拆高价值边界，再拆构造请求与向量执行

优先拆出 `RetrievedChunkDeduplicator`、`RetrievalCandidateFilter`、`RerankStrategy` 和 `EvidenceContextStrategy`，因为这些步骤最容易单测，且最直接承载召回质量行为。

`RetrievalAnchorExtractor`、`RetrievalSearchRequestBuilder`、`VectorSearchExecutor` 可以随后纳入 Pipeline 编排，避免一次性移动过多私有方法导致回归定位困难。

### Decision 5: 不持久化策略决策

本次不新增 `strategy_name`、`decision_reason` 或 `diagnostics_json` 字段。召回策略选择仍是运行时配置行为，推荐响应和现有诊断字段保持不变。

若后续需要在前端或运维页面展示“本次使用了哪种 rerank/context 策略”，再单独评估新增响应字段或诊断字段。

## Risks / Trade-offs

- 排序稳定性回归 → 为 `rerankEnabled=false`、`rerankEnabled=true` 分别补充顺序断言，确保去重和策略选择不引入非预期排序变化。
- PARENT 上下文内容变化 → 为 PARENT 完整上下文、截断上下文、CHILD_WINDOW 和 CHILD_FALLBACK 建立策略测试，重点比较 `evidenceText`、`contextType`、`hitChildren` 和 `truncated`。
- 类型保留导致新组件依赖旧 Facade 内部类型 → 第一阶段接受该折中，后续可用独立变更迁移结果类型。
- 组件数量增加带来类文件增多 → 只抽真实变化点，不把每个私有 helper 都拆成类；共享 helper 只有在重复使用明显时再抽。
- 无结果诊断行为被遗漏 → 将 Milvus 返回空列表和 null 的场景纳入 Pipeline 或 VectorSearchExecutor 测试，确保仍触发 metadata fallback 诊断并返回空证据。

## Migration Plan

1. 在 `service/retrieval` 下新增候选过滤、去重、重排策略和证据上下文策略组件。
2. 将 `ReportRetrievalService` 中对应私有逻辑逐步迁移到组件，并保留外部入口与返回类型。
3. 新增 `RetrievalPipeline` 编排完整召回流程。
4. 将 `ReportRetrievalService.retrieve` 委托给 Pipeline。
5. 扩展单元测试覆盖过滤、去重、重排、PARENT 聚合、CHILD 扩展和配置开关组合。
6. 执行 `mvn -q test` 和 `openspec validate --all --strict`。

回滚策略：由于不涉及数据库和外部 API，若行为回归，可回退到单类 `ReportRetrievalService` 编排实现，保留测试用例作为行为约束。

## Open Questions

- 第一版是否保留所有 `RetrievedChunk` 相关 record 为 `ReportRetrievalService` 嵌套类型，还是在实现中发现依赖过重时提前迁移到顶层类型？
- `VectorSearchExecutor` 是否负责 `requireVectorStore`，还是由 Pipeline 在调用前负责可用性检查？
- `metadataText`、`limitTokens` 等 helper 是否在策略类内局部保留，还是抽为 package-private helper？建议实现时以最少共享为准。
