# 脚本目录

本目录按任务域组织离线脚本、配置样例、测试和使用说明。新增脚本时优先创建独立子目录，避免把不同任务的入口、配置和测试平铺在 `scripts/` 根目录。

## 当前脚本

| 目录 | 用途 | 入口说明 |
| --- | --- | --- |
| `report-ingest-analysis/` | 研报导入、质量导出和搜索评估流水线 | `report-ingest-analysis/README.md` |

## 约定

- 每个任务域目录用 `commands/`、`config/`、`tests/` 分开脚本、配置和测试。
- 每个任务域根目录的 `README.md` 必须包含“文件地图”，说明目录下每个文件的职责、是否为主入口、什么时候需要修改。
- 本地配置使用 `config.local.json`，不要提交真实账号、密码、密钥或 token。
- 多个任务域真正复用的代码再提取到 `_shared/`，不要提前抽象。

## 新增脚本目录模板

```text
scripts/<task-domain>/
├── README.md              # 文件地图、使用方式、维护边界
├── commands/              # 可执行脚本
│   └── pipeline.py        # 主流程入口；若不是流水线，可用更具体的命令名
├── config/
│   └── config.example.json
└── tests/
    └── test_pipeline.py   # 脚本级回归测试
```

`README.md` 至少包含：

- 文件地图：每个文件做什么。
- 常用命令：如何运行主流程、测试、重试或只执行某个阶段。
- 修改指引：新增输入、输出、命令、配置项时应改哪些文件。
