#!/usr/bin/env python3
"""Re-emit ktlint's checkstyle report as GitHub Actions annotations.

The ktlint Gradle plugin's PLAIN/CHECKSTYLE reporters write to
`app/build/reports/ktlint/`, which is only readable by downloading a build artifact.
Turning each violation into a `::error file=...,line=...::` workflow command puts it
inline on the pull request's Checks tab instead.

Safe to run when no report exists (prints nothing, exits 0), so it never masks the
real ktlint failure it runs after.
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

# Keeps us clear of the annotation limits GitHub applies to a single step.
MAX_ANNOTATIONS = 45

REPORT_GLOBS = (
    "app/build/reports/ktlint/**/*.xml",
    "build/reports/ktlint/**/*.xml",
)


def rel(path: str) -> str:
    """Make a report path relative to the workspace, which is what annotations need."""
    workspace = os.environ.get("GITHUB_WORKSPACE")
    if workspace:
        workspace = os.path.realpath(workspace) + os.sep
        real = os.path.realpath(path)
        if real.startswith(workspace):
            return real[len(workspace):]
    return path


def collect() -> tuple[dict[str, list[tuple[int, str]]], int]:
    """Return {file: [(line, "rule: message")]} and the total number of violations."""
    by_file: dict[str, list[tuple[int, str]]] = {}
    total = 0
    for pattern in REPORT_GLOBS:
        for report in sorted(glob.glob(pattern, recursive=True)):
            try:
                root = ET.parse(report).getroot()
            except ET.ParseError as exc:  # pragma: no cover - malformed report
                print(f"::warning::Could not parse {report}: {exc}")
                continue
            for file_node in root.iter("file"):
                name = rel(file_node.get("name") or "?")
                for error in file_node.findall("error"):
                    line = int(error.get("line") or 0)
                    rule = error.get("source") or "ktlint"
                    message = (error.get("message") or "").strip()
                    by_file.setdefault(name, []).append((line, f"{rule}: {message}"))
                    total += 1
    return by_file, total


def main() -> int:
    by_file, total = collect()
    if not total:
        print("No ktlint violations found in the checkstyle report.")
        return 0

    files = sorted(by_file)
    for index, name in enumerate(files):
        if index >= MAX_ANNOTATIONS:
            print(
                f"::error::...and {len(files) - MAX_ANNOTATIONS} more file(s) with violations; "
                f"see the uploaded ktlint-report artifact for the complete list."
            )
            break
        violations = by_file[name]
        first_line = min(line for line, _ in violations)
        details = " | ".join(f"line {line}: {text}" for line, text in sorted(violations))
        print(
            f"::error file={name},line={first_line},title=ktlint "
            f"({len(violations)} violation(s))::{details}"
        )
    print(f"ktlint reported {total} violation(s) across {len(files)} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
