package com.tamu.pan123.audit

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.history.*
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.scrape.*
import com.tamu.pan123.data.tmdb.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

/** Assertions describe intended behavior; failures reproduce the review findings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditPureTest {
    @Before fun setup() {
        AppDirs.init(RuntimeEnvironment.getApplication()); AppDirs.clearAll()
        HistoryStore.reload(); HistoryStore.clear(); HistoryStore.setEnabled(true)
    }
    @After fun cleanup() { HistoryStore.clear() }

    @Test fun jwtWithoutPaddingPreservesExpiry() {
        var payload = "{\"exp\":2000000000}"
        while (Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()).length % 4 == 0) payload += " "
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        assertEquals(2000000000L, SecureStore.jwtExp("e30.$encoded.signature"))
    }
    @Test fun missingReleaseDateDoesNotAbortScoring() {
        val score = TmdbClient.scoreMovie(TmdbSearchItem(id=1, title="Example", releaseDate=""), listOf("Example"), "2024")
        assertTrue(score.isFinite())
    }
    @Test fun cancellingMkdirCancelsTransitiveDelete() {
        val plan = ScrapePlan(actions=listOf(
            PlanAction("k", "mkdir", parentId="root", newName="Season 1"),
            PlanAction("m", "move", fileId="v", parentId="old", destRef="k"),
            PlanAction("d", "delete", fileId="old")
        ))
        val next = PlanSelection.set(plan, listOf("k"), false)
        assertEquals(emptyList<String>(), PlanSelection.selectedIds(next))
    }
    @Test fun sameNameDifferentWebdavPathsDoNotShareHistory() {
        HistoryStore.add(HistoryRecord("h",1,"dav","webdav", groups=listOf(
            HistoryGroup("/A/Show", "Show", "tv", 1, "A", files=listOf("e.mkv")))))
        assertNull(HistoryStore.remembered("dav", true, "/B/Show", "Show"))
    }
    @Test fun incomingDuplicateEpisodeGetsDistinctDestinationName() = kotlinx.coroutines.runBlocking {
        val canonical=Naming.buildEpisodeBase("Show","2024",1,1,com.tamu.pan123.data.naming.MediaParse.Media(),1)+".mkv"
        val drive=com.tamu.pan123.scrape.TreeDrive(mapOf(
            "F" to listOf(com.tamu.pan123.data.drive.CloudEntry("S","Season 1",true,parentFileId="F"),com.tamu.pan123.data.drive.CloudEntry("X","IncomingCopies",true,parentFileId="F")),
            "S" to listOf(com.tamu.pan123.data.drive.CloudEntry("existing",canonical,false,parentFileId="S")),
            "X" to listOf(com.tamu.pan123.data.drive.CloudEntry("incoming",canonical,false,parentFileId="X"))))
        val p=ScrapePlan(groups=listOf(PlanGroup("g","tv",1,"Show",folderId="F",folderName="Show")))
        val next=Scanner.replanGroup(p,"g",MatchInfo("tv",1,"Show","2024"),drive)
        val incomingMove=next.actions.single{it.fileId=="incoming"&&it.op=="move"}
        assertNotEquals("Incoming file conflicts with existing Season 1 file",canonical,incomingMove.newName)
    }
    @Test fun ordinaryGroupStateExcludesOptionalCleanup() {
        val p = ScrapePlan(actions=listOf(
            PlanAction("r","rename","g", selected=true),
            PlanAction("c","move","g", kind="cleanup",selected=false)))
        assertEquals(PlanSelection.Tri.ALL, PlanSelection.groupState(p,"g"))
        val withDelete=p.copy(actions=p.actions+PlanAction("delete","delete","g",fileId="folder",selected=false))
        assertEquals(listOf("r"),PlanSelection.groupIds(withDelete,"g"))
        assertFalse(PlanSelection.set(withDelete,PlanSelection.groupIds(withDelete,"g"),true).actions.single { it.id=="delete" }.selected)
    }
    @Test fun looseRematchPreservesMoveOperation() = kotlinx.coroutines.runBlocking {
        val p=ScrapePlan(groups=listOf(PlanGroup("g","movie",1,"Old")),actions=listOf(
            PlanAction("r","rename","g",fileId="v",parentId="root",oldName="Old.2020.mkv",newName="Old.2020.mkv",kind="file"),
            PlanAction("m","move","g",fileId="v",parentId="root",oldName="Old.2020.mkv",newName="Old.2020.mkv",kind="file",destParentId="dest")))
        val drive=com.tamu.pan123.scrape.TreeDrive(mapOf("root" to listOf(com.tamu.pan123.data.drive.CloudEntry("v","Old.2020.mkv",false,parentFileId="root"))))
        val next=Scanner.replanGroup(p,"g",MatchInfo("movie",2,"New","2024"),drive)
        assertTrue(next.actions.any { it.fileId=="v" && it.op=="move" })
        assertTrue(next.actions.any { it.op=="mkdir" && it.newName=="New(2024){tmdb-2}" })
    }
    @Test fun validMovieDateControl() {
        assertTrue(TmdbClient.scoreMovie(TmdbSearchItem(id=1,title="Example",releaseDate="2024-01-01"),listOf("Example"),"2024").isFinite())
    }

}
