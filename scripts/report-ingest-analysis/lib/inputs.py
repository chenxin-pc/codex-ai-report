from __future__ import annotations

import csv
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict
from pathlib import Path
from typing import Any

from common import DEFAULT_LIMIT, sha256_file, short_error
from models import ReportInput, RunState


def infer_title(path: Path, metadata: dict[str, str]) -> str:
    return metadata.get("title") or metadata.get("标题") or path.stem


def metadata_from_row(row: dict[str, str]) -> dict[str, str]:
    return {
        "title": row.get("title") or row.get("标题") or "",
        "source": row.get("source") or row.get("来源") or "",
        "institution": row.get("institution") or row.get("机构") or "",
        "publish_date": row.get("publishDate") or row.get("publish_date") or row.get("发布日期") or "",
        "url": row.get("url") or row.get("URL") or row.get("下载地址") or row.get("pdfUrl") or "",
        "file_name": row.get("fileName") or row.get("file_name") or row.get("文件名") or "",
    }


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
    download_dir = Path(input_config.get("download_dir", "./outputs/downloads"))
    limit = int(input_config.get("limit", DEFAULT_LIMIT))
    rows = read_url_manifest(manifest_path)[:limit]
    return _collect_rows_download(rows, download_dir, "url_manifest")


def collect_eastmoney_reports(config: dict[str, Any], state: RunState) -> list[ReportInput]:
    input_config = config.get("input", {})
    limit = int(input_config.get("limit", DEFAULT_LIMIT))
    manifest_path = input_config.get("eastmoney_manifest", "")
    manifest_url = input_config.get("eastmoney_manifest_url", "")
    if manifest_path:
        rows = read_url_manifest(Path(manifest_path))[:limit]
    elif manifest_url:
        request = urllib.request.Request(manifest_url, headers={"User-Agent": "codex-ai-report-script/1.0"})
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if not isinstance(payload, list):
            raise ValueError("eastmoney manifest url must return a JSON array")
        rows = [metadata_from_row(item if isinstance(item, dict) else {}) for item in payload][:limit]
    else:
        raise ValueError("eastmoney mode requires input.eastmoney_manifest or input.eastmoney_manifest_url")
    download_dir = Path(input_config.get("download_dir", "./outputs/downloads")) / "eastmoney"
    reports = _collect_rows_download(rows, download_dir, "eastmoney")
    for report in reports:
        if report.source == "url_manifest":
            report.source = "eastmoney"
    return reports


def _collect_rows_download(rows: list[dict[str, str]], download_dir: Path, default_source: str) -> list[ReportInput]:
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
                source=row.get("source") or default_source,
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
                source=row.get("source") or default_source,
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
    elif mode == "eastmoney":
        reports = collect_eastmoney_reports(config, state)
    else:
        raise ValueError(f"Unsupported input mode: {mode}")
    if len(reports) < int(config.get("input", {}).get("limit", DEFAULT_LIMIT)):
        print(f"Collected {len(reports)} reports; fewer than configured limit.")
    state.input_manifest = [asdict(report) for report in reports]
    return reports
