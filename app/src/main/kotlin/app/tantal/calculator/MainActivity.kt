package app.tantal.calculator

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tantal.theme.TantalTheme
import java.io.File
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TantalTheme { Calculator() } }
    }
}

private const val BACKSPACE = "⌫"
private const val TAPE_LINES = 3
private val keys = listOf(
    listOf("7", "8", "9", "÷", "AC"),
    listOf("4", "5", "6", "×", "( )"),
    listOf("1", "2", "3", "−", "%"),
    listOf("0", ".", BACKSPACE, "+", "="),
)

private fun day(time: Long) =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()

@Composable
private fun Calculator() {
    val context = LocalContext.current
    val history = remember { History(File(context.filesDir, "history.tsv")) }
    val settings = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var expression by rememberSaveable { mutableStateOf("") }
    var evaluated by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var radians by remember { mutableStateOf(settings.getBoolean("radians", false)) }
    var inverse by rememberSaveable { mutableStateOf(false) }
    var scientific by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }

    fun edit(edited: String) {
        expression = edited
        evaluated = false
        failed = false
    }

    fun press(key: String) {
        when (key) {
            "AC" -> edit("")
            BACKSPACE -> edit(backspace(expression))
            "( )" -> edit(append(expression, paren(expression), evaluated))
            "=" -> if (expression.isNotEmpty()) {
                val result = evaluate(expression, radians)?.let(::format)
                failed = result == null
                if (result != null && result != expression) {
                    history.add(expression, result)
                    expression = result
                    evaluated = true
                }
            }

            else -> edit(append(expression, key, evaluated))
        }
    }

    val preview = when {
        failed -> stringResource(R.string.error)
        evaluated -> ""
        else -> evaluate(expression, radians)?.let(::format)?.takeIf { it != expression }?.let { display(it) }.orEmpty()
    }
    val tape = history.entries.takeLast(TAPE_LINES + 1)

    val motion = MaterialTheme.motionScheme
    BackHandler(historyOpen) { historyOpen = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.safeDrawingPadding()) {
            // The history comes down from the top like a sheet and goes back up over the display.
            AnimatedContent(
                historyOpen,
                Modifier.weight(1f),
                transitionSpec = {
                    val slide = motion.slowEffectsSpec<IntOffset>()
                    val fade = motion.defaultEffectsSpec<Float>()
                    if (targetState) {
                        slideInVertically(slide) { -it } + fadeIn(fade) togetherWith fadeOut(fade)
                    } else {
                        (fadeIn(fade) togetherWith slideOutVertically(slide) { -it } + fadeOut(fade))
                            .apply { targetContentZIndex = -1f }
                    }
                },
            ) { open ->
                Column {
                    if (open) {
                        HistoryPane(history, onClose = { historyOpen = false }) {
                            press(it)
                            historyOpen = false
                        }
                    } else {
                        IconButton({ historyOpen = true }, Modifier.padding(start = 12.dp, top = 4.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_history),
                                stringResource(R.string.history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Display(tape, display(expression), evaluated, preview, small = scientific) {
                            historyOpen = true
                        }
                        ScientificKeys(
                            open = scientific,
                            inverse = inverse,
                            radians = radians,
                            onOpen = { scientific = !scientific },
                            onInverse = { inverse = !inverse },
                            onRadians = {
                                radians = !radians
                                settings.edit().putBoolean("radians", radians).apply()
                            },
                            onKey = ::press,
                        )
                    }
                }
            }
            Keypad(::press)
        }
    }
}

