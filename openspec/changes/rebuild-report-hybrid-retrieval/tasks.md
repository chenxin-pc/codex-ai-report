## 1. 数据清空与配置

- [x] 1.1 梳理研报导入域 MySQL 表清单，确认清空顺序和外键约束处理方式
- [x] 1.2 实现显式清空入口，删除研报导入域数据并保留 taxonomy、词库、Prompt 和系统配置
- [x] 1.3 实现旧 Milvus collection 删除逻辑，并要求只能由显式清空入口触发
- [x] 1.4 新增或扩展 MySQL 研报作者元数据表/字段，支持多作者和规范化作者名
- [x] 1.5 增加 hybrid collection 名称、analyzer、ranker 策略、ranker 权重、author metadata、metadata filter 策略等配置项

## 2. Milvus Hybrid Collection

- [x] 2.1 引入或封装 Milvus 原生客户端，隔离 collection 管理、写入和 hybrid search API
- [x] 2.2 实现 hybrid collection schema 初始化，包含主键、chunk 文本、dense vector、sparse vector、BM25 function、研报作者和 scalar metadata
- [x] 2.3 创建 dense、sparse 和必要 scalar index，并确保 collection 在检索前完成 load
- [x] 2.4 实现 collection schema 兼容性检查，发现缺失 BM25 function、sparse vector 或关键 metadata 字段时显式失败

## 3. 向量入库改造

- [x] 3.1 导入阶段解析或接收研报作者，并保存到 MySQL 研报元数据表/字段
- [x] 3.2 将可检索 CHILD chunk 映射为 hybrid collection 写入文档，包含 chunk 文本、dense embedding、从 MySQL 读取的研报作者和结构化 metadata
- [x] 3.3 保留 Qwen embedding 生成 dense vector，并移除推荐索引写入对旧 dense-only VectorStore collection 的依赖
- [x] 3.4 写入 Milvus hybrid collection 成功后再更新 chunk 向量化状态
- [x] 3.5 写入失败时记录阶段事件和失败原因，并保证后续重试可重新处理该 chunk

## 4. Hybrid 召回与排序

- [x] 4.1 实现 QueryAnchors 到 Milvus hard filter、soft boost 和多值 OR filter 的转换，覆盖 ticker、company、author、theme 和 industry
- [x] 4.2 实现 dense embedding search 与 BM25 full-text search 的 Milvus hybrid search 请求构造
- [x] 4.3 支持 RRF 或 Weighted ranker 配置，并将 Milvus 返回结果映射为内部候选
- [x] 4.4 保留 dense score、BM25/sparse score、fused score 和 normalized score 诊断字段
- [x] 4.5 在 hybrid 结果上执行去重、业务加权、候选截断、PARENT 聚合和证据护栏

## 5. 测试与验证

- [x] 5.1 为导入域清空范围、保留数据范围和旧 collection 删除行为补充测试
- [x] 5.2 为 MySQL 研报作者保存、多作者表达和空作者状态补充测试
- [x] 5.3 为 hybrid collection schema 初始化和不兼容 schema 失败补充测试
- [x] 5.4 为向量入库成功、失败、重试和状态更新补充测试
- [x] 5.5 为 metadata hard filter、soft boost、多值 OR、作者过滤和 hybrid score 标准化补充测试
- [x] 5.6 使用代表性研报 query 手工验证 ticker、公司名、作者名、章节词、指标词和宽泛主题 query 的召回效果
- [x] 5.7 执行 `mvn -q test`
- [x] 5.8 执行 `openspec validate --all --strict`
