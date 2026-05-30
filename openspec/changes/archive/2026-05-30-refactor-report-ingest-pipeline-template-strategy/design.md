## Context

当前入库链路分为两层：`ReportIngestAsyncService` 负责异步任务提交、三阶段定时调度、状态推进、重试退避和阶段事件；`ReportIngestService` 负责 OCR、语义切片、MySQL 落库、Milvus 向量写入、chunk 过滤诊断和标签 job 触发。

现状的主要问题不是功能缺失，而是职责密度偏高。异步阶段执行的生命周期已经稳定，但仍通过字符串分支和私有方法散落在单个服务里；chunk 过滤与向量文档构建也混在持久化流程中，导致单元测试往往需要通过反射或大 mock 触达私有逻辑。后续如果继续增加重试策略、阶段观测、metadata 同步或过滤规则，容易让主流程继续膨胀。

本设计的约束是保持业务阅读顺序清晰。重构后的主路径仍应能按“上传 -> OCR -> CHUNK -> VECTOR”阅读，抽象只服务于稳定生命周期和真实变化点。

## Goals / Non-Goals

**Goals:**

- 保留现有上传、任务状态查询、质量观测和阶段事件接口语义。
- 将异步阶段通用生命周期收敛到阶段执行模板，统一处理 claim、attempt、success、failure、retry 和 event。
- 将 OCR、CHUNK、VECTOR 具体动作拆成阶段 handler，使阶段动作可以独立测试。
- 用轻量阶段描述代替散落的阶段字符串分支，集中维护阶段编码、模型名、状态字段、attempt 字段和前置依赖。
- 将 chunk 过滤规则拆为可测试 policy，保持 segmentType、sectionPath、低语义质量和财务表格豁免行为不变。
- 显式化向量文档构建与批量写入边界，保持结构化 metadata 构建优先级不变。
- 避免长耗时外部调用与任务状态占用/完成更新处于同一个长事务。

**Non-Goals:**

- 不修改数据库表结构、Mapper XML 字段、索引或 schema。
- 不修改 Controller、DTO、公开接口 URL 或前端调用方式。
- 不改变 OCR、语义切片、噪声过滤、标签抽取和 Milvus 入库的业务语义。
- 不引入完整工作流引擎、复杂策略注册表或完整 GoF State 类层级。
- 不新增 OCR、LLM、Embedding、Milvus 或 Redis 等外部依赖。

## Decisions

### Decision 1: 使用阶段执行模板承载异步生命周期

新增阶段执行组件负责单个 job 的单阶段执行骨架：读取最新 job、标记处理中、增加 attempt、执行阶段 handler、成功后写 `SUCCEEDED` 和阶段事件、失败后按重试策略写回状态和退避时间。

选择该方案是因为 OCR、CHUNK、VECTOR 的生命周期高度一致，差异主要集中在“执行这一阶段做什么”。模板方法可以减少 `processSingle` 内的大段重复认知负担，并让成功/失败路径有稳定测试入口。

替代方案是继续保留单方法 `switch` 分派。该方案短期文件少，但会让状态推进、重试和事件记录继续与业务动作交织，不利于后续扩展。

### Decision 2: 使用轻量阶段描述，而不是完整状态模式

阶段描述可以用枚举或小型组件表达阶段编码、模型名、状态字段读写、attempt 字段读写和前置依赖。状态流仍保持简单的 `PENDING`、`PROCESSING`、`SUCCEEDED`、`FAILED_RETRYABLE`、`FAILED_FINAL`。

选择该方案是为了控制理解成本。当前还没有暂停、取消、人工审批、跳过阶段或补偿阶段等复杂状态行为，完整 State 类层级会让读者在理解业务前先理解框架。

替代方案是为每个状态创建 State 类并封装转移。该方案在复杂工作流里更灵活，但当前收益不足。

### Decision 3: OCR/CHUNK/VECTOR 使用阶段 handler 分离具体动作

每个阶段 handler 只负责本阶段业务动作和本阶段输出数量：OCR handler 包装 spool 文件并调用 OCR 阶段入库，CHUNK handler 校验 reportId 并调用切片阶段，VECTOR handler 校验 reportId 并调用向量阶段。

选择该方案是为了让阶段执行模板不依赖具体业务服务细节，也让阶段动作可通过 mock 独立验证。

