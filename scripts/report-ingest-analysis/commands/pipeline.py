#!/usr/bin/env python3
"""
Scripted report ingest, quality export, and search evaluation pipeline.

The script is intentionally dependency-light and lives under
scripts/report-ingest-analysis/commands/ so it can run in local and test
environments without changing the online Spring service.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import mimetypes
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable
from xml.sax.saxutils import escape


DEFAULT_CONFIG = Path(__file__).resolve().parents[1] / "config" / "config.example.json"
DEFAULT_LIMIT = 10
DEFAULT_MAX_TEXT_LENGTH = 2000
SENSITIVE_KEYS = ("api_key", "apikey", "authorization", "token", "password", "secret")


@dataclass
class ReportInput:
    local_path: str
    title: str
    source: str = ""
    institution: str = ""
    publish_date: str = ""
    source_url: str = ""
    fingerprint: str = ""
    collect_status: str = "collected"
    error_summary: str = ""


@dataclass
class ReportResult:
    fingerprint: str
    local_path: str
    title: str
    source: str = ""
    institution: str = ""
    publish_date: str = ""
    source_url: str = ""
    status: str = "pending"
    report_id: int | None = None
    chunk_count: int | None = None
    failure_stage: str = ""
    error_summary: str = ""
    skipped_reason: str = ""


@dataclass
class QueryResult:
    query: str
    status: str
    started_at: str
    finished_at: str = ""
    error_summary: str = ""
    top_results: list[dict[str, Any]] = field(default_factory=list)
    analysis: str = ""
    recommendation: str = ""
    risks: list[str] = field(default_factory=list)
    citations: list[str] = field(default_factory=list)


@dataclass
class RunState:
    run_id: str
    created_at: str
    config_path: str
    input_manifest: list[dict[str, Any]] = field(default_factory=list)
    results: list[dict[str, Any]] = field(default_factory=list)
    query_results: list[dict[str, Any]] = field(default_factory=list)
    output_files: dict[str, str] = field(default_factory=dict)
    status: str = "running"
    summary: dict[str, int] = field(default_factory=dict)


def utc_now() -> str:
    return dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        config = json.load(file)
    return resolve_env_placeholders(config)


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


def output_dir(config: dict[str, Any]) -> Path:
    return Path(config.get("output", {}).get("dir", "./outputs/report-ingest-analysis"))


def run_dir(config: dict[str, Any], run_id: str) -> Path:
    return output_dir(config) / "runs" / run_id


def state_path(config: dict[str, Any], run_id: str) -> Path:
    return run_dir(config, run_id) / "state.json"


def save_state(config: dict[str, Any], state: RunState) -> None:
    path = state_path(config, state.run_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        json.dump(asdict(state), file, ensure_ascii=False, indent=2)


def load_state(config: dict[str, Any], run_id: str) -> RunState:
    path = state_path(config, run_id)
    if not path.exists():
        raise FileNotFoundError(f"Run state not found: {path}")
    with path.open("r", encoding="utf-8") as file:
        data = json.load(file)
    return RunState(**data)


def new_run_state(config_path: Path) -> RunState:
    return RunState(
        run_id=dt.datetime.utcnow().strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:8],
        created_at=utc_now(),
        config_path=str(config_path),
    )


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


def infer_title(path: Path, metadata: dict[str, str]) -> str:
    return metadata.get("title") or metadata.get("标题") or path.stem


def metadata_from_row(row: dict[str, str]) -> dict[str, str]:
    return {
        "title": row.get("title") or row.get("标题") or "",
        "source": row.get("source") or row.get("来源") or "",
        "institution": row.get("institution") or row.get("机构") or "",
        "publish_date": row.get("publishDate") or row.get("publish_date") or row.get("发布日期") or "",
        "url": row.get("url") or row.get("URL") or row.get("下载地址") or "",
        "file_name": row.get("fileName") or row.get("file_name") or row.get("文件名") or "",
    }


def collect_local_reports(config: dict[str, Any]) -> list[ReportInput]:
    input_config = config.get("input", {})
    report_dir = Path(input_config.get("local_dir", "./reports"))
    limit = int(input_config.get("limit", DEFAULT_LIMIT))
    if not report_dir.exists():
        raise FileNotFoundError(f"Local report directory not found: {report_dir}")

    manifest = load_optional_manifest(report_dir / "manifest.csv")
    reports: list[ReportInput] = []
    for pdf_path in sorted(report_dir.glob("*.pdf"))[:limit]:
        metadata = manifest.get(pdf_path.name, {})
        reports.append(ReportInput(
            local_path=str(pdf_path),
            title=infer_title(pdf_path, metadata),
            source=metadata.get("source", "local"),
            institution=metadata.get("institution", ""),
            publish_date=metadata.get("publish_date", ""),
            source_url=metadata.get("url", ""),
            fingerprint=sha256_file(pdf_path),
        ))
    return reports


def load_optional_manifest(path: Path) -> dict[str, dict[str, str]]:
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        rows = csv.DictReader(file)
        metadata: dict[str, dict[str, str]] = {}
        for row in rows:
            normalized = metadata_from_row(row)
            file_name = normalized.get("file_name")
            if file_name:
                metadata[file_name] = normalized
        return metadata


def read_url_manifest(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        raise FileNotFoundError(f"URL manifest not found: {path}")
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        sample = file.read(2048)
        file.seek(0)
        if "," in sample or "\t" in sample:
            dialect = csv.Sniffer().sniff(sample, delimiters=",\t")
            return [metadata_from_row(row) for row in csv.DictReader(file, dialect=dialect)]
        return [{"url": line.strip()} for line in file if line.strip() and not line.strip().startswith("#")]


def safe_download_name(url: str, index: int) -> str:
    parsed = urllib.parse.urlparse(url)
    name = Path(urllib.parse.unquote(parsed.path)).name
    if not name.lower().endswith(".pdf"):
        name = f"report-{index:02d}.pdf"
    return re.sub(r"[^A-Za-z0-9._-]+", "_", name)


def download_pdf(url: str, target: Path, timeout: int = 60) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "codex-ai-report-script/1.0"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("wb") as file:
            file.write(response.read())
    if target.stat().st_size == 0:
        raise RuntimeError(f"Downloaded file is empty: {url}")


def collect_url_reports(config: dict[str, Any], state: RunState) -> list[ReportInput]:
    input_config = config.get("input", {})
    manifest_path = Path(input_config.get("url_manifest", "./reports/report_urls.csv"))
    download_dir = Path(input_config.get("download_dir", run_dir(config, state.run_id) / "downloads"))
    limit = int(input_config.get("limit", DEFAULT_LIMIT))
    rows = read_url_manifest(manifest_path)[:limit]

    reports: list[ReportInput] = []
    for index, row in enumerate(rows, start=1):
        url = row.get("url", "").strip()
        if not url:
            reports.append(ReportInput(local_path="", title=row.get("title", ""), collect_status="failed", error_summary="Missing URL"))
            continue
        target = download_dir / safe_download_name(url, index)
        try:
            download_pdf(url, target)
            reports.append(ReportInput(
                local_path=str(target),
                title=row.get("title") or target.stem,
                source=row.get("source") or "url_manifest",
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                source_url=url,
                fingerprint=sha256_file(target),
                collect_status="downloaded",
            ))
        except (OSError, urllib.error.URLError, RuntimeError) as exc:
            reports.append(ReportInput(
                local_path=str(target),
                title=row.get("title") or safe_download_name(url, index),
                source=row.get("source") or "url_manifest",
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                source_url=url,
                collect_status="failed",
                error_summary=short_error(exc),
            ))
    return reports


def collect_inputs(config: dict[str, Any], state: RunState) -> list[ReportInput]:
    mode = config.get("input", {}).get("mode", "local_dir")
    if mode == "local_dir":
        reports = collect_local_reports(config)
    elif mode == "url_manifest":
        reports = collect_url_reports(config, state)
    else:
        raise ValueError(f"Unsupported input mode: {mode}")
    if len(reports) < int(config.get("input", {}).get("limit", DEFAULT_LIMIT)):
        print(f"Collected {len(reports)} reports; fewer than configured limit.")
    state.input_manifest = [asdict(report) for report in reports]
    return reports


def successful_fingerprints(config: dict[str, Any], current_run_id: str) -> dict[str, dict[str, Any]]:
    root = output_dir(config) / "runs"
    if not root.exists():
        return {}
    fingerprints: dict[str, dict[str, Any]] = {}
    for path in root.glob("*/state.json"):
        if path.parent.name == current_run_id:
            continue
        try:
            with path.open("r", encoding="utf-8") as file:
                data = json.load(file)
        except (OSError, json.JSONDecodeError):
            continue
        for result in data.get("results", []):
            if result.get("status") == "success" and result.get("fingerprint"):
                fingerprints[result["fingerprint"]] = result
    return fingerprints


def multipart_form(fields: dict[str, str], file_field: str, file_path: Path) -> tuple[bytes, str]:
    boundary = "----codex-ai-report-" + uuid.uuid4().hex
    body = bytearray()
    for key, value in fields.items():
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode("utf-8"))
        body.extend(str(value).encode("utf-8"))
        body.extend(b"\r\n")
    content_type = mimetypes.guess_type(str(file_path))[0] or "application/pdf"
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(
        f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"\r\n'.encode("utf-8")
    )
    body.extend(f"Content-Type: {content_type}\r\n\r\n".encode("utf-8"))
    body.extend(file_path.read_bytes())
    body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode("utf-8"))
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def post_json(url: str, payload: dict[str, Any], timeout: int = 120) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def upload_report(config: dict[str, Any], report: ReportInput) -> dict[str, Any]:
    api = config.get("api", {})
    endpoint = urllib.parse.urljoin(api.get("base_url", "http://localhost:8080").rstrip("/") + "/", api.get("upload_path", "/api/reports/upload").lstrip("/"))
    pdf_path = Path(report.local_path)
    if not pdf_path.exists():
        raise FileNotFoundError(f"PDF not found: {pdf_path}")
    fields = {
        "title": report.title,
        "source": report.source or report.source_url or "script",
        "institution": report.institution,
    }
    if report.publish_date:
        fields["publishDate"] = report.publish_date
    body, content_type = multipart_form(fields, "file", pdf_path)
    request = urllib.request.Request(endpoint, data=body, headers={"Content-Type": content_type}, method="POST")
    with urllib.request.urlopen(request, timeout=900) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if "reportId" not in payload:
        raise RuntimeError(f"Upload response missing reportId: {json.dumps(payload, ensure_ascii=False)[:500]}")
    return payload


def ingest_reports(config: dict[str, Any], state: RunState, reports: list[ReportInput], force: bool) -> list[ReportResult]:
    previous_success = successful_fingerprints(config, state.run_id)
    continue_on_error = bool(config.get("runtime", {}).get("continue_on_error", True))
    results: list[ReportResult] = []

    for index, report in enumerate(reports, start=1):
        print(f"[{index}/{len(reports)}] {report.title}")
        result = ReportResult(
            fingerprint=report.fingerprint,
            local_path=report.local_path,
            title=report.title,
            source=report.source,
            institution=report.institution,
            publish_date=report.publish_date,
            source_url=report.source_url,
        )
        if report.collect_status == "failed":
            result.status = "failed"
            result.failure_stage = "collect"
            result.error_summary = report.error_summary
            results.append(result)
            if not continue_on_error:
                break
            continue
        if report.fingerprint in previous_success and not force:
            previous = previous_success[report.fingerprint]
            result.status = "skipped"
            result.report_id = previous.get("report_id")
            result.skipped_reason = "already imported in previous successful run"
            results.append(result)
            print("  skipped: already imported")
            continue
        try:
            payload = upload_report(config, report)
            result.status = "success"
            result.report_id = int(payload["reportId"])
            if payload.get("chunkCount") is not None:
                result.chunk_count = int(payload["chunkCount"])
            print(f"  success: reportId={result.report_id}, chunks={result.chunk_count}")
        except (OSError, urllib.error.URLError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
            result.status = "failed"
            result.failure_stage = "upload"
            result.error_summary = short_error(exc)
            print(f"  failed: {result.error_summary}")
            results.append(result)
            if not continue_on_error:
                break
            continue
        results.append(result)
        state.results = [asdict(item) for item in results]
        update_summary(state)
        save_state(config, state)

    state.results = [asdict(item) for item in results]
    update_summary(state)
    save_state(config, state)
    return results


def report_ids_from_state(state: RunState) -> list[int]:
    ids: list[int] = []
    for result in state.results:
        if result.get("status") in {"success", "skipped"} and result.get("report_id") is not None:
            ids.append(int(result["report_id"]))
    return ids


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


def report_ids_by_time_range(config: dict[str, Any], created_from: str, created_to: str) -> list[int]:
    rows = mysql_json_rows(config, report_ids_by_time_sql(created_from, created_to))
    return [int(row["reportId"]) for row in rows]


def reports_sql(report_ids: list[int]) -> str:
    return f"""
