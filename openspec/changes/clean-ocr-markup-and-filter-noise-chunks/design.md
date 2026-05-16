## Context

当前导入链路已经沉淀 OCR 页级文本、段落 atom、chunk 诊断和搜索评估数据。真实研报导入暴露出新的质量问题：DashScope OCR 在文档解析任务中倾向输出 LaTeX/Markdown 风格的版式还原文本，当前清洗逻辑只处理换行、噪声行和基础段落合并，导致结构标记进入 chunk 和 Milvus。已有设计坚持“LLM 只规划边界，不改写正文”，因此需要在 OCR 清洗层和 Service 过滤层补强，而不是让切片 LLM 直接改写文本。

## Goals / Non-Goals

**Goals:**

- 让 OCR 请求尽量输出纯文本，减少 LaTeX/Markdown 标记。
- 在服务端确定性清洗 ` ```latex`、`\begin{...}`、`\end{...}`、`\section*{...}`、`\subsection*{...}`、`\textbf{...}`、`\item` 等常见标记。
- 将 `[Page X]` 从正文中移除，仅保留在 pageNumber、startPageNumber、endPageNumber 等 metadata 中。
- 将 LaTeX 表格尽量转换为可读纯文本，保留财务指标、年份和数值。
- 让切片 LLM 输出更细粒度的 segmentType，帮助识别正文、财务表格、噪声和低价值附录内容。
- Service 层根据 segmentType、sectionPath、文本规则和质量配置决定是否入库/入向量。
- 确保质量导出能体现“清洗了什么、过滤了什么、保留了什么”。

**Non-Goals:**

- 不让 LLM 生成或改写 chunk 正文。
- 不实现复杂表格结构抽取为关系型财务模型。
- 不接入新的 OCR 服务商或商业 rerank 模型。
- 不改变上传和推荐 API 的请求/响应协议。
- 不要求历史数据自动迁移；历史脏数据可通过重新导入获得新质量结果。

## Decisions

### 1. OCR 请求层做软约束，清洗层做硬兜底

DashScope OCR 请求中加入文本指令，要求只输出纯文本，不输出 Markdown 代码块、LaTeX 命令、页眉页脚、免责声明、联系方式等低价值内容。由于多模态模型不保证完全遵循格式约束，`ReportOcrParseService` 必须继续提供确定性清洗兜底。

### 2. LaTeX/Markdown 清洗不改变正文语义

清洗层只移除或转换结构标记：

- `\section*{标题}` 转为 `标题`
- `\subsection*{标题}` 转为 `标题`
- `\textbf{文本}` 转为 `文本`
- `\begin{itemize}`、`\end{itemize}`、`\begin{flushright}`、`\end{flushright}` 等排版容器删除
- `\item` 转为自然段起始
- `\begin{tabular}`、`\end{tabular}`、`\hline` 删除，`&` 和 `\\` 转为可读分隔和换行
- Markdown 代码围栏删除

这保持“清洗格式，不改写事实”的边界。

### 3. 页码标记进入 metadata，不进入正文

当前 `[Page X]` 是系统为了追溯页码主动拼入清洗全文的标记。后续切片应直接使用 `ParagraphAtom.pageNumber`，chunk 通过 `startPageNumber/endPageNumber` 追溯页码，不再把 `[Page X]` 拼入 `cleanedText`、`paragraphText` 或 `chunkText`。

### 4. 切片 LLM 负责类型识别，不负责删除

切片 Prompt 扩展 segmentType 枚举，例如：

- `REPORT_BODY`
- `INVESTMENT_VIEW`
- `FINANCIAL_FORECAST`
- `FINANCIAL_TABLE`
- `RISK`
- `DISCLAIMER`
- `ANALYST_DECLARATION`
- `BROKER_PROFILE`
- `CONTACT_INFO`
- `LAYOUT_NOISE`
- `OTHER`

LLM 输出的 segmentType 需要进入 chunk slice 或诊断数据，供 Service 层做过滤判断。LLM 不返回正文、不删除段落、不改写原文。

### 5. 财务表格保留策略优先于低汉字比例过滤

财务表格天然数字和符号多，不能只用汉字比例过滤。若 sectionPath 或正文包含“盈利预测、财务指标、财务报表、主要财务比率、利润表、资产负债表、现金流量表、EPS、P/E、P/B”等信号，应标记为财务表格候选；在 token 数和噪声比例可接受时保留，并记录 `TABLE_LIKE_FINANCIAL_DATA` 或类似诊断。

### 6. 低价值附录默认过滤

以下内容默认不进入 Milvus：

- 免责声明、免责条款、法律声明
- 投资评级说明
- 分析师声明
- 研究所联系方式
- 券商简介、机构介绍
- 纯页眉页脚、页码、水印、排版残留

是否保留 MySQL 诊断由现有质量表负责；是否入向量由过滤结果决定。

## Risks / Trade-offs

- OCR 指令可能不稳定生效 -> 必须保留服务端清洗兜底。
- 正则清洗过度可能误删正文中的反斜杠或数学表达 -> 规则应优先覆盖常见 OCR 结构标记，并通过样例测试约束。
- 财务表格保留会引入更多数字文本 -> 需要与低价值表格、免责声明表格区分。
- segmentType 由 LLM 输出，存在误判 -> Service 层仍需确定性关键词和质量指标兜底。
- 清洗后 token 数会变化 -> 相关测试需要覆盖页码、段落范围、chunk token 和过滤原因。

## Migration Plan

1. 对新导入数据启用 OCR 请求约束和确定性清洗。
2. 对历史脏数据不做自动迁移；如需分析，使用脚本重新导入。
3. 质量脚本继续读取相同表结构，但会看到更新后的清洗文本、chunk 文本和诊断原因。
4. 部署前用东方财富样例 PDF 回归验证：chunkText 不包含 ` ```latex`、`\begin`、`\section*`、`\textbf`、`[Page X]`。

## Open Questions

- 财务表格是否只保留纯文本，还是后续需要抽成结构化 JSON/表格字段？
- `segmentType` 是否需要持久化到 `report_chunk` 主表，还是仅写入 diagnostics？
- 对券商简介和联系方式是否允许用户配置保留，还是始终过滤？
