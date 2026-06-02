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

EASTMONEY_REPORT_API = "https://reportapi.eastmoney.com/report/list"
EASTMONEY_PDF_TEMPLATE = "https://pdf.dfcfw.com/pdf/H3_{info_code}_1.pdf"
EASTMONEY_HEADERS = {
    "User-Agent": "Mozilla/5.0 codex-ai-report-script/1.0",
    "Referer": "https://data.eastmoney.com/",
}
EASTMONEY_REQUIRED_METADATA = {
    "title": "title",
    "source_url": "PDF URL",
    "company_tags": "companyTags",
    "ticker_tags": "tickerTags",
    "industry_tags": "industryTags",
}


def infer_title(path: Path, metadata: dict[str, str]) -> str:
    return metadata.get("title") or metadata.get("标题") or ""


def row_value(row: dict[str, str], *keys: str) -> str:
    normalized = {
        str(key).strip().lower(): "" if value is None else str(value).strip()
        for key, value in row.items()
        if key is not None
    }
    for key in keys:
        value = normalized.get(key.lower())
        if value:
            return value
    return ""


def metadata_from_row(row: dict[str, str]) -> dict[str, str]:
    return {
        "title": row_value(row, "title", "标题", "reportTitle", "report_name", "报告名称"),
        "source": row_value(row, "source", "来源"),
        "institution": row_value(row, "institution", "机构", "orgName", "org_name", "orgSName", "机构名称"),
        "publish_date": row_value(row, "publishDate", "publish_date", "发布日期", "date", "日期"),
        "url": row_value(row, "url", "URL", "下载地址", "pdfUrl", "pdf_url"),
        "file_name": row_value(row, "fileName", "file_name", "文件名"),
        "pages": row_value(row, "pages", "pageCount", "page_count", "页数"),
        "authors": row_value(row, "authors", "author", "researcher", "analyst", "作者", "研报作者", "分析师"),
        "theme_tags": row_value(row, "themeTags", "theme_tags", "theme", "主题"),
        "industry_tags": row_value(row, "industryTags", "industry_tags", "industry", "industryName", "行业"),
        "company_tags": row_value(row, "companyTags", "company_tags", "company", "companyName", "stockName", "股票简称", "公司", "公司名称"),
        "ticker_tags": row_value(row, "tickerTags", "ticker_tags", "ticker", "code", "stockCode", "securityCode", "股票代码", "代码"),
    }


