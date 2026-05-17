# noise-chunk-filtering Specification

## Purpose
TBD - created by archiving change clean-ocr-markup-and-filter-noise-chunks. Update Purpose after archive.
## Requirements
### Requirement: 切片规划 SHALL 标记内容类型
系统 SHALL 要求语义切片模型在规划边界时输出可用于过滤和诊断的 segmentType，并将该类型传递到 chunk 诊断或持久化数据中。

#### Scenario: 标记正文语义段
- **GIVEN** 段落 atom 描述投资观点、公司分析或盈利预测正文
- **WHEN** LLM 返回切片规划
- **THEN** segmentType SHALL 标记为对应正文类型
- **AND** 系统 SHALL 保留该类型用于后续 chunk 诊断

#### Scenario: 标记低价值附录内容
- **GIVEN** 段落 atom 描述免责声明、投资评级说明、分析师声明、券商简介、研究所联系方式或版式噪声
- **WHEN** LLM 返回切片规划
- **THEN** segmentType SHALL 标记为可过滤类型
- **AND** 系统 SHALL NOT 让 LLM 删除或改写这些段落正文

### Requirement: Service 层 SHALL 决定 chunk 是否入向量
系统 SHALL 由 Service 层基于 segmentType、sectionPath、文本质量指标和配置规则决定 chunk 是否写入 Milvus，LLM 不得直接负责删除正文。

#### Scenario: LLM 标记免责声明
- **GIVEN** LLM 将某个 segment 标记为 DISCLAIMER
- **WHEN** 系统生成对应 chunk
- **THEN** Service 层 SHALL 记录过滤原因
- **AND** 该 chunk SHALL NOT 写入 Milvus

#### Scenario: LLM 类型缺失或误判
- **GIVEN** segmentType 为空、OTHER 或与 sectionPath 不一致
- **WHEN** Service 层执行过滤判断
- **THEN** 系统 SHALL 继续使用 sectionPath、关键词和文本质量指标兜底判断
- **AND** 系统 SHALL NOT 只依赖 LLM 类型做最终决策

### Requirement: 低价值附录 chunk SHALL 默认过滤
系统 SHALL 默认过滤对研报推荐检索价值低的附录类内容，且保留诊断记录用于质量分析。

#### Scenario: 过滤声明类内容
- **GIVEN** chunk 内容或 sectionPath 表示免责声明、免责条款、法律声明、投资评级说明或分析师声明
- **WHEN** 系统执行入库前过滤
- **THEN** 该 chunk SHALL NOT 写入 Milvus
- **AND** 过滤诊断 SHALL 说明具体命中的声明类型

#### Scenario: 过滤联系方式和券商简介
- **GIVEN** chunk 内容或 sectionPath 表示研究所联系方式、邮箱地址、办公地址、券商简介或机构介绍
- **WHEN** 系统执行入库前过滤
- **THEN** 该 chunk SHALL NOT 写入 Milvus
- **AND** 过滤诊断 SHALL 说明该 chunk 属于低价值附录内容

#### Scenario: 过滤纯版式噪声
- **GIVEN** chunk 主要由页眉、页脚、页码、水印、排版残留或结构标记组成
- **WHEN** 系统执行入库前过滤
- **THEN** 该 chunk SHALL NOT 写入 Milvus
- **AND** 系统 SHALL 在诊断中记录噪声原因

### Requirement: 财务表格 chunk SHALL 避免被低汉字比例误杀
系统 SHALL 对盈利预测、财务指标和主要财务报表类表格使用专门保留策略，不得仅因汉字比例低而过滤。

#### Scenario: 保留盈利预测表格
- **GIVEN** chunk 包含盈利预测、营业收入、归母净利润、EPS、P/E 或 P/B 等财务指标
- **WHEN** 系统执行低语义过滤
- **THEN** 系统 SHALL 将其识别为财务表格候选
- **AND** 系统 SHALL NOT 仅因 LOW_HAN_RATIO 过滤该 chunk

#### Scenario: 财务表格仍为噪声
- **GIVEN** 表格 chunk 只有空列、页码、排版符号或无法解释的数字碎片
- **WHEN** 系统执行质量判断
- **THEN** 系统 SHALL 可以过滤该 chunk
- **AND** 过滤原因 SHALL 区分为表格噪声而不是普通低汉字比例

### Requirement: 推荐证据 SHALL 使用清洗后的正文
系统 SHALL 确保写入 Milvus 和推荐证据展示的 chunkText 已经过 OCR 标记清洗和噪声过滤。

#### Scenario: 检索返回 chunk
- **GIVEN** 用户提交推荐 query
- **WHEN** Milvus 返回相关 chunk
- **THEN** topResults 中的 chunkText SHALL NOT 包含 ` ```latex`、`\begin`、`\end`、`\section`、`\subsection` 或 `[Page X]`
- **AND** chunkText SHALL 保留必要的正文、财务指标和风险提示内容

#### Scenario: 质量导出检查 chunk
- **GIVEN** 脚本导出导入质量 Excel
- **WHEN** 人工检查 chunks sheet
- **THEN** chunk 文本 SHALL 展示清洗后的可读正文
- **AND** filtered_chunks sheet SHALL 展示被过滤噪声的具体原因

