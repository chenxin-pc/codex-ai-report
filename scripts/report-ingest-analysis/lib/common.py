from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

SENSITIVE_KEYS = ("api_key", "apikey", "authorization", "token", "password", "secret")
DEFAULT_LIMIT = 10
DEFAULT_MAX_TEXT_LENGTH = 2000


def utc_now() -> str:
    return dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def resolve_env_placeholders(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: resolve_env_placeholders(item) for key, item in value.items()}
    if isinstance(value, list):
        return [resolve_env_placeholders(item) for item in value]
    if isinstance(value, str):
        match = re.fullmatch(r"\$\{([A-Z0-9_]+)(?::([^}]*))?}", value)
        if match:
            return os.environ.get(match.group(1), match.group(2) or "")
    return value


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        config = json.load(file)
    return resolve_env_placeholders(config)


def output_dir(config: dict[str, Any]) -> Path:
    return Path(config.get("output", {}).get("dir", "./outputs/report-ingest-analysis"))


def run_dir(config: dict[str, Any], run_id: str) -> Path:
    return output_dir(config) / "runs" / run_id


def state_path(config: dict[str, Any], run_id: str) -> Path:
    return run_dir(config, run_id) / "state.json"


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        redacted: dict[str, Any] = {}
        for key, item in value.items():
            if any(marker in key.lower() for marker in SENSITIVE_KEYS):
                redacted[key] = "***"
            else:
                redacted[key] = redact(item)
        return redacted
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def short_error(exc: BaseException, limit: int = 500) -> str:
    text = str(exc).replace("\n", " ").strip()
    return text[:limit]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def limit_text(value: Any, max_length: int) -> str:
    text = "" if value is None else str(value)
    if max_length <= 0 or len(text) <= max_length:
        return text
    return text[:max_length] + f"...[truncated {len(text) - max_length} chars]"


def normalize_rows(rows: list[dict[str, Any]], max_text_length: int) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for row in rows:
        normalized.append({
            key: limit_text(value, max_text_length) if key.lower().endswith("text") or key in {"diagnostics", "chunkText", "rawText", "cleanedText"} else value
            for key, value in row.items()
        })
    return normalized
