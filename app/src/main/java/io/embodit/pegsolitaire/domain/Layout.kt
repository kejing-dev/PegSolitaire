package io.embodit.pegsolitaire.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun len(): Float = sqrt(x * x + y * y + z * z)
    fun dot(o: V3): Float = x * o.x + y * o.y + z * o.z
}

/** An axis-aligned box. */
data class Box3(val min: V3, val max: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > min.x - eps && p.x < max.x + eps && p.y > min.y - eps && p.y < max.y + eps && p.z > min.z - eps && p.z < max.z + eps

    fun containsBox(b: Box3, eps: Float = 0f) = contains(b.min, eps) && contains(b.max, eps)

    /** Does the segment a-b pass through the box (slab test)? */
    fun hitBySegment(a: V3, b: V3): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ad = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val lo = floatArrayOf(min.x, min.y, min.z); val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (abs(dd[i]) < 1e-9f) { if (ad[i] < lo[i] || ad[i] > hi[i]) return false; continue }
            var ta = (lo[i] - ad[i]) / dd[i]; var tb = (hi[i] - ad[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    companion object {
        fun around(c: V3, size: V3) = Box3(c - size * 0.5f, c + size * 0.5f)
        fun of(points: List<V3>) = Box3(V3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }), V3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
    }
}

/** Does the segment a-b pass within [r] of [c]? (sphere occlusion) */
fun segmentHitsSphere(a: V3, b: V3, c: V3, r: Float): Boolean {
    val d = b - a
    val l2 = d.dot(d)
    if (l2 < 1e-12f) return (a - c).len() < r
    val t = ((c - a).dot(d) / l2).coerceIn(0f, 1f)
    val p = a + d * t
    return (p - c).len() < r
}

/**
 * Fixed sizes (design gameSpec.board / pieces / spatialLayout, theme.materials; metres). Board-local space: origin on the
 * board top centre, +X right, +Z toward the user, +Y the board normal.
 */
object Layout {
    /** Hole pitch 5.2 cm (corrections C1; template D17 >= 4.5 cm). */
    const val P = 0.052f
    const val BOARD_R = 0.21f
    const val BOARD_T = 0.03f
    /** Ivory square cup 3.4 cm, the shallow dimple in it 2.1 cm across. */
    const val CUP = 0.034f
    const val BALL_R = 0.014f
    /** The ball sits 2.5 mm down into the ivory dimple. */
    const val BALL_SINK = 0.0025f
    const val BALL_Y = BALL_R - BALL_SINK
    /** Selected ball floats 8 mm. */
    const val LIFT = 0.008f
    /** Apex of the hop: ball bottom 10 cm above the board. */
    const val APEX_BOTTOM = 0.10f
    /** The jumped ball rises 4 cm before flying to the tray. */
    const val OVER_RISE = 0.04f
    /** Full solve: the centre ball rises 5 cm (one left elsewhere: 2 cm); stuck: all sink 3 mm and spring back. */
    const val SOLVE_RISE = 0.05f
    const val ONE_RISE = 0.02f
    const val STUCK_SINK = 0.003f
    /** Hole click area (0.92 x pitch, never overlapping) and the visible marker (<= 5 mm, 2026-11-06 rule). */
    const val HOLE_HIT = 0.048f
    const val MARKER = 0.005f
    const val MARKER_T = 0.0010f
    /**
     * Lit landing targets float over their hole (template 「落点和提示标记浮在格上 1.2 cm，细光柱连到格心」) and carry their
     * own collider there, so the ball in front never covers them: 2.2 cm on the flat board (seen almost edge-on from the
     * emulator's reset eye; 2.0 cm is the lowest that clears every eye, v0.1.1 came down from 4 cm so the ring no longer
     * reads as circling the selected ball, QA K5), 1.2 cm from 30° on (LayoutTest: every target visible from every eye).
     */
    fun targetLift(tiltDeg: Int): Float = if (tiltDeg < 20) 0.022f else 0.012f

    /** Rings around a cup (selection / target / jumped / alert / landing glow): 4.44-5.04 cm. */
    const val RING_IN = 0.0222f
    const val RING_OUT = 0.0252f

    /** Volume (manifest): 0.70 x 0.60 x 0.48 m. The design's 0.40 m height grew for the 90° tilt stop (deviation D-tilt). */
    val VOLUME = V3(0.70f, 0.60f, 0.48f)
    /** Board centre x in the volume (design boardRoot x = -0.095: board on the left, tray on the right). */
    const val BOARD_X = -0.095f
    /** Highest point above the board top any content reaches (hop apex ball top + glow), for the depth fit. */
    const val CONTENT_H = 0.14f
    const val MARGIN = 0.02f

    /** Tray (dish Ø 16 cm, 1.5 cm high) on the volume floor right of the board; it never tilts (balls stay in at 90°). */
    const val TRAY_R = 0.08f
    const val TRAY_H = 0.015f
    val TRAY = V3(BOARD_X + 0.32f, 0f, 0.08f)

    /** Counter plate (AttachmentPanel "剩 n"): 100 x 40 mm design; dp -> m measured on the emulator (royal-ur / abalone 0.00069). */
    const val PLATE_W_DP = 148
    const val PLATE_H_DP = 58
    const val DP_M = 0.00069f
    val PLATE_W: Float get() = PLATE_W_DP * DP_M
    val PLATE_H: Float get() = PLATE_H_DP * DP_M
    /** Plate bottom 5 cm over the board surface at the far rim (both ways the board tilts). */
    const val PLATE_GAP = 0.05f

    /** Hole k in board-local metres (centre of the cup top). */
    fun hole(k: Int, y: Float = 0f): V3 = V3((Peg.COL[k] - 3) * P, y, (Peg.ROW[k] - 3) * P)

    fun ball(k: Int, lift: Float = 0f): V3 = hole(k, BALL_Y + lift)

    /** The 31 tray seats (local to the tray centre, base on the dish floor): 19 + 12 (+ the first of 7) in hexagonal layers. */
    val PILE: List<V3> = run {
        val r0 = TRAY_R - 0.007f - BALL_R
        val d = 2 * BALL_R + 0.0004f
        val base = 0.003f + BALL_R
        fun layer(ox: Float, oz: Float, rad: Float, y: Float): List<V3> {
            val l = ArrayList<V3>()
            for (a in -5..5) for (b in -5..5) { val x = (a + b * 0.5f) * d + ox; val z = b * d * 0.866f + oz; if (sqrt(x * x + z * z) <= rad) l += V3(x, y, z) }
            return l.sortedWith(compareBy({ sqrt(it.x * it.x + it.z * it.z) }, { it.x }, { it.z }))
        }
        (layer(0f, 0f, r0, base) + layer(d * 0.5f, d * 0.2887f, r0 - d * 0.3f, base + d * 0.8165f) + layer(0f, 0f, r0 - d * 0.75f, base + 2 * d * 0.8165f)).take(31)
    }

    /** Scene position of tray seat i (scene space: volume floor, x / z centred). */
    fun traySeat(i: Int): V3 = TRAY + PILE[i.coerceIn(0, PILE.size - 1)]

    /** A parabola from a to b at t peaking [arc] above the chord along [up]. */
    fun arc(a: V3, b: V3, t: Float, arc: Float, up: V3 = V3(0f, 1f, 0f)): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + up * (4f * arc * k * (1f - k))
    }

    /** Ease-out (decelerating) 0..1. */
    fun easeOut(t: Float): Float { val k = t.coerceIn(0f, 1f); return 1f - (1f - k) * (1f - k) * (1f - k) }

    fun easeInOut(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    /** A landing settle (D51: decelerate + one small bounce): 0..1 -> height factor, 0 at the end. */
    fun bounce(t: Float): Float { val k = t.coerceIn(0f, 1f); return if (k < 1f) sin(k * Math.PI.toFloat()) * (1f - k) * 0.6f else 0f }
}

/**
 * Where everything sits for one tilt stop. Scene space: origin on the volume floor, centred in x and z, +Z toward the user.
 *
 *   sceneRoot (volume floor)
 *   ├ tiltRoot  the board's near-bottom edge, tipped [tiltDeg] about X (far edge up)
 *   │ └ boardRoot  board top centre; holes, markers, rings: board-local
 *   ├ balls, tray, counter plate (scene space; balls fly between the tilted board and the flat tray)
 *
 * The near edge moves back as the board tips so the hop envelope (CONTENT_H along the board normal) stays inside the
 * volume's front face: z0 = VOLUME.z / 2 - MARGIN - (T + CONTENT_H) * sin θ (LayoutTest checks every stop).
 */
class Geo(val tiltDeg: Int) {
    val theta: Float = Math.toRadians(tiltDeg.toDouble()).toFloat()
    private val c = cos(theta)
    private val s = sin(theta)
    val tiltOrigin = V3(Layout.BOARD_X, 0f, Layout.VOLUME.z / 2 - Layout.MARGIN - (Layout.BOARD_T + Layout.CONTENT_H) * s)
    val boardOffset = V3(0f, Layout.BOARD_T, -Layout.BOARD_R)
    /** The board normal in scene space. */
    val up = V3(0f, c, s)

    private fun rotX(v: V3): V3 = V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    private fun rotXInv(v: V3): V3 = V3(v.x, v.y * c + v.z * s, -v.y * s + v.z * c)

    fun toScene(local: V3): V3 = tiltOrigin + rotX(boardOffset + local)
    fun toLocal(scene: V3): V3 = rotXInv(scene - tiltOrigin) - boardOffset

    fun ball(k: Int, lift: Float = 0f): V3 = toScene(Layout.ball(k, lift))
    fun hole(k: Int, y: Float = 0f): V3 = toScene(Layout.hole(k, y))

    /** The far rim's top in scene space. */
    fun farRim(): V3 = toScene(V3(0f, 0f, -Layout.BOARD_R))

    /** Counter plate centre: upright above the far rim, bottom [Layout.PLATE_GAP] over it (D35 tests: never over a hole). */
    fun plateCentre(): V3 = farRim() + V3(0f, Layout.PLATE_GAP + Layout.PLATE_H / 2, -0.01f)

    fun plateBox(): Box3 = Box3.around(plateCentre(), V3(Layout.PLATE_W, Layout.PLATE_H, 0.004f))

    fun plateCorners(): List<V3> {
        val p = plateCentre(); val w = Layout.PLATE_W / 2; val h = Layout.PLATE_H / 2
        return listOf(p + V3(-w, -h, 0f), p + V3(w, -h, 0f), p + V3(w, h, 0f), p + V3(-w, h, 0f))
    }

    /** Scene bounding box of the board disc. */
    fun boardBox(): Box3 {
        val pts = ArrayList<V3>()
        for (a in 0 until 24) {
            val ang = a * Math.PI / 12
            val x = (Layout.BOARD_R * cos(ang)).toFloat(); val z = (Layout.BOARD_R * sin(ang)).toFloat()
            pts += toScene(V3(x, 0f, z)); pts += toScene(V3(x, -Layout.BOARD_T, z))
        }
        return Box3.of(pts)
    }

    /** Scene bounding box of everything that moves over the board: hop apex, lifted / rising balls (any hole). */
    fun envelopeBox(): Box3 {
        val pts = ArrayList<V3>()
        for (k in 0 until Peg.N) {
            pts += hole(k, Layout.CONTENT_H); pts += hole(k, -Layout.BOARD_T)
            pts += ball(k, Layout.SOLVE_RISE) + V3(Layout.BALL_R, Layout.BALL_R, Layout.BALL_R)
            pts += ball(k, Layout.SOLVE_RISE) - V3(Layout.BALL_R, Layout.BALL_R, Layout.BALL_R)
        }
        return Box3.of(pts)
    }

    fun trayBox(): Box3 = Box3(Layout.TRAY - V3(Layout.TRAY_R, 0f, Layout.TRAY_R), Layout.TRAY + V3(Layout.TRAY_R, Layout.PILE.maxOf { it.y } + Layout.BALL_R, Layout.TRAY_R))

    fun volume(): Box3 = Box3(V3(-Layout.VOLUME.x / 2, 0f, -Layout.VOLUME.z / 2), V3(Layout.VOLUME.x / 2, Layout.VOLUME.y, Layout.VOLUME.z / 2))

    /** Does the eye -> target segment cross the plate? */
    fun plateBlocks(eye: V3, target: V3): Boolean = plateBox().hitBySegment(eye, target)

    /** Holes whose marker (cup centre) is hidden behind another ball from [eye] with every hole except [emptyHole] filled. */
    fun hiddenHoles(eye: V3, pos: Long): List<Int> = (0 until Peg.N).filter { k ->
        val target = hole(k, Layout.MARKER_T)
        (0 until Peg.N).any { o -> o != k && Peg.has(pos, o) && segmentHitsSphere(eye, target, ball(o), Layout.BALL_R) }
    }

    /** Empty holes whose floating target ring would be hidden behind a ball from [eye] (every other hole filled). */
    fun hiddenTargets(eye: V3): List<Int> = (0 until Peg.N).filter { k ->
        val target = hole(k, Layout.targetLift(tiltDeg))
        (0 until Peg.N).any { o -> o != k && segmentHitsSphere(eye, target, ball(o), Layout.BALL_R) }
    }

    /** Balls whose upper half is hidden behind another ball from [eye]. */
    fun hiddenBalls(eye: V3, pos: Long): List<Int> = (0 until Peg.N).filter { k ->
        Peg.has(pos, k) && run {
            val target = ball(k) + up * (Layout.BALL_R * 0.6f)
            (0 until Peg.N).any { o -> o != k && Peg.has(pos, o) && segmentHitsSphere(eye, target, ball(o), Layout.BALL_R * 0.98f) }
        }
    }

    companion object {
        /**
         * Eyes for the occlusion checks (scene space): the emulator's camera reset (eye about level with the volume centre,
         * 1.2 / 1.9 m in front, azimuth 0 / ±30°; measured v0.1.0 in ui-audit), and the design's sit / stand eyes
         * (spatialLayout.panelGeometryCheck: sit (0, 0.45, 0.65), far (0, 0.30, 1.10), stand (0, 0.90, 0.50) about the
         * volume centre at floor + 0.20 m in the 0.40 m design volume -> here relative to the floor).
         */
        fun eyes(): Map<String, V3> {
            val out = LinkedHashMap<String, V3>()
            for (d in listOf(1.2f, 1.9f)) for (az in listOf(0, -30, 30)) {
                val a = Math.toRadians(az.toDouble())
                out["reset d=$d az=$az"] = V3((d * sin(a)).toFloat(), Layout.VOLUME.y / 2, (d * cos(a)).toFloat())
            }
            out["sit"] = V3(0f, 0.20f + 0.45f, 0.65f)
            out["far"] = V3(0f, 0.20f + 0.30f, 1.10f)
            out["stand"] = V3(0f, 0.20f + 0.90f, 0.50f)
            return out
        }
    }
}
