package com.tamu.pan123.data.exec

import com.tamu.pan123.data.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object TaskStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; isLenient = true }
    private val _run = MutableStateFlow<TaskRun?>(null)
    val run: StateFlow<TaskRun?> = _run.asStateFlow()

    private fun file(): File = File(AppDirs.plans, "task_run.json")

    fun load(): TaskRun? {
        val f = file()
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<TaskRun>(f.readText()) }
            .getOrNull()
            ?.also { _run.value = it }
    }

    fun save(run: TaskRun) {
        AppDirs.plans.mkdirs()
        val f = file()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(run))
        tmp.renameTo(f)
        _run.value = run
    }

    fun clear() {
        file().delete()
        _run.value = null
    }
}