SELECT JSON_OBJECT(
    'reportId', id,
    'title', title,
    'source', source,
    'institution', IFNULL(institution, ''),
    'publishDate', IFNULL(DATE_FORMAT(publish_date, '%Y-%m-%d'), ''),
    'createdAt', DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s')
)
FROM report_document
WHERE id IN ({sql_in(report_ids)})
ORDER BY id;
""".strip()


def ocr_pages_sql(report_ids: list[int]) -> str:
    return f"""
SELECT JSON_OBJECT(
    'reportId', report_id,
    'pageNumber', page_number,
    'rawText', IFNULL(raw_text, ''),
    'cleanedText', IFNULL(cleaned_text, ''),
    'diagnostics', IFNULL(diagnostics, ''),
    'createdAt', DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s')
)
FROM report_ocr_page
WHERE report_id IN ({sql_in(report_ids)})
ORDER BY report_id, page_number;
""".strip()


def paragraph_atoms_sql(report_ids: list[int]) -> str:
    return f"""
SELECT JSON_OBJECT(
    'reportId', report_id,
    'paragraphId', paragraph_id,
    'pageNumber', IFNULL(page_number, 0),
    'sectionPath', IFNULL(section_path, ''),
    'tokenCount', IFNULL(token_count, 0),
    'diagnostics', IFNULL(diagnostics, ''),
    'paragraphText', IFNULL(paragraph_text, ''),
    'createdAt', DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s')
)
FROM report_paragraph_atom
WHERE report_id IN ({sql_in(report_ids)})
ORDER BY report_id, paragraph_id;
""".strip()


def chunks_sql(report_ids: list[int]) -> str:
    return f"""
