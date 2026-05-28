package com.debugbundle.android.runtime

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class TestApplication(
    private val rootDir: File,
) : Application() {
    private val preferences = ConcurrentHashMap<String, InMemorySharedPreferences>()

    override fun getFilesDir(): File = rootDir

    override fun getPackageName(): String = "com.debugbundle.android.test"

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return preferences.getOrPut(name) { InMemorySharedPreferences() }
    }

    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: IntentFilter?): Intent? = null
}

internal open class TestActivity : Activity()

private class InMemorySharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = values[key] as? Set<String>
        return value?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyChange(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            return applyChange(key, values?.toSet())
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyChange(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyChange(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyChange(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyChange(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = applyChange(key, Removed)

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) {
                values.clear()
            }
            for ((key, value) in pending) {
                if (value === Removed) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
            }
        }

        private fun applyChange(key: String?, value: Any?): SharedPreferences.Editor {
            pending[key ?: return this] = value
            return this
        }

        private companion object {
            private object Removed
        }
    }
}
