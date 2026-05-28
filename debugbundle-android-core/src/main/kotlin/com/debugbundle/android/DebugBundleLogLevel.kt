package com.debugbundle.android

enum class DebugBundleLogLevel {
    Debug,
    Info,
    Warning,
    Error,
    Fatal,
    Critical,
    ;

    internal fun captures(level: DebugBundleLogLevel): Boolean {
        return severity <= level.severity
    }

    private val severity: Int
        get() = when (this) {
            Debug -> 10
            Info -> 20
            Warning -> 30
            Error -> 40
            Fatal -> 50
            Critical -> 60
        }
}
