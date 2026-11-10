package com.tamu.pan123.data

import android.content.SharedPreferences

/** 只在内存里的 SharedPreferences（加密存储不可用时用来放登录信息；进程结束即消失，从不写盘）。 */
internal class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized override fun getAll(): MutableMap<String, *> = HashMap(map)
    @Synchronized override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String?) = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Ed()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

    private inner class Ed : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            synchronized(this@MemoryPrefs) {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                for ((k, v) in puts) if (v == null) map.remove(k) else map[k] = v
            }
            return true
        }
        override fun apply() { commit() }
    }
}
