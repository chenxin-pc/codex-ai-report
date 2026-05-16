# 项目 Agent 指南

## 目的
本文档定义了 AI 编码代理与人类协作者在项目中的实现规则。

## 文档语言规则
- 后续生成或更新的 Markdown 文档必须使用简体中文，代码标识、命令、路径、配置键、API 字段名和必要英文专有名词除外。

## 技术基线
- Java 17

- Spring Boot 3.5.x
- Spring AI 1.1.2
- MyBatis（SQL 写在 XML 中）
- MySQL + Redis + Milvus
- 通过 DashScope 兼容端点接入 Qwen

## 架构规则
- 保持 Controller 简洁：仅做请求校验和服务委托。
- 将编排逻辑放在 Service 层；复杂流程拆分为私有方法或专用组件。
- 可复用的第三方集成放在 `common` 包下。
- 工具类仅在“无状态且不需要依赖注入（DI）”时放在 `common.util`。

## DTO 命名规则
- 请求 DTO 必须以 `ReqDTO` 结尾。
- 响应 DTO 必须以 `RespDTO` 结尾。
- DTO 字段应简洁且语义明确。

## 持久化规则
- 使用 MyBatis Mapper 接口 + XML 文件，XML 放在 `src/main/resources/mapper`。
- 不要在 Mapper 接口中使用 SQL 注解。
- 任何表结构变更都必须同步到 `src/main/resources/schema.sql`。

## AI / 向量规则
- 向量检索必须使用 Milvus ANN 搜索。
- Embedding 模型必须使用 `application.yml` 中配置的 Qwen embedding 模型。
- Prompt 文本必须存放在 `src/main/resources/prompts`，并通过模板服务加载。
- 优先使用 Spring AI 的结构化输出映射（`entity(Class<T>)`），避免手动 JSON 解析。

## 配置规则
- 共享默认配置放在 `application.yml`。
- `application-dev.yml` 和 `application-test.yml` 保持最小化，只覆盖环境特定配置。
- 避免在不同 profile 中重复配置属性。

## 测试规则
- 任何重构或行为变更后都应保证 `mvn -q test` 通过。
- 逻辑有变更时，新增或更新有针对性的单元测试。

## Git 规则
- 按逻辑块提交，提交信息清晰明确。
- 新分支默认前缀为 `codex/`。

## Java 编码规则（基于阿里巴巴 Java 开发规范）
以下规则是后续 AI Coding 的强制约束；若与本项目已有架构规则冲突，以本文件中更贴合项目的规则为准。

### 命名规范
- 类名使用 `UpperCamelCase`；方法名、参数名、局部变量名、成员变量名使用 `lowerCamelCase`。
- 常量使用全大写加下划线，如 `MAX_SEGMENT_TOKENS`。
- 禁止使用中文、拼音、拼音英文混合命名；禁止使用无意义缩写，如 `tmp`、`obj`、`data`，除非作用域极小且语义明确。
- 抽象类以 `Abstract` 或 `Base` 开头；异常类以 `Exception` 结尾；枚举类以 `Enum` 结尾。
- Boolean 包装类型字段不要以 `is` 开头，避免序列化和框架反射歧义。
- DTO 遵守本项目规则：请求对象以 `ReqDTO` 结尾，响应对象以 `RespDTO` 结尾。
- 测试类沿用当前项目约定，以被测类名开头并以 `Tests` 结尾。

### 代码格式与可读性
- 使用 4 个空格缩进，保持现有代码风格，不做无关格式化。
- 禁止魔法值直接散落在业务代码中；有业务含义的数字、字符串必须抽取为命名清晰的常量。
- 单个方法只做一层清晰职责；复杂编排拆成私有方法或专用组件。
- 条件分支应优先表达正常路径，避免过深嵌套；必要时使用提前返回。
- 注释只解释业务意图、边界条件或非显然逻辑，不写“赋值给变量”这类重复代码含义的注释。

### 面向对象与空值处理
- Service、Component、Mapper 等 Spring Bean 使用构造器注入，不使用字段注入。
- 工具类必须 `final` 且构造器私有；只有无状态且不需要 DI 的逻辑才能放入 `common.util`。
- 方法返回集合时优先返回空集合，不返回 `null`。
- 字符串判空使用 `isBlank()` / `isEmpty()` 前先处理 `null`；对象相等比较优先使用确定非空对象调用 `equals`。
- 金额、精确小数、评分阈值等需要精度语义的场景使用 `BigDecimal` 或明确的 double 误差策略。
- 不暴露可变集合内部状态；必要时使用 `List.copyOf`、`Map.copyOf` 或不可变集合。

