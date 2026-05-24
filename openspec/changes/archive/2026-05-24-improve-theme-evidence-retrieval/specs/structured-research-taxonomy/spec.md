## ADDED Requirements

### Requirement: 投研结构化词库 MUST 落库并支持版本化

系统 MUST 使用数据库保存主题、行业、公司、股票代码和主题词关系。主题词库 MUST 区分主题概念、词条和词条关系，并支持版本、状态和审计字段。YAML 或文本文件 MAY 作为初始化 seed 或测试数据，但 MUST NOT 作为正式运行时主存储。

#### Scenario: 保存主题词关系

- **GIVEN** 管理员维护主题 `STORAGE`
- **AND** 维护词条“新型储能”“电化学储能”“储能PCS”
- **WHEN** 系统保存词库
- **THEN** 系统 MUST 分别保存主题概念、词条和主题词条关系
- **AND** 系统 MUST 记录关系类型，例如 `REQUIRED`、`ALIAS` 或 `EXCLUDE`

#### Scenario: 词库版本生效

- **GIVEN** 系统存在多个词库版本
- **WHEN** 推荐检索或入库标签抽取运行
- **THEN** 系统 MUST 使用当前 ACTIVE 版本词库
- **AND** 系统 MUST 能记录标签产生时使用的词库版本

#### Scenario: YAML 仅作为 seed

- **GIVEN** 项目包含主题 seed 文件
- **WHEN** 系统启动或执行初始化脚本
- **THEN** 系统 MAY 将 seed 导入数据库
- **AND** 运行时 MUST 以数据库 ACTIVE 词库为准

### Requirement: 系统 MUST 将结构化标签结果落库

系统 MUST 在入库阶段为 report 或 chunk 生成结构化标签，并将标签结果保存到数据库。标签类型 MUST 至少支持 `THEME`、`INDUSTRY`、`COMPANY` 和 `TICKER`。标签结果 MUST 包含标签编码、标签名称、置信度、来源和词库版本。

#### Scenario: chunk 生成主题标签

- **GIVEN** 研报 chunk 命中 ACTIVE 词库中的储能主题规则
- **WHEN** 系统完成入库标签抽取
- **THEN** 系统 MUST 写入 `report_chunk_tag`
- **AND** 标签 MUST 包含 `tag_type=THEME`、`tag_code=STORAGE`、置信度、来源和词库版本

#### Scenario: chunk 生成股票代码标签

- **GIVEN** 研报标题区或 chunk 中出现 `300750.SZ`
- **WHEN** 系统完成入库标签抽取
- **THEN** 系统 MUST 写入 `TICKER` 标签
- **AND** 若证券词库存在对应公司，系统 SHOULD 同步生成公司标签

#### Scenario: 标签抽取未命中

- **GIVEN** 某个 chunk 未命中主题、行业、公司或代码规则
- **WHEN** 系统完成入库标签抽取
- **THEN** 系统 MUST 允许该 chunk 无标签
- **AND** 系统 MUST NOT 因无标签中断入库主流程

### Requirement: 标签抽取 MUST 采用 job 化执行

系统 MUST 使用 `report_chunk_tag_job` 管理 chunk 级标签抽取。`report_chunk_tag_job` MUST 支持新入库打标、失败重试、历史重打标和词库版本升级后的重算。标签抽取 job MUST 记录处理对象、词库版本、执行状态、重试次数、错误信息和执行时间。

#### Scenario: 新入库 chunk 创建标签抽取 job

- **GIVEN** 研报切片已经完成并写入 chunk 表
- **WHEN** 系统需要为该 chunk 生成结构化标签
- **THEN** 系统 MUST 创建或调度 `report_chunk_tag_job`
- **AND** job MUST 记录 `chunk_uid`、`report_id`、`dictionary_version` 和初始状态

#### Scenario: 标签抽取 job 成功写入标签主数据

- **GIVEN** `report_chunk_tag_job` 处于 `PROCESSING`
- **WHEN** 标签抽取成功完成
- **THEN** 系统 MUST 将抽取结果写入 `report_chunk_tag`
- **AND** 系统 MUST 将 job 状态更新为 `SUCCEEDED`
- **AND** 标签结果 MUST 保留本次 job 使用的词库版本

#### Scenario: 标签抽取 job 失败后可重试

- **GIVEN** `report_chunk_tag_job` 执行失败
- **WHEN** 失败次数未超过系统配置的最大重试次数
- **THEN** 系统 MUST 记录失败原因
- **AND** 系统 MUST 允许后续重新调度该 job

#### Scenario: 历史数据和词库版本升级触发重打标

