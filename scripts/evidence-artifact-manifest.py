#!/usr/bin/env python3
"""Retain exact local source, staged publication and qualification identities."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
version_match = re.search(r"^VERSION_NAME=([0-9]+\.[0-9]+\.[0-9]+)$", (root / "gradle.properties").read_text(), re.M)
if version_match is None:
    raise SystemExit("Missing canonical candidate version")
version = version_match.group(1)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

names = subprocess.check_output(["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard", "-z"]).decode().split("\0")
sources = []
for name in sorted(set(names) - {""}):
    path = root / name
    if any(part in {".tmp", ".gradle", ".android-sdk", ".android-home", "build"} for part in path.relative_to(root).parts):
        continue
    if path.is_symlink() or not path.is_file():
        continue
    if path.suffix.lower() in {".pem", ".p12", ".jks", ".keystore", ".key"}:
        continue
    sources.append({"path": name, "sha256": digest(path)})
artifacts = []
for path in sorted((root / ".tmp/evidence-maven").rglob("*")):
    if path.is_file() and not path.is_symlink() and version in path.parts and path.suffix in {".jar", ".aar", ".pom", ".module"}:
        artifacts.append({"path": str(path.relative_to(root)), "sha256": digest(path), "bytes": path.stat().st_size})
if not artifacts:
    raise SystemExit("No staged candidate artifacts; run make evidence-smoke first")
receipt_paths = [Path("/tmp/debugbundle-zod4-android-schema-qualification.log")]
device_root = root.parent.parent / ".tmp/evidence-release"
qualified_apis = []
for api in (23, 36, 37):
    for passed in sorted((device_root / f"android-api{api}").glob("*/passed.json"), key=lambda path: path.stat().st_mtime, reverse=True):
        if json.loads(passed.read_text()).get("mode") != "performance":
            continue
        inputs = json.loads((passed.parent / "inputs.json").read_text())
        checked = {item["path"]: item["sha256"] for item in inputs["artifacts"]}
        # Never associate preceding device results with republished local Maven bytes.
        if not all(checked.get("sdks/debugbundle-android/" + item["path"]) == item["sha256"] for item in artifacts):
            continue
        receipt_paths.extend(passed.parent / name for name in ["inputs.json", "runtime.txt", "instrumentation.txt", "performance.txt", "passed.json"])
        qualified_apis.append(api)
        break
manifest = {
    "version": 1, "candidate_version": version, "source_state": "uncommitted local candidate",
    "base_commit": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
    "source_sha256": hashlib.sha256(json.dumps(sources, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
    "sources": sources, "artifacts": artifacts,
    "receipts": [{"path": str(path), "sha256": digest(path)} for path in receipt_paths],
    "qualified_android_apis": qualified_apis,
    "remaining": [f"connected API{api} and ART performance" for api in (23, 36, 37) if api not in qualified_apis]
        + ["whole-phase qualification", "separate publication authority"],
    "measurement_limits": ["ART allocation counters are approximate process-wide totals", "emulator results are not physical-device measurements"],
}
output = root / ".tmp/evidence-artifact-manifest.json"
output.write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Retained {len(sources)} source identities and {len(artifacts)} unpublished {version} artifacts: {output}")
