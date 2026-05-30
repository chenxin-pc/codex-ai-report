## 1. 行为基线测试

- [x] 1.1 为异步阶段成功路径补充测试，覆盖 OCR、CHUNK、VECTOR 成功后状态、attempt、nextRunAt、错误清理和阶段事件写入。
- [x] 1.2 为异步阶段失败路径补充测试，覆盖可重试失败退避、超过上限最终失败、不可重试最终失败和错误摘要保留。
- [x] 1.3 为后置阶段前置依赖补充测试，确保 CHUNK 依赖 OCR 成功、VECTOR 依赖 OCR 和 CHUNK 成功。
- [x] 1.4 为 chunk 过滤原因补充或调整测试，覆盖 segmentType、sectionPath、低 token、短文本、低汉字比例、高噪声比例和财务表格豁免。
- [x] 1.5 为向量阶段幂等行为补充测试，覆盖只处理未向量化 CHILD、成功后回写 `vectorStored=true`、跳过已向量化 CHILD。

## 2. 异步阶段模板重构

- [x] 2.1 新增轻量阶段描述结构，集中表达阶段编码、模型名、状态读写、attempt 读写和前置依赖。
- [x] 2.2 新增阶段 handler 接口，并实现 OCR、CHUNK、VECTOR 三个阶段 handler。
- [x] 2.3 新增重试与退避 policy，迁移异常可重试判断、错误码、短错误消息和退避时间计算。
- [x] 2.4 新增阶段执行模板，统一处理任务 claim、attempt 增加、成功收尾、失败收尾和阶段事件写入。
- [x] 2.5 调整事务边界，使阶段 claim、成功收尾、失败收尾使用短事务，外部 OCR、LLM 和 Milvus 调用不处于同一个长事务中。
- [x] 2.6 将 `ReportIngestAsyncService` 收敛为任务提交、调度触发、查询入口和阶段执行委托。

## 3. 入库策略边界重构

- [x] 3.1 新增 chunk 过滤 policy，迁移 `resolveFilterReason`、segmentType 标准化、低语义判断和财务表格候选判断。
- [x] 3.2 调整 chunk 持久化流程，使 PARENT、CHILD 和诊断落库继续按现有顺序执行，但过滤判断委托给 policy。
- [x] 3.3 新增或抽取向量文档构建组件，保留结构化 metadata 服务优先、默认 metadata 回退的行为。
- [x] 3.4 显式化向量批量写入边界，确保 Milvus 写入成功后才回写 CHILD 的 `vectorStored=true`。
- [x] 3.5 保持 `ReportIngestService` 对外方法签名兼容，避免影响 Controller、异步 handler 和现有测试夹具。

## 4. 回归与验证

- [x] 4.1 执行并修复入库相关单元测试：`ReportIngestAsyncServiceTests`、`ReportIngestServiceTests` 以及新增组件测试。
- [x] 4.2 执行 `mvn -q test`，确保全量测试通过。
- [x] 4.3 执行 `openspec validate --all --strict`，确保 OpenSpec 变更和主规格校验通过。
- [x] 4.4 检查本变更未修改 `schema.sql`、公开 DTO、Controller URL、Prompt 和外部依赖。
