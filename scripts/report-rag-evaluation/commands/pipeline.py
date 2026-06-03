#!/usr/bin/env python3
"""
Offline report RAG evaluation pipeline.

The script intentionally lives in its own task-domain directory so eval case
schemas, project scoring, and Ragas exports do not pollute the report ingest
analysis workflow.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape


DEFAULT_CONFIG = Path(__file__).resolve().parents[1] / "config" / "config.example.json"
DEFAULT_MAX_TEXT_LENGTH = 2000


@dataclass
class EvalCase:
    case_id: str
    query: str
    case_type: str = ""
    difficulty: str = ""
    expected_intent: str = ""
    expected_anchors: dict[str, Any] = field(default_factory=dict)
    expected_output_level: str = ""
    expected_degradation_reasons: list[str] = field(default_factory=list)
    reference_answer: str = ""
    required_claims: list[str] = field(default_factory=list)
    forbidden_claims: list[str] = field(default_factory=list)
    forbidden_terms: list[str] = field(default_factory=list)
    reference_contexts: list[dict[str, Any]] = field(default_factory=list)
    forbidden_contexts: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class EvalResult:
    case: EvalCase
    status: str
    started_at: str
    finished_at: str = ""
    error_summary: str = ""
    response: dict[str, Any] = field(default_factory=dict)
    retrieved_contexts: list[dict[str, Any]] = field(default_factory=list)
    auto_scores: dict[str, Any] = field(default_factory=dict)


@dataclass
class RunState:
    run_id: str
    created_at: str
    config_path: str
    corpus: dict[str, Any] = field(default_factory=dict)
    run_snapshot: dict[str, Any] = field(default_factory=dict)
    results: list[dict[str, Any]] = field(default_factory=list)
    output_files: dict[str, str] = field(default_factory=dict)
    status: str = "running"
    summary: dict[str, Any] = field(default_factory=dict)


def utc_now() -> str:
    return dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        return resolve_env_placeholders(json.load(file))


def resolve_env_placeholders(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: resolve_env_placeholders(item) for key, item in value.items()}
    if isinstance(value, list):
        return [resolve_env_placeholders(item) for item in value]
    if isinstance(value, str):
        if value.startswith("${") and value.endswith("}"):
            body = value[2:-1]
            name, _, default = body.partition(":")
            return os.environ.get(name, default)
    return value


def new_run_state(config_path: Path, config: dict[str, Any]) -> RunState:
    return RunState(
        run_id=dt.datetime.utcnow().strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:8],
        created_at=utc_now(),
        config_path=str(config_path),
        corpus=config.get("corpus", {}),
        run_snapshot=run_snapshot_from_config(config),
    )


def run_snapshot_from_config(config: dict[str, Any]) -> dict[str, Any]:
    snapshot = config.get("run_snapshot") or config.get("runSnapshot") or {}
    models = config.get("models", {})
    return {
        "appCommit": first_config_value(snapshot, "appCommit", "app_commit", default="unknown"),
        "promptVersion": first_config_value(snapshot, "promptVersion", "prompt_version", default="unknown"),
        "promptHash": first_config_value(snapshot, "promptHash", "prompt_hash", default="unknown"),
        "embeddingModel": first_config_value(snapshot, "embeddingModel", "embedding_model",
                                             default=first_config_value(models, "embeddingModel", "embedding_model", "embedding", default="unknown")),
        "llmModel": first_config_value(snapshot, "llmModel", "llm_model",
                                      default=first_config_value(models, "llmModel", "llm_model", "llm", default="unknown")),
        "dictionaryVersion": first_config_value(snapshot, "dictionaryVersion", "dictionary_version", default="unknown"),
        "retrievalConfigSummary": first_config_value(snapshot, "retrievalConfigSummary", "retrieval_config_summary",
                                                     "retrievalConfig", "retrieval_config",
                                                     default=config.get("retrieval", {}) or "unknown"),
    }


def output_dir(config: dict[str, Any]) -> Path:
    return Path(config.get("output", {}).get("dir", "./outputs/report-rag-evaluation"))


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
        return RunState(**json.load(file))


def recommend_url(config: dict[str, Any]) -> str:
    api = config.get("api", {})
    return urllib.parse.urljoin(
        api.get("base_url", "http://localhost:8080").rstrip("/") + "/",
        api.get("recommend_path", "/api/reports/recommend").lstrip("/"),
    )


def post_json(url: str, payload: dict[str, Any], timeout: int = 120) -> dict[str, Any]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def load_eval_cases(config: dict[str, Any]) -> list[EvalCase]:
    evaluation = config.get("evaluation", {})
    if evaluation.get("source") == "database" or evaluation.get("db_cases_sql"):
        return cases_from_database(config)
    cases_file = evaluation.get("cases_file", "")
    if cases_file:
        return cases_from_file(Path(cases_file))
    queries = evaluation.get("queries", [])
    return [
        EvalCase(case_id=f"query-{index:03d}", query=str(query))
        for index, query in enumerate(queries, start=1)
    ]


def cases_from_file(path: Path) -> list[EvalCase]:
    if not path.exists():
        raise FileNotFoundError(f"Eval case file not found: {path}")
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        return []
    rows = json.loads(text) if text.startswith("[") else [json.loads(line) for line in text.splitlines() if line.strip()]
    return [case_from_row(row, index) for index, row in enumerate(rows, start=1)]


def cases_from_database(config: dict[str, Any]) -> list[EvalCase]:
    sql = config.get("evaluation", {}).get("db_cases_sql", "")
    if not sql:
        raise ValueError("evaluation.db_cases_sql is required when source is database")
    rows = []
    for line in run_command(mysql_command(config, sql)).splitlines():
        text = line.strip()
        if text:
            rows.append(json.loads(text))
    return [case_from_row(row, index) for index, row in enumerate(rows, start=1)]


def mysql_command(config: dict[str, Any], sql: str) -> list[str]:
    database = config.get("database", {})
    mysql_container = database.get("mysql_container", "")
    username = database.get("username", "root")
    password = database.get("password", "")
    db_name = database.get("database", "ai_report_rag")
    if mysql_container:
        return [
            "docker", "exec", str(mysql_container), "mysql", "--default-character-set=utf8mb4",
            f"-u{username}", f"-p{password}", "--batch", "--raw", "--skip-column-names", db_name, "-e", sql,
        ]
    return [
        database.get("mysql_command", "mysql"), "--default-character-set=utf8mb4",
        "-h", str(database.get("host", "localhost")),
        "-P", str(database.get("port", 3306)),
        f"-u{username}", f"-p{password}", "--batch", "--raw", "--skip-column-names", db_name, "-e", sql,
    ]


def run_command(args: list[str]) -> str:
    result = subprocess.run(args, check=False, capture_output=True)
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        stdout = result.stdout.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"Command failed: {args[0]}\n{stderr or stdout}")
    return result.stdout.decode("utf-8", errors="replace")


def case_from_row(row: dict[str, Any], index: int) -> EvalCase:
    return EvalCase(
        case_id=str(row.get("caseId") or row.get("case_id") or f"case-{index:03d}"),
        query=str(row.get("query", "")),
        case_type=str(row.get("caseType") or row.get("case_type") or ""),
        difficulty=str(row.get("difficulty", "")),
        expected_intent=str(row.get("expectedIntent") or row.get("expected_intent") or ""),
        expected_anchors=row.get("expectedAnchors") or row.get("expected_anchors") or {},
        expected_output_level=str(row.get("expectedOutputLevel") or row.get("expected_output_level") or ""),
        expected_degradation_reasons=string_list(row.get("expectedDegradationReasons") or row.get("expected_degradation_reasons")),
        reference_answer=str(row.get("referenceAnswer") or row.get("reference_answer") or ""),
        required_claims=string_list(row.get("requiredClaims") or row.get("required_claims")),
        forbidden_claims=string_list(row.get("forbiddenClaims") or row.get("forbidden_claims")),
        forbidden_terms=string_list(row.get("forbiddenTerms") or row.get("forbidden_terms")),
        reference_contexts=list(row.get("referenceContexts") or row.get("reference_contexts") or []),
        forbidden_contexts=list(row.get("forbiddenContexts") or row.get("forbidden_contexts") or []),
    )


def evaluate_cases(config: dict[str, Any], state: RunState) -> list[EvalResult]:
    cases = load_eval_cases(config)
    if not cases:
        raise RuntimeError("No eval cases configured")
    continue_on_error = bool(config.get("runtime", {}).get("continue_on_error", True))
    results: list[EvalResult] = []
    for case in cases:
        result = EvalResult(case=case, status="running", started_at=utc_now())
        try:
            payload = post_json(recommend_url(config), {"query": case.query})
            result.response = payload
            result.retrieved_contexts = normalize_retrieved_contexts(payload.get("top5", []))
            result.auto_scores = auto_score(case, payload, result.retrieved_contexts)
            result.status = "success"
        except (OSError, urllib.error.URLError, RuntimeError, json.JSONDecodeError) as exc:
            result.status = "failed"
            result.error_summary = short_error(exc)
            if not continue_on_error:
                result.finished_at = utc_now()
                results.append(result)
                break
        result.finished_at = utc_now()
        results.append(result)
        state.results = [result_to_dict(item) for item in results]
        save_state(config, state)
    state.status = "complete" if all(item.status == "success" for item in results) else "partial_failed"
    state.results = [result_to_dict(item) for item in results]
    save_state(config, state)
    return results


def normalize_retrieved_contexts(top_results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    contexts: list[dict[str, Any]] = []
    for rank, item in enumerate(top_results or [], start=1):
        contexts.append({
            "stage": "FINAL",
            "rank": rank,
            "score": item.get("score", item.get("distance")),
            "reportTitle": item.get("title", item.get("reportTitle", "")),
            "source": item.get("source", ""),
            "sectionPath": item.get("sectionPath", ""),
            "chunkUid": item.get("chunkUid", ""),
            "parentChunkUid": item.get("parentChunkUid", ""),
            "text": item.get("parentContext") or item.get("evidenceText") or item.get("chunkText") or item.get("text", ""),
            "chunkText": item.get("chunkText", item.get("text", "")),
            "themeCodes": item.get("themeCodes", []) or [],
            "industryCodes": item.get("industryCodes", []) or [],
            "companyNames": item.get("companyNames", []) or [],
            "tickers": item.get("tickers", []) or [],
            "diagnosticOnly": bool(item.get("diagnosticOnly", False)),
        })
    return contexts


def auto_score(case: EvalCase, response: dict[str, Any], contexts: list[dict[str, Any]]) -> dict[str, Any]:
    response_text = response_text_from_payload(response)
    scores = {
        "intentMatch": match_expected(case.expected_intent, response.get("inputIntent", "")),
        "anchorMatch": match_anchors(case.expected_anchors, response.get("evidenceQuality", {}).get("structuredAnchors", [])),
        "outputLevelMatch": match_expected(case.expected_output_level, response.get("outputLevel", "")),
        "degradationReasonMatch": match_expected_tokens(case.expected_degradation_reasons, response.get("degradationReasons", [])),
        "referenceContextHit": reference_context_hit(case.reference_contexts, contexts),
        "forbiddenContextHit": forbidden_context_hit(case.forbidden_contexts, contexts),
        "forbiddenClaimHit": forbidden_claim_hit(case, response_text),
    }
    scores["needsManualReview"] = any(value is False for value in scores.values()) or scores["forbiddenContextHit"] or scores["forbiddenClaimHit"]
    return scores


def match_expected(expected: str, actual: Any) -> bool | None:
    if not expected:
        return None
    return normalize(expected) == normalize(str(actual or ""))


def match_expected_tokens(expected: list[str], actual: Any) -> bool | None:
    if not expected:
        return None
    actual_text = normalize(json.dumps(actual, ensure_ascii=False) if isinstance(actual, (list, dict)) else str(actual or ""))
    return all(normalize(token) in actual_text for token in expected)


def match_anchors(expected: dict[str, Any], actual: Any) -> bool | None:
    expected_tokens: list[str] = []
    for values in expected.values():
        expected_tokens.extend(string_list(values))
    if not expected_tokens:
        return None
    actual_text = normalize(json.dumps(actual, ensure_ascii=False) if isinstance(actual, (list, dict)) else str(actual or ""))
    return all(normalize(token) in actual_text for token in expected_tokens)


def reference_context_hit(reference_contexts: list[dict[str, Any]], contexts: list[dict[str, Any]]) -> bool | None:
    if not reference_contexts:
        return None
    actual_ids = {
        normalize(value)
        for context in contexts
        for value in (context.get("chunkUid"), context.get("parentChunkUid"))
        if value
    }
    for reference in reference_contexts:
        expected_ids = [
            reference.get("contextId"),
            reference.get("chunkUid"),
            reference.get("parentChunkUid"),
        ]
        if any(normalize(value) in actual_ids for value in expected_ids if value):
            return True
    return False


def forbidden_context_hit(forbidden_contexts: list[dict[str, Any]], contexts: list[dict[str, Any]]) -> bool:
    for forbidden in forbidden_contexts:
        value = normalize(str(forbidden.get("value", "")))
        if not value:
            continue
        for context in contexts:
            haystack = normalize(json.dumps(context, ensure_ascii=False))
            if value in haystack:
                return True
    return False


def forbidden_claim_hit(case: EvalCase, response_text: str) -> bool:
    normalized_response = normalize(response_text)
    return any(normalize(token) in normalized_response for token in case.forbidden_claims + case.forbidden_terms if token)


def response_text_from_payload(payload: dict[str, Any]) -> str:
    parts: list[str] = []
    for key in ("analysis", "recommendation"):
        value = payload.get(key, "")
        if value:
            parts.append(f"{key}: {value}")
    risks = payload.get("risks", [])
    if risks:
        parts.append("risks: " + json.dumps(risks, ensure_ascii=False))
    return "\n\n".join(parts)


def write_results(config: dict[str, Any], state: RunState) -> Path:
    path = run_dir(config, state.run_id) / f"evaluation-results-{state.run_id}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        json.dump({
            "runId": state.run_id,
            "corpus": state.corpus,
            "runSnapshot": state.run_snapshot,
            "results": state.results,
        }, file, ensure_ascii=False, indent=2)
    state.output_files["evaluation_json"] = str(path)
    save_state(config, state)
    return path


def build_workbook(config: dict[str, Any], state: RunState) -> Path:
    max_text_length = int(config.get("output", {}).get("max_text_length", DEFAULT_MAX_TEXT_LENGTH))
    eval_rows: list[dict[str, Any]] = []
    top_rows: list[dict[str, Any]] = []
    score_rows: list[dict[str, Any]] = []
    manual_rows: list[dict[str, Any]] = []
    for result in state.results:
        case = result["case"]
        eval_rows.append({
            "caseId": case["case_id"],
            "query": case["query"],
            "caseType": case["case_type"],
            "expectedIntent": case["expected_intent"],
            "expectedOutputLevel": case["expected_output_level"],
            "status": result["status"],
            "errorSummary": result["error_summary"],
        })
        for context in result.get("retrieved_contexts", []):
            top_rows.append({
                "caseId": case["case_id"],
                "rank": context.get("rank", ""),
                "score": context.get("score", ""),
                "chunkUid": context.get("chunkUid", ""),
                "parentChunkUid": context.get("parentChunkUid", ""),
                "sectionPath": context.get("sectionPath", ""),
                "text": limit_text(context.get("text", ""), max_text_length),
            })
            manual_rows.append({
                "caseId": case["case_id"],
                "rank": context.get("rank", ""),
                "chunkUid": context.get("chunkUid", ""),
                "isRelevant": "",
                "relevanceLevel": "",
                "issueNotes": "",
                "suggestedAction": "",
            })
        scores = result.get("auto_scores", {})
        score_rows.append({"caseId": case["case_id"], **scores})
    output_path = run_dir(config, state.run_id) / f"evaluation-{state.run_id}.xlsx"
    write_xlsx(output_path, [
        ("eval_cases", rows_for_sheet(eval_rows, ["caseId", "query", "caseType", "expectedIntent", "expectedOutputLevel", "status", "errorSummary"])),
        ("top_results", rows_for_sheet(top_rows, ["caseId", "rank", "score", "chunkUid", "parentChunkUid", "sectionPath", "text"])),
        ("auto_scores", rows_for_sheet(score_rows, ["caseId", "intentMatch", "anchorMatch", "outputLevelMatch", "degradationReasonMatch", "referenceContextHit", "forbiddenContextHit", "forbiddenClaimHit", "needsManualReview"])),
        ("manual_review", rows_for_sheet(manual_rows, ["caseId", "rank", "chunkUid", "isRelevant", "relevanceLevel", "issueNotes", "suggestedAction"])),
    ])
    state.output_files["evaluation_xlsx"] = str(output_path)
    save_state(config, state)
    return output_path


def export_ragas(config: dict[str, Any], state: RunState) -> Path:
    rows: list[dict[str, Any]] = []
    skipped = 0
    skip_reasons: dict[str, int] = {}
    missing_reference = 0
    missing_context = 0
    run_snapshot = state.run_snapshot or run_snapshot_from_config(config)
    for result in state.results:
        if result["status"] != "success":
            skipped += 1
            increment_counter(skip_reasons, str(result.get("status") or "unknown"))
            continue
        case = result["case"]
        reference = case.get("reference_answer") or "; ".join(case.get("required_claims", []))
        if not reference:
            missing_reference += 1
        retrieved_contexts = [context.get("text", "") for context in result.get("retrieved_contexts", []) if context.get("text")]
        if not retrieved_contexts:
            missing_context += 1
        rows.append({
            "user_input": case["query"],
            "retrieved_contexts": retrieved_contexts,
            "retrieved_context_ids": [context.get("chunkUid") or context.get("parentChunkUid") for context in result.get("retrieved_contexts", [])],
            "response": response_text_from_payload(result.get("response", {})),
            "reference": reference,
            "reference_contexts": [context.get("text", "") for context in case.get("reference_contexts", []) if context.get("text")],
            "reference_context_ids": [context.get("chunkUid") or context.get("parentChunkUid") or context.get("contextId") for context in case.get("reference_contexts", [])],
            "metadata": {
                "caseId": case["case_id"],
                "caseType": case["case_type"],
                "expectedIntent": case["expected_intent"],
                "expectedOutputLevel": case["expected_output_level"],
                "expectedDegradationReasons": case["expected_degradation_reasons"],
                "actualIntent": result.get("response", {}).get("inputIntent", ""),
                "actualOutputLevel": result.get("response", {}).get("outputLevel", ""),
                "degradationReasons": result.get("response", {}).get("degradationReasons", []),
                "evidenceQuality": result.get("response", {}).get("evidenceQuality", {}),
                "autoScores": result.get("auto_scores", {}),
                "corpus": state.corpus,
                "corpusCode": first_config_value(state.corpus, "corpusCode", "corpus_code", default="unknown"),
                "corpusVersion": first_config_value(state.corpus, "corpusVersion", "corpus_version", default="unknown"),
                "runId": state.run_id,
                "appCommit": run_snapshot.get("appCommit", "unknown"),
                "promptVersion": run_snapshot.get("promptVersion", "unknown"),
                "promptHash": run_snapshot.get("promptHash", "unknown"),
                "embeddingModel": run_snapshot.get("embeddingModel", "unknown"),
                "llmModel": run_snapshot.get("llmModel", "unknown"),
                "dictionaryVersion": run_snapshot.get("dictionaryVersion", "unknown"),
                "retrievalConfigSummary": run_snapshot.get("retrievalConfigSummary", "unknown"),
                "runSnapshot": run_snapshot,
                "ragasMetricHints": ragas_metric_hints(case, retrieved_contexts, reference),
            },
        })
    if not rows:
        raise RuntimeError("No successful eval records to export")
    output_path = run_dir(config, state.run_id) / f"ragas-{state.run_id}.jsonl"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    summary = {
        "total": len(state.results),
        "exported": len(rows),
        "skipped": skipped,
        "skipReasons": skip_reasons,
        "missingReference": missing_reference,
        "missingRetrievedContext": missing_context,
        "outputPath": str(output_path),
    }
    state.output_files["ragas_jsonl"] = str(output_path)
    state.output_files["ragas_summary"] = json.dumps(summary, ensure_ascii=False)
    state.summary = summary
    save_state(config, state)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return output_path


def ragas_metric_hints(case: dict[str, Any], retrieved_contexts: list[str], reference: str) -> dict[str, bool]:
    unanalyzable = case.get("case_type") == "UNANALYZABLE"
    return {
        "contextRecall": bool(retrieved_contexts and reference and not unanalyzable),
        "faithfulness": bool(retrieved_contexts and not unanalyzable),
        "answerCorrectness": bool(reference and not unanalyzable),
        "projectGuardrailOnly": unanalyzable,
    }


def result_to_dict(result: EvalResult) -> dict[str, Any]:
    data = asdict(result)
    data["case"] = asdict(result.case)
    return data


def rows_for_sheet(rows: list[dict[str, Any]], headers: list[str]) -> list[list[Any]]:
    values = [headers]
    for row in rows:
        values.append([json.dumps(row.get(header), ensure_ascii=False) if isinstance(row.get(header), (list, dict)) else row.get(header, "") for header in headers])
    return values


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
    workbook_sheets = "".join(f'<sheet name="{escape(name)}" sheetId="{idx}" r:id="rId{idx}"/>' for idx, (name, _) in enumerate(sheets, start=1))
    workbook_rels = "".join(f'<Relationship Id="rId{idx}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{idx}.xml"/>' for idx in range(1, len(sheets) + 1))
    content_types = "".join(f'<Override PartName="/xl/worksheets/sheet{idx}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for idx in range(1, len(sheets) + 1))
    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as workbook:
        workbook.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' + content_types + '</Types>')
        workbook.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        workbook.writestr("xl/workbook.xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>' + workbook_sheets + '</sheets></workbook>')
        workbook.writestr("xl/_rels/workbook.xml.rels", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + workbook_rels + '</Relationships>')
        for idx, (_, rows) in enumerate(sheets, start=1):
            workbook.writestr(f"xl/worksheets/sheet{idx}.xml", sheet_xml(rows))


def string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item)]
    if isinstance(value, str):
        if not value:
            return []
        try:
            parsed = json.loads(value)
            if isinstance(parsed, list):
                return [str(item) for item in parsed if str(item)]
        except json.JSONDecodeError:
            pass
        return [part.strip() for part in value.replace("；", ",").replace("、", ",").split(",") if part.strip()]
    return [str(value)]


def first_config_value(values: dict[str, Any], *keys: str, default: Any = "") -> Any:
    for key in keys:
        value = values.get(key)
        if value is None:
            continue
        if isinstance(value, str):
            if value.strip():
                return value.strip()
            continue
        if isinstance(value, (list, dict)) and not value:
            continue
        return value
    return default


def increment_counter(values: dict[str, int], key: str) -> None:
    values[key] = values.get(key, 0) + 1


def normalize(value: Any) -> str:
    return str(value or "").strip().lower()


def short_error(exc: BaseException, limit: int = 500) -> str:
    return str(exc).replace("\n", " ").strip()[:limit]


def limit_text(value: str, max_length: int) -> str:
    text = value or ""
    return text if len(text) <= max_length else text[:max_length] + "...[truncated]"


def command_evaluate(args: argparse.Namespace) -> RunState:
    config_path = Path(args.config)
    config = load_config(config_path)
    state = new_run_state(config_path, config)
    save_state(config, state)
    evaluate_cases(config, state)
    write_results(config, state)
    build_workbook(config, state)
    print(f"runId: {state.run_id}")
    return state


def command_export_ragas(args: argparse.Namespace) -> None:
    config = load_config(Path(args.config))
    state = load_state(config, args.run_id)
    export_ragas(config, state)


def command_run_all(args: argparse.Namespace) -> None:
    state = command_evaluate(args)
    config = load_config(Path(args.config))
    export_ragas(config, state)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Report RAG evaluation pipeline")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG), help="Path to config JSON")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("evaluate", help="Run eval cases and write JSON/XLSX").set_defaults(func=command_evaluate)
    export_parser = subparsers.add_parser("export-ragas", help="Export an existing run as Ragas JSONL")
    export_parser.add_argument("--run-id", required=True)
    export_parser.set_defaults(func=command_export_ragas)
    subparsers.add_parser("run-all", help="Run eval and export Ragas JSONL").set_defaults(func=command_run_all)
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
