# report-evaluation-dataset Specification

## Purpose
TBD - created by archiving change add-report-rag-evaluation-dataset. Update Purpose after archive.
## Requirements
### Requirement: 评测语料集 MUST 独立管理业务数据快照

系统 MUST 提供独立的评测语料集数据模型，用于记录 corpus 标识、名称、版本、词典版本、embedding 模型、语料说明和关联报告快照。评测语料集 MUST 通过 `report_id` 弱关联业务报告，但判卷所需标题、来源、发布日期和文件指纹 MUST 冗余到评测域。

#### Scenario: 从已导入报告创建评测语料

- **GIVEN** 业务库中存在已完成导入的研报和 chunk
- **WHEN** 用户或脚本将报告加入评测 corpus
- **THEN** 系统 MUST 创建 corpus report 记录
- **AND** 系统 MUST 保存 reportId、标题、来源、发布日期和文件指纹快照
- **AND** 后续业务报告元数据变化 MUST NOT 改写既有 corpus report 快照

#### Scenario: 业务报告被重导入或重切片

- **GIVEN** 某个 corpus report 对应的业务报告后续被重导入或重切片
- **WHEN** 用户查询历史评测 corpus
- **THEN** 系统 MUST 保留历史 corpus report 快照
- **AND** 系统 MUST 允许通过 reportId 或 fingerprint 识别当前业务数据是否与历史快照一致

### Requirement: Eval case MUST 定义可判卷的投研标准

系统 MUST 为每条 eval case 保存稳定的 caseId、query、caseType、difficulty、expectedIntent、expectedOutputLevel、expectedDegradationReasons、referenceAnswer、requiredClaims、forbiddenClaims、forbiddenTerms 和启用状态。Eval case MUST 属于一个 corpus。

#### Scenario: 创建主题研究 eval case

- **GIVEN** 评测 corpus 已存在
- **WHEN** 用户或脚本创建“哪些研报看好储能板块，核心逻辑和风险是什么？”的主题研究 case
- **THEN** 系统 MUST 保存 query 和 caseType
- **AND** 系统 MUST 保存 expectedIntent 为主题研究类意图
- **AND** 系统 MUST 保存 expectedOutputLevel 和 forbiddenClaims

#### Scenario: 创建证据不足 eval case

- **GIVEN** 评测 corpus 不包含某主题的有效证据
- **WHEN** 用户或脚本创建该主题的证据不足 case
- **THEN** 系统 MUST 允许 expectedOutputLevel 指向证据不足或污染等级
- **AND** 系统 MUST 允许 expectedDegradationReasons 包含主题覆盖不足或证据缺失原因

### Requirement: Eval case MUST 支持标准锚点和禁止命中项

系统 MUST 为 eval case 保存标准锚点和禁止命中项。标准锚点 MUST 至少支持主题、行业、公司、股票代码和章节意图。禁止命中项 MUST 至少支持主题、行业、公司、股票代码、关键词和 chunk 标识。

#### Scenario: 保存标准主题和行业锚点

- **GIVEN** eval case 查询储能主题
- **WHEN** 用户为 case 标注标准锚点
- **THEN** 系统 MUST 保存 THEME=STORAGE
- **AND** 系统 MAY 保存 INDUSTRY=POWER_EQUIPMENT
- **AND** 系统 MUST 标记锚点是否为 required

#### Scenario: 保存污染证据禁止项

- **GIVEN** eval case 用于验证储能主题检索
- **WHEN** 用户标注港口、军工或算力为禁止命中主题
- **THEN** 系统 MUST 保存 forbidden context 规则
- **AND** 后续评测 MUST 能根据该规则判断召回污染

### Requirement: 标准证据 MUST 保存 reference context 快照

系统 MUST 将标准证据保存为独立 reference context。Reference context MUST 记录 corpusId、reportId、chunkUid、parentChunkUid、contextType、sectionPath、page range、referenceText、主题标签、行业标签、公司名、股票代码和创建时间。Reference context MUST 可被多个 eval case 复用。

#### Scenario: 从业务 chunk 创建标准证据

- **GIVEN** 业务库中存在 CHILD chunk 及其 PARENT 上下文
- **WHEN** 用户或脚本将该 chunk 标为标准证据
- **THEN** 系统 MUST 保存 chunkUid 和 parentChunkUid
- **AND** 系统 MUST 保存 referenceText 快照
- **AND** 系统 MUST 保存 sectionPath、页码和标签快照

#### Scenario: 标准证据文本与业务 chunk 后续不同

- **GIVEN** reference context 已保存 referenceText 快照
- **AND** 对应业务 chunk 后续被重切片或文本变化
- **WHEN** 用户查看历史 eval case
- **THEN** 系统 MUST 使用 referenceText 快照作为判卷依据
- **AND** 系统 MUST NOT 强制用当前业务 chunkText 覆盖历史 referenceText

### Requirement: Eval case MUST 关联标准证据并标记相关等级

系统 MUST 支持 eval case 与 reference context 的多对多关联，并记录 relevanceLevel、required 标记和备注。自动评测 MUST 能使用该关联计算命中、漏召回和排序质量。

#### Scenario: 关联必需标准证据

- **GIVEN** eval case 已创建
- **AND** reference context 已创建
- **WHEN** 用户将 reference context 关联到 eval case
- **THEN** 系统 MUST 保存 relevanceLevel
- **AND** 系统 MUST 支持 required=true 表示该证据应被召回

#### Scenario: Case 没有关联标准证据

- **GIVEN** eval case 缺少 reference context
- **WHEN** 用户尝试将其用于需要检索判分的评测运行
- **THEN** 系统 MUST 标记该 case 无法计算检索命中类指标
- **AND** 系统 MUST 仍可用于不可分析输入或纯降级行为评测