### 集合与并发
- 已知集合容量时设置初始容量，避免不必要扩容。
- 遍历集合时不要在增强 `for` 中直接修改原集合；需要修改时使用迭代器或构造新集合。
- 共享状态必须明确线程安全策略；Spring 单例 Bean 中禁止保存请求级可变状态。
- 禁止直接使用 `Executors` 创建生产线程池；如需异步执行，使用显式配置的 `ThreadPoolTaskExecutor` 或受控 `Executor`。
- 时间处理统一使用 `java.time`，不要使用过时的 `Date` / `Calendar` 新增逻辑。

### 异常与日志
- 禁止吞异常；捕获异常后必须处理、转换为有业务含义的异常，或记录足够上下文后重新抛出。
- 禁止使用 `System.out.println`、`printStackTrace`；统一使用 SLF4J 日志。
- 日志使用占位符，不使用字符串拼接输出变量。
- 日志中禁止输出 API Key、Token、数据库密码、用户敏感信息、完整大文本 Prompt 或 OCR 原文。
- Controller 不承载复杂异常处理逻辑，统一交给全局异常处理或 Service 层转换。

### MyBatis 与数据库
- Mapper 接口禁止 SQL 注解，所有 SQL 写在 `src/main/resources/mapper/*.xml`。
- SQL 禁止拼接用户输入，必须使用参数绑定。
- 查询字段应显式列出，避免新增查询使用 `SELECT *`。
- 表结构、字段、索引变更必须同步更新 `src/main/resources/schema.sql`。
- 新增查询条件或排序字段时，应评估并补充索引。
- 需要保证多表或多步骤一致性的写操作必须放在 `@Transactional` 边界内。

### Spring Boot 项目规则
- Controller 只做参数接收、基础校验和服务委托。
- Service 负责业务编排；第三方接入放入 `common` 下的专用包，如 `common.ocr`、`common.llm`。
- 外部服务配置使用 `@ConfigurationProperties` 或集中配置类，不在业务代码中硬编码 endpoint、model、key。
- profile 配置只覆盖环境差异，公共默认值放在 `application.yml`。
- 新增配置必须提供环境变量占位符，并避免泄露真实密钥。

### AI / OCR / 向量链路规则
- 报告上传主链路必须遵循：OCR 识别结果 -> 大模型语义切片 -> MySQL 入库 / Milvus 向量化。
- 大模型切片只负责语义边界规划，不改写原文，不生成切片正文。
- LLM 返回的切片边界必须做合法性校验和修复：连续、不重叠、不遗漏、不越界。
- 大模型切片失败不能静默伪装成功；必须暴露明确异常或诊断信息。
- Prompt 必须存放在 `src/main/resources/prompts`，通过 `PromptTemplateService` 加载。
- 向量检索必须使用 Milvus ANN；Embedding 模型必须来自 `application.yml` 中配置的 Qwen embedding 模型。
- 入 Milvus 的向量文档优先使用 CHILD chunk，必要上下文通过 `parentChunkUid` 回查 PARENT chunk。

### 安全规则
- 所有外部输入都必须校验，包括上传文件、OCR 返回、用户 query、日期和分页参数。
- 文件上传不得信任文件名和 Content-Type；涉及解析时必须处理空文件、异常文件和超大文件。
- 禁止把用户输入直接拼入 SQL、Prompt 控制指令、日志敏感字段或外部请求 URL。
- 外部 HTTP 调用必须有清晰的配置入口和失败处理；不得硬编码生产地址和密钥。

### 测试与验证
- 行为变更必须新增或更新针对性测试。
- 涉及 OCR 清洗、LLM 边界修复、切片过滤、向量入库 metadata 的逻辑必须覆盖边界样例。
- 提交前必须运行 `mvn -q test`；若无法运行，必须说明原因和风险。
- 测试不得依赖真实外部 OCR、Qwen、Milvus 服务；需要 mock 或使用 profile 隔离。

## OpenSpec 规则
- 当前项目使用 OpenSpec 管理能力规格，主规格位于 `openspec/specs`。
- 开始新增功能、行为变更或架构调整前，应先检查相关主规格，并在 `openspec/changes/<change-id>` 下创建变更提案、设计、规格增量和任务。
- 变更规格必须使用 Given/When/Then 场景描述，覆盖正常路径、边界条件和异常场景。
- 实现完成后应运行 `openspec validate --all --strict`，确保变更规格和主规格可校验。
- 归档或同步规格时，必须保证实现、测试、`schema.sql`、Prompt 和配置与主规格保持一致。

## 何时使用本文件
Agent 应在以下场景加载并遵循本文件：
1. 在本仓库开始任何实现/重构任务时。
2. 在修改架构、DTO、持久化、Prompt 或配置之前。
3. 在创建提交/分支之前，以确保命名和流程一致。
