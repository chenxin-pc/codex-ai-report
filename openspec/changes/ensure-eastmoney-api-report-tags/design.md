## Context

当前脚本已经支持读取和上传 `companyTags`、`tickerTags`、`industryTags`、`themeTags`，但现有东方财富 URL 清单只包含 `url`、`title`、`source`、`institution`、`publishDate`、`pages`，无法保证导入后的研报具备公司、代码和行业标签。

东方财富研报列表 API `https://reportapi.eastmoney.com/report/list` 返回的个股研报记录包含 `stockName`、`stockCode`、`indvInduName`、`industryName`、`researcher`、`author`、`attachPages`、`infoCode` 等字段。该 API 可以作为更完整的采集入口，用于替代只保存 PDF URL 的清单来源。主题字段目前没有在样本响应中稳定出现，因此不能作为强制标签来源。

## Goals / Non-Goals

**Goals:**

- 新增东方财富 API 采集路径，直接生成包含公司、代码、行业、作者、页数和 PDF URL 的标准输入记录。
- 对东方财富 API 采集结果执行导入前校验，保证成功上传的记录一定具有 `companyTags`、`tickerTags` 和 `industryTags`。
- 继续支持 `themeTags` 可选直取：API 或页面直接返回时导入，没有则为空。
- 在运行状态和摘要中暴露元数据覆盖情况，让每次爬取后能确认标签质量。

**Non-Goals:**

- 不从 PDF、OCR 文本、切片文本或标题中提取公司、代码、行业或主题。
- 不使用 LLM、关键词规则或词库匹配推断 `themeTags`。
- 不修改后端 OCR、切片、向量化和推荐检索主链路。
- 不要求后端新增 `pages` 持久化；页数继续作为脚本采集结果字段。

## Decisions

1. 东方财富 API 作为标签保障来源

   - 决策：新增 `eastmoney_api` 输入模式，调用 `report/list` 分页获取研报记录，再基于 `infoCode` 拼接 PDF 下载地址。
   - 原因：API 响应直接包含公司、代码、行业和作者，能够在下载 PDF 前完成元数据校验。
   - 备选：继续维护人工 URL 清单。该方式无法保证公司、代码、行业存在，只能依赖人工补列，质量不可控。

2. 公司、代码、行业作为东方财富 API 模式的准入条件

   - 决策：`companyTags`、`tickerTags`、`industryTags` 缺任一项时，该记录标记为采集失败并跳过上传。
   - 原因：用户要求后续导入成功的数据必须存在公司、代码和行业；把校验放在采集阶段可以避免污染后端标签表。
   - 备选：允许导入后再补。该方式会让数据在一段时间内处于不完整状态，也会让失败原因更难追踪。

3. 行业字段优先级

   - 决策：优先使用 `indvInduName`，为空时兜底 `industryName`。
   - 原因：样本中 `indvInduName` 覆盖个股行业分类更稳定，`industryName` 可能为空但仍可作为兼容字段。
   - 备选：只使用 `industryName`。样本覆盖率不足，不适合作为必填来源。

4. 主题标签保持可选直取

   - 决策：仅当 API 或页面直接返回主题、概念或同义字段时填充 `themeTags`；没有则为空并继续导入。
   - 原因：当前研报 API 样本没有稳定主题字段；强行补主题会引入推断语义，违背“直接从网站爬取”的约束。
   - 备选：通过股票代码查询概念题材后映射为主题。该路径属于二次映射，不是研报元数据直取，应作为后续独立变更讨论。

5. 运行摘要记录覆盖率

   - 决策：每次运行输出公司、代码、行业必填校验失败数、主题非空数和导入成功数。
   - 原因：爬取质量需要可观测，尤其是主题可选时，需要能看出覆盖率而不是静默为空。

## Risks / Trade-offs

- [Risk] 东方财富公开 API 字段或访问策略变化。→ Mitigation：集中封装 API 请求、字段映射和错误摘要；保留 URL 清单模式作为人工兜底。
- [Risk] 个别新股或特殊报告没有行业字段导致跳过。→ Mitigation：运行摘要记录跳过原因；如业务决定允许某类报告缺行业，可后续调整准入配置。
- [Risk] 主题覆盖率可能长期为 0。→ Mitigation：把主题定义为可选直取字段，并在摘要中展示覆盖率，避免误以为已稳定覆盖。
- [Risk] API 分页和时间范围配置不当导致重复或漏抓。→ Mitigation：保留现有 runId、指纹、来源 URL 幂等机制，并在配置中显式声明 `beginTime`、`endTime`、`limit`。
