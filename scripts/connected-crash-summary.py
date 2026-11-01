"""Emit only fixed crash categories from a disposable fixture's crash buffer."""
import json
import re
import sys


def summarize(raw):
    # No log lines, free-form messages, class names, addresses or payload values
    # are copied. Require the fixture identity before reporting any category.
    fixture_seen = "com.debugbundle.smoke" in raw
    categories = {
        "out_of_memory": "java.lang.OutOfMemoryError" in raw,
        "assertion": "java.lang.AssertionError" in raw,
        "timeout": "java.util.concurrent.TimeoutException" in raw,
        "missing_method": "java.lang.NoSuchMethodError" in raw,
        "missing_class": "java.lang.NoClassDefFoundError" in raw,
        "stack_overflow": "java.lang.StackOverflowError" in raw,
        "link_error": "java.lang.UnsatisfiedLinkError" in raw,
        "verify_error": "java.lang.VerifyError" in raw,
        "illegal_state": "java.lang.IllegalStateException" in raw,
        "java_crash": "FATAL EXCEPTION" in raw,
        "anr": bool(re.search(r"(?:ANR in|am_anr).*com\.debugbundle\.smoke", raw)),
        "low_memory_kill": bool(re.search(r"(?:lowmemorykiller|ActivityManager).*kill.*com\.debugbundle\.smoke", raw, re.IGNORECASE)),
    }
    return {
        "log_available": bool(raw.strip()),
        "fixture_seen": fixture_seen,
        "categories": sorted(name for name, present in categories.items() if fixture_seen and present),
        "signals": sorted({int(value) for value in re.findall(r"Fatal signal (\d{1,2})\b", raw)}) if fixture_seen else [],
    }


if __name__ == "__main__":
    print(json.dumps(summarize(sys.stdin.read())))
