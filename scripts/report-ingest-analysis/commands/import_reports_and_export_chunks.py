#!/usr/bin/env python3
"""
Import Eastmoney report PDFs through the application upload API, then export
the imported document/chunk rows to an Excel workbook.

This script intentionally uses only Python standard library plus local CLI
tools (`curl`, `docker`) so it can run without adding project dependencies.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import urljoin
from xml.sax.saxutils import escape


DEFAULT_REPORT_DIR = Path("/Users/cxx/Desktop/东方财富研报_10篇_小于30页_20260514")
DEFAULT_OUTPUT = DEFAULT_REPORT_DIR / "导入切片明细.xlsx"


def run_command(args: list[str], *, capture: bool = True) -> str:
    result = subprocess.run(
        args,
        check=False,
        capture_output=capture,
    )
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip() if result.stderr else ""
        stdout = result.stdout.decode("utf-8", errors="replace").strip() if result.stdout else ""
        raise RuntimeError(f"Command failed: {' '.join(args)}\n{stderr or stdout}")
    return result.stdout.decode("utf-8", errors="replace") if capture else ""


def mysql_command(args: argparse.Namespace, sql: str) -> list[str]:
    if args.mysql_container:
        return [
            "docker",
            "exec",
            args.mysql_container,
            "mysql",
            f"-u{args.mysql_user}",
            f"-p{args.mysql_password}",
            "--batch",
            "--raw",
            "--skip-column-names",
            args.mysql_database,
            "-e",
            sql,
        ]
    return [
        args.mysql_command,
        "-h",
        args.mysql_host,
        "-P",
        str(args.mysql_port),
        "mysql",
    ][:-1] + [
        f"-u{args.mysql_user}",
        f"-p{args.mysql_password}",
        "--batch",
        "--raw",
        "--skip-column-names",
        args.mysql_database,
        "-e",
        sql,
    ]


def mysql_json_rows(sql: str, args: argparse.Namespace) -> list[dict[str, Any]]:
    output = run_command(mysql_command(args, sql))
    rows: list[dict[str, Any]] = []
    for line in output.splitlines():
        value = line.strip()
        if value:
            rows.append(json.loads(value))
    return rows


def mysql_scalar(sql: str, args: argparse.Namespace) -> int:
    output = run_command(mysql_command(args, sql))
    value = output.strip()
    return int(value) if value else 0


def read_manifest(report_dir: Path) -> list[dict[str, str]]:
    manifest_path = report_dir / "研报清单.csv"
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")
    with manifest_path.open("r", encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file))


def upload_report(base_url: str, row: dict[str, str], pdf_path: Path) -> dict[str, Any]:
    endpoint = urljoin(base_url.rstrip("/") + "/", "api/reports/upload")
    args = [
        "curl",
        "-sS",
        "--noproxy",
        "*",
        "-X",
        "POST",
        endpoint,
        "-F",
        f"file=@{pdf_path}",
        "-F",
        f"title={row.get('标题', pdf_path.stem)}",
        "-F",
        "source=东方财富",
        "-F",
        f"institution={row.get('机构', '')}",
    ]
    publish_date = row.get("发布日期", "").strip()
    if publish_date:
        args.extend(["-F", f"publishDate={publish_date}"])

    response = run_command(args)
    try:
        payload = json.loads(response)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Upload response is not JSON for {pdf_path.name}: {response[:500]}") from exc
    if "reportId" not in payload:
        raise RuntimeError(f"Upload failed for {pdf_path.name}: {response[:1000]}")
    return payload


def import_reports(report_dir: Path, base_url: str) -> list[dict[str, Any]]:
    rows = read_manifest(report_dir)
    uploads: list[dict[str, Any]] = []
    for row in rows:
        pdf_path = report_dir / row["文件名"]
        if not pdf_path.exists():
            raise FileNotFoundError(f"PDF not found: {pdf_path}")
        print(f"Importing: {pdf_path.name}")
        payload = upload_report(base_url, row, pdf_path)
        uploads.append({
            "fileName": pdf_path.name,
            "title": row.get("标题", ""),
            "institution": row.get("机构", ""),
            "publishDate": row.get("发布日期", ""),
            "pages": row.get("页数", ""),
            "reportId": payload.get("reportId"),
            "chunkCount": payload.get("chunkCount"),
            "message": payload.get("message", ""),
        })
        print(f"  -> reportId={payload.get('reportId')}, chunks={payload.get('chunkCount')}")
    return uploads


def chunk_export_sql(min_report_id: int) -> str:
    return f"""
