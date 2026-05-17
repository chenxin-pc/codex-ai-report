from __future__ import annotations

import datetime as dt
import json
import uuid
from dataclasses import asdict
from pathlib import Path
from typing import Any

from common import output_dir, state_path, utc_now
from models import RunState


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
