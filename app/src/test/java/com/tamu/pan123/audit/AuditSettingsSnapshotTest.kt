package com.tamu.pan123.audit

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.scrape.Scanner
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
class AuditSettingsSnapshotTest {
    @Test fun namingSettingsAreConsistentWithinOneScan()=runBlocking {
        AppDirs.init(RuntimeEnvironment.getApplication()); AppDirs.clearAll()
        val prior=Scanner.tmdb; val history=Scanner.historyLookup
        val simple=Naming.simpleNaming; val marker=Naming.tmdbInFileName
        try {
            Naming.simpleNaming=false; Naming.tmdbInFileName=false; Scanner.historyLookup=null
            var calls=0
            Scanner.tmdb=object:Scanner.Tmdb {
                override suspend fun show(names:List<String>,fixedId:Int?)=null to -1
                override suspend fun movie(stem:String,fallbackNames:List<String>,fallbackId:Int?):MatchInfo {
                    calls++
                    // Simulates the user changing the naming option between two async matches.
                    if(calls==2) Naming.simpleNaming=true
                    return MatchInfo("movie",calls,if(calls==1)"Alpha" else "Beta","2024")
                }
                override suspend fun movieInfo(id:Int):MatchInfo?=null
                override suspend fun collectionOf(movieId:Int):Pair<Int,String>?=null
            }
            val drive=TreeDrive(mapOf("F" to listOf(
                CloudEntry("a","SourceA.2024.2160p.HDR.mkv",false,parentFileId="F"),
                CloudEntry("b","SourceB.2024.2160p.HDR.mkv",false,parentFileId="F"))))
            val plan=Scanner.scan(listOf("F" to "待整理影片"),false,drive)
            val names=plan.actions.filter{it.op=="rename"&&it.kind=="file"}.map{it.newName}
            assertEquals(2,names.size)
            assertTrue("One scan should use its initial full-naming settings: $names",names.all{it.contains("HDR")})
        } finally {
            Scanner.tmdb=prior; Scanner.historyLookup=history
            Naming.simpleNaming=simple; Naming.tmdbInFileName=marker
        }
    }
}
