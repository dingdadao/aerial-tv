package app.orchard.tv.ui.kids

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.kids.KidsMode
import app.orchard.tv.kids.Question
import app.orchard.tv.kids.QuizBankLoader
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.InterDisplay
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The parental side of the child mode: the global daily time, the allowed hours, the quiz switch,
 * the parent password, and each app's category (free, blocked, timed). Opened behind the parent
 * password, from Personalization or the time-up screen's parent menu.
 */
@Composable
fun KidsPanel(visible: Boolean, apps: List<AppEntry>, onClose: () -> Unit) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var categories by remember { mutableStateOf(KidsMode.categories) }
    LaunchedEffect(visible) {
        while (visible) {
            usage = withContext(Dispatchers.IO) { KidsMode.usageToday(context) }
            categories = KidsMode.categories
            delay(5_000)
        }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            delay(180)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler(enabled = visible, onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.86f)))
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().consumeTaps().padding(start = 44.dp, end = 44.dp, top = 20.dp, bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(stringResource(R.string.kids_manage_title), style = Type.LargeTitle)
                        BasicText(
                            stringResource(R.string.kids_manage_subtitle),
                            style = Type.Caption.copy(color = Palette.SecondaryLabel),
                        )
                    }
                    KidChip(stringResource(R.string.kids_on), on = KidsMode.enabled, focusRequester = firstFocus) {
                        KidsMode.setOn(true)
                    }
                    Spacer(Modifier.width(8.dp))
                    KidChip(stringResource(R.string.kids_off), on = !KidsMode.enabled) { KidsMode.setOn(false) }
                }
                Spacer(Modifier.height(10.dp))
                if (KidsMode.enabled && !KidsMode.canCountTime(context)) {
                    BasicText(
                        stringResource(R.string.kids_no_usage),
                        style = Type.Callout.copy(color = Palette.Orange),
                    )
                }
                BasicText(
                    stringResource(R.string.kids_phone_hint),
                    style = Type.Caption.copy(color = Palette.TertiaryLabel),
                )
                Spacer(Modifier.height(8.dp))
                // One global daily time, one set of hours, one quiz switch for every Timed app.
                SettingRow(stringResource(R.string.kids_quota_label)) {
                    KidsMode.QUOTA_CHOICES.forEach { minutes ->
                        KidChip(
                            if (minutes == 0) stringResource(R.string.kids_unlimited)
                            else stringResource(R.string.kids_minutes, minutes),
                            on = KidsMode.dailyQuota == minutes,
                        ) { KidsMode.setQuota(minutes) }
                    }
                }
                SettingRow(stringResource(R.string.kids_slots_label)) {
                    KidsMode.SLOT_PRESETS.forEach { preset ->
                        KidChip(
                            if (preset.isEmpty()) stringResource(R.string.kids_all_day) else hoursText(preset),
                            on = KidsMode.slots == preset,
                        ) { KidsMode.setSlotList(preset) }
                    }
                }
                SettingRow(stringResource(R.string.kids_quiz_toggle)) {
                    KidChip(stringResource(R.string.kids_on), on = KidsMode.quizEnabled) { KidsMode.setQuizOn(true) }
                    Spacer(Modifier.width(8.dp))
                    KidChip(stringResource(R.string.kids_off), on = !KidsMode.quizEnabled) { KidsMode.setQuizOn(false) }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(apps, key = { it.key }) { app ->
                        KidsAppRow(app, categories[app.key] ?: KidsMode.Category.Free, usage[app.key] ?: 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = Type.Body.copy(color = Palette.SecondaryLabel), maxLines = 1, modifier = Modifier.width(150.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun KidsAppRow(app: AppEntry, category: KidsMode.Category, usedMinutes: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(app.label, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val note = when (category) {
                    KidsMode.Category.Timed -> stringResource(R.string.kids_used_today, usedMinutes)
                    KidsMode.Category.Blocked -> stringResource(R.string.kids_blocked_note)
                    KidsMode.Category.Free -> ""
                }
                BasicText(note, style = Type.Caption.copy(color = Palette.TertiaryLabel))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KidChip(stringResource(R.string.kids_free), on = category == KidsMode.Category.Free) {
                    KidsMode.setCategory(app.key, KidsMode.Category.Free)
                }
                KidChip(stringResource(R.string.kids_blocked), on = category == KidsMode.Category.Blocked) {
                    KidsMode.setCategory(app.key, KidsMode.Category.Blocked)
                }
                KidChip(stringResource(R.string.kids_timed), on = category == KidsMode.Category.Timed) {
                    KidsMode.setCategory(app.key, KidsMode.Category.Timed)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
    }
}

/** "08:00-20:00", or "08:00-12:00, 14:00-20:00". */
private fun hoursText(slots: List<KidsMode.Slot>): String = slots.joinToString(", ") { KidsMode.formatSlot(it) }

/** A round of questions; each right answer earns minutes for the whole child mode's budget. */
@Composable
fun KidsQuiz(visible: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var chosen by remember { mutableStateOf<Int?>(null) }
    var correct by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var awarded by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            // The parent presets the question set on the phone page, so a round starts at once,
            // and today's earlier rounds are left out until midnight.
            questions = QuizBankLoader.round(
                context,
                KidsMode.quizTopics,
                KidsMode.QUESTIONS_PER_ROUND,
                KidsMode.askedToday,
            )
            KidsMode.markAsked(questions.map { it.id })
            index = 0; chosen = null; correct = 0; done = false; awarded = 0
            delay(180)
            runCatching { firstFocus.requestFocus() }
        }
    }
    LaunchedEffect(done) {
        if (done) {
            awarded = (correct * KidsMode.MINUTES_PER_CORRECT).coerceAtMost(KidsMode.MAX_REWARD_PER_ROUND)
            KidsMode.addEarned(awarded)
        }
    }
    BackHandler(enabled = visible, onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.88f)))
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().consumeTaps().padding(horizontal = 56.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    done -> {
                        val ok = correct > 0
                        Spacer(Modifier.weight(0.55f))
                        BasicText(
                            if (ok) "✓" else "✗",
                            style = DoneMarkText.copy(color = if (ok) Palette.Green else Palette.SecondaryLabel),
                        )
                        if (ok) {
                            Spacer(Modifier.height(6.dp))
                            BasicText("+$awarded", style = DoneMinutesText)
                        }
                        Spacer(Modifier.height(12.dp))
                        BasicText(
                            if (ok) stringResource(R.string.kids_done, correct, awarded) else stringResource(R.string.kids_done_none),
                            style = DoneTitleText.copy(textAlign = TextAlign.Center),
                        )
                        Spacer(Modifier.height(24.dp))
                        KidChip(
                            stringResource(R.string.kids_close),
                            focusRequester = firstFocus,
                            modifier = Modifier.trapColumn(true, true),
                            onClick = onClose,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    else -> {
                        val q = questions.getOrNull(index) ?: return@Column
                        // Progress dots, instead of a long sentence.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                repeat(questions.size) { i ->
                                    Box(
                                        Modifier
                                            .size(if (i == index) 10.dp else 8.dp)
                                            .background(
                                                if (i <= index) Palette.Label else Color.White.copy(alpha = 0.22f),
                                                CircleShape,
                                            ),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            BasicText(
                                "${index + 1}/${questions.size}",
                                style = Type.Caption.copy(color = Palette.SecondaryLabel),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        BasicText(
                            q.text,
                            style = QuizQuestionText.copy(textAlign = TextAlign.Center),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(18.dp))
                        // Four answers go two by two; three stay in one column so no row looks half empty.
                        val singleColumn = q.options.size == 3
                        val rows = if (singleColumn) q.options.map { listOf(it) } else q.options.chunked(2)
                        rows.forEachIndexed { rowIndex, rowOptions ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                rowOptions.forEachIndexed { column, option ->
                                    val i = if (singleColumn) rowIndex else rowIndex * 2 + column
                                    val state = when {
                                        chosen == null -> OptionState.Normal
                                        i == q.answer -> OptionState.Correct
                                        i == chosen -> OptionState.Wrong
                                        else -> OptionState.Normal
                                    }
                                    QuizOption(
                                        option, state,
                                        focusRequester = if (i == 0) firstFocus else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .trapGrid(rowIndex == 0, rowIndex == rows.lastIndex, column, rowOptions.lastIndex),
                                    ) {
                                        if (chosen != null) return@QuizOption
                                        chosen = i
                                        if (i == q.answer) correct++
                                        scope.launch {
                                            delay(900)
                                            chosen = null
                                            if (index + 1 >= questions.size) done = true else index++
                                        }
                                    }
                                }
                                if (!singleColumn && rowOptions.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        BasicText(
                            stringResource(R.string.kids_quiz_reward_hint),
                            style = Type.Caption.copy(color = Palette.TertiaryLabel, textAlign = TextAlign.Center),
                        )
                    }
                }
            }
        }
    }
}

private enum class OptionState { Normal, Correct, Wrong }

/**
 * Keeps focus inside a full-screen child overlay: without this a direction key at an edge moves
 * focus to Home behind the overlay, where a child could open Control Center or an app.
 */
private fun Modifier.trapRows(first: Boolean, last: Boolean): Modifier = focusProperties {
    up = FocusRequester.Cancel
    down = FocusRequester.Cancel
    if (first) left = FocusRequester.Cancel
    if (last) right = FocusRequester.Cancel
}

/** The same for a vertical stack of controls. */
private fun Modifier.trapColumn(first: Boolean, last: Boolean): Modifier = focusProperties {
    left = FocusRequester.Cancel
    right = FocusRequester.Cancel
    if (first) up = FocusRequester.Cancel
    if (last) down = FocusRequester.Cancel
}

/** The same for a two-column grid of answers: only the outermost edges cancel. */
private fun Modifier.trapGrid(firstRow: Boolean, lastRow: Boolean, column: Int, lastColumn: Int): Modifier =
    focusProperties {
        if (firstRow) up = FocusRequester.Cancel
        if (lastRow) down = FocusRequester.Cancel
        if (column == 0) left = FocusRequester.Cancel
        if (column == lastColumn) right = FocusRequester.Cancel
    }

/** A question, read from across the room. */
private val QuizQuestionText = TextStyle(
    fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
    fontSize = 32.sp, lineHeight = 40.sp, color = Palette.Label,
)

/** An answer card, big enough that a child can read it without leaning in. */
private val QuizAnswerText = TextStyle(
    fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp, lineHeight = 28.sp, color = Palette.Label,
)

/** The tick or cross that closes a round. */
private val DoneMarkText = TextStyle(
    fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
    fontSize = 56.sp, lineHeight = 60.sp, color = Palette.Label,
)

/** The minutes a round paid, the first thing the child sees when it ends. */
private val DoneMinutesText = TextStyle(
    fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
    fontSize = 48.sp, lineHeight = 52.sp, color = Palette.Green,
)

/** What the round paid, in the same words the parent panel uses. */
private val DoneTitleText = TextStyle(
    fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
    fontSize = 30.sp, lineHeight = 40.sp, color = Palette.Label,
)

@Composable
private fun QuizOption(
    text: String,
    state: OptionState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick, modifier = modifier.fillMaxWidth().height(64.dp), focusRequester = focusRequester) { st ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Right and wrong outrank focus: the answer must stay visible during the pause.
                    when {
                        state == OptionState.Correct -> Palette.Green.copy(alpha = 0.42f)
                        state == OptionState.Wrong -> Palette.Red.copy(alpha = 0.42f)
                        st.focused -> Palette.Focus
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    text,
                    style = QuizAnswerText.copy(
                        color = if (st.focused && state == OptionState.Normal) Palette.OnFocus else Palette.Label,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 2,
                )
                if (state != OptionState.Normal) {
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        if (state == OptionState.Correct) "✓" else "✗",
                        style = QuizAnswerText.copy(color = Palette.Label),
                    )
                }
            }
        }
    }
}

/**
 * Says why the child was taken out of an app: it is set to Blocked, it is outside its hours, or
 * the child mode's time is up. The password always opens the parent menu; the quiz earns more
 * minutes only when the time is up and the parent left it on.
 */
@Composable
fun KidsTimeUp(
    visible: Boolean,
    appLabel: String?,
    block: KidsMode.Block,
    onQuiz: () -> Unit,
    onPin: () -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            delay(180)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler(enabled = visible, onBack = onClose)
    val (title, body, withQuiz) = when (block) {
        KidsMode.Block.Hard -> Triple(
            R.string.kids_hard_title, R.string.kids_hard_body, false,
        )
        KidsMode.Block.OutsideSlot -> Triple(
            R.string.kids_slot_up_title, R.string.kids_slot_up_pin_body, false,
        )
        KidsMode.Block.TimeUp -> Triple(
            R.string.kids_time_up_title,
            if (KidsMode.quizEnabled) R.string.kids_time_up_quiz_body else R.string.kids_tomorrow_body,
            KidsMode.quizEnabled,
        )
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.86f)))
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().consumeTaps().padding(horizontal = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(stringResource(title), style = Type.LargeTitle)
                Spacer(Modifier.height(10.dp))
                BasicText(
                    if (block == KidsMode.Block.OutsideSlot || block == KidsMode.Block.Hard)
                        stringResource(body, appLabel ?: "")
                    else stringResource(body),
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                )
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (withQuiz) {
                        KidChip(
                            stringResource(R.string.kids_earn_now),
                            focusRequester = firstFocus,
                            modifier = Modifier.trapRows(true, false),
                            onClick = onQuiz,
                        )
                    }
                    KidChip(
                        stringResource(R.string.kids_pin_now),
                        focusRequester = if (withQuiz) null else firstFocus,
                        // Middle of three when the quiz shows; the first of two when it does not.
                        modifier = Modifier.trapRows(!withQuiz, false),
                        onClick = onPin,
                    )
                    KidChip(
                        stringResource(R.string.kids_back_home),
                        modifier = Modifier.trapRows(false, true),
                    ) { onClose() }
                }
            }
        }
    }
}

