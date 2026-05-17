# report-ingest-quality Specification

## Purpose
TBD - created by archiving change optimize-report-ingest-retrieval-quality. Update Purpose after archive.
## Requirements
### Requirement: OCR 结果 MUST 保留原始页级文本和清洗后文本

系统 MUST 为每篇导入研报保留 OCR 原始全文、页级 OCR 文本、清洗后全文和页级诊断信息。清洗逻辑 MUST 不改写正文语义，只能删除或标记页眉、页脚、页码、目录、免责声明、水印和明显噪声。

#### Scenario: 成功识别 PDF 页面

- **GIVEN** 用户上传包含多页内容的 PDF 研报
- **WHEN** OCR 服务返回每页文本
- **THEN** 系统 MUST 记录每页页码和原始 OCR 文本
- **AND** 系统 MUST 生成清洗后文本
- **AND** 系统 MUST 保留清洗诊断信息

#### Scenario: OCR 返回空文本

- **GIVEN** 用户上传的文件通过基础校验
- **WHEN** OCR 服务返回空全文且没有可用页级文本
- **THEN** 系统 MUST 终止导入
- **AND** 系统 MUST 返回明确的 OCR 无可用文本错误
- **AND** 系统 MUST 不写入报告 chunk 和 Milvus 向量

#### Scenario: OCR 页面存在疑似噪声

- **GIVEN** OCR 页级文本包含页码、页眉、页脚或重复免责声明
- **WHEN** 系统执行 OCR 清洗
- **THEN** 系统 MUST 在清洗后文本中移除或降低这些噪声对切片的影响
- **AND** 系统 MUST 在诊断信息中记录被识别的噪声类型

### Requirement: 段落 atom MUST 支持质量分析追溯

系统 MUST 将清洗后的 OCR 文本拆解为段落 atom，并保留 paragraphId、pageNumber、sectionPath、text、tokenCount 和清洗诊断。段落 atom MUST 可被语义切片服务使用，也 MUST 可被后续脚本从数据库或受控查询能力中读取。

#### Scenario: 生成段落 atom

- **GIVEN** OCR 清洗后文本包含多个自然段落
- **WHEN** 系统执行段落拆解
- **THEN** 系统 MUST 为每个段落分配连续 paragraphId
- **AND** 每个段落 atom MUST 保留页码、章节路径、文本和 tokenCount

#### Scenario: 脚本读取段落 atom

- **GIVEN** 一篇报告已成功导入
- **WHEN** 后续脚本按 reportId 拉取质量分析数据
- **THEN** 系统 MUST 能提供该报告的段落 atom 数据
- **AND** 数据 MUST 足以追溯 chunk 的来源段落

### Requirement: 切片结果 MUST 可追溯到段落和页码

系统 MUST 在语义切片结果中保留段落范围、页码范围、sectionPath、chunkType、tokenCount 和 chunkText。LLM 只 MUST 规划边界，不得改写 OCR 原文来生成切片正文。

#### Scenario: LLM 返回合法切片边界

- **GIVEN** 系统已生成连续的段落 atom
- **WHEN** LLM 返回覆盖所有 paragraphId 的语义边界
- **THEN** 系统 MUST 基于原始段落文本生成 PARENT 和 CHILD chunk
- **AND** 每个 chunk MUST 保留 sectionPath、tokenCount、段落范围和页码范围

#### Scenario: LLM 返回边界不连续

- **GIVEN** 系统已生成连续的段落 atom
- **WHEN** LLM 返回的边界存在遗漏、重叠或越界
- **THEN** 系统 MUST 执行边界合法性校验和修复
- **AND** 系统 MUST 记录修复诊断
- **AND** 修复后仍无法连续覆盖时 MUST 终止导入并返回明确错误

### Requirement: Chunk 过滤 MUST 记录原因

系统 MUST 在过滤低语义 chunk、免责声明、评级说明、异常短文本或噪声文本时记录过滤原因。过滤诊断 MUST 可被后续脚本从数据库或受控查询能力中读取，用于生成离线分析报告。

#### Scenario: 过滤免责声明 chunk

- **GIVEN** 切片结果包含 sectionPath 为免责声明或法律声明的 chunk
- **WHEN** 系统执行入库前过滤
- **THEN** 系统 MUST 不将该 chunk 写入 Milvus
- **AND** 系统 MUST 记录过滤原因为免责声明或法律声明

#### Scenario: 过滤低语义 chunk

- **GIVEN** 切片结果包含 token 数过低或噪声比例过高的 chunk
- **WHEN** 系统执行入库前过滤
- **THEN** 系统 MUST 不将该 chunk 写入 Milvus
- **AND** 系统 MUST 记录低语义过滤原因和关键诊断指标

### Requirement: 质量数据 MUST 支持脚本按报告查询

系统 MUST 提供数据库结构或受控查询能力，使后续脚本能够按 reportId 查询报告元数据、OCR 页级结果、段落 atom、PARENT/CHILD chunk、过滤诊断和向量入库状态。本能力 MUST 不要求在线服务自动生成 Excel。

#### Scenario: 按 reportId 查询导入质量数据

- **GIVEN** 一篇报告已成功导入
- **WHEN** 后续脚本按 reportId 拉取导入质量数据
- **THEN** 系统 MUST 能提供该报告的 OCR、段落、chunk 和过滤诊断数据
- **AND** 数据 MUST 包含足够主键或 UID 用于关联各层结果

#### Scenario: 导入失败时保留失败诊断

- **GIVEN** 一篇报告在 OCR、切片、MySQL 或 Milvus 阶段失败
- **WHEN** 后续脚本或运维人员查询失败原因
- **THEN** 系统 MUST 能提供失败阶段和错误摘要
- **AND** 系统 MUST 不把失败导入伪装成成功导入

