package com.tamu.pan123.audit

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.drive.*
import com.tamu.pan123.data.exec.*
import com.tamu.pan123.data.history.HistoryStore
import com.tamu.pan123.data.scrape.*
import com.tamu.pan123.exec.FakeDrive
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AuditEngineTest {
    @Before fun setup() {
        AppDirs.init(RuntimeEnvironment.getApplication()); AppDirs.clearAll(); TaskStore.clear()
        HistoryStore.reload(); HistoryStore.setEnabled(false)
        ExecutionEngine.OP_SLEEP_MS=0; ExecutionEngine.SETTLE_MS=0
        ExecutionEngine.UPLOAD_RETRY_MS=emptyList()
    }
    @After fun cleanup() {
        ExecutionEngine.stop(); ExecutionEngine.driveOverride=null; ExecutionEngine.artworkOverride=null
        ExecutionEngine.OP_SLEEP_MS=200; ExecutionEngine.SETTLE_MS=2000
        ExecutionEngine.UPLOAD_RETRY_MS=listOf(2000,5000,10000)
        HistoryStore.setEnabled(true); TaskStore.clear()
    }
    private fun save(p:ScrapePlan):ScrapePlan { PlanStore.save(p,"scrape_audit.json"); return p }
    @Test fun retryUsesSuccessfulMkdirFromFirstAttempt() = runBlocking {
        val base=FakeDrive(); var fail=true
        ExecutionEngine.driveOverride=object:CloudDrive by base {
            override suspend fun move(fileId:String,destParentId:String):Result<String> =
                if(fail) Result.failure(IOException("transient")) else base.move(fileId,destParentId)
        }
        val p=save(ScrapePlan(actions=listOf(
            PlanAction("k","mkdir",parentId="root",newName="Season 1"),
            PlanAction("m","move",fileId="video",parentId="root",destRef="k"))))
        assertEquals(1,ExecutionEngine.run(p).fail)
        fail=false
        val retry=ExecutionEngine.run(PlanStore.load(),listOf("m"))
        assertEquals("Retry should resolve already created directory: ${retry.failed}",0,retry.fail)
    }
    @Test fun failedSubtitleMovePreventsDeletingItsFolder() = runBlocking {
        val base=FakeDrive(); var videoMoved=false; val deleted=mutableListOf<String>()
        ExecutionEngine.driveOverride=object:CloudDrive by base {
            override val trashSupport=TrashSupport.PERMANENT
            override suspend fun list(folderId:String):List<CloudEntry> = if(folderId=="old") buildList {
                if(!videoMoved) add(CloudEntry("v","e.mkv",false,parentFileId="old"))
                add(CloudEntry("s","e.srt",false,parentFileId="old"))
            } else emptyList()
            override suspend fun move(fileId:String,destParentId:String):Result<String> {
                if(fileId=="s") return Result.failure(IOException("subtitle move denied"))
                videoMoved=true; return Result.success(fileId)
            }
            override suspend fun trash(fileId:String):Result<String> { deleted+=fileId; return Result.success("deleted") }
        }
        val p=save(ScrapePlan(actions=listOf(
            PlanAction("v-move","move",fileId="v",parentId="old",destParentId="new",kind="file"),
            PlanAction("s-move","move",fileId="s",parentId="old",destParentId="new",kind="subtitle"),
            PlanAction("delete","delete",fileId="old"))))
        ExecutionEngine.run(p)
        assertTrue("Subtitle remains in old directory; must not delete it. deleted=$deleted",deleted.isEmpty())
    }
    @Test fun stopDuringRetryDelayDoesNotStartNewAttempt() = runBlocking {
        ExecutionEngine.driveOverride=FakeDrive(); ExecutionEngine.OP_SLEEP_MS=1
        ExecutionEngine.UPLOAD_RETRY_MS=listOf(400)
        var attempts=0
        ExecutionEngine.artworkOverride={ attempts++; throw IOException("image unavailable") }
        val p=save(ScrapePlan(actions=listOf(PlanAction("art","artwork",parentId="root",newName="poster.jpg",kind="image"))))
        withTimeout(5000) {
            val job=async(Dispatchers.IO) { ExecutionEngine.run(p) }
            while(ExecutionEngine.live.value?.message?.contains("后重试")!=true) delay(5)
            ExecutionEngine.stop(); job.await()
        }
        assertEquals("Stopping before retry must prevent a new fetch",1,attempts)
    }
}
