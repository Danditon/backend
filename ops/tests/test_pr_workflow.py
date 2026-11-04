#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["PyYAML>=6.0,<7"]
# ///
# ─── How to run ───
# 1. Install uv: curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run: uv run ops/tests/test_pr_workflow.py [ci.yml]
#    With system PyYAML: python3 ops/tests/test_pr_workflow.py [ci.yml]
# 3. Or: chmod +x ops/tests/test_pr_workflow.py && ./ops/tests/test_pr_workflow.py
# ──────────────────
"""Check the PR isolation and required-check contract, independently of actionlint."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Final

import yaml

ROOT: Final = Path(__file__).resolve().parents[2]
BUILD: Final = "./gradlew --no-daemon clean build"


def check_workflow(path: Path) -> None:
    """Reject configuration that skips, weakens, or privileges the required PR check."""
    # Given a workflow file, parse scalars as strings so YAML 1.1 cannot rename `on`.
    source = path.read_text(encoding="utf-8")
    workflow = yaml.load(source, Loader=yaml.BaseLoader)

    # When evaluating its event, permission, job, and step contracts.
    assert workflow["on"] == {"pull_request": {"branches": ["develop"]}}, "PR_TRIGGER"
    assert workflow["permissions"] == {"contents": "read"}, "READ_ONLY_PERMISSION"
    assert set(workflow) <= {"name", "on", "permissions", "jobs"}, "WORKFLOW_SCOPE"
    assert len(workflow["jobs"]) == 1, "SINGLE_CHECK"
    job = next(iter(workflow["jobs"].values()))
    assert job["name"] == "Backend CI", "CHECK_NAME"
    assert set(job) <= {"name", "runs-on", "timeout-minutes", "steps"}, "JOB_ISOLATION"
    assert job["runs-on"] == "ubuntu-24.04", "HOSTED_RUNNER"
    assert 1 <= int(job["timeout-minutes"]) <= 30, "BOUNDED_JOB"
    assert not re.search(r"\bsecrets\s*[.\[]", source), "SECRET_REFERENCE"

    steps = job["steps"]
    assert len(steps) == 4, "STEP_SCOPE"
    actions = ("actions/checkout", "actions/setup-java", "gradle/actions/setup-gradle")
    for step, action in zip(steps[:3], actions, strict=True):
        assert set(step) <= {"name", "uses", "with"}, "ACTION_SCOPE"
        assert re.fullmatch(re.escape(action) + r"@[0-9a-f]{40}", step["uses"]), "ACTION_PIN"
    assert steps[0]["with"] == {"persist-credentials": "false"}, "CHECKOUT_CREDENTIALS"
    assert steps[1]["with"] == {"distribution": "temurin", "java-version": "17"}, "JAVA_17"
    assert steps[2]["with"] == {
        "validate-wrappers": "true",
        "cache-provider": "basic",
        "cache-read-only": "true",
    }, "WRAPPER_VALIDATION"
    assert set(steps[3]) <= {"name", "run"}, "BUILD_SCOPE"
    assert steps[3]["run"].strip() == BUILD, "CLEAN_BUILD"

    # Then failures cannot be skipped or swallowed, and the check name is unambiguous.
    for sibling in (*path.parent.glob("*.yml"), *path.parent.glob("*.yaml")):
        if sibling.resolve() == path.resolve():
            continue
        other = yaml.load(sibling.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
        for job_id, other_job in other.get("jobs", {}).items():
            assert other_job.get("name", job_id) != "Backend CI", "DUPLICATE_CHECK"


def main() -> None:
    """Allow a disposable workflow fixture as the sole optional argument."""
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / ".github/workflows/ci.yml"
    check_workflow(path)
    print("PASS: PR trigger, isolation, pins, Java 17, wrapper, build, unique Backend CI")


if __name__ == "__main__":
    main()
