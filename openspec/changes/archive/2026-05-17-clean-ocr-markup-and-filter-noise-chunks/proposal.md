## Why

真实导入东方财富 5 页研报后，质量导出显示 OCR 原始文本中包含大量 ` ```latex`、`\begin{...}`、`\section*{...}`、`\textbf{...}` 和 `[Page X]` 等结构化标记，当前清洗层未充分剥离，导致段落 atom、chunk、Milvus 向量文本和 Excel 导出被污染。与此同时，财务表格被 `LOW_HAN_RATIO` 过滤，而分析师声明、券商简介、研究所联系方式等低价值内容仍可能进入向量库，影响检索质量。

## What Changes

- 约束 OCR 请求优先返回纯文本，降低 Markdown/LaTeX 结构标记进入系统的概率。
- 增加确定性 OCR 清洗规则，移除 Markdown 代码块、LaTeX 排版命令、标题命令和页码标记，并将常见 LaTeX 表格转换为可读纯文本。
- 明确 `[Page X]` 仅作为页码 metadata 参与追溯，不进入用于切片、向量化或推荐证据的正文。
- 扩展切片规划的内容类型识别，让 LLM 标记正文、财务表格、风险提示、免责声明、分析师声明、券商简介、联系方式和版式噪声等类型。
- 由 Service 层基于确定性规则和 LLM 标记做最终过滤，避免让 LLM 改写或删除正文。
- 调整财务表格保留策略，避免有价值的盈利预测、财务指标和主要财务比率因中文比例低被直接过滤。
- 增强质量导出中的诊断字段，使脚本能区分 OCR 标记清洗、噪声过滤、财务表格保留和低价值内容过滤原因。

## Capabilities

### New Capabilities
- `ocr-markup-cleaning`: 规范 OCR 输出和清洗行为，确保 LaTeX/Markdown/页码等结构标记不会污染正文、chunk 和向量文本。
- `noise-chunk-filtering`: 规范切片类型标记、低价值内容过滤和财务表格保留策略，提升向量入库内容纯度。

### Modified Capabilities
- 无。

## Impact

- 影响 `common.ocr.OcrClient` 的 DashScope OCR 请求内容组织。
- 影响 `ReportOcrParseService` 的 OCR 文本清洗、页级文本、段落 atom 生成和诊断记录。
- 影响 `ReportSemanticChunkService` 的切片 Prompt、segmentType 约束和边界规划输入。
- 影响 `SemanticChunkUtils`、`ReportIngestService` 的 chunk 构造、过滤原因和 metadata 传递。
- 影响 `report_ocr_page`、`report_paragraph_atom`、`report_chunk_diagnostic` 等质量分析数据的内容质量，但不要求新增外部依赖。
- 影响脚本导出的 Excel 分析结果，使其能显示清洗后纯文本和更准确的过滤/保留原因。
