package com.debugbundle.android

import java.time.Instant

internal class DebugBundleRemoteProbeState {
    private var probesEnabled: Boolean = true
    private var remoteProbesEnabled: Boolean = false
    private var remoteProbeDirectives: List<DebugBundleRemoteProbeDirective> = emptyList()
    private var activeTriggerDirective: DebugBundleRemoteProbeDirective? = null
    private var triggerTokenKey: String? = null

    @Synchronized
    fun probesEnabled(): Boolean = probesEnabled

    @Synchronized
    fun triggerTokenKey(): String? = triggerTokenKey

    @Synchronized
    fun activateTrigger(directive: DebugBundleRemoteProbeDirective) {
        activeTriggerDirective = directive
    }

    @Synchronized
    fun matchingDirectives(
        label: String,
        service: String,
        environment: String,
        now: Instant,
    ): List<DebugBundleRemoteProbeDirective> {
        activeTriggerDirective = activeTriggerDirective?.takeIf { it.isActive(now) }
        if (!probesEnabled || !remoteProbesEnabled) {
            return emptyList()
        }
        remoteProbeDirectives = remoteProbeDirectives.filter { it.isActive(now) }
        val directives = activeTriggerDirective?.let { remoteProbeDirectives + it } ?: remoteProbeDirectives
        return directives.filter { directive ->
            directive.effectiveActivationId.isNotBlank() && directive.matches(label, service, environment)
        }
    }

    @Synchronized
    fun applyConfig(
        probesEnabled: Boolean,
        remoteProbesEnabled: Boolean,
        directives: List<DebugBundleRemoteProbeDirective>,
        triggerTokenKey: String?,
        now: Instant,
    ) {
        this.probesEnabled = probesEnabled
        this.remoteProbesEnabled = remoteProbesEnabled
        this.remoteProbeDirectives = if (remoteProbesEnabled) {
            directives.filter { it.isActive(now) }
        } else {
            emptyList()
        }
        this.triggerTokenKey = triggerTokenKey
        if (!probesEnabled || !remoteProbesEnabled) {
            activeTriggerDirective = null
        }
    }

    @Synchronized
    fun applyPiggybackDirectives(directives: List<DebugBundleRemoteProbeDirective>?, now: Instant) {
        if (directives == null || !probesEnabled || !remoteProbesEnabled) {
            return
        }
        remoteProbeDirectives = directives.filter { it.isActive(now) }
    }
}

private fun DebugBundleRemoteProbeDirective.isActive(now: Instant): Boolean {
    val expiresAtInstant = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return false
    return expiresAtInstant.isAfter(now)
}

private fun DebugBundleRemoteProbeDirective.matches(label: String, service: String, environment: String): Boolean {
    if (this.service != "*" && this.service != service) {
        return false
    }
    if (this.environment != "*" && this.environment != environment) {
        return false
    }
    return when {
        labelPattern == "*" -> true
        labelPattern.endsWith(".*") -> label.startsWith(labelPattern.removeSuffix(".*") + ".")
        else -> label == labelPattern
    }
}
