package com.tamu.pan123.live

import com.tamu.pan123.data.pan.Pan123Client
import com.tamu.pan123.data.pan.PanItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/** Explicit opt-in integration test; never uses saved phone credentials or the execution engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pan123LiveIntegrationTest {
    @Test(timeout = 240_000)
    fun isolatedUploadRenameMove(): Unit = runBlocking {
        assumeTrue("Real API test disabled", System.getenv("PAN123_LIVE_WRITE") == "1")
        val token = System.getenv("PAN123_TOKEN").orEmpty().trim().removePrefix("Bearer ")
        require(token.isNotEmpty()) { "Configure PAN123_TOKEN in environment secrets; never paste it into chat" }
        val name = "PanScraper_live_test_" + UUID.randomUUID().toString().replace("-", "")
        val parent = System.getenv("PAN123_TEST_PARENT_ID")?.toLongOrNull() ?: 0L
        require(parent >= 0) { "Invalid test parent ID" }
        val createdDirs = mutableSetOf<Long>()
        val createdFiles = mutableSetOf<Long>()
        val steps = mutableListOf<String>()
        val evidence = JSONObject().put("status", "not_started").put("test_directory_name", name)
            .put("real_account", true).put("uses_execution_engine", false).put("deletion_enabled", false)
        val output = File("build/live-123/result.json")
        fun save() { output.parentFile?.mkdirs(); output.writeText(evidence.put("steps", org.json.JSONArray(steps)).toString(2)) }
        fun step(label: String) { steps += label; save() }
        fun ownDir(id: Long) = require(id in createdDirs) { "Refused access outside newly created test directories" }
        fun ownFile(id: Long) = require(id in createdFiles) { "Refused mutation of a file not created by this test" }
        fun md5(bytes: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        Pan123Client.init()
        val field = Pan123Client::class.java.getDeclaredField("http").apply { isAccessible = true }
        val original = field.get(Pan123Client) as OkHttpClient
        var creatingTestRoot = false
        val guarded = original.newBuilder().addNetworkInterceptor { chain ->
            val req = chain.request()
            if (!req.url.isHttps) throw IOException("Live test refuses non-HTTPS redirects and storage URLs")
            val auth = req.header("Authorization").orEmpty()
            if (auth.isNotBlank() && (req.url.host != "123pan.com" || !req.url.encodedPath.startsWith("/b/api/"))) {
                throw IOException("Live test refuses credential forwarding outside the official API")
            }
            val response = chain.proceed(req)
            if (creatingTestRoot && req.method == "POST" && req.url.encodedPath.endsWith("/file/upload_request")) {
                val obj = runCatching { JSONObject(response.peekBody(1_048_576).string()) }.getOrNull()
                if (!response.isSuccessful || obj?.optInt("code", -1) !in listOf(0, 200)) {
                    response.close()
                    throw IOException("Fresh isolated directory creation was not confirmed; no existing directory will be reused")
                }
            }
            response
        }.build()
        field.set(Pan123Client, guarded)
        save()
        try {
            // One read-only page, not ping(), which currently enumerates all pages.
            val url = "https://123pan.com/b/api/file/list/new?driveId=0&parentFileId=$parent&limit=1&Page=1&orderBy=file_name&orderDirection=asc&trashed=false&inDirectSpace=false&event=homeListFile&OnlyLookAbnormalFile=0"
            val req = Request.Builder().url(url).header("authorization", "Bearer $token")
                .header("platform", "open_platform").header("app-version", "3")
                .header("user-agent", "Mozilla/5.0 AppleWebKit/600 Safari/600 Chrome/124.0.0.0 Edg/124.0.0.0").build()
            guarded.newCall(req).execute().use { response ->
                require(response.isSuccessful) { "Read-only authentication probe failed: HTTP ${response.code}" }
                val obj = JSONObject(response.body?.string().orEmpty())
                require(obj.optInt("code", -1) in listOf(0, 200) && obj.optJSONObject("data")?.optJSONArray("InfoList") != null) {
                    "Read-only authentication probe failed: business code ${obj.optInt("code", -1)}"
                }
            }
            step("read_only_authentication_passed")
            creatingTestRoot = true
            val root = try { Pan123Client.mkdir(parent, name, token).getOrThrow() } finally { creatingTestRoot = false }
            require(root > 0 && root != parent) { "Unexpected test root ID" }
            createdDirs += root
            evidence.put("test_directory_id", root.toString()); step("isolated_root_created")
            ownDir(root)
            require(Pan123Client.listFiles(root, token = token).isEmpty()) { "New root is not empty; stopping without further writes" }
            suspend fun mkdirUnderRoot(child: String): Long {
                ownDir(root)
                val id = Pan123Client.mkdir(root, child, token).getOrThrow()
                require(id > 0 && id != root && id != parent && id !in createdDirs) { "Unexpected child directory ID" }
                createdDirs += id
                ownDir(id)
                require(Pan123Client.listFiles(id, token = token).isEmpty()) { "Child directory is not empty" }
                return id
            }
            val source = mkdirUnderRoot("source")
            val destination = mkdirUnderRoot("destination")
            step("empty_source_and_destination_created")
            val fixtureName = "probe_" + name.takeLast(12) + ".txt"
            val renamed = "renamed_" + name.takeLast(12) + ".txt"
            val bytes = ("PanScraper isolated integration fixture\n$name\n").toByteArray()
            ownDir(source)
            assertTrue(Pan123Client.listFiles(source, token = token).isEmpty())
            Pan123Client.uploadBytes(source, fixtureName, bytes, token).getOrThrow()
            suspend fun entries(dir: Long): List<PanItem> { ownDir(dir); return Pan123Client.listFiles(dir, token = token) }
            val uploaded = entries(source).single { !it.isDir && it.fileName == fixtureName }
            require(uploaded.fileId > 0 && uploaded.fileId !in createdDirs && uploaded.fileId != parent) { "Unexpected uploaded ID" }
            assertEquals(bytes.size.toLong(), uploaded.size)
            if (!uploaded.etag.isNullOrBlank()) assertEquals(md5(bytes), uploaded.etag!!.lowercase())
            createdFiles += uploaded.fileId
            step("upload_visible_with_expected_size_and_optional_md5")
            ownFile(uploaded.fileId)
            Pan123Client.rename(uploaded.fileId, renamed, token).getOrThrow()
            assertEquals(renamed, entries(source).single { it.fileId == uploaded.fileId }.fileName)
            step("rename_confirmed_by_listing")
            ownFile(uploaded.fileId); ownDir(destination)
            assertTrue(entries(destination).isEmpty())
            Pan123Client.move(uploaded.fileId, destination, token).getOrThrow()
            assertFalse(entries(source).any { it.fileId == uploaded.fileId })
            assertEquals(renamed, entries(destination).single { it.fileId == uploaded.fileId }.fileName)
            step("move_confirmed_in_both_directories")
            evidence.put("status", "passed")
        } catch (e: Exception) {
            // Keep credentials, original exception text, and arbitrary account data out of JUnit/XML output.
            evidence.put("status", "failed").put("error_type", e.javaClass.simpleName)
            save()
            throw AssertionError("Live 123 test stopped; see build/live-123/result.json for completed steps (credentials omitted)")
        } finally {
            field.set(Pan123Client, original)
            save()
            // Deliberately retain this run's isolated data for review. No trash/delete method is called.
        }
    }
}
