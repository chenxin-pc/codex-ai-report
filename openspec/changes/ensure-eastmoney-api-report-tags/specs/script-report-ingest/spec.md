## ADDED Requirements

### Requirement: 脚本 MUST 支持东方财富 API 采集并保证核心标签

脚本 MUST 提供东方财富 API 采集模式，直接从东方财富研报列表 API 获取研报元数据并下载 PDF。对于该模式下成功上传导入的研报，脚本 MUST 保证 `companyTags`、`tickerTags` 和 `industryTags` 均为非空。`themeTags` MUST 仅来自 API 或页面直接返回的主题、概念或同义字段；字段缺失时 MUST 保持为空，并且 MUST NOT 阻断导入。

#### Scenario: 东方财富 API 记录包含核心标签

- **GIVEN** 用户配置东方财富 API 采集模式、时间范围和导入数量上限
- **AND** 东方财富 API 返回一条记录，包含 `title`、`infoCode`、`stockName`、`stockCode`、`indvInduName`、`researcher` 和 `attachPages`
- **WHEN** 脚本收集该记录
- **THEN** 脚本 MUST 将 `stockName` 归一化为 `companyTags`
- **AND** 脚本 MUST 将 `stockCode` 归一化为 `tickerTags`
- **AND** 脚本 MUST 将 `indvInduName` 归一化为 `industryTags`
- **AND** 脚本 MUST 将 `researcher` 或 `author` 归一化为 `authors`
- **AND** 脚本 MUST 将 `attachPages` 归一化为 `pages`
- **AND** 脚本 MUST 基于 `infoCode` 生成 PDF 下载地址并下载 PDF

#### Scenario: 行业字段使用兜底来源

- **GIVEN** 东方财富 API 返回的研报记录中 `indvInduName` 为空
- **AND** 该记录的 `industryName` 非空
- **WHEN** 脚本归一化该记录
- **THEN** 脚本 MUST 使用 `industryName` 填充 `industryTags`
- **AND** 脚本 MUST 允许该记录继续下载和上传

#### Scenario: 核心标签缺失时跳过上传

- **GIVEN** 东方财富 API 返回一条记录，缺少 `stockName`、`stockCode` 或可用行业字段中的任意一项
- **WHEN** 脚本处理该记录
- **THEN** 脚本 MUST 将该记录标记为采集失败
- **AND** 脚本 MUST 在错误摘要中记录缺失的核心标签字段
- **AND** 脚本 MUST NOT 下载或上传该记录对应的 PDF

#### Scenario: 主题标签缺失时仍可导入

- **GIVEN** 东方财富 API 返回一条记录，包含 `stockName`、`stockCode` 和可用行业字段
- **AND** 该记录没有主题、概念或同义字段
- **WHEN** 脚本处理并上传该记录
- **THEN** 脚本 MUST 将 `themeTags` 记录为空值
- **AND** 脚本 MUST 上传非空的 `companyTags`、`tickerTags` 和 `industryTags`
- **AND** 脚本 MUST 在 `authors` 非空时上传作者字段
- **AND** 脚本 MUST NOT 通过 OCR、正文解析、标题规则、LLM 或 chunk 标签抽取补齐 `themeTags`

#### Scenario: 运行摘要展示标签覆盖情况

- **GIVEN** 用户运行东方财富 API 采集模式
- **WHEN** 脚本完成收集和导入
- **THEN** 脚本 MUST 在运行状态或摘要中记录总采集数、下载成功数、导入成功数
- **AND** 脚本 MUST 记录因公司、代码或行业缺失而跳过的数量
- **AND** 脚本 MUST 记录 `themeTags` 非空数量或覆盖率
