# 注释审计记录

## 本次处理范围

- 已清理本次实现触达的导入、检索、清空、作者元数据和新增测试文件中的模板化注释。
- 已确认以下范围不再命中模板关键词：`ReportIngestService`、`ReportController`、`ReportAuthorService`、`ReportIngestDataResetService`、`ReportIngestDataResetMapper`、`service/retrieval` 新增与改造文件、相关新增测试。
- 已为新增代码的关键字段、构造器、业务分支、过滤表达式、Milvus schema、reset 删除顺序和测试断言补充说明性注释。

## 历史遗留结果

- 审计命令：`rg -n "负责相关业务能力|按方法或类型既定职责|详见方法签名|详见返回类型|执行.*相关业务处理" src/main/java src/test/java`
- 当前历史遗留命中：711 处。
- 当前历史遗留涉及文件：82 个。
- 典型遗留文件包括：`CodexAiReportApplication.java`、`PromptTemplateService.java`、`GlobalExceptionHandler.java`、多个 DTO、多个 Mapper、OCR/Prompt/工具类和部分历史测试。

## 处理建议

- 本次 OpenSpec 变更聚焦 hybrid retrieval，已避免把全仓注释重写混入功能 diff。
- 若要彻底消除历史模板注释，建议单独开一个纯注释清理变更，按包分批处理并配合 `mvn -q test` 验证。