SELECT JSON_OBJECT(
    'documentId', d.id,
    'title', d.title,
    'source', d.source,
    'institution', IFNULL(d.institution, ''),
    'publishDate', IFNULL(DATE_FORMAT(d.publish_date, '%Y-%m-%d'), ''),
    'documentCreatedAt', DATE_FORMAT(d.created_at, '%Y-%m-%d %H:%i:%s'),
    'chunkId', c.id,
    'chunkIndex', c.chunk_index,
    'chunkUid', c.chunk_uid,
    'parentChunkUid', IFNULL(c.parent_chunk_uid, ''),
    'chunkType', c.chunk_type,
    'sectionPath', IFNULL(c.section_path, ''),
    'tokenCount', IFNULL(c.token_count, 0),
    'pageNumber', IFNULL(c.page_number, 0),
    'chunkText', c.chunk_text,
    'chunkCreatedAt', DATE_FORMAT(c.created_at, '%Y-%m-%d %H:%i:%s')
)
FROM report_document d
JOIN report_chunk c ON c.report_id = d.id
WHERE d.id > {min_report_id}
ORDER BY d.id, FIELD(c.chunk_type, 'PARENT', 'CHILD'), c.chunk_index, c.id;
""".strip()


def document_export_sql(min_report_id: int) -> str:
    return f"""
