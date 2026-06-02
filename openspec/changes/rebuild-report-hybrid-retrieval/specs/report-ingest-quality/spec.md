## ADDED Requirements

### Requirement: 导入阶段 MUST 在 MySQL 保存研报作者元数据

导入阶段 MUST 将解析或录入的研报作者信息保存到 MySQL 研报元数据表或关联表中。MySQL 中保存的作者信息 MUST 作为 Milvus hybrid collection author metadata 的事实来源。系统 MUST 支持多作者研报，并保留作者原始名称和用于过滤的规范化名称或等价可检索表达。

#### Scenario: 新导入研报包含作者信息

- **Given** 导入的研报文件或录入参数包含一个或多个作者
- **When** 系统完成研报基础元数据入库
- **Then** MySQL MUST 保存该研报的作者信息
- **And** 多作者信息 MUST 不得被压缩为不可拆分的单个普通字符串
- **And** 后续 Milvus author metadata MUST 来源于 MySQL 中保存的作者信息

#### Scenario: 导入研报未解析到作者

- **Given** 导入阶段未能解析或录入研报作者
- **When** 系统保存研报基础元数据
- **Then** MySQL MUST 保存明确的空作者状态或空集合
- **And** Milvus 写入阶段 MUST NOT 伪造作者 metadata

### Requirement: 系统 MUST 支持破坏式清空研报导入域数据

系统 MUST 提供显式方式清空历史研报导入域数据和旧 Milvus collection，以便在 hybrid collection schema 生效后重新导入研报。清空范围 MUST 限定为导入域数据，包括报告、OCR 页、段落 atom、chunk、chunk 诊断、导入任务、阶段事件、失败记录、报告/切片标签、标签任务、向量 metadata sync job 和旧检索 collection。系统 MUST 保留结构化投研词库、taxonomy 主数据、Prompt 和系统配置。

#### Scenario: 运维执行导入域清空

- **Given** 系统中存在历史上传研报、导入任务、chunk 和旧 Milvus collection
- **When** 运维显式执行导入域清空动作
- **Then** MySQL 中历史研报导入域数据 MUST 被清空
- **And** 旧 Milvus collection MUST 被删除
- **And** 结构化投研词库、taxonomy 主数据、Prompt 和系统配置 MUST 保留

#### Scenario: 应用正常启动

- **Given** 系统配置了 hybrid collection
- **When** 应用启动
- **Then** 应用 MUST NOT 自动清空 MySQL 导入域数据
- **And** 应用 MUST NOT 自动删除已有 Milvus collection，除非清空动作被显式触发

### Requirement: Milvus hybrid collection MUST 被显式初始化

系统 MUST 在写入向量前确保 Milvus hybrid collection 存在且 schema 符合要求。collection MUST 包含可 analyzer 的 chunk text 字段、dense `FLOAT_VECTOR` 字段、BM25 sparse `SPARSE_FLOAT_VECTOR` 字段、BM25 function、主键字段和推荐检索所需 scalar metadata 字段。metadata MUST 包含从 MySQL 研报元数据投影而来的研报作者字段，并支持多作者研报的可检索表达。collection MUST 创建 dense、sparse 和必要 scalar index，并在检索前完成 load。

#### Scenario: collection 不存在

- **Given** Milvus 中不存在目标 hybrid collection
- **When** 系统执行 collection 初始化
- **Then** 系统 MUST 创建包含 text、dense vector、sparse vector、BM25 function 和 metadata scalar 字段的 collection
- **And** metadata scalar 字段 MUST 包含研报作者字段
- **And** 系统 MUST 创建 dense 与 sparse index
- **And** 系统 MUST 确保 collection 可被写入和检索

#### Scenario: collection schema 不兼容

- **Given** Milvus 中已存在同名 collection
- **And** collection 缺少 BM25 function、sparse vector 或关键 metadata 字段
- **When** 系统执行 collection 初始化
- **Then** 系统 MUST 报告 schema 不兼容
- **And** 系统 MUST NOT 静默写入不兼容 collection
- **And** 运维 MAY 通过显式清空动作删除并重建 collection

### Requirement: 向量阶段 MUST 写入 Milvus hybrid collection

向量入库阶段 MUST 为可检索 CHILD chunk 生成 Qwen dense embedding，并将 chunk 文本、dense embedding 和结构化 metadata 写入 Milvus hybrid collection。结构化 metadata MUST 包含从 MySQL 研报元数据读取的研报作者信息。BM25 sparse vector MUST 由 Milvus BM25 function 在 collection 内生成。只有当 hybrid collection 写入成功后，系统才能把 chunk 标记为已向量化。

#### Scenario: CHILD chunk 写入 hybrid collection

- **Given** 研报已完成 OCR、段落 atom 和 chunk 生成
- **And** CHILD chunk 通过质量过滤
- **When** 向量入库阶段处理该 CHILD chunk
- **Then** 系统 MUST 生成 Qwen dense embedding
- **And** 系统 MUST 从 MySQL 读取研报作者元数据
- **And** 系统 MUST 写入 chunk text、dense embedding 和包含研报作者的 metadata 到 Milvus hybrid collection
- **And** 系统 MUST 依赖 Milvus BM25 function 生成 sparse vector
- **And** MySQL chunk 状态 MUST 在写入成功后标记为已向量化

#### Scenario: hybrid collection 写入失败

- **Given** CHILD chunk 已生成 dense embedding
- **When** 写入 Milvus hybrid collection 失败
- **Then** 系统 MUST 记录阶段失败原因
- **And** 系统 MUST NOT 将该 chunk 标记为已向量化
- **And** 后续重试 MUST 能够重新处理该 chunk

## MODIFIED Requirements

### Requirement: 入库策略拆分 MUST 保持 chunk 过滤和向量入库行为

入库策略拆分后，手动上传和自动入库入口 MUST 共享同一套 OCR、段落 atom、语义切片、chunk 过滤和 hybrid collection 写入规则。向量入库阶段 MUST 只处理通过质量过滤的 CHILD chunk，并写入 Milvus hybrid collection。系统 MUST 保持 chunk 过滤诊断、阶段事件、失败记录和向量化状态可追溯。

#### Scenario: 手动上传使用统一 hybrid 入库策略

- **Given** 手动上传入口接收一份研报
- **When** 系统执行完整入库流程
- **Then** 该入口 MUST 复用统一 OCR、切片、过滤和 hybrid collection 写入逻辑
- **And** 被过滤 chunk MUST 记录过滤原因
- **And** 通过过滤的 CHILD chunk MUST 写入 Milvus hybrid collection

#### Scenario: 自动入库使用统一 hybrid 入库策略

- **Given** 自动入库入口接收一份研报
- **When** 系统执行完整入库流程
- **Then** 该入口 MUST 复用统一 OCR、切片、过滤和 hybrid collection 写入逻辑
- **And** 向量入库失败 MUST 记录阶段事件和失败原因
- **And** 成功写入 hybrid collection 后 chunk 才能标记为已向量化
