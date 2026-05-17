from __future__ import annotations

import json
import re
import subprocess
from typing import Any, Iterable

from common import SENSITIVE_KEYS


def mysql_command(config: dict[str, Any], sql: str) -> list[str]:
    database = config.get("database", {})
    mysql_container = database.get("mysql_container", "")
    username = database.get("username", "root")
    password = database.get("password", "")
    db_name = database.get("database", "ai_report_rag")
    if mysql_container:
        return [
            "docker", "exec", str(mysql_container), "mysql", "--default-character-set=utf8mb4", f"-u{username}", f"-p{password}",
            "--batch", "--raw", "--skip-column-names", db_name, "-e", sql,
        ]
    return [
        database.get("mysql_command", "mysql"),
        "--default-character-set=utf8mb4",
        "-h", str(database.get("host", "localhost")),
        "-P", str(database.get("port", 3306)),
        f"-u{username}",
        f"-p{password}",
        "--batch", "--raw", "--skip-column-names", db_name, "-e", sql,
    ]


def run_command(args: list[str]) -> str:
    result = subprocess.run(args, check=False, capture_output=True)
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        stdout = result.stdout.decode("utf-8", errors="replace").strip()
        redacted = ["***" if any(marker in part.lower() for marker in SENSITIVE_KEYS) else part for part in args]
        raise RuntimeError(f"Command failed: {' '.join(redacted)}\n{stderr or stdout}")
    return result.stdout.decode("utf-8", errors="replace")


def mysql_json_rows(config: dict[str, Any], sql: str) -> list[dict[str, Any]]:
    output = run_command(mysql_command(config, sql))
    rows: list[dict[str, Any]] = []
    for line in output.splitlines():
        text = line.strip()
        if text:
            rows.append(json.loads(text))
    return rows


def sql_in(values: Iterable[int]) -> str:
    unique = sorted({int(value) for value in values})
    if not unique:
        return "NULL"
    return ",".join(str(value) for value in unique)


def validate_date_time(value: str, field_name: str) -> str:
    if not value:
        return ""
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}( \d{2}:\d{2}:\d{2})?", value):
        raise ValueError(f"{field_name} must use YYYY-MM-DD or YYYY-MM-DD HH:MM:SS")
    return value


def report_ids_by_time_sql(created_from: str, created_to: str) -> str:
    conditions: list[str] = []
    if created_from:
        conditions.append(f"created_at >= '{validate_date_time(created_from, 'created_from')}'")
    if created_to:
        conditions.append(f"created_at <= '{validate_date_time(created_to, 'created_to')}'")
    if not conditions:
        raise ValueError("created_from or created_to is required for time range query")
    return f"""
SELECT JSON_OBJECT('reportId', id)
FROM report_document
WHERE {' AND '.join(conditions)}
ORDER BY id;
""".strip()
