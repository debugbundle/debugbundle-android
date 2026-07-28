#!/usr/bin/env python3

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


MINIMUM_LINE_PERCENT = 80.0
REPO_ROOT = Path(__file__).resolve().parent.parent


def fail(message: str) -> None:
    print(f"Android coverage gate failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def source_key(path: Path) -> str:
    source_root = next(
        parent for parent in path.parents if parent.as_posix().endswith("/src/main/kotlin")
    )
    return path.relative_to(source_root).as_posix()


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: check-coverage.py <JaCoCo XML path>")

    report_path = Path(sys.argv[1]).resolve()
    if not report_path.is_file():
        fail(f"coverage report does not exist: {report_path}")

    committed_sources = {
        source_key(path): path.resolve()
        for path in REPO_ROOT.glob("*/src/main/kotlin/**/*.kt")
    }
    if not committed_sources:
        fail("no committed Kotlin production sources were found")

    measured: dict[str, tuple[int, int]] = {}
    root = ET.parse(report_path).getroot()
    for package in root.findall("package"):
        package_name = package.attrib["name"]
        for source_file in package.findall("sourcefile"):
            key = f"{package_name}/{source_file.attrib['name']}"
            line_counter = source_file.find("counter[@type='LINE']")
            if line_counter is None:
                continue
            missed = int(line_counter.attrib["missed"])
            covered = int(line_counter.attrib["covered"])
            measured[key] = (missed, covered)

    missing = set(committed_sources) - set(measured)
    unexpected = set(measured) - set(committed_sources)
    if missing:
        fail(
            "production sources are absent from the merged report: "
            + ", ".join(sorted(missing))
        )
    if unexpected:
        fail(
            "the merged report contains untracked production sources: "
            + ", ".join(sorted(unexpected))
        )

    failures: list[tuple[str, float]] = []
    percentages: list[tuple[str, float]] = []
    for key, (missed, covered) in measured.items():
        total = missed + covered
        percent = 100.0 if total == 0 else covered * 100.0 / total
        percentages.append((key, percent))
        if percent + sys.float_info.epsilon < MINIMUM_LINE_PERCENT:
            failures.append((key, percent))

    if failures:
        for key, percent in sorted(failures, key=lambda item: item[1]):
            print(
                f"  {key}: {percent:.2f}% (minimum {MINIMUM_LINE_PERCENT:.2f}%)",
                file=sys.stderr,
            )
        fail(f"{len(failures)} source file(s) are below the per-file threshold")

    lowest_key, lowest_percent = min(percentages, key=lambda item: item[1])
    print(
        f"Android per-file line coverage passed for {len(percentages)} source files; "
        f"lowest is {lowest_key} at {lowest_percent:.2f}%."
    )


if __name__ == "__main__":
    main()
