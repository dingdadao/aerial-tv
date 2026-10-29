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
 * The phone page's password, and the session it buys (see PhoneServer.login). The address in the
 * QR code is fixed on purpose, so the password is the only thing standing between a child with a
 * phone on the home network and the child mode, the parent password and every picture on the TV.
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

    /** Eight of the alphabet without look-alikes: it is read off a TV across the room and typed on a phone. */
    @Test
    fun aNewInstallHasAPagePasswordToReadOffTheTv() {
        val pw = AerialSettings.webPassword
        assertEquals(8, pw.length)
        assertTrue(pw.all { it in "abcdefghjkmnpqrstuvwxyz23456789" })
        assertTrue(AerialSettings.phonePageOpens(pw))
    }

    @Test
    fun thePagePasswordIsNotTheParentPassword() {
        assertNotEquals(AerialSettings.DEFAULT_PASSWORD, AerialSettings.webPassword)
        // It opens the page and nothing else: at the TV it is just a wrong entry.
        assertFalse(AerialSettings.unlock(AerialSettings.webPassword))
        assertTrue(AerialSettings.locked)
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
