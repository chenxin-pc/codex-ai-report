# dev-services

用于本地开发环境中间件（MySQL、Redis、Milvus 相关组件）的一键启动与状态确认。

## 文件地图

- `commands/start_services.sh`
  - 作用：启动 `docker-compose.yml` 中的中间件服务并等待服务就绪。
  - 是否主入口：是。
  - 何时修改：新增/删除中间件服务、调整健康等待策略、调整默认启动服务集合时。

## 常用命令

从项目根目录执行：

```bash
bash scripts/dev-services/commands/start_services.sh
```

仅启动指定服务：

```bash
bash scripts/dev-services/commands/start_services.sh mysql8 redis
```

## 修改指引

- 若 `docker-compose.yml` 服务名变更，需同步更新 `DEFAULT_SERVICES`。
- 若某服务没有 `healthcheck`，脚本会在容器 `running` 时判定就绪；如需更严格校验，可在脚本中增加端口或命令探活。
- 若团队统一使用 `docker compose` 或 `docker-compose`，脚本无需改动，已内置自动识别。
