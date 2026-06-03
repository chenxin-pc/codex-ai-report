from __future__ import annotations

import json
import mimetypes
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from models import ReportInput


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
    if report.authors:
        fields["authors"] = report.authors
    if report.theme_tags:
        fields["themeTags"] = report.theme_tags
    if report.industry_tags:
        fields["industryTags"] = report.industry_tags
    if report.company_tags:
        fields["companyTags"] = report.company_tags
    if report.ticker_tags:
        fields["tickerTags"] = report.ticker_tags
    body, content_type = multipart_form(fields, "file", pdf_path)
    request = urllib.request.Request(endpoint, data=body, headers={"Content-Type": content_type}, method="POST")
    with urllib.request.urlopen(request, timeout=900) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if "reportId" not in payload:
        raise RuntimeError(f"Upload response missing reportId: {json.dumps(payload, ensure_ascii=False)[:500]}")
    return payload
