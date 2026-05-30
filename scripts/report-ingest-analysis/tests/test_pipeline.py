import json
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock
from contextlib import redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "commands"))

import pipeline


class ReportIngestAnalysisPipelineTests(unittest.TestCase):

    def test_collect_local_reports_limits_to_ten_and_reads_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report_dir = root / "reports"
            report_dir.mkdir()
            for index in range(12):
                (report_dir / f"report-{index:02d}.pdf").write_bytes(f"pdf-{index}".encode("utf-8"))
            (report_dir / "manifest.csv").write_text(
                "fileName,title,source,institution,publishDate\n"
                "report-00.pdf,Title 0,Local,Inst,2026-05-01\n",
                encoding="utf-8",
            )
            config = {"input": {"mode": "local_dir", "local_dir": str(report_dir), "limit": 10}}

            reports = pipeline.collect_local_reports(config)

            self.assertEqual(10, len(reports))
            self.assertEqual("Title 0", reports[0].title)
            self.assertTrue(all(report.fingerprint for report in reports))

    def test_collect_local_reports_allows_fewer_than_ten(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir) / "reports"
            report_dir.mkdir()
            for index in range(6):
                (report_dir / f"report-{index:02d}.pdf").write_bytes(f"pdf-{index}".encode("utf-8"))
            config = {"input": {"mode": "local_dir", "local_dir": str(report_dir), "limit": 10}}

            reports = pipeline.collect_local_reports(config)

            self.assertEqual(6, len(reports))

    def test_read_url_manifest_supports_plain_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manifest = Path(temp_dir) / "urls.txt"
            manifest.write_text("# comment\nhttps://example.test/a.pdf\n\nhttps://example.test/b.pdf\n", encoding="utf-8")

            rows = pipeline.read_url_manifest(manifest)

            self.assertEqual(["https://example.test/a.pdf", "https://example.test/b.pdf"], [row["url"] for row in rows])

    def test_collect_url_reports_records_download_failure(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest = root / "urls.csv"
            manifest.write_text("url,title\nhttps://example.test/fail.pdf,Failure\n", encoding="utf-8")
            config = {
                "input": {
                    "mode": "url_manifest",
                    "url_manifest": str(manifest),
                    "download_dir": str(root / "downloads"),
                    "limit": 10,
                },
                "output": {"dir": str(root / "outputs")},
            }
            state = pipeline.RunState(run_id="run", created_at=pipeline.utc_now(), config_path="config.json")

            with mock.patch("inputs.download_pdf", side_effect=RuntimeError("boom")):
                reports = pipeline.collect_url_reports(config, state)

            self.assertEqual("failed", reports[0].collect_status)
            self.assertEqual("download failure", "download failure" if "boom" in reports[0].error_summary else "")

    def test_collect_eastmoney_reports_from_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest = root / "eastmoney.csv"
            manifest.write_text(
                "url,title,institution,publishDate\nhttps://example.test/em.pdf,EM Title,EM Inst,2026-05-01\n",
                encoding="utf-8",
            )
            config = {
                "input": {
                    "mode": "eastmoney",
                    "eastmoney_manifest": str(manifest),
                    "download_dir": str(root / "downloads"),
                    "limit": 10,
                },
                "output": {"dir": str(root / "outputs")},
            }
            state = pipeline.RunState(run_id="run", created_at=pipeline.utc_now(), config_path="config.json")

            def fake_download(url, target, timeout=60):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"%PDF-1.4")

            with mock.patch("inputs.download_pdf", side_effect=fake_download):
                reports = pipeline.collect_eastmoney_reports(config, state)

            self.assertEqual(1, len(reports))
            self.assertEqual("eastmoney", reports[0].source)
            self.assertEqual("EM Title", reports[0].title)

    def test_successful_fingerprints_excludes_current_run_and_supports_skip(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            config = {"output": {"dir": temp_dir}}
            previous = Path(temp_dir) / "runs" / "old-run"
            previous.mkdir(parents=True)
            (previous / "state.json").write_text(json.dumps({
                "results": [{"status": "success", "fingerprint": "abc", "report_id": 7}]
            }), encoding="utf-8")
            current = Path(temp_dir) / "runs" / "current-run"
            current.mkdir(parents=True)
            (current / "state.json").write_text(json.dumps({
                "results": [{"status": "success", "fingerprint": "current", "report_id": 8}]
            }), encoding="utf-8")

            fingerprints = pipeline.successful_fingerprints(config, "current-run")

            self.assertIn("abc", fingerprints)
            self.assertNotIn("current", fingerprints)

    def test_ingest_reports_skips_previous_success_unless_forced(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            config = {"output": {"dir": temp_dir}, "runtime": {"continue_on_error": True}}
            previous = Path(temp_dir) / "runs" / "old-run"
            previous.mkdir(parents=True)
            (previous / "state.json").write_text(json.dumps({
                "results": [{"status": "success", "fingerprint": "abc", "report_id": 7}]
            }), encoding="utf-8")
            report = pipeline.ReportInput(local_path="/tmp/report.pdf", title="Report", fingerprint="abc")

            state = pipeline.RunState(run_id="current-run", created_at=pipeline.utc_now(), config_path="config.json")
            with mock.patch("pipeline.existing_title_url_keys", return_value=(set(), set())):
                with redirect_stdout(io.StringIO()):
                    skipped = pipeline.ingest_reports(config, state, [report], force=False)
            self.assertEqual("skipped", skipped[0].status)
            self.assertEqual(7, skipped[0].report_id)

            forced_state = pipeline.RunState(run_id="forced-run", created_at=pipeline.utc_now(), config_path="config.json")
            with mock.patch("pipeline.existing_title_url_keys", return_value=(set(), set())):
                with mock.patch("pipeline.upload_report", return_value={"reportId": 8, "chunkCount": 2}):
                    with redirect_stdout(io.StringIO()):
                        forced = pipeline.ingest_reports(config, forced_state, [report], force=True)
            self.assertEqual("success", forced[0].status)
            self.assertEqual(8, forced[0].report_id)

    def test_ingest_reports_stops_or_continues_after_collect_failure(self):
        failed = pipeline.ReportInput(local_path="", title="Bad", fingerprint="", collect_status="failed", error_summary="bad")
        next_report = pipeline.ReportInput(local_path="/tmp/good.pdf", title="Good", fingerprint="good")
        with tempfile.TemporaryDirectory() as temp_dir:
            stop_config = {"output": {"dir": temp_dir}, "runtime": {"continue_on_error": False}}
            stop_state = pipeline.RunState(run_id="stop-run", created_at=pipeline.utc_now(), config_path="config.json")
            with mock.patch("pipeline.existing_title_url_keys", return_value=(set(), set())):
                with redirect_stdout(io.StringIO()):
                    stopped = pipeline.ingest_reports(stop_config, stop_state, [failed, next_report], force=False)
            self.assertEqual(1, len(stopped))

            continue_config = {"output": {"dir": temp_dir}, "runtime": {"continue_on_error": True}}
            continue_state = pipeline.RunState(run_id="continue-run", created_at=pipeline.utc_now(), config_path="config.json")
            with mock.patch("pipeline.existing_title_url_keys", return_value=(set(), set())):
                with mock.patch("pipeline.upload_report", return_value={"reportId": 3, "chunkCount": 1}):
                    with redirect_stdout(io.StringIO()):
                        continued = pipeline.ingest_reports(continue_config, continue_state, [failed, next_report], force=False)
            self.assertEqual(["failed", "success"], [item.status for item in continued])

    def test_build_search_workbook_contains_required_sheets(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            config = {"output": {"dir": temp_dir, "max_text_length": 20}}
            state = pipeline.RunState(
                run_id="test-run",
                created_at=pipeline.utc_now(),
                config_path="config.json",
                query_results=[{
                    "query": "query",
                    "status": "success",
                    "started_at": pipeline.utc_now(),
                    "finished_at": pipeline.utc_now(),
                    "error_summary": "",
                    "top_results": [{"chunkUid": "c1", "title": "T", "chunkText": "x" * 100}],
                    "analysis": "analysis",
                    "recommendation": "recommendation",
                    "risks": ["risk"],
                    "citations": ["citation"],
                }],
            )

            output = pipeline.build_search_workbook(config, state)

            with zipfile.ZipFile(output) as workbook:
                xml = workbook.read("xl/workbook.xml").decode("utf-8")
            self.assertIn('name="queries"', xml)
            self.assertIn('name="top_results"', xml)
            self.assertIn('name="recommendations"', xml)
            self.assertIn('name="manual_review"', xml)

    def test_build_quality_workbook_contains_required_sheets(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            config = {"output": {"dir": temp_dir, "max_text_length": 20}}
            state = pipeline.RunState(run_id="test-run", created_at=pipeline.utc_now(), config_path="config.json")
            quality_data = {
                "reports": [{"reportId": 1, "title": "T", "source": "S", "institution": "I", "publishDate": "2026-05-01", "createdAt": "2026-05-01 00:00:00"}],
                "ocr_pages": [{"reportId": 1, "pageNumber": 1, "rawText": "raw", "cleanedText": "clean", "diagnostics": "", "createdAt": ""}],
                "paragraph_atoms": [{"reportId": 1, "paragraphId": "p1", "pageNumber": 1, "sectionPath": "", "tokenCount": 1, "diagnostics": "", "paragraphText": "paragraph", "createdAt": ""}],
                "chunks": [{"reportId": 1, "chunkUid": "c", "parentChunkUid": "", "chunkType": "CHILD", "sectionPath": "", "tokenCount": 1, "startPageNumber": 1, "endPageNumber": 1, "vectorStored": True, "chunkText": "text"}],
                "filtered_chunks": [{"reportId": 1, "chunkUid": "f", "parentChunkUid": "", "chunkType": "CHILD", "sectionPath": "", "tokenCount": 1, "filterReason": "short", "diagnostics": "", "chunkText": "text"}],
            }

            with mock.patch("pipeline.query_quality_data", return_value=quality_data):
                output = pipeline.build_quality_workbook(config, state, [1])

            with zipfile.ZipFile(output) as workbook:
                xml = workbook.read("xl/workbook.xml").decode("utf-8")
            self.assertIn('name="reports"', xml)
            self.assertIn('name="ocr_pages"', xml)
            self.assertIn('name="paragraph_atoms"', xml)
            self.assertIn('name="chunks"', xml)
            self.assertIn('name="filtered_chunks"', xml)
            self.assertIn('name="summary"', xml)

    def test_report_ids_by_time_sql_validates_input_and_uses_explicit_fields(self):
        sql = pipeline.report_ids_by_time_sql("2026-05-01", "2026-05-02 23:59:59")

        self.assertIn("JSON_OBJECT('reportId', id)", sql)
        self.assertNotIn("SELECT *", sql)
        with self.assertRaises(ValueError):
            pipeline.report_ids_by_time_sql("2026-05-01' OR 1=1 --", "")

    def test_upload_report_client_handles_success_and_failure_payloads(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            pdf_path = Path(temp_dir) / "report.pdf"
            pdf_path.write_bytes(b"pdf")
            report = pipeline.ReportInput(local_path=str(pdf_path), title="T", fingerprint="abc")
            config = {"api": {"base_url": "http://example.test", "upload_path": "/upload"}}

            class Response:
                def __init__(self, payload):
                    self.payload = payload

                def __enter__(self):
                    return self

                def __exit__(self, exc_type, exc, traceback):
                    return False

                def read(self):
                    return json.dumps(self.payload).encode("utf-8")

            with mock.patch("urllib.request.urlopen", return_value=Response({"reportId": 9, "chunkCount": 3})):
                payload = pipeline.upload_report(config, report)
            self.assertEqual(9, payload["reportId"])

            with mock.patch("urllib.request.urlopen", return_value=Response({"message": "bad"})):
                with self.assertRaises(RuntimeError):
                    pipeline.upload_report(config, report)


if __name__ == "__main__":
    unittest.main()