SELECT JSON_OBJECT(
    'reportId', report_id,
    'chunkUid', IFNULL(chunk_uid, ''),
    'parentChunkUid', IFNULL(parent_chunk_uid, ''),
    'chunkType', chunk_type,
    'sectionPath', IFNULL(section_path, ''),
    'tokenCount', IFNULL(token_count, 0),
    'pageNumber', IFNULL(page_number, 0),
    'startPageNumber', IFNULL(start_page_number, 0),
    'endPageNumber', IFNULL(end_page_number, 0),
    'filterReason', IFNULL(filter_reason, ''),
    'diagnostics', IFNULL(diagnostics, ''),
    'vectorStored', IFNULL(vector_stored, FALSE),
    'chunkText', IFNULL(chunk_text, ''),
    'createdAt', DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s')
)
FROM report_chunk
WHERE report_id IN ({sql_in(report_ids)})
ORDER BY report_id, chunk_type, chunk_uid;
""".strip()


def filtered_chunks_sql(report_ids: list[int]) -> str:
    return f"""
SELECT JSON_OBJECT(
    'reportId', report_id,
    'chunkUid', IFNULL(chunk_uid, ''),
    'parentChunkUid', IFNULL(parent_chunk_uid, ''),
    'chunkType', chunk_type,
    'sectionPath', IFNULL(section_path, ''),
    'tokenCount', IFNULL(token_count, 0),
    'startPageNumber', IFNULL(start_page_number, 0),
    'endPageNumber', IFNULL(end_page_number, 0),
    'filterReason', IFNULL(filter_reason, ''),
    'diagnostics', IFNULL(diagnostics, ''),
    'chunkText', IFNULL(chunk_text, ''),
    'createdAt', DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s')
)
FROM report_chunk_diagnostic
WHERE report_id IN ({sql_in(report_ids)})
  AND kept = FALSE
