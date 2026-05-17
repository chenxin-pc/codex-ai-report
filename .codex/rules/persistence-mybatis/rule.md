# 持久化与 MyBatis 规则

## MyBatis
- 使用 Mapper 接口 + XML，XML 放在 `src/main/resources/mapper`。
- Mapper 接口中禁止 SQL 注解。
- SQL 禁止拼接用户输入，必须参数绑定。
- 查询字段显式列出，避免新增 `SELECT *`。

## 数据库变更
- 表结构/字段/索引变更必须同步更新 `src/main/resources/schema.sql`。
- 新增查询条件或排序字段需评估并补充索引。
- 多表或多步骤一致性写操作必须放在 `@Transactional` 边界内。
