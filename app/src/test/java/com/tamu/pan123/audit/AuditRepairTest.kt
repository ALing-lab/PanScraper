package com.tamu.pan123.audit

import com.tamu.pan123.data.*
import com.tamu.pan123.data.net.fetch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import com.tamu.pan123.data.drive.*
import com.tamu.pan123.data.exec.*
import com.tamu.pan123.data.history.HistoryStore
import com.tamu.pan123.data.scrape.*
import com.tamu.pan123.data.tmdb.*
import com.tamu.pan123.exec.*
import com.tamu.pan123.scrape.TreeDrive
import com.tamu.pan123.service.ExecForegroundService
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AuditRepairTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val oldTmdb = Scanner.tmdb
    private val oldHistory = Scanner.historyLookup
    @Before fun setup() {
        AppDirs.init(app); AppDirs.clearAll(); TaskStore.clear(); HistoryStore.reload(); HistoryStore.setEnabled(false)
        ExecutionEngine.OP_SLEEP_MS=0; ExecutionEngine.SETTLE_MS=0; ExecutionEngine.UPLOAD_RETRY_MS=emptyList()
        ExecutionEngine.driveOverride=FakeDrive(); Scanner.historyLookup=null
        SecureStore.testPlainPrefs=true; SecureStore.resetForTest(); SecureStore.init(app)
    }
    @After fun cleanup() {
        ExecutionEngine.stop(); ExecutionEngine.driveOverride=null; ExecutionEngine.artworkOverride=null
        ExecutionEngine.OP_SLEEP_MS=200; ExecutionEngine.SETTLE_MS=2000; ExecutionEngine.UPLOAD_RETRY_MS=listOf(2000,5000,10000)
        TaskStore.clear(); Scanner.tmdb=oldTmdb; Scanner.historyLookup=oldHistory; HistoryStore.setEnabled(true)
        TmdbNet.apply(TmdbNetConfig()); SecureStore.clearTmdbKey()
    }
    private fun save(p: ScrapePlan, name: String="scrape_repair.json"):ScrapePlan { PlanStore.save(p,name); return p }
    private fun rename(id:String)=PlanAction(id,"rename",fileId=id,parentId="root",oldName="old.mkv",newName="new.mkv",kind="file")
    @Test fun executingOldPlanWritesOnlyToBoundFile()=runBlocking {
        val original=save(ScrapePlan(actions=listOf(rename("a"))),"scrape_old.json")
        val later=ScrapePlan(actions=listOf(rename("b")))
        val base=FakeDrive()
        ExecutionEngine.driveOverride=object:CloudDrive by base {
            override suspend fun rename(fileId:String,newName:String):Result<String> {
                PlanStore.save(later,"scrape_later.json")
                return base.rename(fileId,newName)
            }
        }
        assertEquals(1,ExecutionEngine.run(original).ok)
        assertTrue(PlanStore.loadNamed("scrape_old.json").actions.single().done)
        assertFalse(PlanStore.loadNamed("scrape_later.json").actions.single().done)
        assertEquals("scrape_old.json",TaskStore.load()!!.planName)
    }
    @Test fun selectionDeltaPreservesNewDoneAndError() {
        val before=save(ScrapePlan(actions=listOf(rename("a"),rename("b"))))
        PlanStore.update { it.copy(actions=it.actions.map { a -> if(a.id=="a") a.copy(done=true,note="uploaded") else a.copy(error="denied") }) }
        val result=PlanStore.mergeSelection(before,PlanSelection.set(before,listOf("a","b"),false))
        assertTrue(result.actions[0].done); assertEquals("uploaded",result.actions[0].note)
        assertEquals("denied",result.actions[1].error); assertFalse(result.actions[1].selected)
    }
    @Test fun staleSelectionCannotReplaceRegeneratedPlan() {
        val before=save(ScrapePlan(actions=listOf(rename("a"))))
        save(ScrapePlan(actions=listOf(rename("replacement"))))
        assertTrue(runCatching { PlanStore.mergeSelection(before,PlanSelection.setAll(before,false)) }.isFailure)
        assertEquals("replacement",PlanStore.load().actions.single().id)
    }
    @Test fun clearIsRejectedUntilExecutionFinishes()=runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val base=FakeDrive()
        ExecutionEngine.driveOverride=object:CloudDrive by base {
            override suspend fun rename(fileId:String,newName:String):Result<String> {
                entered.complete(Unit); release.await(); return base.rename(fileId,newName)
            }
        }
        val p=save(ScrapePlan(actions=listOf(rename("a"))))
        coroutineScope {
            val job=async(Dispatchers.IO) { ExecutionEngine.run(p) }
            try { withTimeout(3000) { entered.await() }; assertTrue(runCatching { AppDirs.clearAll() }.isFailure)
                assertTrue("执行期间不得替换计划",runCatching { PlanStore.replace(p,p.copy(flags=listOf("replacement"))) }.isFailure) }
            finally { release.complete(Unit); job.await() }
        }
        assertTrue(PlanStore.load().actions.single().done)
        AppDirs.clearAll(); assertNull(PlanStore.latestFile())
    }
    @Test fun pauseDuringRetryDelayWaitsForResume()=runBlocking {
        ExecutionEngine.OP_SLEEP_MS=1; ExecutionEngine.UPLOAD_RETRY_MS=listOf(200)
        var attempts=0
        ExecutionEngine.artworkOverride={ attempts++; if(attempts==1) throw IOException("first attempt") else byteArrayOf(1) }
        val p=save(ScrapePlan(actions=listOf(PlanAction("art","artwork",parentId="root",newName="poster.jpg"))))
        coroutineScope {
            val job=async(Dispatchers.IO) { ExecutionEngine.run(p) }
            try {
                withTimeout(5000) { while(ExecutionEngine.live.value?.message?.contains("后重试")!=true) delay(5) }
                ExecutionEngine.pause(); delay(350); assertEquals(1,attempts)
                ExecutionEngine.resume(); assertEquals(0,withTimeout(5000) { job.await() }.fail); assertEquals(2,attempts)
            } finally { ExecutionEngine.stop(); job.cancelAndJoin() }
        }
    }
    @Test fun retryRetainsPathRemapFromSuccessfulFolderRename()=runBlocking {
        val base=FakePathDrive(setOf("/a/v.mkv"), mutableSetOf("/","/a","/dest")); var deny=true
        ExecutionEngine.driveOverride=object:CloudDrive by base {
            override suspend fun move(fileId:String,destParentId:String):Result<String> =
                if(deny) Result.failure(IOException("transient")) else base.move(fileId,destParentId)
        }
        val p=save(ScrapePlan(accountId=base.accountId,actions=listOf(
            PlanAction("r","rename",fileId="/a",parentId="/",oldName="a",newName="b",kind="folder"),
            PlanAction("m","move",fileId="/a/v.mkv",parentId="/a",destParentId="/dest",kind="file"))))
        assertEquals(1,ExecutionEngine.run(p).fail); deny=false
        assertEquals(0,ExecutionEngine.run(PlanStore.load(),listOf("m")).fail)
        assertTrue("/dest/v.mkv" in base.paths)
    }
    @Test fun startErrorIsNeverShownAsAllSuccessful() {
        ExecutionEngine.reportStartError("missing plan")
        assertFalse(TaskStore.load()!!.fullySucceeded)
    }
    @Test fun serviceStartCarriesChosenPlanIdentity() {
        val p=save(ScrapePlan(actions=listOf(rename("a"))),"scrape_chosen.json")
        ExecForegroundService.start(app,listOf("a"),plan=p)
        val intent=shadowOf(app).nextStartedService
        assertEquals("scrape_chosen.json",intent.getStringExtra(ExecForegroundService.EXTRA_PLAN_NAME))
        assertEquals(PlanStore.key(p),intent.getStringExtra(ExecForegroundService.EXTRA_PLAN_KEY))
    }
    @Test fun taskRestoreUsesItsFileAndRejectsOtherAccount() {
        val p=save(ScrapePlan(actions=listOf(rename("a"))),"scrape_first.json")
        val task=TaskRun("t","scrape_first.json",listOf("a"),accountId=p.accountId,planKey=PlanStore.key(p))
        save(ScrapePlan(actions=listOf(rename("b"))),"scrape_second.json")
        assertEquals("a",PlanStore.forTask(task).actions.single().id)
        assertTrue(runCatching { PlanStore.forTask(task.copy(accountId="other")) }.isFailure)
    }
    @Test fun tmdbBearerGoesInHeaderAndApiKeyStaysInQuery()=runBlocking {
        val s=MockWebServer();s.start()
        try {
            TmdbNet.apply(TmdbNetConfig(apiBase=s.url("/3").toString(),doh=false))
            SecureStore.saveTmdbKey("header.payload.signature")
            s.enqueue(MockResponse().setBody("{}")); assertTrue(TmdbClient.test().isSuccess)
            val jwt=s.takeRequest(); assertEquals("Bearer header.payload.signature",jwt.getHeader("Authorization")); assertNull(jwt.requestUrl!!.queryParameter("api_key"))
            SecureStore.saveTmdbKey("apikey123")
            s.enqueue(MockResponse().setBody("{}")); assertTrue(TmdbClient.test().isSuccess)
            val key=s.takeRequest(); assertNull(key.getHeader("Authorization")); assertEquals("apikey123",key.requestUrl!!.queryParameter("api_key"))
        } finally { s.shutdown() }
    }
    @Test fun looseRematchRebuildsMovieDestinationSubtitlesAndMetadata()=runBlocking {
        val v=CloudEntry("v","Source.2024.mkv",false,parentFileId="R")
        val sub=CloudEntry("s","Source.2024.chs.srt",false,parentFileId="R")
        val cloud=TreeDrive(mapOf("R" to listOf(v,sub)))
        val p=ScrapePlan(groups=listOf(PlanGroup("g","movie",1,"Old")),actions=listOf(
            PlanAction("k","mkdir","g",parentId="R",newName="Old(2024){tmdb-1}",tmdbId=1),
            PlanAction("m","move","g",fileId="v",parentId="R",oldName=v.fileName,destRef="k",kind="file"),
            PlanAction("art","artwork","g",parentId="R",newName="movie.nfo",tmdbId=1,destRef="k")))
        val next=Scanner.replanGroup(p,"g",MatchInfo("movie",2,"New","2025","/new.jpg"),cloud)
        assertTrue(next.actions.any { it.op=="move" && it.fileId=="v" })
        assertTrue(next.actions.any { it.op=="move" && it.fileId=="s" })
        assertTrue(next.actions.any { it.op=="mkdir" && it.newName=="New(2025){tmdb-2}" })
        assertTrue(next.actions.filter { it.op=="artwork" }.all { it.tmdbId==2 })
        assertFalse(next.actions.any { it.tmdbId==1 })
    }
    @Test fun rootDisplayPathDoesNotBecomeFolderName()=runBlocking {
        val cloud=TreeDrive(mapOf("F" to listOf(CloudEntry("v","Source.2024.mkv",false,parentFileId="F"))))
        Scanner.tmdb=object:Scanner.Tmdb {
            override suspend fun show(names:List<String>,fixedId:Int?)=null to -1
            override suspend fun movie(stem:String,fallbackNames:List<String>,fallbackId:Int?)=MatchInfo("movie",1,"Alpha","2024")
            override suspend fun movieInfo(id:Int)=MatchInfo("movie",1,"Alpha","2024")
            override suspend fun collectionOf(movieId:Int):Pair<Int,String>?=null
        }
        val p=Scanner.scan(listOf("F" to "Library/Movies/Alpha(2024)"),false,cloud)
        assertEquals("Alpha(2024)",p.groups.single().folderName)
        assertFalse(p.actions.filter { it.kind=="folder" }.any { '/' in it.oldName })
    }
    @Test fun loginUsesHttpsOfficialRootsInsteadOfBrokenLoginPath() {
        assertTrue(com.tamu.pan123.ui.screens.LOGIN_URLS.all { com.tamu.pan123.ui.screens.isOfficialUrl(it, com.tamu.pan123.ui.screens.PAN123_HOSTS) })
        assertTrue(com.tamu.pan123.ui.screens.LOGIN_URLS.none { it.endsWith("/login") || it.contains("login.123pan.com") })
        val attempts=com.tamu.pan123.ui.screens.LoginAttempts()
        assertNotNull(attempts.next()); assertNotNull(attempts.next()); assertNull(attempts.next())
        assertEquals(com.tamu.pan123.ui.screens.LOGIN_URLS.first(),attempts.reset())
    }
    @Test fun loginPreservesCurrentWebViewVersionAndSupportsTabletDesktopMode() {
        val native="Mozilla/5.0 (Linux; Android 16; Tablet; wv) AppleWebKit/537.36 Version/4.0 Chrome/143.0.7499.10 Mobile Safari/537.36"
        val desktop=com.tamu.pan123.ui.screens.loginUserAgent(native,true)
        assertTrue(desktop.contains("Chrome/143.0.7499.10")); assertFalse(desktop.contains("Android 13"))
        assertFalse(desktop.contains(" Mobile")); assertFalse(desktop.contains("; wv"))
        assertTrue(com.tamu.pan123.ui.screens.loginUserAgent(native,false).contains("Android 16"))
        assertFalse(com.tamu.pan123.ui.screens.isOfficialUrl("http://www.123pan.com/login",com.tamu.pan123.ui.screens.PAN123_HOSTS))
        assertFalse(com.tamu.pan123.ui.screens.isOfficialUrl("https://123pan.com.evil.invalid/",com.tamu.pan123.ui.screens.PAN123_HOSTS))
    }
    @Test fun pan123PingOnlyRequestsFirstPageEvenWhenMoreExist()=runBlocking {
        val field=com.tamu.pan123.data.pan.Pan123Client::class.java.getDeclaredField("http").apply { isAccessible=true }
        com.tamu.pan123.data.pan.Pan123Client.init()
        val previous=field.get(null); var requests=0
        val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val page=chain.request().url.queryParameter("Page")!!.toInt()
            val body=if(page==1) """{"code":0,"data":{"InfoList":[{"FileId":1,"FileName":"fixture"}],"Next":"1"}}""" else """{"code":0,"data":{"InfoList":[],"Next":"-1"}}"""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        field.set(null,client)
        try { assertTrue(com.tamu.pan123.data.pan.Pan123Client.ping("fixture-token")); assertEquals(1,requests) }
        finally { field.set(null,previous) }
    }
    @Test fun oversizedApiResponseFailsInsteadOfAllocatingUnboundedMemory()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setChunkedBody("X".repeat(8*1024*1024+1),8192))
            val client=okhttp3.OkHttpClient()
            assertTrue(runCatching { client.fetch(okhttp3.Request.Builder().url(server.url("/big")).build()) }.isFailure)
        } finally { server.shutdown() }
    }
    @Test fun timeoutSavesPauseAndStopsForegroundService() {
        val r=TaskRun("timeout","scrape_timeout.json",listOf("a"),status=TaskRun.STATUS_RUNNING)
        TaskStore.save(r);ExecutionEngine.reportLive(r)
        val controller=org.robolectric.Robolectric.buildService(ExecForegroundService::class.java).create()
        val service=controller.get()
        service.onTimeout(1,1)
        assertEquals(TaskRun.STATUS_PAUSED,TaskStore.load()!!.status)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
    @Test fun childRootBeforeParentDoesNotReplanAlreadyVisitedVideo()=runBlocking {
        val cloud=TreeDrive(mapOf("F" to listOf(CloudEntry("S","Season 1",true,parentFileId="F")),
            "S" to listOf(CloudEntry("v","Show.S01E01.mkv",false,parentFileId="S"))))
        Scanner.tmdb=object:Scanner.Tmdb {
            override suspend fun show(names:List<String>,fixedId:Int?)=MatchInfo("tv",1,"Show","2024") to 0
            override suspend fun movie(stem:String,fallbackNames:List<String>,fallbackId:Int?)=null
            override suspend fun movieInfo(id:Int):MatchInfo?=null
            override suspend fun collectionOf(movieId:Int):Pair<Int,String>?=null
        }
        val p=Scanner.scan(listOf("S" to "Season 1","F" to "Show(2024)"),false,cloud)
        assertEquals(1,p.actions.count { it.fileId=="v" && it.op=="rename" })
        assertEquals(p.groups.size,p.groups.map { it.id }.distinct().size)
    }
}
