package moe.evil.hwhh.ui.widget

import android.annotation.SuppressLint
import android.graphics.Region
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import moe.evil.hwhh.R
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.ui.theme.Dimensions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val log = HLog("LogoPuzzle")

private const val VIEWPORT = 192f
private const val SQRT2 = 1.4142135f
private const val LOBE_RADIUS = 36.9f
private const val PEN_HALF_WIDTH = 8.3f
private const val BAND_OVERSHOOT = 40f
private const val AXIS_TOLERANCE = 20f
private const val COVERAGE_SLACK = 0.2f
private const val IN_BAND_RATIO = 0.9f
private const val MIN_POINTS = 12
private const val CLEAVAGE_Y = 50f
private const val GRID = 12
private const val CELL = VIEWPORT / GRID
private const val BACKDROP_COVERAGE = 0.85f
private val BONUS_HOLD = 900.milliseconds
private val SPEEDRUN_LIMIT = 6.09.seconds
private val HOST_TOP = Color(0xFFFFA247)
private val HOST_BOTTOM = Color(0xFFFF8B23)
private val BRAND_TOP = Color(0xFF49DA8A)
private val BRAND_BOTTOM = Color(0xFF3CDA84)

private val LOBE_LEFT = Offset(60.9f, 76.3f)
private val LOBE_RIGHT = Offset(131.1f, 76.3f)
private val APEX = Offset(95.5f, 160.5f)
private val FLANK_LEFT = Offset(27.9f, 92.9f)
private val FLANK_RIGHT = Offset(164.1f, 92.9f)
private val CLEAVAGE_INSET =
    sqrt(LOBE_RADIUS * LOBE_RADIUS - (LOBE_LEFT.y - CLEAVAGE_Y) * (LOBE_LEFT.y - CLEAVAGE_Y))
private val CLEAVAGE_LEFT = Offset(LOBE_LEFT.x + CLEAVAGE_INSET, CLEAVAGE_Y)
private val CLEAVAGE_RIGHT = Offset(LOBE_RIGHT.x - CLEAVAGE_INSET, CLEAVAGE_Y)

private class Band(
    val axis: Float,
    val halfWidth: Float,
    val qStart: Float,
    val qEnd: Float,
    val cappedTip: Boolean
)

private enum class Answer(
    @param:StringRes val url: Int,
    @param:StringRes val bonus: Int,
    val band: Band? = null,
    val backdrop: List<Color>? = null
) {
    LEFT(
        R.string.easter_link_left,
        R.string.easter_bonus_left,
        band = Band(145f, 7.8f, -65f, 36f, false)
    ),
    RIGHT(
        R.string.easter_link_right,
        R.string.easter_bonus_right,
        band = Band(206.5f, 8.85f, -65f, 51.7f, true)
    ),
    BACKDROP(
        R.string.easter_link_backdrop,
        R.string.easter_bonus_backdrop,
        backdrop = listOf(HOST_TOP, HOST_BOTTOM)
    ),
    SPEEDRUN(
        R.string.easter_link_speedrun,
        R.string.easter_bonus_speedrun,
        backdrop = listOf(BRAND_TOP, BRAND_BOTTOM)
    )
}

private fun pointAt(p: Float, q: Float) = Offset((p + q) / 2f, (p - q) / 2f)

private fun List<Offset>.toPolygon() = Path().apply {
    forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x, y) else lineTo(x, y) }
    close()
}

private fun bandOf(gap: Band): Path {
    val start = pointAt(gap.axis, gap.qStart - BAND_OVERSHOOT)
    val end = pointAt(gap.axis, gap.qEnd + if (gap.cappedTip) 0f else BAND_OVERSHOOT)
    val normal = Offset(gap.halfWidth, gap.halfWidth) / SQRT2
    val body = listOf(start - normal, end - normal, end + normal, start + normal).toPolygon()
    if (!gap.cappedTip) return body
    val tip = Path().apply { addOval(Rect(end, gap.halfWidth)) }
    return Path().apply { op(body, tip, PathOperation.Union) }
}

private class HeartArt {
    private val heart = Path().apply {
        val lobes = Path().apply {
            addOval(Rect(LOBE_LEFT, LOBE_RADIUS))
            addOval(Rect(LOBE_RIGHT, LOBE_RADIUS))
        }
        val body =
            listOf(FLANK_LEFT, APEX, FLANK_RIGHT, CLEAVAGE_RIGHT, CLEAVAGE_LEFT).toPolygon()
        op(lobes, body, PathOperation.Union)
    }

    val pieces = Answer.entries
        .mapNotNull { answer ->
            answer.band?.let {
                answer to Path().apply {
                    op(
                        heart,
                        bandOf(it),
                        PathOperation.Intersect
                    )
                }
            }
        }
        .toMap()

    private val slashes = pieces.values
        .reduce { a, b -> Path().apply { op(a, b, PathOperation.Union) } }

    val carved = Path().apply { op(heart, slashes, PathOperation.Difference) }

    private fun cellsWithin(shape: Path) = BooleanArray(GRID * GRID).also { cells ->
        val bounds = Region(0, 0, VIEWPORT.toInt(), VIEWPORT.toInt())
        val region = Region().apply { setPath(shape.asAndroidPath(), bounds) }
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val x = ((column + 0.5f) * CELL).toInt()
                val y = ((row + 0.5f) * CELL).toInt()
                cells[row * GRID + column] = region.contains(x, y)
            }
        }
    }

    val solid = cellsWithin(heart)
    val forbidden = cellsWithin(slashes)
    val outsideCells = solid.count { !it }
}

