# ocr-markup-cleaning Specification

## Purpose
TBD - created by archiving change clean-ocr-markup-and-filter-noise-chunks. Update Purpose after archive.
## Requirements
### Requirement: OCR 请求 SHALL 优先约束为纯文本输出
系统 SHALL 在调用 DashScope OCR 时明确要求返回纯文本内容，并要求模型避免输出 Markdown 代码块、LaTeX 排版命令、页眉、页脚、页码、免责声明和联系方式等低价值内容。

#### Scenario: 请求 DashScope OCR
- **GIVEN** OCR endpoint 指向 DashScope 兼容接口
- **WHEN** 系统构造单页图片 OCR 请求
- **THEN** 请求 SHALL 包含纯文本输出约束
- **AND** 请求 SHALL 明确禁止输出 Markdown 代码围栏和 LaTeX 排版命令
- **AND** 请求 SHALL 要求表格转为可读纯文本行

#### Scenario: OCR 仍返回结构标记
- **GIVEN** OCR 请求已包含纯文本约束
- **WHEN** OCR 响应中仍包含 Markdown 或 LaTeX 标记
- **THEN** 系统 SHALL 继续进入服务端确定性清洗流程
- **AND** 系统 SHALL NOT 因模型未完全遵循格式约束而直接信任原文进入切片

### Requirement: OCR 清洗 SHALL 剥离 Markdown 和 LaTeX 结构标记
系统 SHALL 在生成段落 atom 前移除或转换 OCR 输出中的 Markdown/LaTeX 结构标记，且不得改写正文事实含义。

#### Scenario: 清洗 Markdown 代码围栏
- **GIVEN** OCR 文本包含 ` ```latex` 或 ` ````
- **WHEN** 系统执行 OCR 文本清洗
- **THEN** 清洗后文本 SHALL 不包含 Markdown 代码围栏
- **AND** 围栏内部的正文内容 SHALL 被保留

#### Scenario: 清洗 LaTeX 标题命令
- **GIVEN** OCR 文本包含 `\section*{铜牛信息}` 或 `\subsection*{投资要点}`
- **WHEN** 系统执行 OCR 文本清洗
- **THEN** 清洗后文本 SHALL 保留标题文字
- **AND** 清洗后文本 SHALL 不包含 `\section` 或 `\subsection` 命令

#### Scenario: 清洗 LaTeX 排版容器
- **GIVEN** OCR 文本包含 `\begin{flushright}`、`\end{flushright}`、`\begin{center}`、`\end{center}`、`\begin{itemize}` 或 `\end{itemize}`
- **WHEN** 系统执行 OCR 文本清洗
- **THEN** 清洗后文本 SHALL 删除这些排版容器命令
- **AND** 容器中的正文 SHALL 被保留

#### Scenario: 清洗 LaTeX 文本样式
- **GIVEN** OCR 文本包含 `\textbf{买入}` 或其他常见文本样式命令
- **WHEN** 系统执行 OCR 文本清洗
- **THEN** 清洗后文本 SHALL 保留样式命令中的文字
- **AND** 清洗后文本 SHALL 不包含对应样式命令

### Requirement: 页码标记 SHALL 只作为 metadata 保留
系统 SHALL 使用页级 OCR 结果和段落 atom 的 pageNumber 字段追溯页码，不得把 `[Page X]` 拼入用于切片、向量化或推荐证据的正文。

#### Scenario: 生成页级清洗文本
- **GIVEN** OCR 返回第 1 页文本
- **WHEN** 系统生成该页清洗结果
- **THEN** 页码 SHALL 写入 pageNumber 字段
- **AND** cleanedText SHALL NOT 以 `[Page 1]` 作为正文内容

#### Scenario: 生成 chunk 文本
- **GIVEN** chunk 来源段落覆盖第 1 页到第 2 页
- **WHEN** 系统生成 chunkText
- **THEN** chunk SHALL 通过 startPageNumber 和 endPageNumber 记录页码范围
- **AND** chunkText SHALL NOT 包含 `[Page 1]` 或 `[Page 2]`

### Requirement: LaTeX 表格 SHALL 转换为可读纯文本
系统 SHALL 将 OCR 输出中的 LaTeX 表格结构转换为可读纯文本，保留指标名称、年份、数值和必要单位。

#### Scenario: 清洗 tabular 表格
- **GIVEN** OCR 文本包含 `\begin{tabular}`、`\hline`、`&` 和 `\\`
- **WHEN** 系统执行 OCR 文本清洗
- **THEN** 清洗后文本 SHALL 不包含 `\begin{tabular}`、`\end{tabular}` 或 `\hline`
- **AND** 表格行中的指标名称、年份和数值 SHALL 以可读分隔形式保留

#### Scenario: 表格包含财务预测指标
- **GIVEN** OCR 表格包含营业收入、归母净利润、EPS、P/E 或 P/B 等指标
- **WHEN** 系统完成清洗
- **THEN** 这些指标名称和对应数值 SHALL 保留在清洗后文本中
- **AND** 系统 SHALL NOT 因其来自 LaTeX 表格而直接删除整段内容

### Requirement: 清洗诊断 SHALL 记录标记清洗情况
系统 SHALL 在 OCR 清洗诊断中记录 Markdown/LaTeX 标记清洗数量或类别，供质量导出和问题定位使用。

#### Scenario: 记录清洗诊断
- **GIVEN** OCR 文本包含 Markdown 和 LaTeX 标记
- **WHEN** 系统完成 OCR 清洗
- **THEN** diagnostics SHALL 记录已清洗的标记类别
- **AND** 质量导出 SHALL 能展示该页是否发生结构标记清洗

