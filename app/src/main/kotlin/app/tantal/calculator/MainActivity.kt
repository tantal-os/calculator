package app.tantal.calculator

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import app.tantal.theme.TantalTheme
import app.tantal.theme.mathFont
import java.text.DecimalFormatSymbols
import kotlin.math.ceil
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val calculator = ViewModelProvider(this)[CalculatorState::class.java]
        setContent { TantalTheme { Calculator(calculator) } }
    }
}

private const val TAPE_LINES = 3
private val keys = listOf(
    listOf("7", "8", "9", "÷", "AC"),
    listOf("4", "5", "6", "×", "( )"),
    listOf("1", "2", "3", "−", "%"),
    listOf("0", ".", BACKSPACE, "+", CALCULATE),
)

private fun day(time: Long) =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()

@Composable
private fun Calculator(state: CalculatorState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val motion = MaterialTheme.motionScheme
    var inverse by rememberSaveable { mutableStateOf(false) }
    var scientific by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val reveal = remember(scope, density, motion) {
        HistoryReveal(historyOpen, scope, motion.defaultEffectsSpec(), with(density) { 400.dp.toPx() }) {
            historyOpen = it
        }
    }
    val historyVisible = historyOpen || reveal.fraction > 0f
    val storageError = state.storageError
    LaunchedEffect(storageError) {
        if (storageError != null) Toast.makeText(context, storageError.message, Toast.LENGTH_SHORT).show()
    }

    BackHandler(historyVisible) { reveal.moveTo(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val preferredKeyHeight = (maxWidth - 56.dp) / 4f
            val scientificHeight = if (scientific) 228.dp else 60.dp
            val availableKeyHeight = (maxHeight - scientificHeight - 208.dp) / 4f
            val keyHeight by animateDpAsState(
                minOf(preferredKeyHeight, availableKeyHeight).coerceAtLeast(48.dp),
                motion.slowEffectsSpec(),
            )
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { reveal.height = it.height }.clipToBounds()) {
                    Column(
                        Modifier.fillMaxSize().then(
                            if (reveal.fraction == 1f) Modifier.semantics { hideFromAccessibility() } else Modifier,
                        ),
                    ) {
                        IconButton({ reveal.moveTo(true) }, Modifier.padding(start = 12.dp, top = 4.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_history),
                                stringResource(R.string.history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Display(
                            state,
                            small = scientific,
                            modifier = Modifier.draggable(
                                rememberDraggableState { reveal.drag(it) },
                                Orientation.Vertical,
                                onDragStopped = { reveal.settle(it) },
                            ),
                        )
                        ScientificKeys(
                            open = scientific,
                            inverse = inverse,
                            radians = state.radians,
                            onOpen = { scientific = !scientific },
                            onInverse = { inverse = !inverse },
                            onRadians = state::toggleRadians,
                            onKey = state::press,
                        )
                    }
                    if (historyVisible) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationY = (reveal.fraction - 1f) * size.height }
                                .background(MaterialTheme.colorScheme.surface)
                                .draggable(
                                    rememberDraggableState { reveal.drag(it) },
                                    Orientation.Vertical,
                                    onDragStopped = { reveal.settle(it) },
                                )
                                .nestedScroll(reveal),
                        ) {
                            HistoryPane(state, onClose = { reveal.moveTo(false) }) {
                                state.recall(it)
                                reveal.moveTo(false)
                            }
                        }
                    }
                }
                Keypad(keyHeight, state::press)
            }
        }
    }
}

