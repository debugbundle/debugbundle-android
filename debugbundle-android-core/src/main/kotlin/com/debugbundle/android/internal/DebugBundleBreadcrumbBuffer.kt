package com.debugbundle.android.internal

import com.debugbundle.android.DebugBundleBreadcrumb

internal class DebugBundleBreadcrumbBuffer(
    private val capacity: Int,
) {
    private val breadcrumbs = ArrayDeque<DebugBundleBreadcrumb>()

    @Synchronized
    fun add(breadcrumb: DebugBundleBreadcrumb) {
        breadcrumbs.addLast(breadcrumb)
        while (breadcrumbs.size > capacity) {
            breadcrumbs.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<DebugBundleBreadcrumb> {
        return breadcrumbs.toList()
    }

    @Synchronized
    fun clear() {
        breadcrumbs.clear()
    }
}
