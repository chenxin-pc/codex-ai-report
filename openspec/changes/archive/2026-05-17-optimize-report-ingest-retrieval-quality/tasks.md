## 1. 数据模型与配置

- [x] 1.1 设计并新增 OCR 页级结果、段落 atom、chunk 诊断、过滤原因和导入失败诊断所需实体类。
- [x] 1.2 更新 `src/main/resources/schema.sql`，新增或调整质量数据相关表、索引和外键。
- [x] 1.3 新增 MyBatis Mapper 接口和 `src/main/resources/mapper/*.xml`，所有 SQL 使用参数绑定并显式列出字段。
- [x] 1.4 新增质量优化配置类，覆盖 OCR 渲染/清洗、切片 token、搜索 topK、相似度阈值和重排开关。
- [x] 1.5 更新 `application.yml` 默认配置，并保持 `application-dev.yml`、`application-test.yml` 只覆盖环境差异。

## 2. OCR 质量观测与清洗

- [x] 2.1 扩展 OCR 识别结果模型，保留页码、页级原始文本、全文和基础诊断信息。
- [x] 2.2 实现 OCR 文本清洗/规范化服务，处理页眉、页脚、页码、目录、免责声明、水印和明显噪声。
- [x] 2.3 将清洗后的 OCR 文本拆解为段落 atom，并保留 paragraphId、pageNumber、sectionPath 和 tokenCount。
- [x] 2.4 在导入流程中持久化 OCR 页级结果、清洗结果、段落 atom 和诊断信息。
- [x] 2.5 确保 OCR 空文本、异常响应和不可用配置会显式失败，且不会写入 chunk 或 Milvus 向量。

## 3. 语义切片质量优化

- [x] 3.1 调整切片服务入参，使 LLM 基于段落 atom 规划语义边界，并继续通过 PromptTemplateService 加载 prompt。
- [x] 3.2 优化切片 Prompt，要求边界连续、不重叠、不遗漏，并输出 topic、segmentType、confidence。
- [x] 3.3 强化 LLM 边界校验和修复逻辑，记录遗漏、重叠、越界和修复结果。
- [x] 3.4 生成 PARENT/CHILD chunk 时保留段落范围、页码范围、sectionPath、tokenCount 和 chunkText。
- [x] 3.5 调整 CHILD chunk 目标长度和最大长度，使其更适合 embedding 相似性检索。
- [x] 3.6 过滤低语义 chunk、免责声明和噪声 chunk 时记录过滤原因和诊断指标。

## 4. 向量检索与推荐质量优化

- [x] 4.1 抽取共享检索组件，统一在线推荐和脚本评估可复用的 Milvus ANN 检索逻辑。
- [x] 4.2 支持初召回 topK 与最终 topK 分离，完成候选去重、阈值过滤和排序。
- [x] 4.3 确保 Milvus 入库文档优先使用 CHILD chunk，并写入完整可追溯 metadata。
- [x] 4.4 推荐证据构建时通过 parentChunkUid 回查 PARENT chunk，并按 token 上限截断上下文。
- [x] 4.5 增加可配置轻量重排入口，默认可关闭，避免影响基础召回链路。
- [x] 4.6 优化推荐 Prompt，要求 analysis、recommendation、risks、citations 严格基于召回证据生成。

## 5. 脚本分析支撑能力

- [x] 5.1 提供按 reportId 查询 OCR 页级结果、段落 atom、chunk 和过滤诊断的数据访问能力。
- [x] 5.2 提供导入失败阶段和错误摘要的持久化或查询能力。
- [x] 5.3 确认脚本可通过数据库或受控查询入口拉取完整质量数据，不需要在线服务自动生成 Excel。
- [x] 5.4 确认在线导入流程不包含每 10 篇批量计数、Excel 导出或自动搜索评估编排。

## 6. 测试与验证

- [x] 6.1 为 OCR 清洗、页级诊断、空文本失败和异常 OCR 响应补充单元测试。
- [x] 6.2 为段落 atom、LLM 边界修复、chunk 页码范围、过滤原因和 token 限制补充单元测试。
- [x] 6.3 为共享检索组件补充 mock Milvus/VectorStore 测试，覆盖 topK 分离、空召回和 PARENT 上下文扩展。
- [x] 6.4 为质量数据查询能力补充测试，确认脚本可按 reportId 拉取 OCR、段落、chunk 和过滤诊断。
- [x] 6.5 为导入流程补充集成级 mock 测试，确认失败时不污染 MySQL chunk 和 Milvus 向量。
- [x] 6.6 运行 `mvn -q test` 并修复失败。
- [x] 6.7 运行 `openspec validate --all --strict` 并修复规格问题。
