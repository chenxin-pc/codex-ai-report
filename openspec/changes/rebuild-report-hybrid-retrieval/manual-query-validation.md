# 代表性 Query 手工验证记录

## 执行方式

- 本地未运行后端 `localhost:8080`，无法直接调用推荐 API 访问真实已导入语料。
- 为避免把未就绪环境误判为功能失败，本次使用可重复的组件级验证：
  - `RetrievalSearchRequestBuilder` 生成 Milvus hard filter。
  - `RetrievalBusinessBoostRanker` 对模拟 hybrid 候选执行 soft boost 排序。
  - `RepresentativeHybridRetrievalManualValidationTests` 固化六类代表性 query 的预期。

## 覆盖 Query

- ticker：`分析 300750.SZ 的盈利预测和估值弹性`
  - 预期：filter 包含 `ticker == "300750.SZ"`，命中 ticker 和估值章节的候选排第一。
- 公司名：`宁德时代储能业务竞争优势如何`
  - 预期：filter 包含 `companyName == "宁德时代"`，命中公司和主题的候选排第一。
- 作者名：`作者张三对储能板块怎么看`
  - 预期：filter 包含 `authorText like "%|张三|%"`，命中作者和主题的候选排第一。
- 章节词：`储能系统风险提示有哪些`
  - 预期：默认只下推 `chunkType == "CHILD"`，风险章节通过 soft boost 排第一。
- 指标词：`这家公司 PE 和盈利预测是否改善`
  - 预期：默认只下推 `chunkType == "CHILD"`，盈利预测/财务指标章节通过 soft boost 排第一。
- 宽泛主题：`储能产业链未来一年景气度如何`
  - 预期：默认不把主题下推为 hard filter，主题命中候选通过 soft boost 排第一。

## 结果

- 已通过 `mvn -q test` 执行。
- 该验证确认本期实现的 hard filter、作者 OR filter、默认宽泛主题 soft boost、章节/指标 soft boost 和诊断分数字段均按预期工作。

## 后续建议

- 等 hybrid collection 重建并重新导入真实研报后，再用 `scripts/report-ingest-analysis` 的 search evaluation 导出工作簿做端到端人工相关性标注。
