package com.tamu.pan123.audit

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.drive.*
import com.tamu.pan123.data.history.*
import com.tamu.pan123.data.scrape.*
import com.tamu.pan123.data.tmdb.MatchInfo
import com.tamu.pan123.scrape.TreeDrive
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AuditScannerTest {
    private val movie=MatchInfo("movie",1,"Alpha","2024","/poster.jpg")
    private val oldTmdb=Scanner.tmdb
    private val oldHistory=Scanner.historyLookup
    private fun f(id:String,name:String,parent:String)=CloudEntry(id,name,false,parentFileId=parent)
    private fun d(id:String,name:String,parent:String)=CloudEntry(id,name,true,parentFileId=parent)
    @Before fun setup() {
        AppDirs.init(RuntimeEnvironment.getApplication()); AppDirs.clearAll(); HistoryStore.reload(); HistoryStore.clear(); HistoryStore.setEnabled(true)
        Scanner.historyLookup=null
        Scanner.tmdb=object:Scanner.Tmdb {
            override suspend fun show(names:List<String>,fixedId:Int?)=null to -1
            override suspend fun movie(stem:String,fallbackNames:List<String>,fallbackId:Int?)=if(stem.startsWith("Beta")) null else movie
            override suspend fun movieInfo(id:Int)=movie
            override suspend fun collectionOf(movieId:Int):Pair<Int,String>?=null
        }
    }
    @After fun cleanup() { Scanner.tmdb=oldTmdb; Scanner.historyLookup=oldHistory; HistoryStore.clear() }
    @Test fun tvToMovieProducesMovieNfoDespiteOldSeasonNfo() = runBlocking {
        val drive=TreeDrive(mapOf("F" to listOf(d("S","Season 1","F")),
            "S" to listOf(f("v","Wrong.S01E01.mkv","S"),f("n","season.nfo","S"))))
        val plan=ScrapePlan(groups=listOf(PlanGroup("g","tv",2,"Wrong",folderId="F",folderName="Wrong")))
        val next=Scanner.replanGroup(plan,"g",movie,drive)
        assertTrue("Corrected movie needs movie.nfo; actual=${next.actions.map{it.newName}}",
            next.actions.any{it.op=="artwork"&&it.newName=="movie.nfo"})
    }
    @Test fun unmatchedMoviesSubtitleMustNotBeRenamedForMatchedMovie() = runBlocking {
        val drive=TreeDrive(mapOf("F" to listOf(f("a","Alpha.2024.mkv","F"),f("b","Beta.2024.mkv","F"),f("sub","Beta.2024.chs.srt","F"))))
        val p=Scanner.scan(listOf("F" to "待整理影片"),false,drive)
        assertFalse("Unmatched Beta subtitle must stay untouched: ${p.actions.filter{it.fileId=="sub"}}",
            p.actions.any{it.fileId=="sub"&&it.op=="rename"})
    }
    @Test fun overlappingRootsProduceUniqueActionsForEachFile() = runBlocking {
        val drive=TreeDrive(mapOf("R" to listOf(d("F","Alpha(2024)","R")),
            "F" to listOf(f("v","Alpha.2024.mkv","F"))))
        val p=Scanner.scan(listOf("R" to "电影","F" to "Alpha(2024)"),false,drive)
        assertEquals("Each directory should have one plan group",p.groups.size,p.groups.map{it.id}.distinct().size)
    }
    @Test fun partlyFinishedHistoryDoesNotSuppressMissingArtwork() = runBlocking {
        val file="Alpha.2024.mkv"
        val group=PlanGroup("g","movie",1,"Alpha","2024",folderId="F",folderName="Alpha(2024)")
        val rename=PlanAction("r","rename","g",fileId="v",oldName="a.mkv",newName=file,kind="file",tmdbId=1,done=true)
        val art=PlanAction("art","artwork","g",parentId="F",newName="movie.nfo",kind="movie_nfo",tmdbId=1,error="failed")
        val prior=ScrapePlan(accountId="tree",groups=listOf(group),actions=listOf(rename,art))
        HistoryStore.recordRun(prior,prior.actions.associateBy{it.id},listOf("r"))
        Scanner.historyLookup={a,p,id,n->HistoryStore.remembered(a,p,id,n)}
        val drive=TreeDrive(mapOf("F" to listOf(f("v",file,"F"))))
        val p=Scanner.scan(listOf("F" to "Alpha(2024)"),false,drive)
        assertTrue("Missing movie.nfo should still be planned",p.actions.any{it.newName=="movie.nfo"})
    }
}
