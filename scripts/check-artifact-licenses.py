#!/usr/bin/env python3
"""Verify license metadata and full license text in the actual Maven artifacts."""

import argparse
import io
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", help="Staged Maven directory; defaults to Maven Central")
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-SNAPSHOT)?", args.version):
        parser.error("Expected a release or SNAPSHOT version")

    root = Path(__file__).resolve().parent.parent
    expected_license = (root / "LICENSE").read_bytes()
    artifacts = re.findall(r'include\(":([^"]+)"\)', (root / "settings.gradle.kts").read_text())
    if not artifacts:
        raise SystemExit("No publication modules found")
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

    def read_artifact(path):
        if args.repository:
            staged_path = Path(args.repository) / path
            if not staged_path.exists() and args.version.endswith("-SNAPSHOT"):
                metadata = ET.parse(staged_path.parent / "maven-metadata.xml").getroot()
                extension = staged_path.suffix.lstrip(".")
                matches = [entry.findtext("value") for entry in
                           metadata.findall("versioning/snapshotVersions/snapshotVersion")
                           if entry.findtext("extension") == extension and not entry.findtext("classifier")]
                if len(matches) != 1 or not matches[0]:
                    raise SystemExit(f"Ambiguous staged snapshot metadata: {path}")
                staged_path = staged_path.with_name(staged_path.name.replace(args.version, matches[0]))
            return staged_path.read_bytes()
        with urllib.request.urlopen("https://repo1.maven.org/maven2/" + path, timeout=30) as response:
            return response.read()

    for artifact in artifacts:
        base = f"com/debugbundle/{artifact}/{args.version}/{artifact}-{args.version}"
        pom = ET.fromstring(read_artifact(base + ".pom"))
        license_urls = [entry.text for entry in pom.findall("m:licenses/m:license/m:url", namespace)]
        if "https://www.apache.org/licenses/LICENSE-2.0" not in license_urls:
            raise SystemExit(f"Missing Apache-2.0 POM license: {artifact}")
        packaging = pom.findtext("m:packaging", default="jar", namespaces=namespace)
        if packaging == "pom":
            print(f"PASS {artifact}: Apache-2.0 POM")
            continue
        if packaging not in {"jar", "aar"}:
            raise SystemExit(f"Unexpected packaging: {artifact}: {packaging}")
        archive = zipfile.ZipFile(io.BytesIO(read_artifact(base + "." + packaging)))
        if packaging == "aar":
            archive = zipfile.ZipFile(io.BytesIO(archive.read("classes.jar")))
        license_path = f"META-INF/{artifact}/LICENSE"
        if license_path not in archive.namelist() or archive.read(license_path) != expected_license:
            raise SystemExit(f"Missing or incomplete packaged license: {artifact}: {license_path}")
        print(f"PASS {artifact}: Apache-2.0 POM and full packaged license")


if __name__ == "__main__":
    main()
