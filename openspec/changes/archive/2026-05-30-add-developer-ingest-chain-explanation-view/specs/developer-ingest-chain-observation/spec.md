## ADDED Requirements

### Requirement: 系统 MUST 提供面向开发者的导入链路解释视图

系统 MUST 提供以 `reportId` 为主线的导入链路解释视图。该视图 MUST 按阶段展示报告上传、OCR 文本解析、语义切片、向量入库等环节，并 MUST 为每个环节提供操作说明、执行前数据状态、执行后数据状态、读写数据、影响点和失败解释。默认视图 MUST 优先展示摘要和结论，不得默认把大段 OCR 文本、chunkText 或原始 diagnostics 全量铺开。

#### Scenario: 查看成功导入报告的链路解释

- **GIVEN** 一篇报告已完成 OCR、语义切片和向量入库
- **WHEN** 开发者在观测页面按 reportId 打开导入链路解释视图
- **THEN** 系统 MUST 展示报告摘要和整体导入状态
- **AND** 系统 MUST 展示 OCR、语义切片和向量入库阶段的状态、耗时和输出摘要
- **AND** 每个阶段 MUST 展示该阶段做了什么、写入或更新了哪些数据、对后续阶段产生什么影响

#### Scenario: 默认视图不展示大文本明细

- **GIVEN** 一篇报告包含多页 OCR 文本和多个 chunk
- **WHEN** 开发者打开导入链路解释视图
- **THEN** 系统 MUST 默认展示页数、段落数、PARENT 数、CHILD 数、过滤数和向量入库数量等摘要
- **AND** 系统 MUST 将 OCR 页文本、段落 atom 文本、chunkText 和 diagnostics 放在可展开明细或分页明细中

#### Scenario: 失败报告自动聚焦失败阶段

- **GIVEN** 一篇报告在 CHUNK 或 VECTOR 阶段失败
- **WHEN** 开发者打开该报告的导入链路解释视图
- **THEN** 系统 MUST 标识失败阶段、错误码、错误摘要和重试次数
- **AND** 系统 MUST 默认聚焦到失败阶段的解释卡
- **AND** 系统 MUST 展示该失败对后续阶段的影响

### Requirement: 语义切片阶段 MUST 以 PARENT-CHILD 树展示最终切片结构

导入链路解释视图中的语义切片阶段 MUST 以 PARENT-CHILD 树展示 `report_chunk` 的最终落库结构。系统 MUST 通过 `CHILD.parentChunkUid = PARENT.chunkUid` 关联 CHILD 与 PARENT，并 MUST 同时展示 chunk 类型、索引、段落范围、页码范围、sectionPath、tokenCount、vectorStored 和过滤诊断摘要。

#### Scenario: 展示 PARENT 下的 CHILD 列表

- **GIVEN** 一篇报告已生成 PARENT 和 CHILD chunk
- **WHEN** 开发者打开语义切片阶段详情
- **THEN** 系统 MUST 按 PARENT 分组展示 CHILD
- **AND** 每个 CHILD MUST 展示 chunkUid、parentChunkUid、tokenCount、页码范围和 vectorStored 状态
- **AND** 系统 MUST 能让开发者从 CHILD 定位到对应 PARENT

#### Scenario: 展示过滤切片诊断

- **GIVEN** 语义切片阶段产生了被过滤的 chunk 诊断记录
- **WHEN** 开发者打开语义切片阶段详情
- **THEN** 系统 MUST 展示过滤数量和过滤原因分布
- **AND** 系统 MUST 在可展开明细中展示被过滤项的 chunkType、段落范围、页码范围、tokenCount、filterReason 和 diagnostics 摘要
- **AND** 系统 MUST 明确被过滤项不会进入向量入库候选

#### Scenario: 诊断记录缺少落库 chunkUid

- **GIVEN** 某个切片候选被过滤且没有写入 report_chunk
- **WHEN** 系统构建语义切片阶段详情
- **THEN** 系统 MUST 仍然展示该诊断记录
- **AND** 系统 MUST 将其标识为未落库候选或过滤项
- **AND** 系统 MUST 不把该记录展示为已入库 CHILD

### Requirement: 向量入库阶段 MUST 关联语义切片产物

导入链路解释视图中的向量入库阶段 MUST 展示其输入来自语义切片阶段保留的 CHILD。系统 MUST 展示可入库 CHILD 候选数量、已入库数量、未入库数量和未入库原因摘要。具体 CHILD 明细 MUST 能关联或筛选到语义切片阶段的 PARENT-CHILD 树，而不是在向量阶段重复展示无上下文的大表。

#### Scenario: 展示向量入库候选摘要

- **GIVEN** 一篇报告已完成语义切片并生成 kept=true 的 CHILD
- **WHEN** 开发者查看向量入库阶段
- **THEN** 系统 MUST 展示候选 CHILD 数量
- **AND** 系统 MUST 展示 vectorStored=true 和 vectorStored=false 的数量
- **AND** 系统 MUST 说明这些候选来自语义切片阶段的保留 CHILD

#### Scenario: 定位未入库 CHILD

- **GIVEN** 一篇报告存在 vectorStored=false 的 CHILD
- **WHEN** 开发者在向量入库阶段选择查看未入库 CHILD
- **THEN** 系统 MUST 能定位或筛选到语义切片阶段的对应 CHILD
- **AND** 系统 MUST 展示该 CHILD 的 parentChunkUid、PARENT 摘要、sectionPath、tokenCount 和页码范围

#### Scenario: 向量阶段无候选 CHILD

- **GIVEN** 一篇报告没有保留的 CHILD chunk
- **WHEN** 开发者查看向量入库阶段
- **THEN** 系统 MUST 展示候选 CHILD 数量为 0
- **AND** 系统 MUST 说明向量阶段无法入库的原因来自语义切片阶段没有可用 CHILD

### Requirement: 页面交互 MUST 使用渐进式信息架构

开发者观测页面 MUST 使用报告摘要、链路步骤导航、阶段解释卡和按需明细的渐进式结构。页面 MUST 避免把 OCR、段落、chunk、diagnostic、vector 状态等不相干明细同时铺开。用户选择某个阶段时，页面 MUST 只展示该阶段相关的解释和明细入口。

#### Scenario: 通过步骤导航切换阶段

- **GIVEN** 开发者已打开某篇报告的导入链路解释视图
- **WHEN** 开发者选择 OCR、语义切片或向量入库阶段
- **THEN** 页面 MUST 展示所选阶段的解释卡
- **AND** 页面 MUST 隐藏其他阶段的大文本明细
- **AND** 页面 MUST 保留报告摘要和整体链路状态

#### Scenario: 从向量阶段跳转到 CHILD 明细

- **GIVEN** 开发者正在查看向量入库阶段
- **WHEN** 开发者选择查看候选 CHILD 或未入库 CHILD
- **THEN** 页面 MUST 导航到语义切片阶段
- **AND** 页面 MUST 自动应用 CHILD 或 vectorStored 状态筛选
- **AND** 页面 MUST 保持该 CHILD 所属 PARENT 的上下文可见