@Composable
private fun ColumnScope.Display(
    state: CalculatorState,
    small: Boolean,
    modifier: Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val motion = MaterialTheme.motionScheme
    val maxSize = animateFloatAsState(if (small) 40f else 60f, motion.slowEffectsSpec())
    val resultSize = animateFloatAsState(if (small) 28f else 36f, motion.slowEffectsSpec())
    val density = LocalDensity.current
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .then(modifier)
            .padding(start = 24.dp, end = 24.dp, bottom = if (small) 10.dp else 20.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Box(Modifier.weight(1f).padding(bottom = 12.dp), contentAlignment = Alignment.BottomEnd) {
            RecentExpressions(state)
        }
        Box(
            Modifier.fillMaxWidth().height(with(density) { (maxSize.value.sp * 1.15).toDp() }),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val answer = state.committed
            Text(
                display(answer?.text ?: state.expression),
                Modifier.fillMaxWidth().then(solutionSemantics(answer)),
                fontFamily = mathFont,
                autoSize = TextAutoSize.StepBased(24.sp, maxSize.value.sp, 0.25.sp),
                lineHeight = 1.15.em,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(with(density) { (resultSize.value.sp * 1.25).toDp() }),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (state.committed == null) {
                AnimatedContent(
                    state.preview,
                    transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()) using null },
                    contentKey = { it == null },
                ) { result ->
                    val math = result as? Calculation.Answer
                    val text = when (result) {
                        is Calculation.Answer -> display(result.text)
                        is Calculation.Error -> stringResource(result.failure.message)
                        null -> ""
                    }
                    Text(
                        text,
                        solutionSemantics(math),
                        color = dim,
                        fontFamily = if (math != null) mathFont else null,
                        autoSize = TextAutoSize.StepBased(16.sp, resultSize.value.sp, 0.25.sp),
                        textAlign = TextAlign.End,
                        lineHeight = 1.25.em,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentExpressions(state: CalculatorState) {
    val target = state.committedEntryCount.toFloat()
    val position = remember { Animatable(target) }
    LaunchedEffect(target) {
        position.animateTo(target, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }
    val extraRows by remember(target) {
        derivedStateOf { ceil((target - position.value).coerceAtLeast(0f)).toInt() }
    }
    val density = LocalDensity.current
    val rowHeight = with(density) { 32.sp.toDp() }
    val rowPixels = with(density) { rowHeight.roundToPx() }.coerceAtLeast(1)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .layout { measurable, constraints ->
                val visibleRows = (constraints.maxHeight / rowPixels).coerceAtMost(TAPE_LINES)
                val viewport = measurable.measure(constraints.copy(maxHeight = rowPixels * visibleRows))
                layout(viewport.width, viewport.height) { viewport.placeRelative(0, 0) }
            }
            .graphicsLayer {
                clip = true
                if (position.value != target) compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val height = size.height.coerceAtLeast(1f)
                val edge = (8.dp.toPx() / height).coerceAtMost(0.5f)
                val mask = Brush.verticalGradient(
                    0f to Color.Transparent,
                    edge to Color.White,
                    (1f - edge) to Color.White,
                    1f to Color.Transparent,
                    endY = height,
                )
                onDrawWithContent {
                    drawContent()
                    if (position.value != target) drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
            .wrapContentHeight(Alignment.Bottom, unbounded = true)
            .graphicsLayer { translationY = (target - position.value) * rowPixels },
        horizontalAlignment = Alignment.End,
    ) {
        for (entry in state.recentEntries(TAPE_LINES + extraRows)) {
            key(entry.time, entry.expression) {
                val expression = display(entry.expression)
                val answer = display(entry.answer.text)
                val line = remember(expression, answer, entry.answer.solution, dim) {
                    buildAnnotatedString {
                        val separator = if (entry.answer.solution == null) " = " else " → "
                        withStyle(SpanStyle(color = dim)) { append(expression + separator) }
                        append(answer)
                    }
                }
                Text(
                    line,
                    Modifier.height(rowHeight).then(solutionSemantics(entry.answer, "$expression. ")),
                    fontFamily = mathFont,
                    fontSize = 18.sp,
                    lineHeight = 32.sp,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
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
            Insert("√")
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
                    SmallKey(if (radians) "RAD" else "DEG", open, on = radians, math = false, onClick = onRadians)
                    for (name in listOf("sin", "cos", "tan")) {
                        if (inverse) Insert("$name⁻¹", "$name⁻¹(") else Insert(name, "$name(")
                    }
                    SmallKey("INV", open, on = inverse, math = false, onClick = onInverse)
                }
                Row(horizontalArrangement = gap) {
                    Insert("e")
                    if (inverse) Insert("eˣ", "e^(") else Insert("ln", "ln(")
                    if (inverse) Insert("10ˣ", "10^(") else Insert("log", "log(")
                    Insert("1/x", "^(−1)")
                    Insert("|x|", "abs(")
                }
                Row(horizontalArrangement = gap) {
                    Insert("x")
                    Insert("=")
                    Insert("x²", "^2")
                    Box(Modifier.weight(2f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.SmallKey(
    label: String,
    filled: Boolean,
    on: Boolean = false,
    math: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fade = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val container = animateColorAsState(
        if (on) colors.secondaryContainer else colors.surfaceContainer.copy(alpha = if (filled) 1f else 0f),
        fade,
    )
    val color by animateColorAsState(if (on) colors.onSecondaryContainer else colors.onSurfaceVariant, fade)
    Key(Modifier.weight(1f).height(48.dp), { container.value }, color, onClick) {
        Text(
            label,
            fontFamily = if (math) mathFont else null,
            fontSize = if (filled) 20.sp else 22.sp,
            fontWeight = if (on) FontWeight.SemiBold else null,
        )
    }
}

@Composable
private fun Keypad(height: Dp, onKey: (String) -> Unit) {
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
                        CALCULATE -> colors.primary to colors.onPrimary
                        "÷", "×", "−", "+", "( )", "%" -> colors.secondaryContainer to colors.onSecondaryContainer
                        else -> colors.surfaceContainerHigh to colors.onSurface
                    }
                    Key(Modifier.weight(1f).height(height), { container }, color, { onKey(key) }) {
                        when (key) {
                            BACKSPACE -> Icon(
                                painterResource(R.drawable.ic_backspace),
                                stringResource(R.string.delete),
                                Modifier.size(28.dp),
                            )
                            CALCULATE -> Icon(
                                painterResource(R.drawable.ic_keyboard_return),
                                stringResource(R.string.calculate),
                                Modifier.size(30.dp),
                            )
                            else -> Text(
                                if (key == ".") decimal else key,
                                fontFamily = if (key == "AC") null else mathFont,
                                fontSize = if (height < 64.dp) 28.sp else 34.sp,
                            )
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
private fun ColumnScope.HistoryPane(state: CalculatorState, onClose: () -> Unit, onPick: (Entry) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }
    val back = stringResource(R.string.back)
    val groups = remember(state.entries.toList()) { state.entries.asReversed().groupBy { day(it.time) } }

    Row(Modifier.height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(painterResource(R.drawable.ic_arrow_back), back) }
        Text(
            stringResource(R.string.history),
            Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        TextButton({ confirmClear = true }, enabled = state.entries.isNotEmpty()) {
            Text(stringResource(R.string.clear))
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        for ((day, entries) in groups) {
            item {
                Text(
                    day,
                    Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(entries.size) { index ->
                val entry = entries[index]
                val top = if (index == 0) 28.dp else 0.dp
                val bottom = if (index == entries.lastIndex) 28.dp else 0.dp
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(top, top, bottom, bottom))
                        .background(colors.surfaceContainerLow),
                ) {
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(display(entry.expression), color = colors.onSurfaceVariant, fontFamily = mathFont)
                        Text(
                            display(entry.answer.text),
                            solutionSemantics(entry.answer),
                            fontFamily = mathFont,
                            fontSize = 28.sp,
                            lineHeight = 36.sp,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton({
                    state.clearHistory()
                    confirmClear = false
                    onClose()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
            title = { Text(stringResource(R.string.clear_history)) },
        )
    }
}

private val Failure.message: Int
    get() = when (this) {
        Failure.Incomplete -> R.string.incomplete_expression
        Failure.Unsupported -> R.string.unsupported_expression
        Failure.Limit -> R.string.calculation_limit
        Failure.Unavailable -> R.string.calculation_unavailable
        Failure.Domain -> R.string.undefined_result
        Failure.Invalid, Failure.Cancelled -> R.string.error
    }

@Composable
private fun solutionSemantics(answer: Calculation.Answer?, prefix: String = ""): Modifier {
    if (answer?.solution == null) return Modifier
    val text = answer.text
    val spoken = when (answer.solution) {
        SolutionKind.All -> stringResource(R.string.solution_all)
        SolutionKind.Empty -> stringResource(R.string.solution_empty)
        SolutionKind.One -> stringResource(R.string.solution_one, display(text.substringAfter(" = ")))
        SolutionKind.Two -> stringResource(
            R.string.solution_two,
            display(text.substringAfter(" = ").substringBefore(',')),
            display(text.substringAfterLast(" = ")),
        )
        SolutionKind.Radical -> {
            val root = text.substringAfter('√')
            val factor = text.substringAfter('±').substringBefore('√')
            val term = if (factor.isEmpty()) {
                stringResource(R.string.solution_root, display(root))
            } else {
                stringResource(R.string.solution_scaled_root, display(factor), display(root))
            }
            val center = text.substringAfter(" = ").substringBefore('±').trim()
            if (center.isEmpty()) stringResource(R.string.solution_plus_minus, term) else {
                stringResource(R.string.solution_centered, display(center), term)
            }
        }
    }
    return Modifier.clearAndSetSemantics { contentDescription = prefix + spoken }
}
