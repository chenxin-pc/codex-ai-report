## Context

当前结构化标签体系已经有两层数据模型：`report_chunk_tag` 用于 chunk 级证据标签，`report_document_tag` 用于报告级聚合标签。现有检索链路主要消费 chunk 级标签同步出来的 Milvus metadata，例如 `themeCode`、`industryCode` 和 `ticker`，但没有把报告级父标签应用到查询。

这会让主题类查询过度依赖单个 chunk 是否命中标签。实际语义上，`report_document_tag` 更适合表达“这篇研报归属于哪个父主题或父范围”，查询时应先限定报告集合，再在集合内用 query 锚点和 chunk 标签定位证据。

## Goals / Non-Goals

**Goals:**

- 将 `report_document_tag` 明确为导入时确定的报告级父标签主数据。
- 让 Milvus metadata 携带报告级父标签摘要，供在线检索过滤使用。
- 推荐检索时将报告级父标签作为强约束，与现有 query 锚点过滤组合。
- 保留 MySQL 标签表作为可追溯主数据和 metadata 滞后诊断来源。
- 保持同步推荐和流式推荐共享同一套召回语义。

**Non-Goals:**

- 不引入新的检索引擎或全文搜索能力。
- 不用 `report_document_tag` 替代 `report_chunk_tag` 的证据覆盖判断。
- 不在查询阶段扫描 chunk 大文本或 parent 大文本。
- 不要求一次性迁移全部历史向量；历史数据可通过重打标和 metadata 同步任务补齐。

## Decisions

### 1. 父标签是报告级强约束，chunk 标签是证据级命中

查询过滤语义采用：

```text
report_document_tag 约束 AND (query 主题锚点 OR 行业锚点 OR 代码锚点)
```

这样可以把“在哪些报告里查”和“哪些 chunk 回答问题”分开。替代方案是只把父标签并入现有 `themeCode` OR 条件，但这会削弱父标签的约束意义，也可能让错误主题的 chunk 被召回。

### 2. Milvus metadata 保存报告级父标签摘要

metadata 同步时在每个 CHILD chunk 文档上增加报告级字段，例如 `reportTagCode`、`reportTagCodes`、`reportTagType` 或更具体的 `reportThemeCode`。第一版优先使用稳定单值字段承载 scalar filter，多值字段用于解释和展示。

替代方案是在查询前先查 MySQL `report_document_tag` 得到 reportId 列表，再拼入 Milvus filter。这个方案可作为诊断或 fallback，但在线主路径会增加数据库依赖和候选集合拼接复杂度，因此不作为首选。

### 3. `report_document_tag` 由导入或标签聚合链路确定

新导入数据应在标签抽取完成后产生报告级父标签。父标签可以来自导入参数、报告元数据规则或 chunk 标签聚合，但一旦写入 `report_document_tag`，查询链路以该表为主数据来源。

历史数据通过已有标签 job 或新增报告级标签补齐任务生成父标签，并触发 metadata 同步。

### 4. metadata 快照哈希需要包含报告级标签

当前 `tagSnapshotHash` 主要反映 chunk 标签摘要。加入报告级父标签后，hash 必须覆盖报告级标签字段，避免 `report_document_tag` 变化后 Milvus metadata 不更新。

### 5. 无父标签时采用保守降级

当 query 需要父标签约束但报告级 metadata 缺失时，系统不应退回无约束广泛召回并生成强结论。可以返回无结果、触发诊断，或在证据质量阶段降级为信息不足。

## Risks / Trade-offs

- [Risk] 父标签抽取错误会造成过窄召回 -> Mitigation：保留 MySQL 主数据诊断和重打标入口，测试覆盖误缺失和 metadata 滞后场景。
- [Risk] Milvus metadata 同步滞后导致查询 0 结果 -> Mitigation：通过 `report_document_tag` 诊断主数据是否存在，并将同步问题与确实无证据区分开。
- [Risk] 多父标签报告只用单值字段会漏召回 -> Mitigation：第一版记录 primary 字段和列表字段，后续根据 Milvus 数组过滤能力扩展多值过滤。
- [Risk] 历史数据没有父标签 -> Mitigation：通过任务补齐历史 `report_document_tag` 和 metadata，未补齐前查询按信息不足处理。

## Migration Plan

1. 新增或补全 `ReportDocumentTag` 实体、Mapper 和服务，读取与写入 `report_document_tag`。
2. 在导入或标签聚合链路中生成报告级父标签，并保证可覆盖重算。
3. 扩展 metadata 构建和同步 job，把报告级父标签写入 Milvus metadata，并纳入 `tagSnapshotHash`。
4. 修改检索 filter 构造，将报告级父标签作为强约束组合到现有 query 锚点 filter。
5. 增加测试覆盖新导入、历史补齐、metadata 滞后、无父标签和无结果诊断场景。
6. 部署后先对测试数据重打标并同步 metadata，再启用查询侧父标签过滤。

## Open Questions

- 第一版父标签来源是否只采用 chunk 标签聚合，还是允许导入参数显式指定父标签。
- metadata 字段命名采用通用 `reportTagCode`，还是按主题类型先落 `reportThemeCode`。
- 多父标签过滤是否需要第一版支持数组过滤，还是先使用 primary 父标签。
