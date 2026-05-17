# 脚本组织规则

## 适用范围

- 新增、移动或修改 `scripts/` 下的脚本、配置样例、脚本测试和脚本文档。
- 将一次性命令沉淀为可复用脚本，或为已有脚本补充说明与测试。

## 目录结构

脚本按任务域组织，不在 `scripts/` 根目录平铺业务脚本。

推荐结构：

```text
scripts/<task-domain>/
├── README.md
├── commands/
│   └── pipeline.py
├── config/
│   └── config.example.json
└── tests/
    └── test_pipeline.py
```

约束：

- `README.md` 放在任务域根目录，说明文件地图、常用命令、测试方式和修改指引。
- `commands/` 只放可执行脚本或命令入口。
- `config/` 放可提交的配置样例；本地配置使用 `config.local.json`，不得提交真实账号、密码、密钥或 token。
- `tests/` 放脚本级回归测试，测试文件命名优先使用 `test_<target>.py`。
- 多个任务域真正复用的代码再提取到 `scripts/_shared/`，不要提前抽象。

## 命名约定

- 任务域目录使用 kebab-case，例如 `report-ingest-analysis`。
- Python 主流水线入口优先命名为 `commands/pipeline.py`。
- 专用辅助脚本使用动作化文件名，例如 `commands/import_reports_and_export_chunks.py`。
- 配置样例使用 `config/config.example.json`；本地配置使用 `config/config.local.json`。

## 路径与配置

- 脚本默认配置路径应基于 `__file__` 推导，避免依赖当前工作目录。
- README 中的命令示例使用从项目根目录执行的路径。
- 移动脚本后必须同步更新 README、测试 import 路径、默认配置路径和相关规则/skill 中的引用。

示例：

```python
from pathlib import Path

DEFAULT_CONFIG = Path(__file__).resolve().parents[1] / "config" / "config.example.json"
```

反例：

```python
DEFAULT_CONFIG = Path("scripts/report_ingest_analysis_config.example.json")
```

## 测试要求

- 修改脚本行为后，优先运行对应任务域的脚本测试。
- 如果脚本改动影响 Java 服务集成或项目行为，仍需执行 `mvn -q test`。
- README 中新增命令时，应确保命令路径可从项目根目录复制执行。

