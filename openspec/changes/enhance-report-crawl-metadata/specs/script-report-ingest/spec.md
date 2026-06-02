## ADDED Requirements

### Requirement: 脚本 MUST 使用真实研报标题

脚本在爬取导入研报时 MUST 使用网站/API 或用户显式清单提供的真实研报标题。脚本 MUST NOT 使用 PDF 文件名、URL 文件名、下载生成名、空标题或明显的文件 stem 伪造 `title`。当真实标题不可用时，脚本 MUST 将该条输入标记为采集失败或跳过上传，并 MUST 记录明确原因，避免把伪造标题写入后端 `report_document.title`。

#### Scenario: 网站提供真实研报标题

- **GIVEN** 网站/API 或 URL 清单提供真实研报标题
- **WHEN** 用户运行批量导入脚本收集研报
- **THEN** 脚本 MUST 使用该真实标题写入输入清单
- **AND** 脚本 MAY 继续下载并上传该研报

#### Scenario: 只有 PDF 文件名没有真实标题

- **GIVEN** 某条爬取输入只有 PDF URL 或本地下载文件名
- **AND** 标题为空或标题形如 `H3_AP202605171822381467_1`、`report-01.pdf` 或其他自动生成文件 stem
- **WHEN** 脚本归一化研报元数据
- **THEN** 脚本 MUST NOT 将文件名或文件 stem 当作研报标题
- **AND** 脚本 MUST 将该条输入标记为采集失败或跳过上传
- **AND** 脚本 MUST 记录缺少真实研报标题的原因

#### Scenario: 清单显式提供标题但文件名为生成名

- **GIVEN** URL 清单中的 PDF 文件名为自动生成名
- **AND** 清单显式提供真实研报标题
- **WHEN** 脚本归一化研报元数据
- **THEN** 脚本 MUST 使用清单中的真实研报标题
- **AND** 脚本 MUST NOT 使用 PDF 文件名覆盖该标题

### Requirement: 脚本 MUST 支持网站直取的可选研报元数据列

脚本 MUST 在输入清单、采集结果和运行状态中支持网站或 API 直接提供的可选元数据列。标准可选列 MUST 包含 `pages`、`authors`、`tickerTags`、`companyTags`、`industryTags` 和 `themeTags`。这些字段 MUST 仅来自网站/API 响应或用户显式清单列；系统 MUST NOT 为这些字段执行 OCR 提取、正文解析、LLM 推断或 chunk 级标签抽取。字段缺失时 MUST 保留为空值，并且 MUST NOT 阻断 PDF 下载、上传或后端导入。

#### Scenario: URL 清单包含完整可选元数据

- **GIVEN** URL 清单包含 `url`、`title`、`source`、`institution`、`publishDate`、`pages`、`authors`、`tickerTags`、`companyTags`、`industryTags` 和 `themeTags`
- **WHEN** 用户运行批量导入脚本收集研报
- **THEN** 脚本 MUST 将这些字段写入本次运行的输入清单
- **AND** 脚本 MUST 保留原始可选元数据值供运行状态和后续输出查看

#### Scenario: 网站未提供部分可选元数据

- **GIVEN** 网站或 URL 清单只提供 `url`、`title` 和 `publishDate`
- **WHEN** 用户运行批量导入脚本收集研报
- **THEN** 脚本 MUST 将缺失的 `pages`、`authors`、`tickerTags`、`companyTags`、`industryTags` 和 `themeTags` 记录为空值
- **AND** 脚本 MUST 继续执行下载和上传流程
- **AND** 脚本 MUST NOT 将这些字段缺失判定为采集失败

#### Scenario: 研报作者仅从网站字段读取

- **GIVEN** 网站或清单提供 `authors`、`author` 或 `researcher` 字段
- **WHEN** 脚本归一化研报元数据
- **THEN** 脚本 MUST 将网站字段值清洗后写入 `authors`
- **AND** 脚本 MUST NOT 从 PDF、OCR 文本或 chunk 文本中提取作者

### Requirement: 脚本 MUST 透传后端已支持的导入标签字段

脚本 MUST 在上传研报时透传后端上传接口已支持的 `themeTags`、`industryTags`、`companyTags` 和 `tickerTags` 字段。脚本 MUST NOT 将 `authors`、`pages` 或 `sourceUrl` 当作后端必填字段上传，也 MUST NOT 要求后端为这些字段新增持久化能力。上传后，系统 MUST 保持既有 OCR、语义切片、chunk 标签抽取、向量入库和推荐检索逻辑不变。

#### Scenario: 清单包含四类导入标签

- **GIVEN** 输入清单中的某篇研报包含 `themeTags`、`industryTags`、`companyTags` 和 `tickerTags`
- **WHEN** 脚本向后端上传该研报
- **THEN** 脚本 MUST 将四类标签作为 multipart 表单字段提交给后端上传接口
- **AND** 后端现有导入链路 MUST 接收这些字段并按既有导入表单标签规则处理

#### Scenario: 清单不包含导入标签

- **GIVEN** 输入清单中的某篇研报没有 `themeTags`、`industryTags`、`companyTags` 或 `tickerTags`
- **WHEN** 脚本向后端上传该研报
- **THEN** 脚本 MUST 省略空标签字段或提交为空值
- **AND** 后端导入任务 MUST 继续按无显式标签的既有逻辑执行

#### Scenario: 新增爬取元数据不改变导入后链路

- **GIVEN** 脚本输入清单包含 `authors`、`pages` 和四类可选标签
- **WHEN** 后端导入任务进入 OCR、CHUNK 和 VECTOR 阶段
- **THEN** 系统 MUST 使用既有 OCR、语义切片、chunk 标签抽取和向量入库逻辑
- **AND** 系统 MUST NOT 因为 `authors` 或 `pages` 字段而新增 OCR 提取、chunk 级主题抽取或 Milvus metadata 写入行为
