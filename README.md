# codex-ai-report

基于 `Java 17 + Spring Boot + Spring AI + Milvus + MySQL + Redis + 通义千问` 的研报推荐系统 V1。

## 核心能力

- `POST /api/reports/upload`：上传 PDF，自动完成解析、语义切片、MySQL 元数据落库、Milvus 向量入库。
- `POST /api/reports/recommend`：基于 Milvus 检索 Top5，并调用通义千问输出结构化推荐 JSON。
- 推荐结果增加 Redis 缓存（默认 30 分钟）。
- 持久层框架使用 MyBatis。

## 本地一键基础设施（Milvus + MySQL）

项目已提供 `docker-compose.yml`，包含：`etcd`、`minio`、`milvus`、`mysql`。

```bash
docker compose up -d
```

查看状态：

```bash
docker compose ps
```

停止并保留数据卷：

```bash
docker compose down
```

## 应用环境变量

复制模板：

```bash
cp .env.example .env
```

你当前给定的连接参数：

- MySQL: `localhost:3306`，`root/123456`
- Redis: `localhost:6379`，密码 `123456`
- Milvus: `localhost:19530`

你需要至少确认：

- `DASHSCOPE_API_KEY`
- `VECTOR_STORE_TYPE=milvus`
- `MILVUS_HOST=localhost`
- `MILVUS_PORT=19530`
- `MYSQL_*`
- `REDIS_*`

## 启动后端

```bash
set -a
source .env
set +a
mvn spring-boot:run
```

健康检查：

- `GET http://localhost:8080/api/ping`
- `GET http://localhost:8080/actuator/health`

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

默认前端地址：`http://localhost:5173`

## API 示例

### 上传研报

```bash
curl -X POST http://localhost:8080/api/reports/upload \
  -F "file=@/path/to/report.pdf" \
  -F "title=某行业深度报告" \
  -F "source=券商研报" \
  -F "institution=XX证券" \
  -F "publishDate=2026-05-10"
```

### 检索推荐

```bash
curl -X POST http://localhost:8080/api/reports/recommend \
  -H "Content-Type: application/json" \
  -d '{"query":"AI 算力产业链未来一年景气度如何？"}'
```

返回字段包含：`query`、`top5[]`、`analysis`、`recommendation`、`risks[]`、`citations[]`。
