package app.orchard.tv.settings

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The parent password, and the lock every parental gate in Home asks about
 * ([app.orchard.tv.HomeViewModel.whenUnlocked], see also [app.orchard.tv.ParentalGateTest]).
 *
 * The child mode enforces nothing while this lock is open (KidsMode.restricted), so the
 * password is the whole of the parental control: what it opens, what it replaces, and what it
 * holds across a restart is what these pin down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParentalPasswordTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /**
     * A fresh install: preferences cleared, and the singleton put back in its un-started state
     * (it is an object, so it outlives one test method into the next).
     */
    @Before
    fun freshInstall() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AerialSettings::class.java.getDeclaredField("started").apply { isAccessible = true }
            .setBoolean(AerialSettings, false)
        AerialSettings.init(context)
        AerialSettings.relock()
    }

    /** A cold start of the app: the same preferences read again into a built-from-scratch singleton. */
    private fun restart() {
        AerialSettings::class.java.getDeclaredField("started").apply { isAccessible = true }
            .setBoolean(AerialSettings, false)
        AerialSettings.init(context)
        AerialSettings.relock()
    }

    @Test
    fun aNewInstallHasTheDefaultPasswordAndIsLocked() {
        assertTrue(AerialSettings.pinSet)
        assertTrue(AerialSettings.locked)
    }

    @Test
    fun aWrongPasswordLeavesItLocked() {
        assertFalse(AerialSettings.unlock("000000"))
        assertTrue(AerialSettings.locked)
    }

    @Test
    fun theDefaultPasswordOpensIt() {
        assertTrue(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
        assertFalse(AerialSettings.locked)
    }

    @Test
    fun relockLocksItAgain() {
        AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD)
        AerialSettings.relock()
        assertTrue(AerialSettings.locked)
    }

    @Test
    fun aNewPasswordRetiresTheOldOne() {
        AerialSettings.choosePin("246810")
        AerialSettings.relock()
        assertFalse(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
        assertTrue(AerialSettings.locked)
        assertTrue(AerialSettings.unlock("246810"))
    }

    /**
     * `adb shell am broadcast -a app.orchard.tv.CONFIG --ez clear_pin true` (ConfigReceiver), the
     * only way back in for an owner who forgot it: the default password again, never no password.
     */
    @Test
    fun aForgottenPasswordGoesBackToTheDefault() {
        AerialSettings.choosePin("246810")
        AerialSettings.choosePin(null)
        AerialSettings.relock()
        assertTrue(AerialSettings.pinSet)
        assertTrue(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
    }

    @Test
    fun thePasswordOutlivesARestart() {
        AerialSettings.choosePin("246810")
        restart()
        assertTrue(AerialSettings.locked)
        assertFalse(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
        assertTrue(AerialSettings.unlock("246810"))
    }

    /**
     * The last step of the bypass this test came from: setting a password opens the lock for
     * [AerialSettings]'s window. That is the parent's own doing when they are the one setting it,
     * and the reason the pad that sets it must not be reachable without the old password
     * (HomeScreen's onPin, behind whenUnlocked).
     */
    @Test
    fun settingAPasswordOpensTheLockForAWhile() {
        assertTrue(AerialSettings.locked)
        val before = AerialSettings.unlockTick
        AerialSettings.choosePin("246810")
        assertFalse(AerialSettings.locked)
        // Home arranges itself again on every tick: the child mode's rules change with this one.
        assertTrue(AerialSettings.unlockTick > before)
    }
}
