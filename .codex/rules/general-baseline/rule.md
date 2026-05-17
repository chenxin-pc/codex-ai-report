# 通用基线规则

## 文档语言
- 生成或更新的 Markdown 文档默认使用简体中文，代码标识、命令、路径、配置键、API 字段名和必要英文专有名词除外。

## 技术基线
- Java 17
- Spring Boot 3.5.x
- Spring AI 1.1.2
- MyBatis（SQL 写在 XML）
- MySQL + Redis + Milvus
- DashScope 兼容端点接入 Qwen

## 架构总则
- Controller 仅做参数接收、基础校验与服务委托。
- 业务编排放在 Service 层，复杂流程拆分私有方法或专用组件。
- 可复用第三方接入放在 `common` 包下。
- 工具类仅在无状态且不依赖 DI 时放在 `common.util`。

## 配置总则
- 共享默认配置放在 `application.yml`。
- `application-dev.yml` / `application-test.yml` 仅覆盖环境差异。
- 避免跨 profile 重复配置。

## 测试与提交流程
- 行为变更后需补充或更新针对性测试。
- 提交前必须执行 `mvn -q test` 并通过；如无法执行，需说明原因与风险。
- 按逻辑块提交，提交信息清晰明确；新分支默认前缀 `codex/`。
