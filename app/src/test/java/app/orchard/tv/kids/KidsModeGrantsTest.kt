package app.orchard.tv.kids

import android.app.Application
import android.content.Context
import app.orchard.tv.settings.AerialSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the parent password opens from the time-up screen: the one app the child was stopped in,
 * for that launch, 30 minutes or the rest of the day. Every other app stays shut, and the pass
 * for one launch is over once Aerial is in front again — the child who leaves the app and opens
 * it again is asked for the password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KidsModeGrantsTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        app.getSharedPreferences("kids", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled", true)
            .putString("rules", """{"com.kid.app":"blocked","com.other.app":"blocked"}""")
            .commit()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (single in listOf(AerialSettings, KidsMode)) {
            single.javaClass.getDeclaredField("started").apply { isAccessible = true }
                .setBoolean(single, false)
        }
        KidsMode.init(app)
    }

    @Test
    fun aBlockedAppStaysShutUntilAParentPassesIt() {
        assertEquals(KidsMode.Block.Hard, KidsMode.blockReason(app, "com.kid.app"))
        KidsMode.grant("com.kid.app", KidsMode.Grant.Once)
        assertNull(KidsMode.blockReason(app, "com.kid.app"))
    }

    @Test
    fun thePassOpensOnlyTheAppItWasGivenFor() {
        KidsMode.grant("com.kid.app", KidsMode.Grant.Today)
        assertEquals(KidsMode.Block.Hard, KidsMode.blockReason(app, "com.other.app"))
    }

    @Test
    fun aPassForOneLaunchIsOverOnceAerialIsBack() {
        KidsMode.grant("com.kid.app", KidsMode.Grant.Once)
        KidsMode.endLaunchGrants()
        assertEquals(KidsMode.Block.Hard, KidsMode.blockReason(app, "com.kid.app"))
    }

    @Test
    fun theTimedPassesOutlastLeavingTheApp() {
        KidsMode.grant("com.kid.app", KidsMode.Grant.Thirty)
        KidsMode.endLaunchGrants()
        assertNull("the 30-minute pass ended when the child left the app", KidsMode.blockReason(app, "com.kid.app"))
    }

    @Test
    fun thePassIsWrittenDownForAGuardThatRestarts() {
        KidsMode.grant("com.kid.app", KidsMode.Grant.Today)
        KidsMode.javaClass.getDeclaredField("started").apply { isAccessible = true }.setBoolean(KidsMode, false)
        KidsMode.init(app)
        assertTrue(KidsMode.enabled)
        assertNull(KidsMode.blockReason(app, "com.kid.app"))
    }
}