替代方案是把 handler 逻辑继续留在异步服务私有方法中。该方案迁移少，但无法真正降低 `ReportIngestAsyncService` 的职责密度。

### Decision 4: chunk 过滤拆为 policy，落库编排保持可顺序阅读

将 `resolveFilterReason`、财务表格豁免、segmentType 黑名单、sectionPath 关键词和低语义质量判断拆到独立 policy。`persistChunks` 或后续 chunk persistence 组件仍按 PARENT、CHILD、诊断记录的顺序编排，不把每个落库动作都拆成策略。

选择该方案是因为过滤规则是真实变化点，且当前测试已经需要反射调用私有方法；落库顺序本身是稳定流程，不适合过度抽象。

替代方案是把 PARENT persistence、CHILD persistence、diagnostic persistence 都做成模板或策略。该方案文件更多，但对当前变化点帮助有限。

### Decision 5: 向量阶段显式化文档构建与写入

将待向量化 CHILD 筛选、Document 构建、批量写入和 `vectorStored` 回写保持在清晰边界内。结构化 metadata 服务可用时仍优先使用增强构建，否则回退默认 metadata。

选择该方案是为了保留 Milvus 入库幂等性，并让“写 Milvus 成功后才回写 vectorStored=true”的关键语义更容易测试。

替代方案是把向量阶段完全并入通用 pipeline。该方案抽象统一，但会模糊 Milvus 成功与 MySQL 状态回写之间的关键边界。

### Decision 6: 拆分阶段状态更新事务与外部耗时调用

阶段 claim、完成和失败更新应使用短事务；OCR、LLM 切片和 Milvus 写入等外部耗时动作不应被同一个长事务包裹。实现时可以通过专用事务组件或服务边界完成，避免自调用导致事务注解失效。

选择该方案是因为“PROCESSING 可见性”和失败恢复比单方法事务更重要。长事务包住外部调用会延迟状态可见性，并增加数据库连接占用时间。

替代方案是保持当前单方法事务。该方案代码最少，但无法改善处理中状态可观测性。

## Risks / Trade-offs

- 类数量增加导致阅读跳转变多 -> 只拆阶段模板、阶段 handler、过滤 policy 和向量边界，不引入注册表或工作流引擎。
- 事务边界调整引入行为回归 -> 用测试覆盖 claim 可见、成功收尾、可重试失败、最终失败和事件写入。
- chunk 过滤拆分后原因字符串变化 -> 为 segmentType、sectionPath、低 token、短文本、低汉字比例、噪声比例和财务表格豁免补充断言。
- 向量阶段部分成功语义不清 -> 保持现有批量写入成功后再回写 `vectorStored=true` 的行为，不在本变更中引入逐条补偿。
- 与召回链路重构并行造成认知干扰 -> 使用独立 change，避免修改 retrieval 包和推荐接口。

## Migration Plan

1. 新增入库内部包或组件，承载阶段描述、阶段执行模板、阶段 handler、重试/退避 policy、chunk 过滤 policy 和向量文档构建边界。
2. 先为现有行为补充单元测试，固定异步状态流、过滤原因、向量幂等和阶段事件语义。
3. 将 `ReportIngestAsyncService` 的单阶段处理迁移到阶段执行模板，保留 scheduler 和查询入口。
4. 将 OCR、CHUNK、VECTOR 具体动作迁移到 handler，并让异步服务只委托阶段执行。
5. 将 `ReportIngestService` 中 chunk 过滤和向量文档构建逻辑迁移到独立组件，保持对外方法签名兼容。
6. 执行 `mvn -q test` 和 `openspec validate --all --strict`。

回滚策略：由于不涉及数据库和公开 API，若出现行为回归，可回退到原有 `ReportIngestAsyncService` 和 `ReportIngestService` 单类编排，同时保留新增测试作为行为约束。

## Open Questions

- 阶段执行模板是否需要独立事务组件，还是通过现有 service 拆分即可满足事务边界要求？
- 新组件包名使用 `service.ingest` 还是 `service.ingest.pipeline` 更贴近项目风格？
- 默认 metadata 文档构建是否保留在 `ReportIngestService` 附近，还是迁移为专用 `VectorDocumentBuilder`？