SELECT JSON_OBJECT(
    'reportId', id,
    'title', title,
    'institution', IFNULL(institution, ''),
    'publishDate', IFNULL(DATE_FORMAT(publish_date, '%Y-%m-%d'), ''),
    'fileName', '',
    'pages', '',
    'message', 'imported',
    'chunkCount', (
        SELECT COUNT(*)
        FROM report_chunk c
        WHERE c.report_id = report_document.id
          AND c.chunk_type = 'CHILD'
    )
)
FROM report_document
WHERE id > {min_report_id}
ORDER BY id;
""".strip()


def collect_report_stats(chunks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    stats_by_report: dict[int, dict[str, Any]] = {}
    for row in chunks:
        report_id = int(row["documentId"])
        stats = stats_by_report.setdefault(report_id, {
            "documentId": report_id,
            "title": row["title"],
            "institution": row["institution"],
            "publishDate": row["publishDate"],
            "parentChunks": 0,
            "childChunks": 0,
            "totalChunks": 0,
            "childTokens": 0,
            "maxChildTokens": 0,
        })
        stats["totalChunks"] += 1
        if row["chunkType"] == "PARENT":
            stats["parentChunks"] += 1
        if row["chunkType"] == "CHILD":
            stats["childChunks"] += 1
            token_count = int(row.get("tokenCount") or 0)
            stats["childTokens"] += token_count
            stats["maxChildTokens"] = max(stats["maxChildTokens"], token_count)
    for stats in stats_by_report.values():
        child_count = stats["childChunks"]
        stats["avgChildTokens"] = round(stats["childTokens"] / child_count, 1) if child_count else 0
    return list(stats_by_report.values())


def cell_ref(row: int, col: int) -> str:
    letters = ""
    value = col
    while value:
        value, remainder = divmod(value - 1, 26)
        letters = chr(65 + remainder) + letters
    return f"{letters}{row}"


def sheet_xml(rows: list[list[Any]]) -> str:
    xml_rows = []
    for row_index, row in enumerate(rows, start=1):
        cells = []
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
        '<sheetData>'
        + "".join(xml_rows)
        + '</sheetData><autoFilter ref="A1:Z1"/></worksheet>'
    )


def write_xlsx(output_path: Path, sheets: list[tuple[str, list[list[Any]]]]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    now = dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
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
    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as workbook:
        workbook.writestr("[Content_Types].xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                          '<Default Extension="xml" ContentType="application/xml"/>'
                          '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                          '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
                          '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
                          + content_types
                          + '</Types>')
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
                          + workbook_rels
                          + '</Relationships>')
        workbook.writestr("docProps/core.xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
                          'xmlns:dc="http://purl.org/dc/elements/1.1/" '
                          'xmlns:dcterms="http://purl.org/dc/terms/" '
                          'xmlns:dcmitype="http://purl.org/dc/dcmitype/" '
                          'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
                          '<dc:title>研报切片导出</dc:title><dc:creator>codex-ai-report</dc:creator>'
                          f'<dcterms:created xsi:type="dcterms:W3CDTF">{now}</dcterms:created>'
                          f'<dcterms:modified xsi:type="dcterms:W3CDTF">{now}</dcterms:modified>'
                          '</cp:coreProperties>')
        workbook.writestr("docProps/app.xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" '
                          'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">'
                          '<Application>codex-ai-report</Application></Properties>')
        for idx, (_, rows) in enumerate(sheets, start=1):
            workbook.writestr(f"xl/worksheets/sheet{idx}.xml", sheet_xml(rows))


def build_workbook(output_path: Path, uploads: list[dict[str, Any]], chunks: list[dict[str, Any]]) -> None:
    upload_headers = ["reportId", "title", "institution", "publishDate", "pages", "chunkCount", "fileName", "message"]
    upload_rows = [upload_headers] + [[row.get(header, "") for header in upload_headers] for row in uploads]

    chunk_headers = [
        "documentId", "title", "institution", "publishDate", "chunkId", "chunkType", "chunkIndex",
        "sectionPath", "tokenCount", "pageNumber", "chunkUid", "parentChunkUid", "chunkText", "chunkCreatedAt",
    ]
    chunk_rows = [chunk_headers] + [[row.get(header, "") for header in chunk_headers] for row in chunks]

    stat_headers = [
        "documentId", "title", "institution", "publishDate", "parentChunks", "childChunks",
        "totalChunks", "avgChildTokens", "maxChildTokens",
    ]
    stat_rows = [stat_headers] + [[row.get(header, "") for header in stat_headers] for row in collect_report_stats(chunks)]

    write_xlsx(output_path, [
        ("导入概览", upload_rows),
        ("按报告统计", stat_rows),
        ("切片明细", chunk_rows),
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description="Import report PDFs and export chunk rows to xlsx.")
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--mysql-container", default="")
    parser.add_argument("--mysql-command", default="mysql")
    parser.add_argument("--mysql-host", default="localhost")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="123456")
    parser.add_argument("--mysql-database", default="ai_report")
    parser.add_argument("--skip-import", action="store_true")
    parser.add_argument("--min-report-id", type=int, default=None)
    args = parser.parse_args()

    min_report_id = args.min_report_id
    if min_report_id is None:
        min_report_id = mysql_scalar(
            "SELECT IFNULL(MAX(id), 0) FROM report_document;",
            args,
        )
    print(f"Current max report_document.id: {min_report_id}")

    if args.skip_import:
        uploads = mysql_json_rows(document_export_sql(min_report_id), args)
    else:
        uploads = import_reports(args.report_dir, args.base_url)

    chunks = mysql_json_rows(
        chunk_export_sql(min_report_id),
        args,
    )
    if not chunks:
        raise RuntimeError("No chunk rows found for newly imported reports.")

    build_workbook(args.output, uploads, chunks)
    print(f"Exported Excel: {args.output}")
    print(f"Imported reports: {len(uploads)}, exported chunk rows: {len(chunks)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
