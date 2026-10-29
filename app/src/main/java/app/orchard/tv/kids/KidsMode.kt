package app.orchard.tv.kids

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.guard.HomeGuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Child mode, one global budget for every timed app:
 * - each app is Free, Blocked (a child can never open it) or Timed (counts against the budget),
 * - all Timed apps share one daily quota (00:00-24:00) and one set of allowed hours,
 * - a quiz round earns minutes for that budget, and a parent can open one app past it ([Grant]).
 *
 * Home Guard, the only place that sees which app is in front, enforces it. The parent password
 * opens the settings behind it, not the apps: from the time-up screen a parent opens the one app
 * the child was stopped in, for that launch, 30 minutes or the rest of the day ([Grant]). The
 * categories and global settings are set on the phone page or in the TV's kids panel; the
 * question set a quiz round uses is set on the phone page.
 */
object KidsMode {
    private const val PREFS = "kids"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RULES = "rules"
    private const val KEY_QUOTA = "quota"
    private const val KEY_SLOTS = "slots"
    private const val KEY_QUIZ_ENABLED = "quiz_enabled"
    private const val KEY_EARNED = "earned"
    private const val KEY_GRANTS = "grants"
    private const val KEY_DAY = "day"
    private const val KEY_QUIZ_TOPICS = "quiz_topics"
    private const val KEY_ASKED = "asked"

    /** The older single-set choice, read once so a TV that already had one keeps a topic. */
    private const val KEY_QUIZ_BANK = "quiz_bank"

    /** Minutes one right answer is worth, and the most a single round pays. */
    const val MINUTES_PER_CORRECT = 2
    const val MAX_REWARD_PER_ROUND = 20
    const val QUESTIONS_PER_ROUND = 8

    /** The tile Home shows to earn time; not a package, so nothing can launch it. */
    const val QUIZ_KEY = "kids:quiz"

    /** Daily choices for the whole child mode; 0 is no limit. 5 and 15 are there to try it out. */
    val QUOTA_CHOICES = listOf(0, 5, 15, 30, 60, 90, 120)

    const val DEFAULT_QUOTA = 60

    /** Slot presets offered on the TV, from no limit to late: all day, 7-19, 8-20, 9-21. */
    val SLOT_PRESETS = listOf(
        emptyList(),
        listOf(Slot(7 * 60, 19 * 60)),
        listOf(Slot(8 * 60, 20 * 60)),
        listOf(Slot(9 * 60, 21 * 60)),
    )

    /** What one app means in child mode. */
    enum class Category(val key: String) {
        /** Not restricted and not counted. */
        Free("free"),

        /** A child can never open it while child mode is on. */
        Blocked("blocked"),

