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
    checkpoints = re.findall(
        r"fixture=com\.debugbundle\.smoke stage=(case_begin|client_ready|worker_held|capture_begin|captured|flush_begin|counter_ready|released|flush_returned|worker_completed|flushed|case_done) repeat=([0-3]) evidence=(true|false) pressure=(true|false) heap_used_kib=(\d{1,10}) heap_limit_kib=(\d{1,10})\b",
        raw,
    )
    last = checkpoints[-1] if checkpoints else None
    fixture_pids = set(re.findall(
        r"\bam_proc_start\b[^\n]*\[\d+,\s*(\d+),\s*\d+,\s*com\.debugbundle\.smoke(?:,|\])", raw,
    ))
    kernel_kill_pids = re.findall(
        r"(?:lowmemorykiller|lmkd)[^\n]*[Kk]ill[^\n]*?\((\d+)\)|(?:Out of memory: Kill process|Killed process) (\d+)", raw,
    )
    memtotal = re.findall(r"^MemTotal:\s+(\d+)\s+kB\s*$", raw, re.M)
    return {
        "log_available": bool(raw.strip()),
        "fixture_seen": fixture_seen,
        "categories": sorted(name for name, present in categories.items() if fixture_seen and present),
        "signals": sorted({int(value) for value in re.findall(r"Fatal signal (\d{1,2})\b", raw)}) if fixture_seen else [],
        "last_checkpoint": {"stage": last[0], "repeat": int(last[1]), "evidence": last[2] == "true", "pressure": last[3] == "true", "heap_used_kib": int(last[4]), "heap_limit_kib": int(last[5])} if last else None,
        "fixture_process_events": {
            tag: len(re.findall(rf"\b{tag}\b[^\n]*com\.debugbundle\.smoke\b", raw))
            for tag in ("am_proc_start", "am_proc_died", "am_kill", "am_crash", "am_anr", "am_force_stop")
        } if fixture_seen else {},
        # Kernel task names may be truncated. Report a device-wide count rather
        # than attributing another process's kill to this fixture.
        "device_low_memory_kills": len(re.findall(
            r"(?:lowmemorykiller|lmkd)[^\n]*[Kk]ill|Out of memory: Kill process|Killed process \d+",
            raw,
        )),
        "fixture_kernel_low_memory_kills": sum(
            any(pid in fixture_pids for pid in match if pid) for match in kernel_kill_pids
        ),
        "device_memtotal_kib": int(memtotal[-1]) if memtotal else None,
    }


if __name__ == "__main__":
    print(json.dumps(summarize(sys.stdin.read())))