/** What a parent can do after the password on the time-up screen. */
@Composable
fun ParentOptions(
    visible: Boolean,
    onSettings: () -> Unit,
    onExempt: () -> Unit,
    onExtend: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            delay(180)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler(enabled = visible, onBack = onClose)
    val rows = buildList {
        add(Triple(R.string.kids_parent_settings, 0, { onSettings() }))
        add(Triple(R.string.kids_parent_exempt, 0, { onExempt() }))
        KidsMode.EXTEND_CHOICES.forEach { minutes ->
            add(Triple(R.string.kids_parent_extend, minutes, { onExtend(minutes) }))
        }
        add(Triple(R.string.kids_close, 0, { onClose() }))
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.86f)))
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().consumeTaps().padding(horizontal = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(stringResource(R.string.kids_parent_options), style = Type.LargeTitle)
                Spacer(Modifier.height(26.dp))
                rows.forEachIndexed { i, (label, minutes, onClick) ->
                    KidChip(
                        if (minutes > 0) stringResource(label, minutes) else stringResource(label),
                        focusRequester = if (i == 0) firstFocus else null,
                        modifier = Modifier.trapColumn(i == 0, i == rows.lastIndex),
                    ) { onClick() }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/**
 * A line by the status pill when the child mode is on: how much time is left today, how much has
 * been used, and the total, so a child and a parent see the budget at a glance. Without the usage
 * grant only the total is shown, since the rest cannot be counted.
 */
@Composable
fun KidsStatusPill(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var used by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) {
        while (enabled) {
            used = withContext(Dispatchers.IO) {
                if (KidsMode.canCountTime(context)) KidsMode.usedToday(context) else null
            }
            delay(15_000)
        }
    }
    if (!enabled) return
    val usedNow = used
    val quota = KidsMode.dailyQuota
    val label = when {
        quota <= 0 -> context.getString(R.string.kids_status_unlimited, usedNow ?: 0)
        usedNow == null -> context.getString(R.string.kids_status_quota_only, KidsMode.managedToday)
        else -> {
            val left = (KidsMode.managedToday - usedNow).coerceAtLeast(0)
            context.getString(R.string.kids_status, left, usedNow, KidsMode.managedToday)
        }
    }
    val up = usedNow != null && quota > 0 && KidsMode.managedToday - usedNow <= 0
    Box(
        Modifier
            .then(modifier)
            .liquidGlass(20.dp)
            .height(36.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Type.Body.copy(color = if (up) Palette.Orange else Palette.Label))
    }
}

/** A small focusable pill shared by the screens above. */
@Composable
private fun KidChip(
    label: String,
    on: Boolean = false,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick, modifier = modifier.height(36.dp), focusRequester = focusRequester) { st ->
        // Height fills the chip; the width stays with the label, so a Row keeps the next chip in view.
        Box(
            Modifier
                .fillMaxHeight()
                .background(
                    when {
                        st.focused -> Palette.Focus
                        on -> Color.White.copy(alpha = 0.20f)
                        else -> Color.White.copy(alpha = 0.07f)
                    },
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = Type.Body.copy(color = if (st.focused) Palette.OnFocus else Palette.Label),
                maxLines = 1,
            )
        }
    }
}
