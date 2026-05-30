## Context

当前导入观测能力由三类入口组成：研报观测列表、阶段事件时间线、切片前后对照。它们能查询事实数据，但页面呈现更像日志和表数据集合，普通开发需要自行理解 OCR、段落 atom、PARENT/CHILD、过滤诊断和向量入库之间的关系。

现有数据已经能串联主要链路：`report_document` 表示报告主档，`report_ocr_page` 表示页级 OCR，`report_paragraph_atom` 表示段落输入，`report_chunk` 表示最终落库的 PARENT/CHILD，`report_chunk_diagnostic` 表示保留或过滤诊断，`report_chunk.vector_stored` 表示 CHILD 是否已进入向量库，`report_ingest_stage_event` 表示阶段执行事件。因此本设计重点不是新增处理链路，而是把现有数据组织成面向开发者的解释视图。

## Goals / Non-Goals

**Goals:**

- 提供以 reportId 为主线的导入链路解释视图，展示上传、OCR、语义切片、向量入库等阶段。
- 每个阶段都输出操作说明、执行前数据状态、执行后数据状态、读写数据、影响点和失败解释。
- 在语义切片阶段以 PARENT-CHILD 树表达 `report_chunk` 的最终落库结构，并结合 `report_chunk_diagnostic` 解释过滤和诊断。
- 在向量入库阶段展示可入库 CHILD 候选、已入库数量、未入库数量，并能关联回语义切片阶段的 PARENT-CHILD 明细。
- 前端采用渐进式信息架构：总览先给结论，阶段卡解释上下文，明细按需展开。

**Non-Goals:**

- 不改变 OCR、语义切片、标签抽取、向量入库的主处理逻辑。
- 不引入新的外部观测系统或日志平台。
- 不把观测页改造成通用数据库浏览器。
- 不在默认视图展示大段 OCR 文本、chunkText 或 diagnostics；这些内容只在展开明细中出现。
- 不改造推荐检索和 RAG 生成接口。

## Decisions

### 1. 新增 report 级链路解释接口，而不是让前端拼多个原始接口

后端应提供一个以 reportId 查询的链路解释视图，聚合报告主档、阶段事件、OCR 页、段落 atom、chunk、chunk diagnostic 和向量状态。前端消费结构化阶段模型，而不是同时请求并手工拼装多个低层接口。

备选方案是继续使用现有 `/observations`、`/ingest-stage-events`、`/{reportId}/chunk-observation`，由前端组装。该方案会把业务关联逻辑散落在前端，难以保证阶段解释、数据统计和影响点一致，因此不采用。

### 2. 阶段模型使用 summary + explanation + detailRefs

每个阶段返回三层信息：

- `summary`：状态、耗时、输入输出数量、错误摘要。
- `explanation`：做了什么、执行前数据状态、执行后数据状态、读写数据、影响点。
- `details`：按需展开的 OCR 页、段落 atom、PARENT-CHILD 树、过滤诊断、向量候选。

这样默认页面能被普通开发快速读懂，需要排障时再展开明细。

### 3. 语义切片阶段是 PARENT-CHILD 树的主位置

`report_chunk` 中 PARENT 和 CHILD 可通过 `CHILD.parentChunkUid = PARENT.chunkUid` 关联。语义切片阶段应以树结构展示最终落库的 PARENT/CHILD，而不是平铺所有 chunk。

`report_chunk_diagnostic` 用于补充保留、过滤、filterReason、diagnostics、段落范围和页码范围。被过滤但未落入 `report_chunk` 的候选应展示在当前 PARENT 或过滤项分组下，避免开发者误以为过滤项进入了向量阶段。

### 4. 向量入库阶段引用 CHUNK 产物，不重复制造大表

向量入库阶段的输入是语义切片阶段保留的 CHILD。页面默认只展示候选 CHILD 数、已入库数、未入库数、跳过原因摘要。需要查看具体 CHILD 时，交互应跳转或筛选到语义切片阶段的 PARENT-CHILD 树。

这样能避免同一批 CHILD 在 CHUNK 和 VECTOR 阶段重复大面积展示，同时保留因果关系。

### 5. 前端采用“报告详情 + 链路步骤导航 + 阶段详情”的布局

观测页应先显示报告摘要和整体状态，再展示上传、OCR、语义切片、向量入库、可检索等步骤导航。用户选择某个步骤后，主区域展示该阶段解释卡和可展开明细。

失败时默认聚焦失败阶段，并展开与失败最相关的诊断入口。例如 CHUNK 失败聚焦切片诊断，VECTOR 失败聚焦未入库 CHILD 和向量配置错误。

## Risks / Trade-offs

- [Risk] 一次返回所有 OCR 文本和 chunkText 会导致响应过大。→ Mitigation：默认只返回摘要、计数和必要定位字段，大文本通过分页、抽样或展开明细加载。
- [Risk] PARENT-CHILD 树和 diagnostic 的关联规则不清导致展示错位。→ Mitigation：以 `parentChunkUid`、`parentIndex`、`chunkIndexInParent` 作为明确关联键，并在缺失关系时显示“未关联诊断”分组。
- [Risk] 阶段解释文案写死后与代码行为漂移。→ Mitigation：解释文案只描述稳定业务语义，数量和状态来自实时数据；后续变更阶段行为时同步更新规格和文案。
- [Risk] 页面信息量仍然过大。→ Mitigation：坚持总览、阶段解释、按需明细三层结构，默认隐藏大文本与原始 diagnostics。
- [Risk] 标题搜索可能命中多个报告，直接进入详情会混淆。→ Mitigation：标题搜索先落到报告列表或分组结果，用户选择明确 reportId 后进入链路解释视图。

## Migration Plan

1. 保留现有观测接口，新增 reportId 维度的链路解释查询能力。
2. 后端先聚合现有表数据形成阶段摘要和解释模型，不改变导入写入逻辑。
3. 前端将“研报观测”改为报告列表入口，点击报告进入链路解释详情。
4. 将现有阶段事件和切片对照作为详情展开能力迁移到新视图中。
5. 增加单元测试和前端交互验证后执行 `mvn -q test` 与 `openspec validate --all --strict`。

回滚策略：保留旧观测列表、阶段事件查询和切片对照入口；若新视图出现问题，可临时隐藏入口并继续使用旧接口排障。

## Open Questions

- 大文本明细第一版采用后端分页接口，还是在 reportId 详情中返回截断预览并按需加载完整文本？
- PARENT-CHILD 树默认是否只展示 kept=true 的最终落库 chunk，过滤项通过单独分组展示，还是将过滤项插入原始父子位置？
- 是否需要在第一版加入 Milvus 侧确认查询，还是只使用 MySQL `vectorStored` 表示向量入库状态？
