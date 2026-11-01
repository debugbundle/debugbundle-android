#!/usr/bin/env python3
"""Fail the connected gate when the native installed-evidence test did not run."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

results = Path(sys.argv[1])
expected = 'com.debugbundle.smoke.InstalledEvidenceDeviceTest'
passed = []
for path in results.rglob('*.xml'):
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        if case.get('classname') == expected:
            if any(case.find(tag) is not None for tag in ['failure', 'error', 'skipped']):
                raise SystemExit(f'Connected evidence test did not pass: {path}')
            passed.append(case.get('name'))
if 'nativeCauseRecoveryPrivacyAndCapabilityDelivery' not in passed:
    raise SystemExit('Required installed native evidence test has no passing connected receipt')
print(f'Connected installed native evidence passed: {len(passed)} test(s)')
