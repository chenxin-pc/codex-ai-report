# 项目 Agent 指南（入口）

## 目的
本文件仅作为规则入口与路由，降低上下文体积。执行任务时按需加载子规则文件，而不是每次加载全部规则。

## 全局硬性要求
- Markdown 文档默认使用简体中文。
- 提交前默认执行 `mvn -q test`。
- 涉及 OpenSpec 变更必须执行 `openspec validate --all --strict`。
- 若同一事项在多份规则冲突，以更贴近当前任务域的子规则为准。

## 子规则目录
- `.codex/rules/general-baseline/rule.md`
- `.codex/rules/java-style/rule.md`
- `.codex/rules/comment-and-lombok/rule.md`
- `.codex/rules/persistence-mybatis/rule.md`
- `.codex/rules/ai-ocr-vector/rule.md`
- `.codex/rules/script-organization/rule.md`
- `.codex/rules/openspec-workflow/rule.md`

## 按需触发路由
1. 任何实现/重构任务：
   加载 `general-baseline/rule.md`。
2. 修改 `src/main/java` 或 `src/test/java`：
   额外加载 `java-style/rule.md`。
3. 涉及类/方法/字段注释或 Lombok：
   额外加载 `comment-and-lombok/rule.md`。
4. 涉及 Mapper、XML SQL、`schema.sql`、索引或事务：
   额外加载 `persistence-mybatis/rule.md`。
5. 涉及 OCR、Prompt、切片、Milvus、Embedding、外部 AI 调用：
   额外加载 `ai-ocr-vector/rule.md`。
6. 涉及 `scripts/` 下脚本、配置、脚本测试或脚本文档：
   额外加载 `script-organization/rule.md`。
7. 涉及 OpenSpec 提案/设计/任务/归档：
   额外加载 `openspec-workflow/rule.md`。

## 使用约束
- 不在入口文件重复展开子规则正文，避免上下文膨胀。
- 仅在任务命中触发条件时加载对应子规则。
- 后续新增子规则必须采用“每条规则一个目录”的结构：`.codex/rules/<rule-name>/rule.md`。
