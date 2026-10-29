package app.orchard.tv.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The console's password, and the session it buys (see PhoneServer.login). The address is the same
 * on every TV and never changes, so this password is the only thing standing between a child with
 * a phone on the home network and the child mode, the parent password and every picture on the TV.
 *
 * It starts as [AerialSettings.DEFAULT_WEB_PASSWORD], the same six digits as the parent password
 * but a different password: this one opens the page, and the page is where the other is changed.
 * The page says both of those things until it is changed ([AerialSettings.webPasswordDefault]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhonePagePasswordTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun freshInstall() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AerialSettings::class.java.getDeclaredField("started").apply { isAccessible = true }
            .setBoolean(AerialSettings, false)
        AerialSettings.init(context)
        AerialSettings.relock()
    }

    private fun restart() {
        AerialSettings::class.java.getDeclaredField("started").apply { isAccessible = true }
            .setBoolean(AerialSettings, false)
        AerialSettings.init(context)
        AerialSettings.relock()
    }

    /**
     * A console starts on the password every console starts with, and says so: the page keeps a
     * notice up (and the TV keeps [AerialSettings.webPasswordDefault]) until it is changed. A
     * console left on it is a console a child with a phone on the home network opens with six
     * digits everyone knows.
     */
    @Test
    fun aNewConsoleStartsOnTheDefaultAndSaysSo() {
        assertEquals("123456", AerialSettings.webPassword)
        assertTrue(AerialSettings.webPasswordDefault)
        assertTrue(AerialSettings.phonePageOpens("123456"))
    }

    /** The console's password and the TV's are two different passwords; neither is the other. */
    @Test
    fun theConsolePasswordIsNotTheParentPassword() {
        assertFalse(AerialSettings.phonePageOpens(AerialSettings.DEFAULT_PASSWORD.dropLast(1)))
        // Once it is changed they diverge for good: this one stops opening the page, ...
        AerialSettings.chooseWebPassword("kitchen-table")
        assertFalse(AerialSettings.webPasswordDefault)
        assertFalse(AerialSettings.phonePageOpens(AerialSettings.DEFAULT_PASSWORD))
        // ... and neither opens what the other does.
        assertFalse(AerialSettings.unlock("kitchen-table"))
        assertTrue(AerialSettings.locked)
    }

    /** A password the parent chose is theirs: the notice goes, and stays gone across a restart. */
    @Test
    fun changingItEndsTheDefaultForGood() {
        AerialSettings.chooseWebPassword("kitchen-table")
        assertFalse(AerialSettings.webPasswordDefault)
        restart()
        assertFalse(AerialSettings.webPasswordDefault)
        assertTrue(AerialSettings.phonePageOpens("kitchen-table"))
    }

    @Test
    fun aWrongOrEmptyEntryDoesNotOpenThePage() {
        assertFalse(AerialSettings.phonePageOpens(""))
        assertFalse(AerialSettings.phonePageOpens("zzzzzzzz"))
        assertFalse(AerialSettings.phonePageOpens(AerialSettings.webPassword.dropLast(1)))
        assertFalse(AerialSettings.phonePageOpens(AerialSettings.webPassword + "a"))
    }

    @Test
    fun aChosenPagePasswordOutlivesARestart() {
        AerialSettings.chooseWebPassword("kitchen-table")
        restart()
        assertTrue(AerialSettings.phonePageOpens("kitchen-table"))
        assertFalse(AerialSettings.phonePageOpens(AerialSettings.DEFAULT_PASSWORD))
    }

    /** "Show me a new one" on the TV: another random password, and the old one stops working. */
    @Test
    fun askingForANewOneReplacesTheOldOne() {
        val before = AerialSettings.webPassword
        val after = AerialSettings.chooseWebPassword()
        assertEquals(8, after.length)
        assertNotEquals(before, after)
        assertFalse(AerialSettings.phonePageOpens(before))
        assertTrue(AerialSettings.phonePageOpens(after))
    }

    /**
     * The two are separate: the page's password changes the parent password (that is the whole
     * point of it, and the only way to reset a forgotten one), and never the other way round.
     */
    @Test
    fun theTwoPasswordsAreChangedIndependently() {
        val pagePw = AerialSettings.webPassword
        AerialSettings.choosePin("246810")
        assertTrue(AerialSettings.phonePageOpens(pagePw))

        AerialSettings.chooseWebPassword("kitchen-table")
        AerialSettings.relock()
        assertTrue(AerialSettings.unlock("246810"))
    }

    /** The session behind a sheet that stays up is stretched; one that is walked away from is not. */
    @Test
    fun theParentPasswordLapsesAfterTenMinutesAndRenewStretchesIt() {
        AerialSettings.unlock(AerialSettings.DEFAULT_PASSWORD)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9))
        assertFalse(AerialSettings.locked)
        // A sheet still up: Home renews it every minute.
        AerialSettings.renew()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9))
        assertFalse(AerialSettings.locked)
        // Let go of: ten minutes on from the last renewal, it locks and renew no longer opens it.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        assertTrue(AerialSettings.locked)
        AerialSettings.renew()
        assertTrue(AerialSettings.locked)
    }
}
