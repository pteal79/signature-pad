package com.pteal79.plugins.signature_pad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nativephp.mobile.ui.nativerender.NativeUIBridge
import com.nativephp.mobile.ui.nativerender.NativeUINode
import kotlin.math.abs
import kotlin.math.min

/**
 * Freehand signature pad (`<native:signature-pad>`).
 *
 * Ink is exchanged with PHP as an SVG path `d` string in a fixed 360x120
 * coordinate space: absolute `M x y` per stroke followed by `L x y` points,
 * one decimal place, space separated. Touch points are mapped from the view's
 * real size into that space with uniform scale + centered letterboxing, so the
 * saved path is correct regardless of the view's aspect ratio.
 *
 * Props (see PTeal79\SignaturePad\Elements\SignaturePad):
 *   value          string  existing ink, same path format
 *   on_change      int     callback id; fires on stroke end with the FULL path
 *                          ("" = cleared). Rides the TEXT_CHANGE event.
 *   clear_token    string  changes => wipe pad + fire on_change("")
 *   read_only      bool    show ink, ignore touches
 *   stroke_width   float   dp
 *   pen_color      string  default black
 *   dark_pen_color string  default: pen_color if set, else white
 */
object SignaturePadRenderer {
    @Composable
    fun Render(node: NativeUINode, modifier: Modifier) {
        val p = node.props
        val value = p.getString("value")
        val clearToken = p.getString("clear_token")
        val readOnly = p.getBool("read_only")
        val strokeWidthDp = p.getFloat("stroke_width", 2.5f).let { if (it > 0f) it else 2.5f }
        val onChangeCb = p.getCallbackId("on_change")
        val nodeId = node.id

        val dark = isSystemInDarkTheme()
        val penArgb = when {
            dark && p.has("dark_pen_color") -> p.getColor("dark_pen_color", 0xFFFFFFFF.toInt())
            p.has("pen_color") -> p.getColor("pen_color", 0xFF000000.toInt())
            dark -> 0xFFFFFFFF.toInt()
            else -> 0xFF000000.toInt()
        }
        val penColor = Color(penArgb)

        val state = remember(nodeId) { PadState(SignaturePadPath.parse(value)) }
        // The last path string this pad produced or loaded. A `value` prop that
        // equals it is an echo of our own output and must not reload the ink.
        var lastSent by remember(nodeId) { mutableStateOf(value) }
        var lastToken by remember(nodeId) { mutableStateOf(clearToken) }

        fun emit(path: String) {
            lastSent = path
            if (onChangeCb != 0) {
                NativeUIBridge.sendTextChangeEvent(onChangeCb, nodeId, path)
            }
        }

        // Programmatic value update (anything that is not an echo of our output).
        LaunchedEffect(value) {
            if (value != lastSent) {
                state.load(SignaturePadPath.parse(value))
                lastSent = value
            }
        }

        // clear-token changed (never fires for the initial composition).
        LaunchedEffect(clearToken) {
            if (clearToken != lastToken) {
                lastToken = clearToken
                state.load(emptyList())
                emit("")
            }
        }

        Canvas(
            modifier = modifier
                .clipToBounds()
                .pointerInput(readOnly, onChangeCb) {
                    if (readOnly) return@pointerInput

                    awaitEachGesture {
                        val frame = SigFrame(size.width.toFloat(), size.height.toFloat())
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!frame.valid) return@awaitEachGesture

                        val stroke = ArrayList<SigPoint>()
                        var pending: SigPoint? = null

                        fun add(at: Offset, force: Boolean) {
                            val pt = frame.toLogical(at)
                            val last = stroke.lastOrNull()
                            if (last == null) {
                                stroke.add(pt)
                                return
                            }
                            val dx = pt.x - last.x
                            val dy = pt.y - last.y
                            val d2 = dx * dx + dy * dy
                            if (force) {
                                if (d2 > 0f) stroke.add(pt)
                                pending = null
                            } else if (d2 >= MIN_POINT_DISTANCE_SQ) {
                                stroke.add(pt)
                                pending = null
                            } else {
                                // Too close to keep, but remember it so the last
                                // sample of a stroke is never lost.
                                pending = pt
                            }
                        }

                        state.live = stroke
                        add(down.position, false)
                        down.consume()
                        state.bump()

                        val pointerId = down.id
                        var aborted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (state.live !== stroke) {
                                // Pad was cleared or reloaded mid-stroke.
                                aborted = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (change.pressed) {
                                // Android batches several samples into one event.
                                // Replay them so fast strokes keep their shape.
                                for (h in change.historical) add(h.position, false)
                                add(change.position, false)
                                change.consume()
                                state.bump()
                            } else {
                                add(change.position, true)
                                change.consume()
                                break
                            }
                        }
                        if (aborted) return@awaitEachGesture

                        pending?.let { stroke.add(it) }

                        val next = ArrayList<List<SigPoint>>(state.strokes)
                        next.add(stroke)
                        state.live = null
                        state.strokes = next
                        state.bump()
                        emit(SignaturePadPath.serialize(next))
                    }
                }
        ) {
            @Suppress("UNUSED_VARIABLE")
            val redrawOnTick = state.tick

            val frame = SigFrame(size.width, size.height)
            if (!frame.valid) return@Canvas

            val stroke = Stroke(
                width = strokeWidthDp.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            for (s in state.strokes) drawSigStroke(s, frame, penColor, stroke)
            state.live?.let { drawSigStroke(it, frame, penColor, stroke) }
        }
    }

