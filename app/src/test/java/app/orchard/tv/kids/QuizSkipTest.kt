package app.orchard.tv.kids

import android.app.Application
import android.content.Context
import app.orchard.tv.settings.AerialSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "没学过，换一题": a question the child says they had not learned must not be asked again — not in
 * today's later rounds, and, unlike one merely asked before, not after midnight either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuizSkipTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        app.getSharedPreferences("kids", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        restart()
    }

    /** A fresh process; [yesterday] leaves the stored day behind, as the TV does after midnight. */
    private fun restart(yesterday: Boolean = false) {
        if (yesterday) {
            app.getSharedPreferences("kids", Context.MODE_PRIVATE).edit().putString("day", "2000-01-01").commit()
        }
        for (single in listOf(AerialSettings, KidsMode)) {
            single.javaClass.getDeclaredField("started").apply { isAccessible = true }.setBoolean(single, false)
        }
        KidsMode.init(app)
    }

    @Test
    fun aSkippedQuestionIsLeftOutOfTheNextRound() {
        KidsMode.skipQuestion("fruit:苹果")
        val round = QuizBankLoader.round(app, setOf(QuizBank.KgFruit), 40, KidsMode.askedToday, KidsMode.skipped)
        assertTrue(round.isNotEmpty())
        assertFalse(round.any { it.id == "fruit:苹果" })
    }

    @Test
    fun midnightForgetsWhatWasAskedButNotWhatWasSkipped() {
        KidsMode.skipQuestion("fruit:苹果")
        KidsMode.markAsked(listOf("fruit:桃子"))
        restart(yesterday = true)
        assertEquals(emptySet<String>(), KidsMode.askedToday)
        assertEquals(setOf("fruit:苹果"), KidsMode.skipped)
    }

    @Test
    fun aSkippedQuestionIsNotUsedToFillARound() {
        // A round short of fresh questions falls back on ones asked before, never on a skipped one.
        val all = QuizBankLoader.round(app, setOf(QuizBank.KgFruit), 200)
        KidsMode.skipQuestion("fruit:苹果")
        val round = QuizBankLoader.round(app, setOf(QuizBank.KgFruit), 40, all.map { it.id }.toSet(), KidsMode.skipped)
        assertFalse(round.isEmpty())
        assertFalse(round.any { it.id == "fruit:苹果" })
    }
}
