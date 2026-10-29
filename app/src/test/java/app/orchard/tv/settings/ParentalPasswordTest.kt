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
     * A new password leaves the lock alone: it does not open one. It used to open a window, and
     * that window was the last step of the bypass this whole test class guards — a pad that set a
     * password nobody had to know, then the ten minutes that came with setting it. No pad on the
     * TV sets one now, and the two callers left want no window: the phone page relocks at once,
     * and the ADB reset has no session to open.
     */
    @Test
    fun aNewPasswordLeavesTheLockAsItWas() {
        assertTrue(AerialSettings.locked)
        val before = AerialSettings.unlockTick
        AerialSettings.choosePin("246810")
        assertTrue(AerialSettings.locked)
        // Home arranges itself again on every tick: the child mode's rules follow this one.
        assertTrue(AerialSettings.unlockTick > before)
    }
}
