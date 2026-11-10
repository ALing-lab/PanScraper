package com.tamu.pan123

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDirsTest {
    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        AppDirs.init(ctx)
        SecureStore.init(ctx)
    }

    @Test fun clearKeepsCredentials() {
        SecureStore.saveToken("aaa.bbb.ccc", passport = "13800138000", expiresAt = System.currentTimeMillis()/1000 + 86400)
        SecureStore.saveTmdbKey("testkey123")
        File(AppDirs.plans, "x.json").writeText("{}")
        File(AppDirs.imgCache, "a.jpg").writeBytes(byteArrayOf(1,2,3))
        val before = AppDirs.sizeBytes()
        assertTrue(before > 0)
        AppDirs.clearAll()
        assertEquals(0, AppDirs.usage().plans)
        assertEquals(0, AppDirs.usage().imgCache)
        assertTrue(SecureStore.session.value.token.isNotBlank())
        assertEquals("testkey123", SecureStore.tmdbKey.value)
    }
}
