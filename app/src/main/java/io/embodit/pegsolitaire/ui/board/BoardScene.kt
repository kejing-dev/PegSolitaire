package io.embodit.pegsolitaire.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.pegsolitaire.domain.Anim
import io.embodit.pegsolitaire.domain.AnimKind
import io.embodit.pegsolitaire.domain.GameFlow
import io.embodit.pegsolitaire.domain.Geo
import io.embodit.pegsolitaire.domain.Layout
import io.embodit.pegsolitaire.domain.Peg
import io.embodit.pegsolitaire.domain.Phase
import io.embodit.pegsolitaire.domain.Placement
import io.embodit.pegsolitaire.domain.PlayState
import io.embodit.pegsolitaire.domain.Settings
import io.embodit.pegsolitaire.domain.Solver
import io.embodit.pegsolitaire.domain.V3
import io.embodit.pegsolitaire.platform.TAG
import io.embodit.pegsolitaire.ui.board.input.GazeTargets
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The GLB templates (tools/build_models.py): the malachite board, the tray, six ball patterns. */
internal class Models(val board: Entity, val tray: Entity, val balls: List<Entity>)

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves is
 * placed in [onFrame], driven by [BoardFrameSystem] (never by Compose).
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped 0 / 30 / 45 / 60 / 90° about X)
 *   │ └ boardRoot (board top centre): board.glb (no collider: rays land on markers and balls first), 33 hole holders
 *   │     "hole:<k>" (collider + interactable + hover, 5 mm marker child), rings, floating target rings "hole:<k>",
 *   │     hint ghost + arc dots, hop trail dots, contact shadow
 *   ├ 32 balls "peg:<i>" (GLB clone with collider + interactable + hover; collider taken off in the tray), tray.glb
 *   └ counter plate (AttachmentPanel, upright above the far rim), key light
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var plate: Entity? = null
    private var geo = Geo(io.embodit.pegsolitaire.domain.DEFAULT_TILT)

    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    private val balls = ArrayList<Entity>()
    private val ballCollider = BooleanArray(Placement.BALLS) { true }
    private val holders = HashMap<Int, Entity>()
    private lateinit var selRing: ModelEntity
    private val targetFloats = ArrayList<Entity>()
    private val targetRings = ArrayList<ModelEntity>()
    private val targetPosts = ArrayList<ModelEntity>()
    private val overRings = ArrayList<ModelEntity>()
    private val alertRings = ArrayList<ModelEntity>()
    private val stuckRings = ArrayList<ModelEntity>()
    private lateinit var landGlow: ModelEntity
    private lateinit var resultGlow: ModelEntity
    private lateinit var hoverRing: ModelEntity
    private lateinit var ghostSure: ModelEntity
    private lateinit var ghostUnsure: ModelEntity
    private val hintDots = ArrayList<ModelEntity>()
    private val hintDotsGrey = ArrayList<ModelEntity>()
    private val hopDots = ArrayList<ModelEntity>()
    private val trails = ArrayList<ModelEntity>()
    private lateinit var shadow: ModelEntity

    @Volatile private var hover: Int? = null
    private var hoverAt = 0L
    private var clock = 0f
    private var selKey: Int? = null
    private var selAt = 0L

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        placeTilt()
    }

    val ready: Boolean get() = built

    /** D50 hover: the hole under a resting pointer; light feedback only, held until another target or 1.5 s without events. */
    fun setHover(k: Int?) {
        hoverAt = SystemClock.uptimeMillis()
        if (k == hover) return
        hover = k
        if (k != null) Log.i(TAG, "HOVER ${Peg.name(k)}")
    }

    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    private fun placeTilt() {
        place(tiltRoot, geo.tiltOrigin)
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), geo.theta))
        place(boardRoot, geo.boardOffset)
        plate?.let { place(it, geo.plateCentre()) }
    }

    /** S00 stand-in: a plain dark disc where the board will be, shown until the models are in. */
    fun showFallback(disc: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        val board = ModelEntity(disc, material).apply { setName("fallbackBoard") }
        place(board, V3(0f, -Layout.BOARD_T / 2, 0f))
        fallback = listOf(board)
        fallback.forEach { boardRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun mark(mesh: MeshResource, m: Material, name: String, parent: Entity = boardRoot): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; parent.addChild(this) }

    fun install(r: SceneResources, models: Models, plateEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        // the board body takes no rays (README 通用模式: markers and balls are hit first)
        boardRoot.addChild(clone(models.board).apply { setName("boardModel") })
        val holes = (0 until Peg.N).associate { BoardNames.hole(it) to Layout.hole(it).let { v -> Vector3(v.x, v.y, v.z) } }
        GazeTargets.cells(boardRoot, holes, r.holeShape, r.physics, r.markerParts, r.marker).forEach { (name, e) ->
            (BoardNames.resolveName(name) as? BoardNames.Hole)?.let { holders[it.k] = e }
        }
        sceneRoot.addChild(clone(models.tray).apply { setName("tray"); place(this, Layout.TRAY) })
        for (i in 0 until Placement.BALLS) {
            val e = clone(models.balls[i % models.balls.size]).apply { setName(BoardNames.peg(i)) }
            GazeTargets.install(e, r.ballShape, r.physics)
            sceneRoot.addChild(e)
            balls += e
        }
        selRing = mark(r.ring, r.select, "selRing")
        repeat(4) { i ->
            val ring = ModelEntity(r.ring, r.target).apply { setName("targetRing:$i") }
            val float = Entity().apply { setName("hole:-1"); enabled = false }
            GazeTargets.install(float, r.targetShape, r.physics, ring)
            boardRoot.addChild(float)
            targetFloats += float; targetRings += ring
            targetPosts += mark(r.post, r.target, "targetPost:$i")
            overRings += mark(r.ring, r.over, "overRing:$i")
            alertRings += mark(r.ring, r.red, "alertRing:$i")
        }
        repeat(Placement.BALLS) { i -> stuckRings += mark(r.ring, r.red, "stuckRing:$i") }
        landGlow = mark(r.ringWide, r.glow, "landGlow")
        resultGlow = mark(r.ringWide, r.glow, "resultGlow")
        hoverRing = mark(r.ring, r.hover, "hoverRing")
        ghostSure = mark(r.ghost, r.ghostSure, "ghostSure")
        ghostUnsure = mark(r.ghost, r.ghostUnsure, "ghostUnsure")
        repeat(14) { i -> hintDots += mark(r.dot, r.dotSure, "hintDot:$i"); hintDotsGrey += mark(r.dot, r.dotUnsure, "hintDotGrey:$i") }
        repeat(14) { i -> hopDots += mark(r.dot, r.dotGold, "hopDot:$i") }
        repeat(2) { i -> trails += mark(r.ghost, r.trail, "trail:$i", sceneRoot) }
        shadow = mark(r.shadow, r.shade, "shadow")
        plate = plateEntity?.also { sceneRoot.addChild(it); place(it, geo.plateCentre()) }
        buildLight()

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms tilt=${geo.tiltDeg} plate=${geo.plateCentre()} far=${geo.farRim()}")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24 / design theme.env: a moderate warm key light from the upper left front, soft shadows; no burnt highlights.
            light.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.84f, 1f), 0.9f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (-35 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-58 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        if (s.tilt != geo.tiltDeg) {
            geo = Geo(s.tilt)
            placeTilt()
            Log.i(TAG, "LAYOUT tilt=${geo.tiltDeg} origin=${geo.tiltOrigin} plate=${geo.plateCentre()} farRim=${geo.farRim()}")
        }
        settings = s
        if (!built) return
        onFrame(0f)
    }

    // ---------------------------------------------------------------- per frame

    /** Scene position of ball [id] at rest in [p] (its hole, or its seat in the tray). */
    private fun restPos(p: Placement, id: Int, lift: Float = 0f): V3 {
        val h = p.holeOf[id]
        return if (h >= 0) geo.ball(h, lift) else Layout.traySeat(p.tray.indexOf(id))
    }

    private fun setBall(id: Int, at: V3, onBoard: Boolean) {
        val e = balls.getOrNull(id) ?: return
        e.enabled = true
        place(e, at)
        // tray balls take no rays (template: 托盘里的子关掉碰撞体)
        if (onBoard != ballCollider[id]) {
            ballCollider[id] = onBoard
            if (onBoard) res?.let { r -> e.components.set(CollisionComponent(collisionShape = listOf(r.ballShape), physicsMaterial = r.physics)) }
            else e.components.remove(CollisionComponent::class.java)
        }
    }

    private fun show(e: Entity, at: V3?) { if (at == null) e.enabled = false else { place(e, at); e.enabled = true } }

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.takeIf { start >= st.freezeAt - 5_000 }?.let { min(e, it) } ?: e }
        val p = st.placement
        val anim = st.anim
        val e = anim?.let { elapsed(it.startedAt) } ?: 0L
        if (st.selected != selKey) { selKey = st.selected; selAt = now }
        val liftK = if (reduced) 1f else Layout.easeOut((now - selAt) / 120f)
        val up = geo.up
        val moving = HashSet<Int>()
        var airborne: V3? = null
        var airborneH = 0f

        // ---------------- motion in flight
        if (anim != null) when (anim.kind) {
            AnimKind.JUMP -> {
                val j = anim.jump!!
                val m = anim.before.idAt[j.from]
                val o = anim.before.idAt[j.over]
                moving += m; moving += o
                val a = geo.ball(j.from, Layout.LIFT)
                val b = geo.ball(j.to)
                if (reduced) {
                    setBall(m, if (e < anim.durMs / 2) a else b, true)
                    setBall(o, if (e < anim.durMs / 2) geo.ball(j.over) else restPos(p, o), e < anim.durMs / 2)
                } else {
                    val arcH = Layout.APEX_BOTTOM + Layout.BALL_R - Layout.BALL_Y - Layout.LIFT / 2
                    val mp = if (e < GameFlow.JUMP_MS) Layout.arc(a, b, e.toFloat() / GameFlow.JUMP_MS, arcH, up)
                        else b + up * (0.003f * Layout.bounce((e - GameFlow.JUMP_MS) / 140f))
                    setBall(m, mp, true)
                    if (e < GameFlow.JUMP_MS) {
                        airborne = mp
                        airborneH = (geo.toLocal(mp).y - Layout.BALL_Y).coerceAtLeast(0f)
                        // 120 ms afterimage: two translucent copies 40 / 80 ms behind
                        trails.forEachIndexed { i, t ->
                            val back = e - 40L * (i + 1)
                            show(t, if (back > 0) Layout.arc(a, b, back.toFloat() / GameFlow.JUMP_MS, arcH, up) else null)
                        }
                    } else trails.forEach { it.enabled = false }
                    val risen = geo.ball(j.over, Layout.OVER_RISE)
                    val op = when {
                        e < GameFlow.RISE_START_MS -> geo.ball(j.over)
                        e < GameFlow.RISE_END_MS -> geo.ball(j.over).lerp(risen, Layout.easeOut((e - GameFlow.RISE_START_MS).toFloat() / (GameFlow.RISE_END_MS - GameFlow.RISE_START_MS)))
                        e < GameFlow.TRAY_END_MS -> Layout.arc(risen, restPos(p, o), (e - GameFlow.RISE_END_MS).toFloat() / (GameFlow.TRAY_END_MS - GameFlow.RISE_END_MS), 0.05f)
                        else -> restPos(p, o)
                    }
                    setBall(o, op, false)
                    // the gold dashed arc of the hop (design signature) while it flies, gone 120 ms after landing
                    hopDots.forEachIndexed { i, d ->
                        val t = (i + 1) / (hopDots.size + 1f)
                        show(d, if (e < GameFlow.JUMP_MS + 120) geo.toLocal(Layout.arc(a, b, t, arcH, up)) else null)
                    }
                }
            }
            AnimKind.UNDO -> {
                val j = anim.jump!!
                val o = anim.before.tray.last()
                val m = anim.before.idAt[j.to]
                moving += m; moving += o
                val half = anim.durMs / 2
                val seat = restPos(anim.before, o)
                val tp = if (e < half) Layout.arc(seat, geo.ball(j.over), e.toFloat() / half, 0.05f) else geo.ball(j.over)
                setBall(o, tp, true)
                val mp = if (e < half) geo.ball(j.to) else Layout.arc(geo.ball(j.to), geo.ball(j.from), (e - half).toFloat() / half, Layout.APEX_BOTTOM * 0.6f, up)
                setBall(m, mp, true)
            }
            AnimKind.RESET -> for (id in 0 until Placement.BALLS) {
                val from = restPos(anim.before, id)
                val to = restPos(p, id)
                if ((from - to).len() < 1e-4f) continue
                moving += id
                // batches 25 ms apart plus a fixed per-ball jitter of 0-20 ms and a slightly different arc height, so no
                // four balls leave on the same frame (QA K4; the design keeps the whole return within 400 ms)
                val jitter = ((id * 37) % 21).toLong()
                val delay = (id % 8) * 25L + jitter
                val t = ((e - delay).toFloat() / (anim.durMs - 195L)).coerceIn(0f, 1f)
                setBall(id, Layout.arc(from, to, Layout.easeInOut(t), 0.04f + 0.004f * ((id * 13) % 5)), p.holeOf[id] >= 0)
            }
        }
        if (anim == null || anim.kind != AnimKind.JUMP) { trails.forEach { it.enabled = false }; hopDots.forEach { it.enabled = false } }

        // ---------------- balls at rest (selected lifted; result effects)
        val res = st.result.takeIf { st.phase == Phase.RESULT }
        val re = (now - st.resultAt).coerceAtLeast(0L)
        for (id in 0 until Placement.BALLS) {
            if (id in moving) continue
            val h = p.holeOf[id]
            if (h < 0) { setBall(id, restPos(p, id), false); continue }
            var at = geo.ball(h, if (h == st.selected) Layout.LIFT * liftK else 0f)
            when (res) {
                Peg.Result.FULL -> if (!reduced) at = geo.ball(h, Layout.SOLVE_RISE * Layout.easeOut(re / 400f)) else at = geo.ball(h, Layout.SOLVE_RISE)
                Peg.Result.ONE -> at = geo.ball(h, Layout.ONE_RISE * (if (reduced) 1f else Layout.easeOut(re / 300f)))
                Peg.Result.STUCK -> if (!reduced && re < 500) at = at - up * (Layout.STUCK_SINK * sin((re / 500f) * PI.toFloat()))
                null -> Unit
            }
            setBall(id, at, true)
            // the full solve: one slow turn about the vertical (1.2 s after the rise)
            val q = if (res == Peg.Result.FULL && !reduced && re in 400..1600) Quat(Vector3(0f, 1f, 0f), ((re - 400) / 1200f * 2 * PI).toFloat()) else Quat(Vector3(0f, 1f, 0f), 0f)
            balls[id].transform().setQuaternion(q)
        }

        // ---------------- rings on the board (board-local)
        val ringY = 0.0012f
        val sel = st.selected?.takeIf { st.phase == Phase.SELECTED }
        show(selRing, sel?.let { Layout.hole(it, ringY) })
        val targets = if (st.phase == Phase.SELECTED) st.targets else emptyList()
        val lift = Layout.targetLift(geo.tiltDeg)
        targetFloats.forEachIndexed { i, f ->
            val j = targets.getOrNull(i)
            if (j == null) { f.enabled = false; targetPosts[i].enabled = false; overRings[i].enabled = false; return@forEachIndexed }
            val n = BoardNames.hole(j.to)
            if (f.getName() != n) f.setName(n)
            show(f, Layout.hole(j.to, lift))
            val post = targetPosts[i]
            place(post, Layout.hole(j.to, lift / 2)); post.transform().setScaleVector(Vector3(1f, lift, 1f)); post.enabled = true
            show(overRings[i], Layout.hole(j.over, ringY))
        }
        val al = st.alert
        val flashing = al != null && (st.freezeMs != null || (now - al.at < GameFlow.FLASH_MS && ((now - al.at) / 75) % 2 == 0L))
        alertRings.forEachIndexed { i, ring -> show(ring, al?.holes?.getOrNull(i)?.takeIf { flashing }?.let { Layout.hole(it, ringY + 0.0004f) }) }
        val stuck = if (res == Peg.Result.STUCK) (0 until Peg.N).filter { Peg.has(st.pos, it) } else emptyList()
        stuckRings.forEachIndexed { i, ring -> show(ring, stuck.getOrNull(i)?.let { Layout.hole(it, ringY) }) }
        // landing glow: 200 ms gilt ring where the hop lands
        val landing = anim?.takeIf { it.kind == AnimKind.JUMP && !reduced && e in GameFlow.JUMP_MS..(GameFlow.JUMP_MS + 200) }?.jump?.to
        show(landGlow, landing?.let { Layout.hole(it, ringY + 0.0006f) })
        landGlow.transform().setScaleVector(Vector3(1f + (e - GameFlow.JUMP_MS).coerceIn(0, 200) / 600f, 1f, 1f + (e - GameFlow.JUMP_MS).coerceIn(0, 200) / 600f))
        // result glow under the risen ball (full solve: gilt, breathing; one left: the same ring, still)
        val lastHole = if (res == Peg.Result.FULL || res == Peg.Result.ONE) (0 until Peg.N).firstOrNull { Peg.has(st.pos, it) } else null
        show(resultGlow, lastHole?.let { Layout.hole(it, ringY) })
        if (lastHole != null) { val k = if (res == Peg.Result.FULL && !reduced) 1.15f + 0.1f * sin(clock * 3f) else 1f; resultGlow.transform().setScaleVector(Vector3(k, 1f, k)) }
        // hover (D50): white ring on the hole under the pointer; never changes state
        val hv = hover?.takeIf { now - hoverAt < 1500 }
        show(hoverRing, hv?.let { Layout.hole(it, ringY - 0.0002f) })

        // ---------------- hint ghost (design gameSpec.hint: sure = cyan + solid arc, unsure = grey + sparse dashes, impossible = none)
        val hint = st.hint?.takeIf { st.hintOn && !it.running && it.ply == st.jumps.size && (st.phase == Phase.IDLE || st.phase == Phase.SELECTED) }
        val hj = hint?.jump?.takeIf { hint.kind != Solver.Kind.IMPOSSIBLE }
        val sure = hint?.kind == Solver.Kind.SURE
        val bob = if (reduced) 0f else 0.002f * sin(clock * 2.5f)
        val ghostAt = hj?.let { Layout.ball(it.from, Layout.BALL_R * 2 + 0.010f + bob) }
        show(ghostSure, ghostAt?.takeIf { sure })
        show(ghostUnsure, ghostAt?.takeIf { !sure })
        // sure: a solid run of cyan dots; unsure: every third dot, grey
        val a0 = hj?.let { Layout.ball(it.from, Layout.BALL_R * 2 + 0.010f) }
        val b0 = hj?.let { Layout.hole(it.to, 0.004f) }
        for ((set, on, step) in listOf(Triple(hintDots, sure, 1), Triple(hintDotsGrey, !sure, 3))) set.forEachIndexed { i, d ->
            if (hj == null || !on || i % step != 0) { d.enabled = false; return@forEachIndexed }
            show(d, Layout.arc(a0!!, b0!!, (i + 1) / (set.size + 1f), 0.05f))
        }

        // ---------------- contact shadow under the ball in the air: grows with height
        if (airborne != null) {
            val loc = geo.toLocal(airborne)
            show(shadow, V3(loc.x, 0.0006f, loc.z))
            val k = 1f + airborneH * 6f
            shadow.transform().setScaleVector(Vector3(k, 1f, k))
        } else shadow.enabled = false
    }

    fun evidence(): String =
        "tilt=${geo.tiltDeg} origin=${geo.tiltOrigin} plate=${geo.plateCentre()} plateW=${Layout.PLATE_W} plateH=${Layout.PLATE_H} " +
            "farRim=${geo.farRim()} centreBall=${geo.ball(Peg.CENTER)} tray=${Layout.TRAY} halfY=$regionHalfY"

    /** Debug `replay`: the real entities of a hole / its marker / the ball standing in it, deepest first. */
    fun replayEntities(k: Int): List<Pair<String, Entity>> {
        val out = ArrayList<Pair<String, Entity>>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().iterator().hasNext()) x = x.getChildren().first(); return x }
        holders[k]?.let { h ->
            out += "holder" to h
            h.getChildren().firstOrNull()?.let { out += "marker" to it }
        }
        targetFloats.firstOrNull { it.enabled && it.getName() == BoardNames.hole(k) }?.let { f ->
            out += "target" to f
            f.getChildren().firstOrNull()?.let { out += "targetRing" to it }
        }
        val id = state.placement.idAt.getOrNull(k) ?: -1
        if (id >= 0) balls.getOrNull(id)?.let { b -> out += "ball" to b; out += "ballMesh" to deepest(b) }
        return out
    }

    fun dispose() {
        res?.close()
        res = null
    }
}