@Composable
private fun ColumnScope.Display(
    tape: List<Entry>,
    expression: String,
    evaluated: Boolean,
    result: String,
    small: Boolean,
    onSwipeDown: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val motion = MaterialTheme.motionScheme
    // Read inside the text lambdas only, so a size animation recomposes one text and not the display.
    val maxSize = animateFloatAsState(if (small) 40f else 60f, motion.slowEffectsSpec())
    val resultSize = animateFloatAsState(if (small) 28f else 36f, motion.slowEffectsSpec())
    val tapeHeight = with(LocalDensity.current) { (32.sp * TAPE_LINES).toDp() }
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > 48.dp.toPx()) onSwipeDown() },
                ) { _, amount -> dragged += amount }
            }
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // The weight makes the tape give way when the expression and the keys need the room.
        Box(Modifier.weight(1f).padding(bottom = 12.dp), contentAlignment = Alignment.BottomEnd) {
            // One entry more than the visible lines sits above the clip, so the oldest line
            // slides out while the new one unfolds instead of vanishing.
            Column(
                Modifier
                    .heightIn(max = tapeHeight)
                    .clipToBounds()
                    .wrapContentHeight(Alignment.Bottom, unbounded = true),
                horizontalAlignment = Alignment.End,
            ) {
                for (entry in tape) {
                    key(entry.time) {
                        AnimatedVisibility(
                            remember { MutableTransitionState(false).apply { targetState = true } },
                            enter = expandVertically(motion.slowEffectsSpec()) + fadeIn(motion.slowEffectsSpec()),
                        ) {
                            Text(
                                remember(entry) {
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = dim)) { append("${display(entry.expression)} = ") }
                                        append(display(entry.result))
                                    }
                                },
                                fontSize = 18.sp,
                                lineHeight = 32.sp,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }
                }
            }
        }
        AnimatedContent(
            expression to evaluated,
            Modifier.fillMaxWidth(),
            transitionSpec = {
                val slide = motion.slowEffectsSpec<IntOffset>()
                val fade = motion.slowEffectsSpec<Float>()
                slideInVertically(slide) { it } + fadeIn(fade) togetherWith slideOutVertically(slide) { -it } + fadeOut(
                    fade
                )
            },
            contentKey = { it.second },
        ) { (text, _) ->
            Text(
                text,
                Modifier.fillMaxWidth(),
                autoSize = TextAutoSize.StepBased(24.sp, maxSize.value.sp, 0.25.sp),
                lineHeight = 1.15.em,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        AnimatedContent(
            result,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()) using null },
            contentKey = { it.isEmpty() },
        ) { text ->
            Text(text, color = dim, fontSize = resultSize.value.sp, lineHeight = 1.25.em, maxLines = 1)
        }
    }
}

@Composable
private fun ScientificKeys(
    open: Boolean,
    inverse: Boolean,
    radians: Boolean,
    onOpen: () -> Unit,
    onInverse: () -> Unit,
    onRadians: () -> Unit,
    onKey: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val rotation = animateFloatAsState(if (open) 180f else 0f, motion.slowEffectsSpec())

    @Composable
    fun RowScope.Insert(label: String, text: String = label) = SmallKey(label, open) { onKey(text) }

    val gap = Arrangement.spacedBy(8.dp)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(horizontalArrangement = gap) {
            if (inverse) Insert("x²", "^2") else Insert("√")
            Insert("π")
            Insert("^")
            Insert("!")
            Key(Modifier.weight(1f).height(48.dp), { colors.surfaceContainer }, colors.onSurfaceVariant, onOpen) {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    stringResource(R.string.scientific_keys),
                    Modifier.graphicsLayer { rotationZ = rotation.value },
                )
            }
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(motion.slowEffectsSpec()) + fadeIn(motion.slowEffectsSpec()),
            exit = shrinkVertically(motion.slowEffectsSpec()) + fadeOut(motion.slowEffectsSpec()),
        ) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = gap) {
                Row(horizontalArrangement = gap) {
                    SmallKey(if (radians) "RAD" else "DEG", open, on = radians, onClick = onRadians)
                    for (name in listOf("sin", "cos", "tan")) {
                        if (inverse) Insert("$name⁻¹", "$name⁻¹(") else Insert(name, "$name(")
                    }
                    SmallKey("INV", open, on = inverse, onClick = onInverse)
                }
                Row(horizontalArrangement = gap) {
                    Insert("e")
                    if (inverse) Insert("eˣ", "e^(") else Insert("ln", "ln(")
                    if (inverse) Insert("10ˣ", "10^(") else Insert("log", "log(")
                    Insert("1/x", "^(−1)")
                    Insert("|x|", "abs(")
                }
            }
        }
    }
}

