import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "commands"))

import pipeline


class ReportRagEvaluationPipelineTests(unittest.TestCase):

    def test_cases_from_jsonl_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            case_file = Path(temp_dir) / "cases.jsonl"
            case_file.write_text(
                json.dumps({
                    "caseId": "theme-storage-001",
                    "query": "储能机会",
                    "expectedIntent": "THEME_RESEARCH",
                    "expectedAnchors": {"THEME": ["STORAGE"]},
                    "requiredClaims": ["需求"],
                }, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )

            cases = pipeline.cases_from_file(case_file)

            self.assertEqual(1, len(cases))
            self.assertEqual("theme-storage-001", cases[0].case_id)
            self.assertEqual(["需求"], cases[0].required_claims)

    def test_cases_from_database_uses_configured_json_sql(self):
        config = {
            "evaluation": {"source": "database", "db_cases_sql": "SELECT JSON_OBJECT('caseId', 'case-1', 'query', '储能')"},
            "database": {"mysql_command": "mysql"},
        }
        with mock.patch("pipeline.run_command", return_value='{"caseId":"case-1","query":"储能"}\n') as run_command:
            cases = pipeline.load_eval_cases(config)

        self.assertEqual("case-1", cases[0].case_id)
        run_command.assert_called_once()

    def test_auto_score_detects_reference_hit_and_forbidden_claim(self):
        case = pipeline.EvalCase(
            case_id="case-1",
            query="储能机会",
            expected_intent="THEME_RESEARCH",
            expected_anchors={"THEME": ["STORAGE"]},
            expected_output_level="L2_THEME_RESEARCH",
            reference_contexts=[{"chunkUid": "chunk-1", "text": "储能需求增长"}],
            forbidden_claims=["目标价"],
        )
        response = {
            "inputIntent": "THEME_RESEARCH",
            "outputLevel": "L2_THEME_RESEARCH",
            "analysis": "储能需求增长",
            "recommendation": "给出目标价",
            "evidenceQuality": {"structuredAnchors": ["STORAGE"]},
            "degradationReasons": [],
        }
        contexts = [{"stage": "FINAL", "chunkUid": "chunk-1", "text": "储能需求增长"}]

        scores = pipeline.auto_score(case, response, contexts)

        self.assertTrue(scores["intentMatch"])
        self.assertTrue(scores["anchorMatch"])
        self.assertTrue(scores["referenceContextHit"])
        self.assertTrue(scores["forbiddenClaimHit"])
        self.assertTrue(scores["needsManualReview"])

    def test_evaluate_cases_writes_complete_result(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            case_file = root / "cases.jsonl"
            case_file.write_text(json.dumps({"caseId": "case-1", "query": "储能机会"}, ensure_ascii=False), encoding="utf-8")
            config = {
                "api": {"base_url": "http://localhost:8080", "recommend_path": "/api/reports/recommend"},
                "evaluation": {"cases_file": str(case_file)},
                "output": {"dir": str(root / "outputs")},
                "runtime": {"continue_on_error": True},
            }
            state = pipeline.RunState(run_id="run", created_at=pipeline.utc_now(), config_path="config.json")
            payload = {
                "inputIntent": "THEME_RESEARCH",
                "outputLevel": "L2_THEME_RESEARCH",
                "analysis": "分析",
                "recommendation": "建议关注研究对象",
                "risks": [],
                "citations": [],
                "top5": [{"score": 0.9, "chunkUid": "chunk-1", "parentContext": "证据"}],
                "evidenceQuality": {"structuredAnchors": []},
                "degradationReasons": [],
            }

            with mock.patch("pipeline.post_json", return_value=payload):
                results = pipeline.evaluate_cases(config, state)
                output = pipeline.write_results(config, state)

            self.assertEqual("success", results[0].status)
            self.assertTrue(output.exists())
            data = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("chunk-1", data["results"][0]["retrieved_contexts"][0]["chunkUid"])

    def test_export_ragas_outputs_jsonl_and_summary(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config = {"output": {"dir": str(root / "outputs")}}
            state = pipeline.RunState(
                run_id="run",
                created_at=pipeline.utc_now(),
                config_path="config.json",
                corpus={"corpus_code": "core-v1", "corpus_version": "v1"},
                run_snapshot={
                    "appCommit": "abc123",
                    "promptHash": "prompt-sha",
                    "embeddingModel": "qwen-embedding",
                    "llmModel": "qwen-plus",
                    "dictionaryVersion": "v1",
                    "retrievalConfigSummary": {"final_top_k": 5},
                },
            )
            state.results = [{
                "case": {
                    "case_id": "case-1",
                    "query": "储能机会",
                    "case_type": "THEME_RESEARCH",
                    "expected_intent": "THEME_RESEARCH",
                    "expected_output_level": "L2_THEME_RESEARCH",
                    "expected_degradation_reasons": [],
                    "reference_answer": "储能需求增长",
                    "required_claims": ["需求增长"],
                    "reference_contexts": [{"chunkUid": "chunk-1", "text": "储能需求增长"}],
                },
                "status": "success",
                "response": {"analysis": "储能需求增长", "recommendation": "关注研究对象"},
                "retrieved_contexts": [{"chunkUid": "chunk-1", "text": "储能需求增长"}],
                "auto_scores": {"referenceContextHit": True},
            }, {
                "status": "failed",
                "error_summary": "timeout",
            }]

            output = pipeline.export_ragas(config, state)

            rows = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(1, len(rows))
            self.assertEqual("储能机会", rows[0]["user_input"])
            self.assertEqual(["储能需求增长"], rows[0]["reference_contexts"])
            metadata = rows[0]["metadata"]
            self.assertEqual("v1", metadata["corpusVersion"])
            self.assertEqual("prompt-sha", metadata["promptHash"])
            self.assertEqual("qwen-embedding", metadata["embeddingModel"])
            self.assertEqual("qwen-plus", metadata["llmModel"])
            self.assertEqual({"final_top_k": 5}, metadata["retrievalConfigSummary"])
            summary = json.loads(state.output_files["ragas_summary"])
            self.assertEqual({"failed": 1}, summary["skipReasons"])


if __name__ == "__main__":
    unittest.main()