ORDER BY report_id, chunk_type, chunk_uid;
""".strip()


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


def query_quality_data(config: dict[str, Any], report_ids: list[int]) -> dict[str, list[dict[str, Any]]]:
    if not report_ids:
        return {"reports": [], "ocr_pages": [], "paragraph_atoms": [], "chunks": [], "filtered_chunks": []}
    max_text_length = int(config.get("output", {}).get("max_text_length", DEFAULT_MAX_TEXT_LENGTH))
    return {
        "reports": mysql_json_rows(config, reports_sql(report_ids)),
        "ocr_pages": normalize_rows(mysql_json_rows(config, ocr_pages_sql(report_ids)), max_text_length),
        "paragraph_atoms": normalize_rows(mysql_json_rows(config, paragraph_atoms_sql(report_ids)), max_text_length),
        "chunks": normalize_rows(mysql_json_rows(config, chunks_sql(report_ids)), max_text_length),
        "filtered_chunks": normalize_rows(mysql_json_rows(config, filtered_chunks_sql(report_ids)), max_text_length),
    }


def cell_ref(row: int, col: int) -> str:
    letters = ""
    value = col
    while value:
        value, remainder = divmod(value - 1, 26)
        letters = chr(65 + remainder) + letters
    return f"{letters}{row}"


def sheet_xml(rows: list[list[Any]]) -> str:
    xml_rows: list[str] = []
    for row_index, row in enumerate(rows, start=1):
        cells: list[str] = []
        for col_index, value in enumerate(row, start=1):
            ref = cell_ref(row_index, col_index)
            if value is None:
                cells.append(f'<c r="{ref}"/>')
            elif isinstance(value, (int, float)) and not isinstance(value, bool):
                cells.append(f'<c r="{ref}"><v>{value}</v></c>')
            else:
                text = escape(str(value), {'"': '&quot;'})
                cells.append(f'<c r="{ref}" t="inlineStr"><is><t>{text}</t></is></c>')
        xml_rows.append(f'<row r="{row_index}">{"".join(cells)}</row>')
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
        '<sheetData>' + "".join(xml_rows) + '</sheetData></worksheet>'
    )


def write_xlsx(output_path: Path, sheets: list[tuple[str, list[list[Any]]]]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    workbook_sheets = "".join(
        f'<sheet name="{escape(name)}" sheetId="{idx}" r:id="rId{idx}"/>'
        for idx, (name, _) in enumerate(sheets, start=1)
    )
    workbook_rels = "".join(
        f'<Relationship Id="rId{idx}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{idx}.xml"/>'
        for idx in range(1, len(sheets) + 1)
    )
    content_types = "".join(
        f'<Override PartName="/xl/worksheets/sheet{idx}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        for idx in range(1, len(sheets) + 1)
    )
    now = utc_now()
    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as workbook:
        workbook.writestr("[Content_Types].xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                          '<Default Extension="xml" ContentType="application/xml"/>'
                          '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                          '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
                          '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
                          + content_types + '</Types>')
        workbook.writestr("_rels/.rels",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                          '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                          '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
                          '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>'
                          '</Relationships>')
        workbook.writestr("xl/workbook.xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                          'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                          f'<sheets>{workbook_sheets}</sheets></workbook>')
        workbook.writestr("xl/_rels/workbook.xml.rels",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                          + workbook_rels + '</Relationships>')
        workbook.writestr("docProps/core.xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
                          'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" '
                          'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
                          '<dc:title>report ingest analysis</dc:title><dc:creator>codex-ai-report</dc:creator>'
                          f'<dcterms:created xsi:type="dcterms:W3CDTF">{now}</dcterms:created>'
                          f'<dcterms:modified xsi:type="dcterms:W3CDTF">{now}</dcterms:modified>'
                          '</cp:coreProperties>')
        workbook.writestr("docProps/app.xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
                          '<Application>codex-ai-report</Application></Properties>')
        for idx, (_, rows) in enumerate(sheets, start=1):
            workbook.writestr(f"xl/worksheets/sheet{idx}.xml", sheet_xml(rows))


def rows_for_sheet(rows: list[dict[str, Any]], headers: list[str]) -> list[list[Any]]:
    return [headers] + [[row.get(header, "") for header in headers] for row in rows]


def build_quality_workbook(config: dict[str, Any], state: RunState, report_ids: list[int]) -> Path:
    data = query_quality_data(config, report_ids)
    if not data["reports"]:
        raise RuntimeError("No quality data found for the selected run/report IDs.")
    summary = [
        ["metric", "value"],
        ["runId", state.run_id],
        ["reportCount", len(data["reports"])],
        ["ocrPageCount", len(data["ocr_pages"])],
        ["paragraphAtomCount", len(data["paragraph_atoms"])],
        ["chunkCount", len(data["chunks"])],
        ["filteredChunkCount", len(data["filtered_chunks"])],
    ]
    output_path = run_dir(config, state.run_id) / f"quality-{state.run_id}.xlsx"
    write_xlsx(output_path, [
        ("reports", rows_for_sheet(data["reports"], ["reportId", "title", "source", "institution", "publishDate", "createdAt"])),
        ("ocr_pages", rows_for_sheet(data["ocr_pages"], ["reportId", "pageNumber", "rawText", "cleanedText", "diagnostics", "createdAt"])),
        ("paragraph_atoms", rows_for_sheet(data["paragraph_atoms"], ["reportId", "paragraphId", "pageNumber", "sectionPath", "tokenCount", "diagnostics", "paragraphText", "createdAt"])),
        ("chunks", rows_for_sheet(data["chunks"], ["reportId", "chunkUid", "parentChunkUid", "chunkType", "sectionPath", "tokenCount", "startPageNumber", "endPageNumber", "vectorStored", "chunkText"])),
        ("filtered_chunks", rows_for_sheet(data["filtered_chunks"], ["reportId", "chunkUid", "parentChunkUid", "chunkType", "sectionPath", "tokenCount", "filterReason", "diagnostics", "chunkText"])),
        ("summary", summary),
    ])
    state.output_files["quality"] = str(output_path)
    save_state(config, state)
    return output_path


def recommend_url(config: dict[str, Any]) -> str:
    api = config.get("api", {})
    return urllib.parse.urljoin(
        api.get("base_url", "http://localhost:8080").rstrip("/") + "/",
        api.get("recommend_path", "/api/reports/recommend").lstrip("/"),
    )


def evaluate_queries(config: dict[str, Any], state: RunState) -> list[QueryResult]:
    queries = config.get("evaluation", {}).get("queries", [])
    continue_on_error = bool(config.get("runtime", {}).get("continue_on_error", True))
    if not queries:
        result = QueryResult(query="", status="skipped", started_at=utc_now(), finished_at=utc_now(), error_summary="No evaluation queries configured")
        state.query_results = [asdict(result)]
        save_state(config, state)
        return [result]

    results: list[QueryResult] = []
    for query in queries:
        started_at = utc_now()
        result = QueryResult(query=query, status="running", started_at=started_at)
        try:
            payload = post_json(recommend_url(config), {"query": query})
            result.status = "success"
            result.top_results = payload.get("top5", [])
            result.analysis = payload.get("analysis", "")
            result.recommendation = payload.get("recommendation", "")
            result.risks = payload.get("risks", []) or []
            result.citations = payload.get("citations", []) or []
        except (OSError, urllib.error.URLError, RuntimeError, json.JSONDecodeError) as exc:
            result.status = "failed"
            result.error_summary = short_error(exc)
            if not continue_on_error:
                result.finished_at = utc_now()
                results.append(result)
                break
        result.finished_at = utc_now()
        results.append(result)
        state.query_results = [asdict(item) for item in results]
        save_state(config, state)
    return results


def build_search_workbook(config: dict[str, Any], state: RunState) -> Path:
    query_results = [QueryResult(**row) for row in state.query_results]
    if not query_results:
        raise RuntimeError("No search evaluation results found.")

    query_rows: list[dict[str, Any]] = []
    top_rows: list[dict[str, Any]] = []
    recommendation_rows: list[dict[str, Any]] = []
    manual_rows: list[dict[str, Any]] = []
    max_text_length = int(config.get("output", {}).get("max_text_length", DEFAULT_MAX_TEXT_LENGTH))
    for query_result in query_results:
        query_rows.append({
            "query": query_result.query,
            "startedAt": query_result.started_at,
            "finishedAt": query_result.finished_at,
            "status": query_result.status,
            "errorSummary": query_result.error_summary,
        })
        recommendation_rows.append({
            "query": query_result.query,
            "analysis": limit_text(query_result.analysis, max_text_length),
            "recommendation": limit_text(query_result.recommendation, max_text_length),
            "risks": limit_text(json.dumps(query_result.risks, ensure_ascii=False), max_text_length),
            "citations": limit_text(json.dumps(query_result.citations, ensure_ascii=False), max_text_length),
        })
        for rank, item in enumerate(query_result.top_results, start=1):
            top_row = {
                "query": query_result.query,
                "rank": rank,
                "score": item.get("score", item.get("distance", "")),
                "reportTitle": item.get("title", item.get("reportTitle", "")),
                "source": item.get("source", ""),
                "sectionPath": item.get("sectionPath", ""),
                "chunkUid": item.get("chunkUid", ""),
                "parentChunkUid": item.get("parentChunkUid", ""),
                "chunkText": limit_text(item.get("chunkText", item.get("text", "")), max_text_length),
                "parentContext": limit_text(item.get("parentContext", item.get("evidenceText", "")), max_text_length),
            }
            top_rows.append(top_row)
            manual_rows.append({
                "query": query_result.query,
                "rank": rank,
                "chunkUid": top_row["chunkUid"],
                "isRelevant": "",
                "relevanceLevel": "",
                "issueNotes": "",
                "suggestedAction": "",
            })

    output_path = run_dir(config, state.run_id) / f"search-evaluation-{state.run_id}.xlsx"
    write_xlsx(output_path, [
        ("queries", rows_for_sheet(query_rows, ["query", "startedAt", "finishedAt", "status", "errorSummary"])),
        ("top_results", rows_for_sheet(top_rows, ["query", "rank", "score", "reportTitle", "source", "sectionPath", "chunkUid", "parentChunkUid", "chunkText", "parentContext"])),
        ("recommendations", rows_for_sheet(recommendation_rows, ["query", "analysis", "recommendation", "risks", "citations"])),
        ("manual_review", rows_for_sheet(manual_rows, ["query", "rank", "chunkUid", "isRelevant", "relevanceLevel", "issueNotes", "suggestedAction"])),
    ])
    state.output_files["search_evaluation"] = str(output_path)
    save_state(config, state)
    return output_path


def update_summary(state: RunState) -> None:
    counts = {"success": 0, "failed": 0, "skipped": 0, "pending": 0}
    for row in state.results:
        status = row.get("status", "pending")
        counts[status] = counts.get(status, 0) + 1
    state.summary = counts


def print_summary(state: RunState) -> None:
    update_summary(state)
    print(f"runId: {state.run_id}")
    print(f"status: {state.status}")
    print(
        "reports: "
        f"success={state.summary.get('success', 0)}, "
        f"failed={state.summary.get('failed', 0)}, "
        f"skipped={state.summary.get('skipped', 0)}"
    )
    if state.output_files:
        print("outputs:")
        for name, path in state.output_files.items():
            print(f"  {name}: {path}")


def parse_report_ids(value: str) -> list[int]:
    if not value:
        return []
    return [int(item.strip()) for item in value.split(",") if item.strip()]


def command_ingest(args: argparse.Namespace) -> int:
    config_path = Path(args.config)
    config = load_config(config_path)
    state = new_run_state(config_path)
    save_state(config, state)
    try:
        reports = collect_inputs(config, state)
        save_state(config, state)
        ingest_reports(config, state, reports, args.force)
        state.status = "ingest_complete"
        save_state(config, state)
        print_summary(state)
        return 0
    except Exception as exc:
        state.status = "failed"
        save_state(config, state)
        print(f"ERROR: {short_error(exc)}", file=sys.stderr)
        return 1


def command_export_quality(args: argparse.Namespace) -> int:
    config_path = Path(args.config)
    config = load_config(config_path)
    state = load_state(config, args.run_id) if args.run_id else new_run_state(config_path)
    if args.created_from or args.created_to:
        report_ids = report_ids_by_time_range(config, args.created_from, args.created_to)
    else:
        report_ids = parse_report_ids(args.report_ids) or report_ids_from_state(state)
    try:
        output_path = build_quality_workbook(config, state, report_ids)
        print(f"quality_export: {output_path}")
        print_summary(state)
        return 0
    except Exception as exc:
        print(f"ERROR: {short_error(exc)}", file=sys.stderr)
        return 1


def command_evaluate_search(args: argparse.Namespace) -> int:
    config = load_config(Path(args.config))
    state = load_state(config, args.run_id)
    try:
        evaluate_queries(config, state)
        output_path = build_search_workbook(config, state)
        print(f"search_evaluation: {output_path}")
        print_summary(state)
        return 0
    except Exception as exc:
        print(f"ERROR: {short_error(exc)}", file=sys.stderr)
        return 1


def command_run_all(args: argparse.Namespace) -> int:
    config_path = Path(args.config)
    config = load_config(config_path)
    state = new_run_state(config_path)
    save_state(config, state)
    try:
        reports = collect_inputs(config, state)
        ingest_reports(config, state, reports, args.force)
        report_ids = report_ids_from_state(state)
        if report_ids:
            build_quality_workbook(config, state, report_ids)
        else:
            print("quality export skipped: no successful report IDs")
        if report_ids:
            evaluate_queries(config, state)
            build_search_workbook(config, state)
        else:
            print("search evaluation skipped: no successful imports")
        state.status = "complete"
        save_state(config, state)
        print_summary(state)
        return 0
    except Exception as exc:
        state.status = "failed"
        save_state(config, state)
        print(f"ERROR: {short_error(exc)}", file=sys.stderr)
        return 1


def command_summary(args: argparse.Namespace) -> int:
    config = load_config(Path(args.config))
    try:
        state = load_state(config, args.run_id)
        print_summary(state)
        return 0
    except Exception as exc:
        print(f"ERROR: {short_error(exc)}", file=sys.stderr)
        return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run report ingest, quality export, and search evaluation scripts.")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG), help="Path to JSON config file.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ingest = subparsers.add_parser("ingest", help="Collect and import report PDFs.")
    ingest.add_argument("--force", action="store_true", help="Re-import files even when a previous successful run has the same fingerprint.")
    ingest.set_defaults(func=command_ingest)

    run_all = subparsers.add_parser("run-all", help="Run collect, ingest, quality export, and search evaluation.")
    run_all.add_argument("--force", action="store_true", help="Re-import files even when a previous successful run has the same fingerprint.")
    run_all.set_defaults(func=command_run_all)

    export_quality = subparsers.add_parser("export-quality", help="Export OCR/chunk quality workbook for an existing run.")
    export_quality.add_argument("--run-id", default="")
    export_quality.add_argument("--report-ids", default="", help="Comma-separated report IDs. Defaults to successful report IDs in run state.")
    export_quality.add_argument("--created-from", default="", help="Report created_at lower bound: YYYY-MM-DD or YYYY-MM-DD HH:MM:SS.")
    export_quality.add_argument("--created-to", default="", help="Report created_at upper bound: YYYY-MM-DD or YYYY-MM-DD HH:MM:SS.")
    export_quality.set_defaults(func=command_export_quality)

    evaluate = subparsers.add_parser("evaluate-search", help="Run configured recommendation queries and export evaluation workbook.")
    evaluate.add_argument("--run-id", required=True)
    evaluate.set_defaults(func=command_evaluate_search)

    summary = subparsers.add_parser("summary", help="Show run summary and output file paths.")
    summary.add_argument("--run-id", required=True)
    summary.set_defaults(func=command_summary)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    start = time.time()
    exit_code = args.func(args)
    print(f"elapsedSeconds: {round(time.time() - start, 2)}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
