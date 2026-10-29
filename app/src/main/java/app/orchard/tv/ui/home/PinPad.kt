package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

private const val PIN_LENGTH = 6

/**
 * The global parent password (6 digits, [AerialSettings.DEFAULT_PASSWORD] until a parent changes
 * it): from the remote's number keys or the keypad on screen (most remotes here have no number
 * keys). It only checks the password, and never sets one: setting it is the phone page's job
 * alone (see PhoneServer), because a pad on the TV that set it is a pad a child could set to
 * something they know.
 */
@Composable
fun PinPad(visible: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    BackHandler(enabled = visible, onBack = onCancel)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Each opening starts empty: the panel leaves composition while hidden.
        PinPanel(onDone, onCancel)
    }
}

@Composable
private fun PinPanel(onDone: () -> Unit, onCancel: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val one = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { one.requestFocus() }
    }

    fun complete(pin: String) {
        if (AerialSettings.unlock(pin)) onDone() else error = R.string.pin_wrong
        entered = ""
    }

    fun type(digit: Char) {
        if (entered.length >= PIN_LENGTH) return
        entered += digit
        if (entered.length == PIN_LENGTH) complete(entered)
    }

    fun erase() { entered = entered.dropLast(1) }

    Box(
        Modifier
            .fillMaxSize()
            .dismissOnTap(onCancel)
            .background(Color.Black.copy(alpha = 0.45f))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = e.nativeKeyEvent.keyCode) {
                    in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9 -> { type('0' + (code - NativeKeyEvent.KEYCODE_0)); true }
                    NativeKeyEvent.KEYCODE_DEL -> { erase(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .consumeTaps()
                .liquidGlass(28.dp)
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(stringResource(R.string.pin_enter), style = Type.Title)
            Spacer(Modifier.height(6.dp))
            BasicText(
                stringResource(error ?: R.string.pin_enter_hint),
                style = Type.Caption.copy(color = if (error != null) Palette.Red else Palette.SecondaryLabel, textAlign = TextAlign.Center),
                modifier = Modifier.widthIn(max = 340.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(if (i < entered.length) Palette.Label else Color.Transparent)
                            .border(1.5.dp, Palette.Label, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            val keys = listOf("123", "456", "789", " 0<")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { key ->
                            when (key) {
                                ' ' -> Spacer(Modifier.size(64.dp, 48.dp))
                                '<' -> PinKey(onClick = ::erase) { fg -> GlyphIcon(Glyph.ChevronLeft, fg, 18.dp) }
                                else -> PinKey(onClick = { type(key) }, focusRequester = if (key == '1') one else null) { fg ->
                                    BasicText(key.toString(), style = Type.Title.copy(color = fg))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(onClick: () -> Unit, focusRequester: FocusRequester? = null, width: Dp = 64.dp, content: @Composable (Color) -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "pin-key")
        Box(Modifier.size(width, 48.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            content(if (state.focused) Palette.OnFocus else Palette.Label)
        }
    }
}
