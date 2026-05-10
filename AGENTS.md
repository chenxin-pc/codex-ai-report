# 项目 Agent 指南

## 目的
本文档定义了 AI 编码代理与人类协作者在项目中的实现规则。

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

## 何时使用本文件
Agent 应在以下场景加载并遵循本文件：
1. 在本仓库开始任何实现/重构任务时。
2. 在修改架构、DTO、持久化、Prompt 或配置之前。
3. 在创建提交/分支之前，以确保命名和流程一致。
