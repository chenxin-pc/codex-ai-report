# codex-ai-report

Spring Boot 3 + Spring AI + Milvus minimal web starter project.

## Requirements

- Java 17
- Maven 3.9+

## Run

```bash
mvn spring-boot:run
```

## Build

```bash
mvn -q -DskipTests package
```

## Verify

- Ping endpoint: `GET http://localhost:8080/api/ping` -> `pong`
- Health endpoint: `GET http://localhost:8080/actuator/health`

## Milvus config

Configure via environment variables or `application-dev.yml`:

- `VECTOR_STORE_TYPE` (default: `none`, set to `milvus` to enable Milvus vector store)
- `MILVUS_HOST` (default: `localhost`)
- `MILVUS_PORT` (default: `19530`)
- `MILVUS_DATABASE` (default: `default`)
- `MILVUS_COLLECTION` (default: `documents`)
- `MILVUS_DIMENSION` (default: `1536`)
- `MILVUS_INDEX_TYPE` (default: `IVF_FLAT`)
- `MILVUS_METRIC_TYPE` (default: `COSINE`)
- `MILVUS_INIT_SCHEMA` (default: `false`)

Current setup is startup-first: app starts by default without a live Milvus.
When you want to connect Milvus, set `VECTOR_STORE_TYPE=milvus` and fill Milvus host/port settings.
