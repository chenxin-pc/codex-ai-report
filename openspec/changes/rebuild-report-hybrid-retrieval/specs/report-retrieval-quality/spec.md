## ADDED Requirements

### Requirement: 推荐检索 MUST 使用 Milvus BM25+dense hybrid search

推荐检索 MUST 使用 Milvus 2.6 hybrid search 同时融合 dense embedding 召回和 BM25 full-text 召回。dense embedding MUST 继续使用 Qwen embedding 生成；BM25 sparse vector MUST 由 Milvus BM25 function 基于可检索 chunk 文本生成。系统 MUST 使用新的 hybrid collection 作为推荐检索主索引，不得在推荐链路继续依赖旧 dense-only collection。

#### Scenario: 股票代码与专有词 query 同时命中 BM25 和 dense

- **Given** hybrid collection 已写入包含股票代码、公司名、章节标题和正文的 CHILD chunk
- **When** 用户输入包含明确股票代码、公司名或财务指标词的推荐 query
- **Then** 检索 MUST 同时执行 dense 召回和 BM25 full-text 召回
- **And** 最终候选 MUST 来自 Milvus hybrid search 的融合结果
- **And** 返回候选 MUST 保留可诊断的 dense、BM25 或 fused score 信息

#### Scenario: BM25 未命中时仍可使用 dense 召回

- **Given** query 不包含可被 analyzer 有效切分的关键词
- **When** 系统执行 hybrid search
- **Then** 检索 MUST 允许 dense 召回结果进入最终候选
- **And** 推荐生成 MUST 继续执行证据护栏和覆盖校验

### Requirement: 推荐检索 MUST 支持 hybrid 排序融合和业务加权

推荐检索 MUST 支持通过配置选择 Milvus hybrid ranker 策略和权重。Milvus ranker 负责融合 dense 与 BM25 召回结果，应用层 MAY 在融合结果之上执行业务加权，包括实体匹配、主题匹配、行业匹配、章节意图和报告新近度。业务加权不得绕过证据护栏生成推荐。

#### Scenario: BM25 与 dense 排名冲突

- **Given** dense 召回结果语义相近但实体不精确
- **And** BM25 召回结果精确命中 ticker 或公司名
- **When** 系统执行 hybrid 排序融合
- **Then** 最终排名 SHOULD 提升实体精确匹配的候选
- **And** 被提升候选 MUST 仍满足 metadata hard filter 和证据可引用要求

## MODIFIED Requirements

### Requirement: 推荐检索 MUST 使用 Milvus metadata scalar filter

推荐检索 MUST 基于 query 锚点生成 Milvus metadata filter，并在 hybrid search 前下推确定性强约束。metadata filter MUST 至少支持 `chunkType=CHILD`、指定 report id、唯一匹配的 ticker/company 等 hard filter。对 author、theme code、industry code、report theme code、section intent、matched terms 等可能误杀召回的信号，系统 MUST 支持配置为 soft boost 或 hard filter。metadata filter MUST 支持多值 OR 表达式，并且不得把无法解析或置信度不足的锚点强行下推为 hard filter。

#### Scenario: query 命中唯一股票锚点

- **Given** query 锚点抽取命中唯一 ticker 或唯一 company
- **When** 推荐检索构造 Milvus hybrid search 请求
- **Then** 系统 SHOULD 将该 ticker 或 company 作为 hard filter 下推
- **And** 检索结果 MUST 只包含满足该实体约束的 CHILD chunk

#### Scenario: query 只命中宽泛主题锚点

- **Given** query 锚点只命中行业、主题或章节意图
- **When** 推荐检索构造 Milvus hybrid search 请求
- **Then** 系统 MUST 根据配置选择 soft boost 或 hard filter
- **And** 默认策略 SHOULD 避免因单个宽泛主题过滤导致召回候选为空

#### Scenario: query 命中研报作者锚点

- **Given** query 锚点命中一个或多个研报作者
- **When** 推荐检索构造 Milvus hybrid search 请求
- **Then** 系统 MUST 支持按作者生成 metadata filter 或 soft boost
- **And** 多作者候选 MUST 使用多值 OR 表达式表达
- **And** 作者锚点置信度不足时不得强行作为 hard filter

#### Scenario: query 命中多个候选主题

- **Given** query 锚点命中多个 theme code 或 industry code
- **When** 系统构造 metadata filter
- **Then** filter MUST 使用多值 OR 表达式表达候选集合
- **And** 不得只保留第一个候选值作为唯一过滤条件

### Requirement: 检索相关性分数 MUST 标准化

推荐检索 MUST 对 Milvus hybrid search 返回的 raw score 和应用层排序信号进行标准化，输出用于候选截断、主题覆盖校验、PARENT 聚合和推荐生成的统一相关性分数。系统 SHOULD 保留 dense score、BM25/sparse score、Milvus fused score 和 normalized score 以支持诊断。不同召回来源的 raw score 不得未经归一直接混合用于业务判断。

#### Scenario: hybrid 结果包含不同来源分数

- **Given** Milvus 返回 dense、BM25 或 fused ranking 信息
- **When** 系统映射为内部检索候选
- **Then** 每个候选 MUST 具有统一 normalized score
- **And** 可诊断字段 SHOULD 保留原始分数或来源信息
- **And** 推荐证据筛选 MUST 使用 normalized score 或覆盖校验结果

## REMOVED Requirements

### Requirement: 向量检索 MUST 使用 Qwen embedding 和 Milvus ANN

**Reason**: 推荐检索主链路从 dense-only ANN 升级为 Milvus 2.6 BM25+dense hybrid search。旧要求只覆盖 Qwen embedding 与 Milvus ANN，无法表达 BM25 sparse vector、hybrid ranker 和融合排序。

**Migration**: 删除旧 dense-only collection，创建新的 hybrid collection。Qwen embedding 继续作为 dense 向量来源，但推荐链路必须通过 hybrid search 获取候选。
