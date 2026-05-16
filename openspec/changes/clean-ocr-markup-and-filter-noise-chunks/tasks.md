## 1. OCR 请求与清洗策略

- [x] 1.1 调整 DashScope OCR 请求内容，增加纯文本输出、禁止 Markdown/LaTeX 标记、表格转纯文本的提示。
- [x] 1.2 在 OCR 清洗流程中增加 Markdown 代码围栏清理。
- [x] 1.3 在 OCR 清洗流程中增加 LaTeX 标题、样式、排版容器和列表命令清理。
- [x] 1.4 在 OCR 清洗流程中增加 LaTeX 表格转可读纯文本逻辑。
- [x] 1.5 调整页码处理，使 `[Page X]` 不进入 cleanedText、paragraphText、chunkText，仅保留在 metadata 字段。
- [x] 1.6 在 OCR diagnostics 中记录清洗的结构标记类别和数量。

## 2. 切片类型与过滤决策

- [x] 2.1 扩展切片 Prompt 的 segmentType 枚举，覆盖正文、财务表格、免责声明、分析师声明、券商简介、联系方式和版式噪声。
- [x] 2.2 将 LLM 返回的 segmentType 传递到 chunk slice 或诊断数据中。
- [x] 2.3 在 Service 层增加基于 segmentType 的过滤兜底规则，并保留 sectionPath/关键词/质量指标兜底。
- [x] 2.4 扩展低价值附录过滤关键词，覆盖分析师声明、研究所联系方式、券商简介和机构介绍。
- [x] 2.5 增加财务表格保留策略，避免盈利预测和财务指标因 LOW_HAN_RATIO 被误杀。

## 3. 数据质量与脚本导出

- [x] 3.1 确认质量导出 Excel 中 OCR 页、段落 atom、chunk 和 filtered_chunks 展示清洗后的中文正文。
- [x] 3.2 确认推荐搜索结果不再展示 Markdown/LaTeX 标记和 `[Page X]`。
- [x] 3.3 使用东方财富样例 PDF 回归验证单篇导入质量。
- [x] 3.4 如现有诊断字段不足，补充 diagnostics 内容，但不新增无必要表结构。

## 4. 测试与验证

- [x] 4.1 为 OCR 清洗补充单元测试，覆盖代码围栏、LaTeX 标题、样式命令、排版容器和表格转换。
- [x] 4.2 为页码 metadata 补充单元测试，确认 chunkText 不包含 `[Page X]`。
- [x] 4.3 为切片 segmentType 传递和过滤原因补充单元测试。
- [x] 4.4 为财务表格保留和低价值附录过滤补充边界测试。
- [x] 4.5 运行 `mvn -q test`。
- [x] 4.6 运行 `openspec validate --all --strict`。
