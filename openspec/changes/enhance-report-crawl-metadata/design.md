## Context

当前批量研报导入脚本承担三件事：从配置来源收集 PDF、上传到后端导入入口、导出质量和搜索评估结果。已有后端上传入口支持 `themeTags`、`industryTags`、`companyTags` 和 `tickerTags`，并会在异步 OCR 阶段把这些导入表单标签写入报告级标签表；但脚本的模块化实现只稳定读取和上传标题、来源、机构、发布日期等基础字段。

本变更聚焦爬取侧元数据增强：网站或 API 能直接给出的字段就写入清单，拿不到就留空。用户已明确本期不需要从 PDF/OCR 文本中提取作者或主题，也不需要改变导入后的 OCR、切片、chunk 标签抽取、向量写入和检索逻辑。

## Goals / Non-Goals

**Goals:**

- 在脚本输入清单、运行状态和可观测输出中标准化保存网站直取元数据：`pages`、`authors`、`tickerTags`、`companyTags`、`industryTags`、`themeTags`。
- 支持从 URL 清单、东方财富清单或未来网站/API 响应中读取这些可选字段。
- 确保爬取导入研报的 `title` 是网站/API 或用户显式清单提供的真实研报标题。
- 上传时透传后端当前已支持的四类标签字段：`themeTags`、`industryTags`、`companyTags`、`tickerTags`。
- 字段缺失时保持空值并继续下载、上传和导入，不把缺失元数据视为采集失败。
- 保持后端导入后处理链路不变。

**Non-Goals:**

- 不从 PDF 首页、OCR 文本或 chunk 文本中提取作者、主题、行业、公司或股票代码。
- 不引入 LLM 推断或规则推断来补齐 `themeTags`。
- 不修改数据库 schema，不要求 `pages` 或 `sourceUrl` 写入 `report_document`。
- 不修改 `report_chunk_tag`、`report_document_tag` 的导入后抽取和聚合规则。
- 不修改 Milvus metadata schema 或推荐检索逻辑。

## Decisions

1. **把新增字段定义为可选爬取元数据**
   - 清单标准列为：`url,title,source,institution,publishDate,pages,authors,tickerTags,companyTags,industryTags,themeTags`。
   - 可选字段为空时不报错、不跳过、不推断。
   - 备选方案是强制补齐标签后再导入，但会把爬取质量和导入可用性耦合过重，因此不采用。

2. **作者只从网站/API 元数据直取**
   - `authors` 可读取 `authors`、`author`、`researcher` 等来源字段并归一到同一列。
   - 多作者保留为分隔字符串，第一版不拆表、不做作者实体主数据。
   - 备选方案是从 OCR 首页提取分析师署名，但这会引入正文提取和误判风险，不符合本期边界。

3. **标题必须真实，不允许文件名兜底**
   - 爬取导入时 `title` 只能来自网站/API 的标题字段，或用户在 URL 清单中显式填写的真实标题。
   - 当标题为空，或标题明显是下载文件名、URL 文件名、自动生成名（例如 `H3_AP..._1`、`report-01.pdf`、纯 PDF stem）时，该条输入不得上传导入，应记录采集失败或跳过原因。
   - 备选方案是继续用文件名兜底，但这会污染 `report_document.title`、标题去重、观测列表和检索展示，因此不采用。

4. **标签字段只传递，不在脚本内推断**
   - `tickerTags`、`companyTags`、`industryTags`、`themeTags` 只来自网站/API 或用户清单显式列。
   - 脚本不使用标题兜底生成主题，不使用机构兜底生成公司，避免污染报告级标签。
   - 备选方案是基于标题关键字推断主题，但主题准确性依赖词库和上下文，留给后续专门变更。

5. **上传只覆盖后端现有能力**
   - `themeTags`、`industryTags`、`companyTags`、`tickerTags` 通过现有 multipart 表单字段上传。
   - `authors` 非空时通过后端现有作者字段上传。
   - `pages` 和 `sourceUrl` 暂时只保留在脚本状态和输出中。
   - 备选方案是同步扩展后端 schema，但这会让变更跨越脚本、数据库、接口和 metadata，不符合本期“只改爬取”的收敛目标。

6. **保持模块化脚本实现一致**
   - 以 `scripts/report-ingest-analysis/lib/*` 为实际实现来源，避免只修改 `commands/pipeline.py` 中已被覆盖的旧逻辑。
   - README、配置示例和脚本测试需要同步体现新增列。

## Risks / Trade-offs

- [Risk] 东方财富不同研报类型的字段不一致，个股研报容易拿到代码/公司/行业，宏观和策略研报更容易拿到作者但没有 ticker。→ Mitigation：所有新增字段均可空，并在测试中覆盖缺失字段场景。
- [Risk] `themeTags` 来源不稳定，直接使用网站字段可能覆盖率低。→ Mitigation：本期明确不追求主题补全，字段缺失保持空值，后续如需提升再独立设计抽取或推断。
- [Risk] 作者多值分隔格式不统一。→ Mitigation：第一版只做字符串清洗，不拆分语义；README 约定推荐使用分号或逗号分隔。
- [Risk] 部分来源只能拿到 PDF URL，拿不到真实标题。→ Mitigation：这类输入不得上传导入，记录明确失败原因，避免用文件名伪造标题污染数据库。
- [Risk] 只在脚本状态中保存 `pages/sourceUrl`，后端查询不到这些字段。→ Mitigation：在文档中明确这是本期边界；若后续需要展示或过滤，再新增后端持久化变更。