    /** Squared minimum spacing (in 360x120 units) between kept points. */
    private const val MIN_POINT_DISTANCE_SQ = 0.25f
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

internal class SigPoint(val x: Float, val y: Float)

private class PadState(initial: List<List<SigPoint>>) {
    /** Committed strokes (compose state, read by the draw pass). */
    var strokes by mutableStateOf(initial)

    /** Stroke currently being drawn; mutated in place, `tick` signals redraw. */
    var live: ArrayList<SigPoint>? = null

    var tick by mutableIntStateOf(0)

    fun bump() {
        tick++
    }

    fun load(next: List<List<SigPoint>>) {
        live = null
        strokes = next
        tick++
    }
}

// ---------------------------------------------------------------------------
// 360x120 <-> view mapping
// ---------------------------------------------------------------------------

internal const val SIG_W = 360f
internal const val SIG_H = 120f

/** Uniform fit (letterbox) of the 360x120 space into a view of w x h. */
internal class SigFrame(w: Float, h: Float) {
    val scale: Float = min(w / SIG_W, h / SIG_H)
    val ox: Float = (w - SIG_W * scale) / 2f
    val oy: Float = (h - SIG_H * scale) / 2f
    val valid: Boolean = w > 0f && h > 0f && scale > 0f

    fun toLogical(at: Offset): SigPoint = SigPoint(
        ((at.x - ox) / scale).coerceIn(0f, SIG_W),
        ((at.y - oy) / scale).coerceIn(0f, SIG_H),
    )

    fun toView(p: SigPoint): Offset = Offset(ox + p.x * scale, oy + p.y * scale)
}

private fun DrawScope.drawSigStroke(
    pts: List<SigPoint>,
    frame: SigFrame,
    color: Color,
    stroke: Stroke,
) {
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        drawCircle(color = color, radius = stroke.width / 2f, center = frame.toView(pts[0]))
        return
    }
    val path = Path()
    val first = frame.toView(pts[0])
    path.moveTo(first.x, first.y)
    if (pts.size == 2) {
        val end = frame.toView(pts[1])
        path.lineTo(end.x, end.y)
    } else {
        // Display-only smoothing: quadratic curves through segment midpoints.
        // The exported path keeps the raw points.
        for (i in 1 until pts.size - 1) {
            val c = frame.toView(pts[i])
            val n = frame.toView(pts[i + 1])
            path.quadraticBezierTo(c.x, c.y, (c.x + n.x) / 2f, (c.y + n.y) / 2f)
        }
        val end = frame.toView(pts[pts.size - 1])
        path.lineTo(end.x, end.y)
    }
    drawPath(path = path, color = color, style = stroke)
}

// ---------------------------------------------------------------------------
// SVG path parse / serialize
// ---------------------------------------------------------------------------

internal object SignaturePadPath {

