#!/usr/bin/env python3
"""Fail the connected gate unless both installed-evidence tests ran and passed."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

results = Path(sys.argv[1])
expected = {
    ('com.debugbundle.smoke.InstalledEvidenceDeviceTest', 'nativeCauseRecoveryPrivacyAndCapabilityDelivery'),
    ('com.debugbundle.smoke.InstalledEvidencePerformanceTest', 'compareNativeCaptureAndDeferredWork'),
}
passed = set()
for path in results.rglob('*.xml'):
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        identity = (case.get('classname'), case.get('name'))
        if identity in expected:
            if any(case.find(tag) is not None for tag in ['failure', 'error', 'skipped']):
                raise SystemExit(f'Connected evidence test did not pass: {path}')
            passed.add(identity)
if expected - passed:
    raise SystemExit('Required installed native evidence or performance test has no passing connected receipt')
print(f'Connected installed native evidence passed: {len(passed)} test(s)')
