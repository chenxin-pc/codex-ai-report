# OpenSpec 工作流规则

## 变更前
- 新增功能、行为变更或架构调整前，先检查 `openspec/specs` 主规格。
- 在 `openspec/changes/<change-id>` 下创建 proposal、design、spec 增量与 tasks。

## 规格要求
- 变更规格使用 Given/When/Then 场景，覆盖正常、边界、异常路径。

## 校验与归档
- 实现完成后执行 `openspec validate --all --strict`。
- 归档或同步规格时，保证实现、测试、`schema.sql`、Prompt、配置一致。
