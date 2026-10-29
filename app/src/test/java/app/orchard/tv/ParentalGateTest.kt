package app.orchard.tv

import android.app.Application
import android.content.Context
import app.orchard.tv.kids.KidsMode
import app.orchard.tv.settings.AerialSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [HomeViewModel.whenUnlocked] is the gate every parental step in Home goes through: the Control
 * Center's Personalize sheet (which holds the child mode's switch), the child mode's panel, hidden
 * apps, and the parent's menu on the time-up screen.
 *
 * A step that reaches its overlay without it is a way past the password: the sheet would open, and
 * with it the child mode's switch. So: locked means asked first, and nothing runs until the right
 * password comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParentalGateTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        // A TV with the child mode on and the parent password locked: the state a child sits in.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("kids", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled", true).commit()
        for (single in listOf(AerialSettings, KidsMode)) {
            single.javaClass.getDeclaredField("started").apply { isAccessible = true }
                .setBoolean(single, false)
        }
        AerialSettings.init(app)
        AerialSettings.relock()
        vm = HomeViewModel(app)
    }

    @Test
    fun aLockedPasswordHoldsTheActionAndAsksForIt() {
        var ran = false
        vm.whenUnlocked { ran = true }
        assertFalse("the step ran while the password was locked", ran)
        val pin = vm.overlay as? Overlay.Pin
        assertNotNull("no password pad was put up", pin)
    }

    @Test
    fun oneRightPasswordRunsTheHeldAction() {
        var ran = 0
        vm.whenUnlocked { ran++ }
        // What the pad does once six digits are in and they are the password.
        assertTrue(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
        (vm.overlay as Overlay.Pin).onDone()
        assertEquals(1, ran)
        assertEquals(Overlay.None, vm.overlay)
    }

    @Test
    fun theWrongPasswordRunsNothing() {
        var ran = false
        vm.whenUnlocked { ran = true }
        assertFalse(AerialSettings.unlock("000000"))
        assertFalse("the step ran on a wrong password", ran)
        assertTrue(vm.overlay is Overlay.Pin)
    }

    @Test
    fun anUnlockedPasswordRunsAtOnce() {
        assertTrue(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
        var ran = false
        vm.whenUnlocked { ran = true }
        assertTrue(ran)
        assertEquals(Overlay.None, vm.overlay)
    }

    /**
     * The password opens the settings behind the gate, not the apps: the child mode holds with the
     * lock open as well, and only a [KidsMode.Grant] lets one app through (see KidsModeGrantsTest).
     */
    @Test
    fun thePasswordDoesNotOpenTheChildModeForEverything() {
        assertTrue(KidsMode.enabled)
        assertTrue(KidsMode.restricted)
        assertTrue(AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD))
        assertTrue("the open password session freed every app", KidsMode.restricted)
    }
}
