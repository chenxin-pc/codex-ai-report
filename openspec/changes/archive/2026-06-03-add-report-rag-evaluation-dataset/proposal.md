## Why

当前研报 RAG 链路已有导入质量、检索质量、输入护栏、证据质量和搜索评估脚本，但评估仍主要围绕临时 query 与人工复核字段，缺少可复现的 eval case、标准证据、运行快照和可对接 Ragas 的结构化数据。

本变更将评测数据从业务数据中独立出来，形成稳定的评测领域模型：业务表只作为评测资产来源，评测表保存判卷所需的快照，避免重导入、重切片、标签升级或 prompt 修改导致历史评测不可解释。

## What Changes

- 新增评测语料集能力，管理 eval corpus、corpus report、eval case、标准锚点、标准证据、禁止项和可复现快照。
- 新增评测运行能力，按 eval case 调用真实推荐链路或受控评测入口，记录输入判定、检索轨迹、证据质量、生成输出、运行配置和模型/prompt 快照。
- 新增 Ragas 导出能力，将 eval case 与 eval run 结果转换为 `user_input`、`retrieved_contexts`、`response`、`reference`、`reference_contexts` 等字段。
- 扩展现有搜索评估脚本，使其支持从 eval case 集合驱动评估，并输出自动判分、人工复核和 Ragas 导出文件。
- 评测域允许冗余业务字段快照，包括 report 标题、chunk 文本、标签、sectionPath、page range、模型版本、词典版本和检索配置。
- 不改变线上推荐接口的用户语义；评测入口和脚本应复用真实链路，但评测数据写入独立 `eval_*` 表或独立输出文件。

## Capabilities

### New Capabilities

- `report-evaluation-dataset`: 管理研报 RAG 评测语料、case schema、标准锚点、标准证据和禁止项。
- `report-evaluation-run`: 执行 eval case 并记录检索轨迹、生成响应、证据质量、自动判分和运行快照。
- `script-ragas-export`: 将评测运行结果导出为 Ragas 可消费的数据集文件和指标输入。

### Modified Capabilities

- `script-search-evaluation`: 搜索评估脚本需要支持 eval case 数据源、标准答案字段、自动判分结果和 Ragas 导出入口。

## Impact

- 数据库：新增独立 `eval_*` 表，评测表通过 `report_id`、`chunk_uid`、`parent_chunk_uid` 与业务数据弱关联，并冗余判卷所需快照。
- 后端：新增或扩展评测查询/运行服务，复用推荐、检索、证据护栏和导入质量查询能力。
- 脚本：扩展 `scripts/report-ingest-analysis` 或新增评测脚本模块，用于构建评测语料、执行 eval run、生成 Excel/JSONL/Ragas 数据。
- 配置：需要记录 corpus version、dictionary version、embedding model、LLM model、prompt hash、retrieval config 和 app commit。
- 外部依赖：Ragas 作为后续可选评测组件；本变更先保证数据结构可导出，不要求在线链路直接依赖 Ragas。