def title_error(title: str, url: str = "", file_name: str = "") -> str:
    text = (title or "").strip()
    if not text:
        return "Missing real report title"
    lowered = text.lower()
    if lowered.startswith("http://") or lowered.startswith("https://") or lowered.endswith(".pdf"):
        return "Report title must be a real title, not a URL or PDF filename"
    stems = {Path(file_name).stem.lower()} if file_name else set()
    if url:
        stems.add(Path(urllib.parse.unquote(urllib.parse.urlparse(url).path)).stem.lower())
    if lowered in stems:
        return "Report title must not be the PDF filename or generated file stem"
    if re.fullmatch(r"H\d+_AP\d+_\d+", text) or re.fullmatch(r"AP\d+", text):
        return "Report title looks like a generated Eastmoney file id"
    if re.fullmatch(r"(report|eastmoney)[_-]?\d+", lowered):
        return "Report title looks like a generated download name"
    return ""


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
        fingerprint = sha256_file(pdf_path)
        title = infer_title(pdf_path, metadata)
        error = title_error(title, file_name=pdf_path.name)
        if error:
            reports.append(ReportInput(
                local_path=str(pdf_path),
                title=title,
                source=metadata.get("source", "local"),
                institution=metadata.get("institution", ""),
                publish_date=metadata.get("publish_date", ""),
                source_url=metadata.get("url", ""),
                pages=metadata.get("pages", ""),
                authors=metadata.get("authors", ""),
                theme_tags=metadata.get("theme_tags", ""),
                industry_tags=metadata.get("industry_tags", ""),
                company_tags=metadata.get("company_tags", ""),
                ticker_tags=metadata.get("ticker_tags", ""),
                fingerprint=fingerprint,
                collect_status="failed",
                error_summary=error,
            ))
            continue
        reports.append(ReportInput(
            local_path=str(pdf_path),
            title=title,
            source=metadata.get("source", "local"),
            institution=metadata.get("institution", ""),
            publish_date=metadata.get("publish_date", ""),
            source_url=metadata.get("url", ""),
            pages=metadata.get("pages", ""),
            authors=metadata.get("authors", ""),
            theme_tags=metadata.get("theme_tags", ""),
            industry_tags=metadata.get("industry_tags", ""),
            company_tags=metadata.get("company_tags", ""),
            ticker_tags=metadata.get("ticker_tags", ""),
            fingerprint=fingerprint,
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


def eastmoney_api_params(input_config: dict[str, Any], page_no: int, page_size: int) -> dict[str, str]:
    return {
        "industryCode": str(input_config.get("industryCode", "*")),
        "pageSize": str(page_size),
        "industry": str(input_config.get("industry", "*")),
        "rating": str(input_config.get("rating", "*")),
        "ratingChange": str(input_config.get("ratingChange", "*")),
        "beginTime": str(input_config.get("beginTime", input_config.get("begin_time", "2000-01-01"))),
        "endTime": str(input_config.get("endTime", input_config.get("end_time", "2030-01-01"))),
        "pageNo": str(page_no),
        "fields": str(input_config.get("fields", "")),
        "qType": str(input_config.get("qType", "0")),
        "orgCode": str(input_config.get("orgCode", "")),
        "code": str(input_config.get("code", "")),
        "rcode": str(input_config.get("rcode", "")),
        "p": str(page_no),
        "pageNum": str(page_no),
        "pageNumber": str(page_no),
    }


def fetch_eastmoney_api_page(input_config: dict[str, Any], page_no: int, page_size: int) -> dict[str, Any]:
    query = urllib.parse.urlencode(eastmoney_api_params(input_config, page_no, page_size))
    request = urllib.request.Request(f"{EASTMONEY_REPORT_API}?{query}", headers=EASTMONEY_HEADERS)
    try:
        with urllib.request.urlopen(request, timeout=int(input_config.get("timeout", 60))) as response:
            return json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"Eastmoney API request failed on page {page_no}: {short_error(exc)}") from exc


def eastmoney_api_records(config: dict[str, Any]) -> list[dict[str, Any]]:
    input_config = config.get("input", {})
    limit = int(input_config.get("limit", DEFAULT_LIMIT))
    page_size = int(input_config.get("pageSize", input_config.get("page_size", min(max(limit, 1), 100))))
    records: list[dict[str, Any]] = []
    page_no = int(input_config.get("pageNo", input_config.get("page_no", 1)))
    while len(records) < limit:
        payload = fetch_eastmoney_api_page(input_config, page_no, page_size)
        rows = payload.get("data") or []
        if not isinstance(rows, list):
            raise RuntimeError(f"Eastmoney API page {page_no} returned invalid data")
        if not rows:
            break
        records.extend(item for item in rows if isinstance(item, dict))
        total_page = int(payload.get("TotalPage") or payload.get("totalPage") or page_no)
        if page_no >= total_page:
            break
        page_no += 1
    return records[:limit]


def first_value(record: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = record.get(key)
        if isinstance(value, list):
            value = ",".join(str(item).split(".", 1)[-1].strip() for item in value if str(item).strip())
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def normalize_eastmoney_api_record(record: dict[str, Any]) -> dict[str, str]:
    info_code = first_value(record, "infoCode")
    return {
        "title": first_value(record, "title"),
        "source": "东方财富",
        "institution": first_value(record, "orgSName", "orgName"),
        "publish_date": first_value(record, "publishDate")[:10],
        "url": EASTMONEY_PDF_TEMPLATE.format(info_code=info_code) if info_code else "",
        "pages": first_value(record, "attachPages"),
        "authors": first_value(record, "researcher", "author"),
        "theme_tags": first_value(record, "themeTags", "theme_tags", "theme", "concept", "conceptName", "conceptNames"),
        "industry_tags": first_value(record, "indvInduName", "industryName"),
        "company_tags": first_value(record, "stockName"),
        "ticker_tags": first_value(record, "stockCode"),
    }


def collect_eastmoney_api_reports(config: dict[str, Any], state: RunState) -> list[ReportInput]:
    input_config = config.get("input", {})
    download_dir = Path(input_config.get("download_dir", "./outputs/downloads")) / "eastmoney-api"
    rows = [normalize_eastmoney_api_record(record) for record in eastmoney_api_records(config)]
    return _collect_rows_download(rows, download_dir, "eastmoney_api", required_metadata=EASTMONEY_REQUIRED_METADATA)


def metadata_error(row: dict[str, str], required_metadata: dict[str, str] | None) -> str:
    if not required_metadata:
        return ""
    missing = [label for field, label in required_metadata.items() if not (row.get(field, "") or "").strip()]
    if missing:
        return "Missing required metadata: " + ", ".join(missing)
    return ""


def _collect_rows_download(
    rows: list[dict[str, str]],
    download_dir: Path,
    default_source: str,
    required_metadata: dict[str, str] | None = None,
) -> list[ReportInput]:
    reports: list[ReportInput] = []
    for index, row in enumerate(rows, start=1):
        url = row.get("url", "").strip()
        if not url:
            reports.append(ReportInput(
                local_path="",
                title=row.get("title", ""),
                source=row.get("source") or default_source,
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                pages=row.get("pages", ""),
                authors=row.get("authors", ""),
                theme_tags=row.get("theme_tags", ""),
                industry_tags=row.get("industry_tags", ""),
                company_tags=row.get("company_tags", ""),
                ticker_tags=row.get("ticker_tags", ""),
                collect_status="failed",
                error_summary="Missing required metadata: PDF URL" if required_metadata else "Missing URL",
            ))
            continue
        target = download_dir / safe_download_name(url, index)
        metadata_failure = metadata_error({**row, "source_url": url}, required_metadata)
        if metadata_failure:
            reports.append(ReportInput(
                local_path=str(target),
                title=row.get("title", ""),
                source=row.get("source") or default_source,
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                source_url=url,
                pages=row.get("pages", ""),
                authors=row.get("authors", ""),
                theme_tags=row.get("theme_tags", ""),
                industry_tags=row.get("industry_tags", ""),
                company_tags=row.get("company_tags", ""),
                ticker_tags=row.get("ticker_tags", ""),
                collect_status="failed",
                error_summary=metadata_failure,
            ))
            continue
        error = title_error(row.get("title", ""), url=url, file_name=target.name)
        if error:
            reports.append(ReportInput(
                local_path=str(target),
                title=row.get("title", ""),
                source=row.get("source") or default_source,
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                source_url=url,
                pages=row.get("pages", ""),
                authors=row.get("authors", ""),
                theme_tags=row.get("theme_tags", ""),
                industry_tags=row.get("industry_tags", ""),
                company_tags=row.get("company_tags", ""),
                ticker_tags=row.get("ticker_tags", ""),
                collect_status="failed",
                error_summary=error,
            ))
            continue
        try:
            download_pdf(url, target)
            reports.append(ReportInput(
                local_path=str(target),
                title=row.get("title", ""),
                source=row.get("source") or default_source,
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                source_url=url,
                pages=row.get("pages", ""),
                authors=row.get("authors", ""),
                theme_tags=row.get("theme_tags", ""),
                industry_tags=row.get("industry_tags", ""),
                company_tags=row.get("company_tags", ""),
                ticker_tags=row.get("ticker_tags", ""),
                fingerprint=sha256_file(target),
                collect_status="downloaded",
            ))
        except (OSError, urllib.error.URLError, RuntimeError) as exc:
            reports.append(ReportInput(
                local_path=str(target),
                title=row.get("title", ""),
                source=row.get("source") or default_source,
                institution=row.get("institution", ""),
                publish_date=row.get("publish_date", ""),
                source_url=url,
                pages=row.get("pages", ""),
                authors=row.get("authors", ""),
                theme_tags=row.get("theme_tags", ""),
                industry_tags=row.get("industry_tags", ""),
                company_tags=row.get("company_tags", ""),
                ticker_tags=row.get("ticker_tags", ""),
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
    elif mode == "eastmoney_api":
        reports = collect_eastmoney_api_reports(config, state)
    else:
        raise ValueError(f"Unsupported input mode: {mode}")
    if len(reports) < int(config.get("input", {}).get("limit", DEFAULT_LIMIT)):
        print(f"Collected {len(reports)} reports; fewer than configured limit.")
    state.input_manifest = [asdict(report) for report in reports]
    return reports