        /** Counts against the global daily quota, only inside the allowed hours. */
        Timed("timed");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Free
        }
    }

    /** Minutes from midnight to midnight; [end] before [start] runs past midnight. */
    data class Slot(val start: Int, val end: Int) {
        fun contains(minute: Int): Boolean =
            if (start <= end) minute in start until end else minute >= start || minute < end
    }

    /** What stops a child in front of an app, shown by the time-up screen. */
    enum class Block(val key: String) {
        /** The app is set to Blocked: never in child mode. */
        Hard("hard"),

        /** A Timed app outside the allowed hours. */
        OutsideSlot("slot"),

        /** The global daily quota (and the minutes quizzes earned) is used up. */
        TimeUp("timeup");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * What the parent password opens from the time-up screen, for the one app the child was
     * stopped in and no other: a pass that ends when the child leaves the app, after 30 minutes,
     * or at midnight.
     */
    enum class Grant {
        /** This launch only: back in Aerial, the password is asked for again. */
        Once,

        /** Thirty minutes from now. */
        Thirty,

        /** The rest of today. */
        Today,
    }

    private var started = false
    private lateinit var app: Context

    var enabled by mutableStateOf(false); private set

    /** Category per app: "free" (or absent), "blocked" or "timed". */
    var categories by mutableStateOf<Map<String, Category>>(emptyMap()); private set

    /** The minutes all Timed apps share per day; 0 is no limit. */
    var dailyQuota by mutableStateOf(DEFAULT_QUOTA); private set

    /** The hours Timed apps may run; empty means all day. */
    var slots by mutableStateOf<List<Slot>>(emptyList()); private set

    /** Whether a quiz may earn more minutes when the daily quota is used up. */
    var quizEnabled by mutableStateOf(true); private set

    /** Extra minutes earned today by quizzes, for the global budget. */
    var earned by mutableStateOf(0); private set

    /**
     * Apps a parent opened from the time-up screen, each with the moment its pass ends: [ONCE] for
     * one that lasts until Aerial is in front again, otherwise wall-clock milliseconds.
     */
    private var grants = emptyMap<String, Long>()

    /** Questions already asked today; a new round leaves them out, and midnight forgets them. */
    var askedToday by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The topics a round draws from, ticked by the parent on the phone page: a child should not
     * have to pick a level, and could not tell which one suits them.
     */
    var quizTopics by mutableStateOf(setOf(QuizBank.KgMath)); private set

    private var day = ""

    /** Apps no child mode blocks: the system UI, installers and the TV's own input players. */
    private val alwaysAllowed = setOf(
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.android.permissioncontroller",
        "com.xiaomi.mitv.tvplayer",
        "com.tcl.tv",
        "com.skyworth.tv",
        "com.android.tv",
    )

    /**
     * On: what Home Guard enforces. The parent password does not lift this: it opens the settings
     * behind it, and the one app a parent passes from the time-up screen ([Grant]); every other
     * app stays shut.
     */
    val restricted: Boolean get() = enabled

    /** Whether Home shows the quiz tile. */
    val quizTile: Boolean get() = enabled && quizEnabled

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        categories = parseCategories(prefs.getString(KEY_RULES, null))
        dailyQuota = prefs.getInt(KEY_QUOTA, DEFAULT_QUOTA)
        slots = parseSlots(prefs.getString(KEY_SLOTS, null))
        quizEnabled = prefs.getBoolean(KEY_QUIZ_ENABLED, true)
        quizTopics = prefs.getStringSet(KEY_QUIZ_TOPICS, null)
            ?.mapNotNull { QuizBank.of(it) }?.toSet()?.takeIf { it.isNotEmpty() }
            ?: QuizBank.of(prefs.getString(KEY_QUIZ_BANK, null))?.let { setOf(it) }
            ?: setOf(QuizBank.KgMath)
        day = prefs.getString(KEY_DAY, null) ?: today()
        if (day != today()) {
            day = today()
            earned = 0
            askedToday = emptySet()
            grants = emptyMap()
            persistDay()
        } else {
            earned = prefs.getInt(KEY_EARNED, 0)
            askedToday = prefs.getStringSet(KEY_ASKED, null).orEmpty().toSet()
            grants = parseGrants(prefs.getString(KEY_GRANTS, null))
        }
        watchUsage()
        migrateV1(prefs)
    }

    /**
     * v1 stored one rule per app (managed/unlock/mode/slots/quota) and per-app quiz minutes.
     * Fold it into the global model once: managed apps become Timed, the first app's slots and
     * quota become the global ones, and today's quiz minutes move into the shared budget.
     */
    private fun migrateV1(prefs: SharedPreferences) {
        val raw = prefs.getString(KEY_RULES, null) ?: return
        if (categories.isNotEmpty() || !raw.contains("\"managed\"")) return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val next = HashMap(categories)
        var quota = dailyQuota
        var firstSlots: List<Slot>? = null
        root.keys().forEach { pkg ->
            val o = root.optJSONObject(pkg) ?: return@forEach
            if (o.optBoolean("managed", false)) next[pkg] = Category.Timed
            if (firstSlots == null) {
                firstSlots = o.optJSONArray("slots")?.let { array ->
                    (0 until array.length()).mapNotNull { parseSlot(array.optString(it)) }
                }?.takeIf { it.isNotEmpty() }
            }
            val oldQuota = o.optInt("quota", 0)
            if (oldQuota > 0 && quota == DEFAULT_QUOTA) quota = oldQuota
        }
        if (next.isEmpty() && root.length() == 0) return
        categories = next
        dailyQuota = quota
        if (firstSlots != null) slots = firstSlots
        val oldEarned = prefs.getString("earned", null)?.let(::parseEarnedMap).orEmpty()
        if (prefs.getString("earned_day", null) == today() && oldEarned.isNotEmpty()) {
            earned += oldEarned.values.sum()
        }
        persist()
    }

    /** Switches the mode on or off. Not `setEnabled`: that would clash with the property's setter. */
    fun setOn(on: Boolean) {
        ensureDay()
        enabled = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
    }

    /** The topics rounds use, ticked by the parent on the phone page. Never empty. */
    fun chooseQuizTopics(topics: Set<QuizBank>) {
        val next = topics.ifEmpty { setOf(QuizBank.KgMath) }
        if (quizTopics == next) return
        quizTopics = next
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_QUIZ_TOPICS, next.map { it.key }.toSet())
        }
    }

    /** The category of [pkg], or Free when no child mode restricts it. */
    fun category(pkg: String): Category = categories[pkg] ?: Category.Free

    fun setCategory(pkg: String, category: Category) {
        categories = if (category == Category.Free) categories - pkg else categories + (pkg to category)
        usageAt = 0L
        persist()
    }

    fun setQuota(minutes: Int) {
        dailyQuota = minutes.coerceIn(0, 24 * 60)
        persist()
    }

    fun setSlotList(next: List<Slot>) {
        slots = next.sortedBy { it.start }
        persist()
    }

    fun setQuizOn(on: Boolean) {
        quizEnabled = on
        persist()
    }

    /** Adds minutes won by a quiz round to the global budget. */
    fun addEarned(minutes: Int) {
        ensureDay()
        val add = minutes.coerceAtLeast(0)
        if (add == 0) return
        earned += add
        persistDay()
    }

    /**
     * A parent opened [pkg] with the password: for this launch, for 30 minutes, or until
     * midnight. Only that app is let through; the rest of the child mode still holds.
     */
    fun grant(pkg: String, grant: Grant) {
        ensureDay()
        val until = when (grant) {
            Grant.Once -> ONCE
            Grant.Thirty -> System.currentTimeMillis() + THIRTY_MS
            Grant.Today -> startOfDay() + DAY_MS
        }
        grants = grants + (pkg to until)
        persistGrants()
    }

    /**
     * Aerial is in front again, so a [Grant.Once] pass is used up: the child who opens that app
     * next is asked for the password again. Called from Home's resume, which is where the child
     * lands after leaving the app by any route.
     */
    fun endLaunchGrants() {
        val left = grants.filterValues { it != ONCE }
        if (left.size == grants.size) return
        grants = left
        persistGrants()
    }

    /** Whether the parent's pass for [pkg] still holds; a lapsed one is dropped on the way. */
    private fun granted(pkg: String): Boolean {
        val until = grants[pkg] ?: return false
        if (until == ONCE || until > System.currentTimeMillis()) return true
        grants = grants - pkg
        persistGrants()
        return false
    }

    /** The whole budget a child may use today: quota plus the minutes quizzes earned. */
    val managedToday: Int get() = dailyQuota + earned

    /** Minutes all Timed apps have been in front today; 0 when usage cannot be counted. */
    fun usedToday(context: Context): Int = usageToday(context).values.sum()

    /** Minutes left for the whole child mode today, or null when it has no limit or cannot count. */
    fun remainingToday(context: Context): Int? {
        if (dailyQuota <= 0 || !canCountTime(context)) return null
        return (managedToday - usedToday(context)).coerceAtLeast(0)
    }

    /** Why Home Guard must take a child out of [pkg], or null when it may stay. */
    fun blockReason(context: Context, pkg: String): Block? {
        if (!restricted) return null
        if (pkg == context.packageName || pkg in alwaysAllowed) return null
        if (granted(pkg)) return null
        return when (category(pkg)) {
            Category.Free -> null
            Category.Blocked -> Block.Hard
            Category.Timed -> {
                if (slots.isNotEmpty() && !inSlot(slots)) Block.OutsideSlot
                else if (dailyQuota > 0 && canCountTime(context) && usedToday(context) >= managedToday) Block.TimeUp
                else null
            }
        }
    }

    private fun inSlot(slots: List<Slot>): Boolean {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return slots.any { it.contains(minute) }
    }

    /** Whether the install script's GET_USAGE_STATS grant is in place; without it minutes cannot run. */
    fun canCountTime(context: Context): Boolean = HomeGuardService.usageStatsAllowed(context)

    private fun ensureDay() {
        val now = today()
        if (now == day) return
        day = now
        earned = 0
        grants = emptyMap()
        // Yesterday's reading must not stand for today's, and neither must yesterday's questions.
        usageAt = 0L
        askedToday = emptySet()
        persistDay()
        persistGrants()
    }

    /** Notes the questions of a round, so the next round leaves them out until midnight. */
    fun markAsked(ids: Collection<String>) {
        ensureDay()
        if (ids.isEmpty()) return
        val next = askedToday + ids
        val capped = if (next.size <= ASKED_LIMIT) next else next.toList().takeLast(ASKED_LIMIT).toSet()
        if (capped == askedToday) return
        askedToday = capped
        persistDay()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private var usageAt = 0L
    @Volatile private var usageCache: Map<String, Int> = emptyMap()
    @Volatile private var usageFront: String? = null

    /** Usage is read here, off the main thread: the guard asks for it while a key is in flight. */
    private val usageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var usageTicker: Job? = null

    /**
     * Keeps the usage reading warm from a background thread, so the guard and Home find it cached
     * instead of querying hours of events on the main thread at every app switch.
     */
    private fun watchUsage() {
        if (usageTicker != null) return
        usageTicker = usageScope.launch {
            while (isActive) {
                // Twice as often as the cache lasts, so a caller on the main thread always finds it
                // fresh and never reads the events there.
                if (restricted) runCatching { readUsage(app) }
                delay(USAGE_CACHE_MS / 2)
            }
        }
    }

    /**
     * Minutes each Timed app has been in front today, the session still running included. Read
     * from the system's usage events, which the install script grants over ADB; empty when it was
     * never granted.
     *
     * The events are read instead of the daily totals [UsageStatsManager] reports, because those
     * add a foreground session only once it ends: a child watching one video for an hour still
     * read as no time used and the quota never ran out. Counting the open session up to now makes
     * the limit run out while the app is in front, and a screen off ends every open session.
     *
     * Only Timed apps are counted: the minutes exist to decide whether the child's budget is
     * used up, not to report on every app on the TV.
     */
    fun usageToday(context: Context): Map<String, Int> {
        readUsage(context)
        return usageCache
    }

    /** The Timed app in front right now, from the same reading as [usageToday]; null when none. */
    fun timedInFront(context: Context): String? {
        readUsage(context)
        return usageFront
    }

    @Synchronized
    private fun readUsage(context: Context) {
        ensureDay()
        val now = System.currentTimeMillis()
        if (now - usageAt < USAGE_CACHE_MS) return
        usageAt = now
        if (!HomeGuardService.usageStatsAllowed(context)) {
            usageCache = emptyMap()
            usageFront = null
            return
        }
        // The string service name, not the class one: getSystemService(Class) needs Android 6.
        @Suppress("DEPRECATION")
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (manager == null) {
            usageCache = emptyMap()
            usageFront = null
            return
        }
        val reading = readUsageEvents(manager, now)
        if (reading != null) {
            usageCache = reading.minutes
            usageFront = reading.inFront
            return
        }
        // A firmware that hands out no events: the daily totals still count the sessions that
        // ended, so the quota is enforced again at the next app switch.
        usageCache = queryDailyTotals(manager, now)
        usageFront = null
    }

    private class Reading(val minutes: Map<String, Int>, val inFront: String?)

    /**
     * Per app the minutes read from usage events, the session still open counted up to [now]; null
     * where the system hands out no events at all. Sessions are clipped at midnight, so one that
     * started before it counts only today's part.
     */
    @Suppress("DEPRECATION")
    private fun readUsageEvents(manager: UsageStatsManager, now: Long): Reading? {
        val dayStart = startOfDay()
        val events = runCatching { manager.queryEvents(dayStart - SESSION_PREROLL_MS, now) }.getOrNull()
            ?: return null
        val open = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        fun close(pkg: String, at: Long) {
            val start = open.remove(pkg) ?: return
            val from = start.coerceAtLeast(dayStart)
            val to = at.coerceAtMost(now)
            if (to > from) totals[pkg] = totals.getOrDefault(pkg, 0L) + (to - from)
        }
        fun closeAll(at: Long) = open.keys.toList().forEach { close(it, at) }
        var seen = false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            seen = true
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    if (open[event.packageName] == null) open[event.packageName] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> close(event.packageName, event.timeStamp)
                // Standby or power off with an app in front: nothing more is watched there.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.DEVICE_SHUTDOWN -> closeAll(event.timeStamp)
            }
        }
        if (!seen) return null
        // Whatever is still open has been in front all along; that is the app to enforce first.
        val inFront = open.entries.maxByOrNull { it.value }?.key
            ?.takeIf { categories[it] == Category.Timed }
        closeAll(now)
        val minutes = totals.mapNotNull { (pkg, ms) ->
            if (categories[pkg] != Category.Timed) null else pkg to (ms / 60_000L).toInt()
        }.toMap()
        return Reading(minutes, inFront)
    }

    /** The daily totals the system reports, which count finished sessions only; see [usageToday]. */
    private fun queryDailyTotals(manager: UsageStatsManager, now: Long): Map<String, Int> {
        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay(), now)
        }.getOrNull().orEmpty()
        return stats.mapNotNull { s ->
            if (categories[s.packageName] != Category.Timed) null
            else s.packageName to (s.totalTimeInForeground / 60_000L).toInt()
        }.toMap()
    }

    /** "08:00-20:00", the form the phone page and the rule store use. */
    fun formatSlot(slot: Slot): String = "%02d:%02d-%02d:%02d".format(
        slot.start / 60, slot.start % 60, slot.end / 60, slot.end % 60,
    )

    fun parseSlot(text: String): Slot? {
        val parts = text.trim().split('-').takeIf { it.size == 2 } ?: return null
        val start = parseTime(parts[0]) ?: return null
        val end = parseTime(parts[1]) ?: return null
        return if (start == end) null else Slot(start, end)
    }

    private fun parseTime(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun parseCategories(raw: String?): Map<String, Category> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Category>()
        root.keys().forEach { pkg -> Category.of(root.optString(pkg)).takeIf { it != Category.Free }?.let { out[pkg] = it } }
        return out
    }

    private fun parseSlots(raw: String?): List<Slot> {
        if (raw.isNullOrEmpty()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { parseSlot(array.optString(it)) }
    }

    private fun parseEarnedMap(raw: String?): Map<String, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Int>()
        root.keys().forEach { pkg -> root.optInt(pkg, 0).takeIf { it > 0 }?.let { out[pkg] = it } }
        return out
    }

    private fun persist() {
        val root = JSONObject()
        categories.forEach { (pkg, category) -> root.put(pkg, category.key) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_RULES, root.toString())
            putInt(KEY_QUOTA, dailyQuota)
            putString(KEY_SLOTS, JSONArray(slots.map(::formatSlot)).toString())
            putBoolean(KEY_QUIZ_ENABLED, quizEnabled)
        }
    }

    private fun persistDay() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_EARNED, earned)
            putString(KEY_DAY, day)
            putStringSet(KEY_ASKED, askedToday)
        }
    }

    /**
     * The passes that outlive the process: the 30-minute and today ones. A [Grant.Once] pass is
     * held in memory only, so a restart asks for the password again rather than guessing whether
     * the child left the app.
     */
    private fun persistGrants() {
        val root = JSONObject()
        grants.forEach { (pkg, until) -> if (until != ONCE) root.put(pkg, until) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_GRANTS, root.toString()) }
    }

    private fun parseGrants(raw: String?): Map<String, Long> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val now = System.currentTimeMillis()
        return root.keys().asSequence()
            .mapNotNull { pkg -> root.optLong(pkg, 0L).takeIf { it > now }?.let { pkg to it } }
            .toMap()
    }

    /** Enough asked ids for many rounds; the set is dropped at midnight anyway. */
    private const val ASKED_LIMIT = 1000

    /** A pass good until Aerial is in front again; every other pass holds a moment instead. */
    private const val ONCE = 0L

    private const val THIRTY_MS = 30 * 60 * 1000L

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Reads the usage events again at most this often; shorter than the guard's poll (see kidsWatch). */
    private const val USAGE_CACHE_MS = 5_000L

    /** How far back to read before midnight, to see a session that started yesterday and still runs. */
    private const val SESSION_PREROLL_MS = 6 * 60 * 60 * 1000L
}
