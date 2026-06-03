## Context

现有研报链路已经保存导入质量数据、段落 atom、PARENT/CHILD chunk、chunk 标签、报告标签、Milvus metadata 同步状态，以及推荐响应中的 top5、证据质量、输出等级和降级原因。`scripts/report-ingest-analysis` 也能按 query 执行搜索评估并导出 Excel。

缺口在于这些数据仍偏“运行观测”：query 没有稳定 case schema，标准证据没有版本化快照，历史评测依赖业务表当前状态，难以解释重导入、重切片、词典升级、prompt 修改或检索配置变化后的分数波动。后续接入 Ragas 也需要稳定的 `user_input`、`retrieved_contexts`、`response`、`reference` 和 `reference_contexts`。

## Goals / Non-Goals

**Goals:**

- 建立独立评测领域，使用 `eval_*` 数据模型保存 corpus、case、标准证据、运行结果和检索轨迹。
- 允许评测域冗余业务字段快照，保证历史评测可复现、可对比、可审计。
- 支持导入阶段生成标准证据候选，查询阶段记录 eval run 轨迹和生成结果。
- 支持把 eval run 导出为 Ragas 可消费的数据集文件。
- 复用现有推荐、检索、证据护栏、导入质量和搜索评估脚本能力，避免重写一套 RAG 链路。

**Non-Goals:**

- 不在本变更中替换线上推荐接口或改变普通用户推荐响应语义。
- 不要求在线服务直接依赖 Ragas；Ragas 作为离线导出后的可选评测组件。
- 不复制完整 PDF、全量 OCR 页或全量业务数据库到评测域。
- 不用 LLM 自动生成所有金标准；人工仍负责定义或审核 eval case 的标准证据与禁止项。

## Decisions

### 决策 1：评测域与业务域物理分表

新增 `eval_corpus`、`eval_corpus_report`、`eval_case`、`eval_case_anchor`、`eval_reference_context`、`eval_case_reference_context`、`eval_case_forbidden_context`、`eval_run`、`eval_case_run`、`eval_retrieved_context` 等评测表。业务表继续表达当前事实，评测表表达判卷依据和运行快照。

替代方案是直接在 `report_*` 表增加评测字段。该方案短期简单，但会把临时评测 case、人工标准和运行结果混入业务域，并且历史评测会被业务数据变化影响。

### 决策 2：评测域保存最小必要快照

评测域保存 report 标题、来源、发布日期、文件指纹、chunk 文本、sectionPath、页码、标签、词典版本、模型版本、prompt hash、检索配置等判卷必需快照。原始 PDF 和完整 OCR 页仍由业务质量表管理，需要排障时通过 `report_id` 或 `chunk_uid` 弱关联回查。

这会带来一定冗余，但能保证“当时为什么这样判分”可以被解释。

### 决策 3：case schema 先服务项目内自动判分，再服务 Ragas

eval case 必须包含项目内标准：期望 intent、期望 anchors、期望 outputLevel、期望降级原因、标准证据、required claims、forbidden claims 和 forbidden context。Ragas 字段由这些标准和运行结果导出生成，而不是反过来由 Ragas schema 主导评测域模型。

这样可以覆盖研报项目特有的主题覆盖、主体一致性、高确定性投资建议禁用和证据污染判断。

### 决策 4：评测运行记录检索轨迹阶段

`eval_retrieved_context` 记录 `INITIAL`、`FILTERED`、`FINAL`、`DIAGNOSTIC` 等候选阶段。最小实现可以先记录 `FINAL`，但数据模型和导出逻辑必须允许后续补充初召回、过滤、重排和诊断候选。

这能把“召回失败”“过滤失败”“主题覆盖失败”“生成失败”拆开定位。

### 决策 5：脚本作为首个操作入口

第一阶段以脚本命令支持构建 corpus、导入/维护 eval case、执行 eval run、生成 Excel/JSONL/Ragas 导出。后端服务负责提供必要受控能力和持久化模型，前端管理页面不作为第一阶段目标。

## Risks / Trade-offs

- [风险] 评测表数量较多，第一版实现成本上升。→ 先实现核心表和脚本闭环，`INITIAL/FILTERED` 轨迹可在受控接口可用后补齐。
- [风险] 标准证据快照与业务 chunk 后续不一致。→ 评测报告同时展示快照和弱关联 ID，并标记源业务记录是否仍存在。
- [风险] 人工标注成本高。→ 支持从已有 chunk/tag 生成 reference context 候选，人工只审核和补充标准。
- [风险] Ragas 分数被误当成唯一质量标准。→ 项目内自动判分优先，Ragas 导出作为补充指标输入。
- [风险] 评测运行可能调用外部 LLM 和 embedding 服务，成本不可控。→ eval run 记录模型与配置，脚本支持 case limit、失败继续和离线导出。

## Migration Plan

1. 新增评测表，不修改现有业务表语义。
2. 为评测脚本增加 corpus/case/run/export 配置与命令，默认不影响现有 `run-all` 行为。
3. 从已导入报告和 chunk 生成第一批 reference context 候选。
4. 使用小规模 core-v1 corpus 验证 eval run、Excel 和 Ragas JSONL 导出。
5. 稳定后再扩展初召回/过滤/诊断轨迹记录。

回滚时可停用评测脚本入口并保留或删除 `eval_*` 表；业务导入、检索、推荐链路不受影响。

## Open Questions

- 第一版是否需要后端管理 API，还是完全由脚本和数据库完成 eval case 维护。
- 初召回和过滤轨迹是通过新增受控评测 endpoint 暴露，还是在检索 pipeline 内增加可选 trace collector。
- app commit、prompt hash、词典版本的采集来源是否全部可在当前运行环境稳定获得。
