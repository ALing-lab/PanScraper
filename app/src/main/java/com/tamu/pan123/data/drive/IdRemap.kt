package com.tamu.pan123.data.drive

/**
 * 执行过程中的 ID 重映射。
 * ID 稳定的提供方（123/115/光鸭）改名后 ID 不变，表永远为空；
 * 路径型（WebDAV）改名 / 移动文件夹后，旧路径及其所有子路径都要换成新前缀。
 */
class IdRemap(initial: Map<String, String> = emptyMap(), private val pathMode: Boolean) {
    private val map = LinkedHashMap(initial)

    fun snapshot(): Map<String, String> = map.toMap()

    fun record(old: String, new: String) {
        if (old == new) return
        map[old] = new
    }

    fun resolve(id: String?): String? {
        if (id == null) return null
        var cur = id
        // 最多迭代几次，处理链式改名（a→b→c）
        repeat(8) {
            val exact = map[cur]
            if (exact != null && exact != cur) { cur = exact; return@repeat }
            if (!pathMode) return cur
            val prefix = map.keys
                .filter { k -> k != "/" && cur!!.startsWith(k.trimEnd('/') + "/") }
                .maxByOrNull { it.length }
                ?: return cur
            cur = map.getValue(prefix).trimEnd('/') + cur!!.substring(prefix.trimEnd('/').length)
        }
        return cur
    }
}
