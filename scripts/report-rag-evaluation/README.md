# 研报 RAG 评测流水线

本目录承载 `add-report-rag-evaluation-dataset` 变更的离线评测脚本，与导入分析脚本分离，避免评测 case、Ragas 导出和人工复核输出混入原有导入流程。

## 文件地图

| 路径 | 角色 | 职责 |
| --- | --- | --- |
| `commands/pipeline.py` | 命令入口 | 读取 eval case、调用推荐接口、生成完整 JSON、Excel 摘要和 Ragas JSONL。 |
| `config/config.example.json` | 配置样例 | 定义推荐接口、case 数据源、运行快照、输出目录和运行策略。 |
| `tests/test_pipeline.py` | 脚本测试 | 覆盖 case 加载、自动判分、完整结果输出和 Ragas 导出。 |

## 常用命令

从项目根目录执行完整评测：

```bash
python3 scripts/report-rag-evaluation/commands/pipeline.py --config scripts/report-rag-evaluation/config/config.example.json run-all
```

仅执行推荐评测并生成 JSON/Excel：

```bash
python3 scripts/report-rag-evaluation/commands/pipeline.py --config scripts/report-rag-evaluation/config/config.example.json evaluate
```

基于已有 run 结果导出 Ragas JSONL：

```bash
python3 scripts/report-rag-evaluation/commands/pipeline.py --config scripts/report-rag-evaluation/config/config.example.json export-ragas --run-id <runId>
```

## Eval case 文件

`evaluation.cases_file` 支持 JSON 数组或 JSONL。单条 case 推荐字段：

```json
{
  "caseId": "theme-storage-001",
  "query": "哪些研报看好储能板块，核心逻辑和风险是什么？",
  "caseType": "THEME_RESEARCH",
  "expectedIntent": "THEME_RESEARCH",
  "expectedOutputLevel": "L2_THEME_RESEARCH",
  "expectedDegradationReasons": [],
  "expectedAnchors": {"THEME": ["STORAGE"]},
  "referenceAnswer": "储能主题的核心逻辑包括需求增长、降本和政策支持。",
  "requiredClaims": ["需求增长", "降本", "政策支持"],
  "forbiddenClaims": ["目标价", "建议买入"],
  "referenceContexts": [
    {"contextId": "chunk-1", "chunkUid": "chunk-1", "text": "储能装机需求增长..."}
  ],
  "forbiddenContexts": [
    {"type": "THEME", "value": "PORT"}
  ]
}
```

## 运行快照

`run_snapshot` 用于写入 Ragas metadata，建议在正式评测时提供 `app_commit`、`prompt_hash`、`embedding_model`、`llm_model` 和 `retrieval_config_summary`，便于后续解释同一 case 在不同配置下的分数变化。

## 测试

```bash
python3 -m unittest scripts/report-rag-evaluation/tests/test_pipeline.py
```
