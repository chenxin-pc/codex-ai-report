from __future__ import annotations

import zipfile
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from common import utc_now


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


def write_xlsx(output_path: Path, sheets: list[tuple[str, list[list[Any]]]], title: str = "report ingest analysis") -> None:
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
                          f"<dc:title>{escape(title)}</dc:title><dc:creator>codex-ai-report</dc:creator>"
                          f'<dcterms:created xsi:type="dcterms:W3CDTF">{now}</dcterms:created>'
                          f'<dcterms:modified xsi:type="dcterms:W3CDTF">{now}</dcterms:modified>'
                          '</cp:coreProperties>')
        workbook.writestr("docProps/app.xml",
                          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                          '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
                          '<Application>codex-ai-report</Application></Properties>')
        for idx, (_, rows) in enumerate(sheets, start=1):
            workbook.writestr(f"xl/worksheets/sheet{idx}.xml", sheet_xml(rows))