private fun tracedAlong(points: List<Offset>, scale: Float, gap: Band): Boolean {
    var inBand = 0
    var minQ = Float.MAX_VALUE
    var maxQ = -Float.MAX_VALUE
    points.forEach {
        val x = it.x / scale
        val y = it.y / scale
        if (abs(x + y - gap.axis) <= AXIS_TOLERANCE) {
            inBand++
            minQ = min(minQ, x - y)
            maxQ = max(maxQ, x - y)
        }
    }
    val slack = COVERAGE_SLACK * (gap.qEnd - gap.qStart)
    return inBand >= points.size * IN_BAND_RATIO &&
            minQ <= gap.qStart + slack &&
            maxQ >= gap.qEnd - slack
}

private fun backdropFilled(points: List<Offset>, scale: Float, art: HeartArt): Boolean {
    val marked = BooleanArray(GRID * GRID)
    var covered = 0
    var spoiled = false
    val step = CELL * scale / 2f

    fun mark(point: Offset) {
        val column = (point.x / scale / CELL).toInt()
        val row = (point.y / scale / CELL).toInt()
        if (column !in 0 until GRID || row !in 0 until GRID) return
        val index = row * GRID + column
        if (marked[index]) return
        marked[index] = true
        if (art.forbidden[index]) spoiled = true
        if (!art.solid[index]) covered++
    }

    for (i in 1..points.lastIndex) {
        val from = points[i - 1]
        val delta = points[i] - from
        val steps = (delta.getDistance() / step).toInt().coerceAtLeast(1)
        for (k in 0..steps) mark(from + delta * (k / steps.toFloat()))
    }
    return !spoiled && covered >= art.outsideCells * BACKDROP_COVERAGE
}

private fun classify(points: List<Offset>, scale: Float, art: HeartArt) = when {
    points.size < MIN_POINTS || scale <= 0f -> null
    else -> Answer.entries
        .firstOrNull { answer -> answer.band?.let { tracedAlong(points, scale, it) } == true }
        ?: Answer.BACKDROP.takeIf { backdropFilled(points, scale, art) }
}

private fun strokeOf(points: List<Offset>) = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    for (i in 1..points.lastIndex) {
        val prev = points[i - 1]
        val next = points[i]
        quadraticTo(prev.x, prev.y, (prev.x + next.x) / 2f, (prev.y + next.y) / 2f)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogoPuzzleDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val art = remember { HeartArt() }
    val points = remember { mutableStateListOf<Offset>() }
    var solved by remember { mutableStateOf<Answer?>(null) }
    var startedAt by remember { mutableStateOf<TimeMark?>(null) }
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(solved) {
        val answer = solved ?: return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        reveal.animateTo(1f, tween(durationMillis = 380))
        delay(BONUS_HOLD)
        @SuppressLint("LocalContextGetResourceValueCall")
        runCatching { uriHandler.openUri(context.getString(answer.url)) }
            .onFailure { log.warn(it) { "Could not open easter egg url" } }
        onDismiss()
    }

    val logoColor = MaterialTheme.colorScheme.primary
    val boardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(Dimensions.SpaceXXL)) {
                Text(
                    text = stringResource(solved?.bonus ?: R.string.easter_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Canvas(
                    modifier = Modifier
                        .padding(top = Dimensions.SpaceXL)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .pointerInput(solved) {
                            if (solved != null) return@pointerInput
                            val scale = min(size.width, size.height) / VIEWPORT
                            detectDragGestures(
                                onDragStart = {
                                    if (startedAt == null) startedAt =
                                        TimeSource.Monotonic.markNow()
                                    points.clear()
                                    points += it
                                },
                                onDrag = { change, _ ->
                                    points += change.position
                                    change.consume()
                                },
                                onDragEnd = {
                                    val answer = classify(points, scale, art)
                                    val spent = startedAt?.elapsedNow() ?: Duration.INFINITE
                                    solved = when {
                                        answer == Answer.BACKDROP && spent < SPEEDRUN_LIMIT ->
                                            Answer.SPEEDRUN

                                        else -> answer
                                    }
                                }
                            )
                        }
                ) {
                    val scale = size.minDimension / VIEWPORT
                    val repaint = solved?.backdrop
                    drawRect(color = boardColor)
                    repaint?.let {
                        drawRect(brush = Brush.verticalGradient(it), alpha = reveal.value)
                    }
                    withTransform({ scale(scale, scale, Offset.Zero) }) {
                        drawPath(art.carved, color = logoColor)
                        if (repaint != null) {
                            drawPath(art.carved, color = Color.White, alpha = reveal.value)
                        }
                        art.pieces[solved]?.let {
                            drawPath(path = it, color = logoColor, alpha = reveal.value)
                        }
                    }
                    if (solved == null && points.isNotEmpty()) {
                        drawPath(
                            path = strokeOf(points),
                            color = logoColor,
                            alpha = 0.45f,
                            style = Stroke(
                                width = PEN_HALF_WIDTH * 2f * scale,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }
    }
}