    /**
     * "M 10.5 20 L 11.2 21.4 M 30 5 L 31 6" -> strokes.
     * Understands M/L (absolute), m/l (relative), H/V/h/v, implicit repeated
     * coordinate pairs, comma or space separators, and compact forms like
     * "M10,20L11,21". Z and curve commands are skipped.
     */
    fun parse(d: String): List<List<SigPoint>> {
        val strokes = ArrayList<List<SigPoint>>()
        if (d.isBlank()) return strokes

        var cur: ArrayList<SigPoint>? = null
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        val args = FloatArray(2)
        var argc = 0

        fun need(c: Char): Int = when (c) {
            'M', 'm', 'L', 'l' -> 2
            'H', 'h', 'V', 'v' -> 1
            else -> 0
        }

        fun finishStroke() {
            val s = cur
            if (s != null && s.isNotEmpty()) strokes.add(s)
            cur = null
        }

        fun lineTarget(): ArrayList<SigPoint> = cur ?: ArrayList<SigPoint>().also { cur = it }

        fun apply() {
            when (cmd) {
                'M', 'm' -> {
                    finishStroke()
                    if (cmd == 'm') { cx += args[0]; cy += args[1] } else { cx = args[0]; cy = args[1] }
                    cur = arrayListOf(SigPoint(cx, cy))
                    cmd = if (cmd == 'M') 'L' else 'l' // further pairs are implicit lineto
                }
                'L', 'l' -> {
                    if (cmd == 'l') { cx += args[0]; cy += args[1] } else { cx = args[0]; cy = args[1] }
                    lineTarget().add(SigPoint(cx, cy))
                }
                'H', 'h' -> {
                    cx = if (cmd == 'h') cx + args[0] else args[0]
                    lineTarget().add(SigPoint(cx, cy))
                }
                'V', 'v' -> {
                    cy = if (cmd == 'v') cy + args[0] else args[0]
                    lineTarget().add(SigPoint(cx, cy))
                }
            }
        }

        val n = d.length
        var i = 0
        while (i < n) {
            val c = d[i]
            when {
                c.isLetter() && c != 'e' && c != 'E' -> {
                    i++
                    if (c == 'Z' || c == 'z') {
                        finishStroke()
                        cmd = ' '
                    } else {
                        cmd = c
                    }
                    argc = 0
                }
                c == '-' || c == '+' || c == '.' || c in '0'..'9' -> {
                    val start = i
                    if (c == '-' || c == '+') i++
                    var seenDot = false
                    var digits = 0
                    while (i < n) {
                        val ch = d[i]
                        if (ch in '0'..'9') {
                            digits++
                            i++
                        } else if (ch == '.' && !seenDot) {
                            seenDot = true
                            i++
                        } else {
                            break
                        }
                    }
                    if (digits == 0) continue // lone sign / dot; i already advanced
                    if (i < n && (d[i] == 'e' || d[i] == 'E')) {
                        var j = i + 1
                        if (j < n && (d[j] == '+' || d[j] == '-')) j++
                        val expStart = j
                        while (j < n && d[j] in '0'..'9') j++
                        if (j > expStart) i = j
                    }
                    val f = d.substring(start, i).toFloatOrNull() ?: continue
                    val k = need(cmd)
                    if (k == 0) continue
                    args[argc++] = f
                    if (argc == k) {
                        apply()
                        argc = 0
                    }
                }
                else -> i++ // whitespace, commas, anything unknown
            }
        }
        finishStroke()
        return strokes
    }

    /** strokes -> "M x y L x y ..." in 360x120 space, 1 decimal, space separated. */
    fun serialize(strokes: List<List<SigPoint>>): String {
        val sb = StringBuilder()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')

            var px = Math.round(stroke[0].x * 10f)
            var py = Math.round(stroke[0].y * 10f)
            sb.append("M ")
            tenths(sb, px)
            sb.append(' ')
            tenths(sb, py)

            var emitted = 0
            for (idx in 1 until stroke.size) {
                val rx = Math.round(stroke[idx].x * 10f)
                val ry = Math.round(stroke[idx].y * 10f)
                if (rx == px && ry == py) continue // identical after rounding
                sb.append(" L ")
                tenths(sb, rx)
                sb.append(' ')
                tenths(sb, ry)
                px = rx
                py = ry
                emitted++
            }
            if (emitted == 0) {
                // A tap: emit a 0.1 long segment so it still renders as a dot.
                sb.append(" L ")
                tenths(sb, px + 1)
                sb.append(' ')
                tenths(sb, py)
            }
        }
        return sb.toString()
    }

    /** Appends tenths (e.g. 215 -> "21.5", 200 -> "20") without locale involvement. */
    private fun tenths(sb: StringBuilder, v: Int) {
        if (v < 0) sb.append('-')
        val a = abs(v)
        sb.append(a / 10)
        val f = a % 10
        if (f != 0) sb.append('.').append(f)
    }
}