- **GIVEN** 系统存在历史 chunk 或 ACTIVE 词库版本发生变化
- **WHEN** 管理员或定时任务触发重打标
- **THEN** 系统 MUST 批量创建或更新 `report_chunk_tag_job`
- **AND** 新 job MUST 使用目标词库版本重新生成标签

### Requirement: 词库匹配 MUST 支持大规模词条

系统 MUST 支持几十万词条规模的词库匹配。运行时 MUST NOT 对每次 query 或每个 chunk 执行逐词 `contains` 全量循环。系统 MUST 使用 ACTIVE 词库快照、缓存或自动机类结构进行高效匹配。

#### Scenario: 加载 ACTIVE 词库快照

- **GIVEN** 数据库中存在 ACTIVE 词库版本
- **WHEN** 应用启动或词库刷新
- **THEN** 系统 MUST 加载 ACTIVE 词库快照
- **AND** 系统 MUST 构建可复用的匹配结构供 query 抽取和 chunk 打标签使用

#### Scenario: 词库刷新失败

- **GIVEN** 系统已有可用词库快照
- **WHEN** 新版本词库加载失败
- **THEN** 系统 MUST 保留上一份可用快照
- **AND** 系统 MUST 记录刷新失败原因

### Requirement: Milvus metadata MUST 同步结构化标签摘要

系统 MUST 在向量入库或标签变更后将结构化标签摘要同步到 Milvus metadata。metadata MUST 保留现有 `sectionPath`，并 SHOULD 包含主题、行业、公司和代码摘要，用于在线 metadata scalar filter、加权、解释和前端展示。

#### Scenario: 向量 metadata 包含标签摘要

- **GIVEN** chunk 已生成 `THEME=STORAGE` 和 `INDUSTRY=POWER_EQUIPMENT` 标签
- **WHEN** 系统构建 Milvus Document
- **THEN** metadata MUST 包含 `sectionPath`
- **AND** metadata SHOULD 包含 `themeCodes`、`industryCodes`、`companyNames` 或 `tickers`

#### Scenario: Milvus 不作为词库主存储

- **GIVEN** 词库或标签发生变更
- **WHEN** 系统需要查询标签主数据
- **THEN** 系统 MUST 以 MySQL 中的词库和标签表为主
- **AND** Milvus metadata MUST 仅作为在线检索索引和解释信息

#### Scenario: 标签变更后同步 Milvus metadata

- **GIVEN** `report_chunk_tag` 中某个 chunk 的主题、行业、公司或代码标签发生变化
- **WHEN** 标签抽取任务完成
- **THEN** 系统 MUST 创建或执行 Milvus metadata 同步动作
- **AND** 同步完成后的 Milvus metadata MUST 与 MySQL 标签主数据保持最终一致

### Requirement: Milvus metadata 同步 MUST 支持独立 job

系统 MUST 支持使用 `report_vector_metadata_sync_job` 管理 Milvus metadata 同步。该 job SHOULD 以 MySQL 中的 `report_chunk_tag` 和 chunk 基础字段为主数据来源，将主题、行业、公司、代码和 `sectionPath` 摘要同步到 Milvus metadata。同步 job SHOULD 支持状态记录、失败重试和基于标签快照的幂等判断。

#### Scenario: 标签抽取成功后创建 metadata 同步 job

- **GIVEN** `report_chunk_tag_job` 已成功写入 `report_chunk_tag`
- **WHEN** 系统需要让 Milvus metadata 使用最新标签
- **THEN** 系统 SHOULD 创建或调度 `report_vector_metadata_sync_job`
- **AND** metadata 同步 job SHOULD 记录 `chunk_uid`、`report_id`、`metadata_version` 和 `tag_snapshot_hash`

#### Scenario: metadata 同步 job 更新 Milvus metadata

- **GIVEN** `report_vector_metadata_sync_job` 处于 `PROCESSING`
- **WHEN** 系统读取到该 chunk 的最新标签主数据
- **THEN** 系统 SHOULD 将 `themeCodes`、`industryCodes`、`companyNames`、`tickers` 和 `sectionPath` 同步到 Milvus metadata
- **AND** 同步成功后 job SHOULD 更新为 `SUCCEEDED`

#### Scenario: metadata 同步失败不回滚标签主数据

- **GIVEN** `report_vector_metadata_sync_job` 执行失败
- **WHEN** `report_chunk_tag` 已经成功保存标签结果
- **THEN** 系统 MUST NOT 回滚 MySQL 标签主数据
- **AND** 系统 SHOULD 记录失败原因并允许后续重试
