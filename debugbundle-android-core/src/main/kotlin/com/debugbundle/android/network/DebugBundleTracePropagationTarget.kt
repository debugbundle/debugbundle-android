package com.debugbundle.android.network

fun interface DebugBundleTracePropagationTarget {
    fun matches(url: String, host: String): Boolean

    companion object {
        fun host(host: String): DebugBundleTracePropagationTarget {
            val normalizedHost = host.lowercase()
            return DebugBundleTracePropagationTarget { _, candidateHost ->
                candidateHost.lowercase() == normalizedHost
            }
        }

        fun hostSuffix(hostSuffix: String): DebugBundleTracePropagationTarget {
            val normalizedSuffix = hostSuffix.lowercase().removePrefix(".")
            return DebugBundleTracePropagationTarget { _, candidateHost ->
                val normalizedHost = candidateHost.lowercase()
                normalizedHost == normalizedSuffix || normalizedHost.endsWith(".$normalizedSuffix")
            }
        }

        fun urlPrefix(urlPrefix: String): DebugBundleTracePropagationTarget {
            return DebugBundleTracePropagationTarget { url, _ ->
                url.startsWith(urlPrefix)
            }
        }
    }
}
