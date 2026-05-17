from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


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