@Composable
private fun RowScope.SmallKey(label: String, filled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fade = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val container = animateColorAsState(
        if (on) colors.secondaryContainer else colors.surfaceContainer.copy(alpha = if (filled) 1f else 0f),
        fade,
    )
    val color by animateColorAsState(if (on) colors.onSecondaryContainer else colors.onSurfaceVariant, fade)
    Key(Modifier.weight(1f).height(48.dp), { container.value }, color, onClick) {
        Text(label, fontSize = if (filled) 20.sp else 22.sp, fontWeight = if (on) FontWeight.SemiBold else null)
    }
}

@Composable
private fun Keypad(onKey: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val decimal = remember { DecimalFormatSymbols.getInstance().decimalSeparator.toString() }
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in keys) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in row) {
                    val (container, color) = when (key) {
                        "AC" -> colors.tertiaryContainer to colors.onTertiaryContainer
                        "=" -> colors.primary to colors.onPrimary
                        "÷", "×", "−", "+", "( )", "%" -> colors.secondaryContainer to colors.onSecondaryContainer
                        else -> colors.surfaceContainerHigh to colors.onSurface
                    }
                    // The height follows the width, so the pad grows with the screen.
                    Key(Modifier.weight(1f).aspectRatio(0.8f), { container }, color, { onKey(key) }) {
                        if (key == BACKSPACE) {
                            Icon(
                                painterResource(R.drawable.ic_backspace),
                                stringResource(R.string.delete),
                                Modifier.size(28.dp),
                            )
                        } else {
                            Text(if (key == ".") decimal else key, fontSize = 34.sp)
                        }
                    }
                }
            }
        }
    }
}

// Everything that animates here is read in the draw phase, so a key never recomposes while it moves.
@Composable
private fun Key(
    modifier: Modifier,
    container: () -> Color,
    color: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    // Corner radius as a fraction of the shorter side: a pill at rest, a rounded rectangle when pressed.
    val corner = remember { Animatable(0.5f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            when (it) {
                // Awaited, so a quick tap plays the whole press before its release is handled.
                is PressInteraction.Press -> corner.animateTo(0.3f, motion.fastEffectsSpec())
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    launch { corner.animateTo(0.5f, motion.slowEffectsSpec()) }
            }
        }
    }
    Box(
        modifier
            .graphicsLayer {
                shape = RoundedCornerShape(size.minDimension * corner.value)
                clip = true
            }
            .drawBehind { drawRect(container()) }
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color, content = content)
    }
}

@Composable
private fun ColumnScope.HistoryPane(history: History, onClose: () -> Unit, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }
    val back = stringResource(R.string.back)

    Row(Modifier.height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(painterResource(R.drawable.ic_arrow_back), back) }
        Text(
            stringResource(R.string.history),
            Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        TextButton({ confirmClear = true }, enabled = history.entries.isNotEmpty()) {
            Text(stringResource(R.string.clear))
        }
    }
    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
        for ((day, entries) in history.entries.asReversed().groupBy { day(it.time) }) {
            item {
                Text(
                    day,
                    Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            item {
                Column(Modifier.clip(RoundedCornerShape(28.dp)).background(colors.surfaceContainerLow)) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(
                            Modifier.padding(horizontal = 20.dp),
                            color = colors.outlineVariant
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.result) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text(display(entry.expression), color = colors.onSurfaceVariant)
                            Text(display(entry.result), fontSize = 28.sp, lineHeight = 36.sp)
                        }
                    }
                }
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(28.dp).clickable(onClickLabel = back, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp, 4.dp).background(colors.outline, CircleShape))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton({
                    history.clear()
                    onClose()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
            title = { Text(stringResource(R.string.clear_history)) },
        )
    }
}
