---
name: experience-triage
description: 用于在真实任务之后，把学到的经验、坑、约束或流程分诊到 Codex agent 架构的正确层级。Use when the user asks where to put a new rule, skill, hook, script, eval, AGENTS.md entry, .codex/rules item, or process memory; examples include “这条规则该写到哪”, “这个经验要不要沉淀”, “帮我判断该加规则还是技能”, “我刚踩了一个坑想记录”, “有个新流程不知道放哪”, or “每次考虑新增规则/技能时触发”.
---

# 经验分诊流程

带用户完成一次经验分诊，输出明确的“该放在哪一层 + 应该怎么写 + 放到哪个文件”的建议。默认使用简体中文。

## 适用范围

当用户想沉淀以下内容时使用：

- 任务后复盘出的经验、坑、约束或默认行为。
- 不确定应该加到 `AGENTS.md`、`.codex/rules`、`.codex/skills`、脚本、hook、CI、测试或 eval 的内容。
- 准备新增规则或技能，但需要先判断抽象层级。

如果用户已经明确要求“直接创建/修改某个 skill 或 rule”，先做快速分诊，再按结论执行；不要只给建议。

## 第一步：问清楚经验

如果用户的描述已经足够具体，直接进入判断树。若描述太泛，先用 1-3 个问题澄清：

- 这条经验具体来自哪个场景或失败案例？
- 它是每次都必须做，还是只在特定文件、模块、任务类型下适用？
- 它需要真实执行动作，还是只是约束 Codex 的判断和写法？
- 它只对当前项目有用，还是跨项目通用？

## 第二步：判断层级

按顺序判断，第一个匹配的就是优先答案。

### Q1：是否必须每次执行、零例外、不能靠模型自觉？

是：推荐放到 hook 或 CI。

常见位置：

- 项目内版本化 hook：`.githooks/pre-commit`
- CI：`.github/workflows/*.yml`
- 当前项目入口只写触发要求：[AGENTS.md](/Users/cxx/AI/codex-work/codex-ai-report/AGENTS.md)

示例：

```bash
#!/usr/bin/env bash
set -euo pipefail

mvn -q test
openspec validate --all --strict
```

反例：把“提交前必须测试”只写进一个长 prompt。必须发生的检查应该下沉到可执行机制。

### Q2：是否需要真实执行命令、查询接口、读取数据或做确定性转换？

是：推荐写成 `script`、CLI 命令或 MCP tool，再由相关 skill 或规则引用。

常见位置：

- 项目脚本：`scripts/<task-domain>/commands/pipeline.py`、`scripts/<task-domain>/commands/<command>.py`
- MCP/tool 集成：Codex 插件或运行环境工具
- 调用说明：`.codex/skills/<skill-name>/SKILL.md`

示例：OCR 清洗噪声、批量分析报告、生成评估数据，应该优先落成脚本，skill 只描述何时调用和如何校验。

反例：让 Codex 每次手写一段临时清洗逻辑，导致结果不可复现。

脚本目录约定：按任务域建子目录，并用 `commands/`、`config/`、`tests/` 分开脚本、配置和测试；说明直接放在任务域根目录的 `README.md`；只有多个任务域真正复用的代码才提取到 `scripts/_shared/`。

### Q3：是否只对某个目录、某类文件、某个模块生效？

是：推荐放到 path-scoped rule，并在入口规则中路由。

当前项目推荐位置：

- 规则正文：`.codex/rules/<rule-name>/rule.md`
- 入口路由：[AGENTS.md](/Users/cxx/AI/codex-work/codex-ai-report/AGENTS.md)

示例：

```markdown
## 按需触发路由
- 修改 `src/main/resources/mapper` 或 XML SQL：
  额外加载 `.codex/rules/persistence-mybatis/rule.md`。
```

反例：把 MyBatis XML 的细节写进全局入口，导致所有任务都加载无关规则。

### Q4：是否是多步流程、专题 checklist、需要分支判断的过程？

是：推荐写成新的 skill。

当前项目推荐位置：

- `.codex/skills/<skill-name>/SKILL.md`

`description` 必须写到能被触发：包含使用场景、用户常见说法、关键对象名。不要只写“处理经验”这种泛描述。

示例 frontmatter：

```markdown
---
name: report-ingest-review
description: 用于审查研报入库、OCR 清洗、切片、Embedding、Milvus 检索质量的完整流程。Use when the user asks to review report ingest quality, diagnose bad retrieval, tune chunking, or validate OCR/vector pipeline changes.
---
```

反例：把十几步 OCR 评估流程塞进 `AGENTS.md`，让所有任务都背负这段上下文。

### Q5：是否是每个会话都应该知道的高频默认行为或项目级约束？

是：推荐放到 `AGENTS.md`。写成入口规则、路由或少量硬性约束，不展开长细节。

当前项目位置：

- [AGENTS.md](/Users/cxx/AI/codex-work/codex-ai-report/AGENTS.md)

写入前检查：

- 是否真的跨任务高频？
- 是否能用一句规则表达？
- 是否只是路由到 `.codex/rules` 或 `.codex/skills`？
- 文件是否已经过长；超过约 200 行时优先拆到子规则或 skill。

反例：把低频、一次性、只影响某个目录的经验写进入口文件。

### 都不匹配

不建议沉淀。它可能是一次性偏好、临时上下文、私人备注或尚未验证的想法。可以先留在当前对话、issue、任务记录或复盘文档中，等重复出现后再上升为规则或 skill。

## 第三步：输出可直接写入的草稿

输出必须包含：

```text
【分诊结论】xxx 层
【推荐位置】具体文件或目录
【判断依据】为什么不放到其他层
【写作模板】
可直接写入的 Markdown / YAML / Shell / workflow 草稿
【后续提醒】
是否需要上移、拆分、补测试、补 hook 或观察几次再沉淀
```

草稿要求：

- 格式正确：YAML frontmatter、Markdown 标题、代码块、shell shebang 等要完整。
- 写具体行为，不写“请遵守某某规则”这种空话。
- 至少包含一个具体例子或反例。
- 如果建议新增 skill，必须给出可触发的 `description`。
- 如果建议新增 rule，必须说明是否需要同步更新 `AGENTS.md` 的路由。
- 如果建议新增 hook/CI，必须说明本地与 CI 的关系，避免只在一个环境生效。

## 第四步：提示上移或下沉

当同类经验多次出现时，提示用户考虑调整层级：

- skill 中反复出现的通用约束：上移到 `AGENTS.md` 或 `.codex/rules`。
- `AGENTS.md` 里越来越长的专题流程：下沉到 `.codex/skills`。
- 依赖模型自觉执行的强制检查：下沉到 hook 或 CI。
- 重复手写的确定性逻辑：下沉到 `scripts` 或 MCP tool。
- 评审中反复发现的问题：补测试、eval 或 CI 检查。
