package app.orchard.tv

import app.orchard.tv.kids.KidsMode
import app.orchard.tv.ui.home.PinMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a parental session is held and when it is left to run out; see [ParentalSession] and
 * HomeViewModel's overlay watcher. A session that is walked away from used to run its full ten
 * minutes, which is enough for a child to pick the remote up and turn the child mode off.
 */
class ParentalSessionTest {

    @Test
    fun theSettingsBehindThePasswordHoldTheSession() {
        assertTrue(ParentalSession.holds(Overlay.Wallpaper))
        assertTrue(ParentalSession.holds(Overlay.Kids))
        assertTrue(ParentalSession.holds(Overlay.ParentOptions))
        assertTrue(ParentalSession.holds(Overlay.HiddenApps))
        // The pad itself: a parent typing the password is mid-session, not away from it.
        assertTrue(ParentalSession.holds(Overlay.Pin(PinMode.Unlock) {}))
    }

    @Test
    fun everythingAChildMayOpenLetsItRunOut() {
        assertFalse(ParentalSession.holds(Overlay.None))
        assertFalse(ParentalSession.holds(Overlay.ControlCenter(CcPage.Main)))
        assertFalse(ParentalSession.holds(Overlay.Quiz(null)))
        assertFalse(ParentalSession.holds(Overlay.TimeUp("YouTube", "com.google.android.youtube.tv", KidsMode.Block.TimeUp)))
    }

    @Test
    fun theGraceIsHalfAMinute() {
        assertEquals(30_000L, ParentalSession.LEAVE_LOCK_MS)
        // The stretch is well inside the ten minutes the password buys, so a sheet that is being
        // used never lapses under the parent.
        assertTrue(ParentalSession.RENEW_MS < 10 * 60 * 1000L)
    }
}
