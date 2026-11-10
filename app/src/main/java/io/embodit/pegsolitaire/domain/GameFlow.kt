package io.embodit.pegsolitaire.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Named states of design-package states[] that live in the game; S01 / S06 / S11 / S12 / S13 / S16 are overlays or pages. */
enum class Phase(val id: String) { LOADING("S00"), IDLE("S03"), SELECTED("S04"), JUMPING("S05"), RESULT("S09") }

/** One sound per event class (D51: the same event always sounds the same, at most one instance at a time). */
enum class Sound { LAND, TRAY, ILLEGAL, STUCK, SOLVED, ONE_LEFT }

enum class AlertKind { PICK_FIRST, CANT_JUMP, NOT_LIT, NOTHING_TO_UNDO, WELCOME_PICK }

/** A one-line notice + a red ring flash (300 ms) on [holes]. */
data class Alert(val kind: AlertKind, val holes: List<Int>, val at: Long)

enum class AnimKind { JUMP, UNDO, RESET }

/**
 * The motion in flight. JUMP: [jump] (the mover arcs from -> to in [GameFlow.JUMP_MS], the jumped ball rises and flies to
 * the tray until [GameFlow.TRAY_END_MS]). UNDO: [jump] taken back (tray top -> over, then to -> from). RESET: every ball from
 * [from] to the new placement (restart, another start board, undo n). [before] = the placement the motion starts from.
 */
data class Anim(val kind: AnimKind, val jump: Peg.Jump?, val before: Placement, val startedAt: Long, val durMs: Long)

enum class ConfirmKind { RESTART, CHANGE_START }

/** The hint (design gameSpec.hint): three answers + RUNNING; [backN] = undo this many jumps to reach a solvable board. */
data class Hint(val kind: Solver.Kind?, val jump: Peg.Jump?, val ply: Int, val backN: Int? = null, val since: Long = 0, val ms: Long = 0, val backRunning: Boolean = false) {
    val running: Boolean get() = kind == null
}

enum class StatusKey { NONE, PICK, TAP_LIT, HOP, COACH_1, COACH_2, COACH_3, RESTORED, ALERT, FULL, ONE, STUCK }

data class Status(val key: StatusKey, val n: Int = 0, val alert: AlertKind? = null)

/** The one state every surface reads (board scene, control window, counter plate, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val start: Start = Start.STANDARD,
    val jumps: List<Peg.Jump> = emptyList(),
    /** positions[i] = the board after i jumps (positions[0] = the start board). */
    val positions: List<Long> = listOf(Start.STANDARD.pos),
    val selected: Int? = null,
    /** Legal jumps of the selected peg (their [Peg.Jump.to] holes are lit amber, [Peg.Jump.over] pink). */
    val targets: List<Peg.Jump> = emptyList(),
    val anim: Anim? = null,
    val alert: Alert? = null,
    val result: Peg.Result? = null,
    val resultAt: Long = 0,
    val hintOn: Boolean = false,
    val hint: Hint? = null,
    val usedHint: Boolean = false,
    val confirm: ConfirmKind? = null,
    val confirmStart: Start? = null,
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val welcome: Boolean = false,
    /** 0 = no coach line, 1..3 = the first-game coach step (design content.coach). */
    val coach: Int = 0,
    val restored: Boolean = false,
    /** Debug hold: animations stop this many ms after their start, notices hold. */
    val freezeMs: Long? = null,
    val freezeAt: Long = 0,
) {
    val pos: Long get() = positions.last()
    val left: Int get() = Peg.count(pos)
    val moves: Int get() = Peg.moves(jumps)
    /** Balls by hole, from the board the game began on (a debug `load` board too, QA K6). */
    val placement: Placement get() = Placement.of(positions.first(), jumps)
    val inGame: Boolean get() = phase in setOf(Phase.IDLE, Phase.SELECTED, Phase.JUMPING)
    val canUndo: Boolean get() = jumps.isNotEmpty() && phase in setOf(Phase.IDLE, Phase.SELECTED, Phase.RESULT)
    val hasProgress: Boolean get() = jumps.isNotEmpty() && phase != Phase.RESULT
    /** Pegs whose jumps are open right now (S01 / S03: a tap on any other peg answers "can't hop"). */
    val movable: Set<Int> get() = Peg.legalJumps(pos).map { it.from }.toSet()

    fun status(): Status {
        alert?.let { return Status(StatusKey.ALERT, alert = it.kind) }
        return when (phase) {
            Phase.RESULT -> when (result) {
                Peg.Result.FULL -> Status(StatusKey.FULL, moves)
                Peg.Result.ONE -> Status(StatusKey.ONE, 1)
                else -> Status(StatusKey.STUCK, left)
            }
            Phase.JUMPING -> if (coach == 3) Status(StatusKey.COACH_3) else Status(StatusKey.HOP)
            Phase.SELECTED -> if (coach >= 1) Status(StatusKey.COACH_2) else Status(StatusKey.TAP_LIT)
            Phase.IDLE -> when {
                coach == 1 -> Status(StatusKey.COACH_1)
                coach == 3 -> Status(StatusKey.COACH_3)
                restored -> Status(StatusKey.RESTORED)
                else -> Status(StatusKey.PICK)
            }
            Phase.LOADING -> Status(StatusKey.NONE)
        }
    }
}

/**
 * The middle layer (rules - state - render). Main-thread confined: every public call and the coroutines it launches run
 * on [scope]; the solver runs on [solverDispatcher]. The volume, the window and the debug broadcast all call the same
 * functions (profile D29).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val solverDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val records: RecordStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val onRecords: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val solverFactory: () -> Solver = { Solver() },
) {
    companion object {
        /** Design gameSpec.turn: the hop, then the jumped ball rises (100-220 ms) and flies to the tray (220-460 ms). */
        const val JUMP_MS = 260L
        const val RISE_START_MS = 100L
        const val RISE_END_MS = 220L
        const val TRAY_END_MS = 460L
        const val UNDO_STEP_MS = 200L
        const val RESET_MS = 400L
        const val REDUCED_MS = 150L
        const val ALERT_MS = 1500L
        const val FLASH_MS = 300L
        /** "在算…" only shows when the solver takes longer than this (design S06). */
        const val SOLVING_VISIBLE_MS = 200L
        const val RESULT_SOUND_DELAY_MS = 220L
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    /** A window-only confirmation (exit / clear records) is open: the volume is locked too. */
    @Volatile var uiLocked: Boolean = false

    private var animJob: Job? = null
    private var alertJob: Job? = null
    private var hintJob: Job? = null
    private var countedPlayed = false
    private var countedSolve = false

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion

    // ---------------------------------------------------------------- lifecycle

    /** S00 exits: first run -> the standard board + rules page (S01); a save -> S16; otherwise a fresh board (S03). */
    fun start() {
        val saved = store.load()
        if (!settings().firstRunDone) {
            newGame(Start.STANDARD, animate = false)
            set(s.copy(welcome = true, coach = if (settings().coach) 1 else 0))
            log("STATE S01 (standard board, rules page open)")
            return
        }
        if (saved != null) restore(saved) else newGame(Start.of(settings().lastStart), animate = false)
    }

    /** [开始] on the welcome rules page / the first tap on a peg that can hop. */
    fun welcomeDone() {
        if (!s.welcome && settings().firstRunDone) return
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(welcome = false))
        log("WELCOME done")
    }

    private fun positionsOf(start: Long, jumps: List<Peg.Jump>): List<Long> {
        val out = ArrayList<Long>(jumps.size + 1)
        out += start
        for (j in jumps) out += Peg.apply(out.last(), j)
        return out
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        val positions = positionsOf(saved.start.pos, saved.jumps)
        val res = Peg.result(positions.last())
        countedPlayed = res != null; countedSolve = res == Peg.Result.FULL
        set(PlayState(
            phase = if (res != null) Phase.RESULT else Phase.IDLE, start = saved.start, jumps = saved.jumps, positions = positions,
            result = res, resultAt = clock() - 10_000, hintOn = saved.hintOn, usedHint = saved.usedHint, elapsedMs = saved.elapsedMs,
            resumedAt = clock(), restored = true,
        ))
        log("GAME restored start=${saved.start.id} jumps=${saved.jumps.size} left=${s.left} result=$res S16 -> ${s.phase.id}")
        refreshHint()
    }

    /** A fresh game on [start] (S03; the hint starts off again, so a new game can count for the records); the tray balls fly back in 400 ms unless reduced motion or [animate] = false. */
    fun newGame(start: Start, animate: Boolean = true) {
        cancelJobs()
        countedPlayed = false; countedSolve = false
        val before = s.placement
        val now = clock()
        val firstGame = !settings().firstRunDone || s.welcome
        set(PlayState(
            phase = Phase.IDLE, start = start, positions = listOf(start.pos), resumedAt = now, hintOn = false, welcome = s.welcome,
            coach = if (firstGame && settings().coach) 1 else 0, freezeMs = s.freezeMs, freezeAt = s.freezeAt,
            anim = if (animate && !reduced && s.phase != Phase.LOADING) Anim(AnimKind.RESET, null, before, now, RESET_MS) else null,
        ))
        if (settings().lastStart != start.id) onSettings(settings().copy(lastStart = start.id))
        log("GAME new start=${start.id} pegs=${start.pegs} S03")
        persist()
        if (s.anim != null) animJob = scope.launch { delay(RESET_MS); awaitUnfrozen(); set(s.copy(anim = null)) }
        refreshHint()
    }

    /** [再来一盘] (S09) / [重开] with nothing to lose: the same start board. */
    fun again() = newGame(s.start)

    /** [重开]: asks first once a jump was made (S13). */
    fun restart() {
        if (locked()) return
        if (s.hasProgress) requestConfirm(ConfirmKind.RESTART) else again()
    }

    /** The start-board strip / [换个起始图]: asks first while a game is under way. */
    fun chooseStart(start: Start) {
        if (locked()) return
        log("START pick ${start.id} (now ${s.start.id}, progress=${s.hasProgress})")
        if (start == s.start && !s.hasProgress && s.phase != Phase.RESULT) return
        if (s.hasProgress) { set(s.copy(confirm = ConfirmKind.CHANGE_START, confirmStart = start)); log("CONFIRM CHANGE_START S13"); return }
        newGame(start)
    }

    /** [换个起始图] on the result card: the next start board in the list. */
    fun nextStart() = chooseStart(Start.entries[(s.start.ordinal + 1) % Start.entries.size])

    /** Going to the background: land a motion in flight, cancel the solver, keep the game. */
    fun onBackground() {
        if (s.anim != null) { animJob?.cancel(); finishAnimNow() }
        hintJob?.cancel()
        if (s.hint?.running == true) set(s.copy(hint = null))
        persist()
        log("BACKGROUND phase=${s.phase.id}")
    }

    fun onForeground() {
        set(s.copy(resumedAt = clock()))
        refreshHint()
    }

    // ---------------------------------------------------------------- input (volume, panel, debug: one path)

    private fun locked(): Boolean {
        if (s.confirm != null || uiLocked) { log("LOCKED by confirm (S13)"); return true }
        return false
    }

    /** A pinch / trigger on a hole or on the ball standing in it (the same thing: a hole index). */
    fun tapHole(k: Int) {
        val st = s
        val peg = Peg.has(st.pos, k)
        log("TAP hole ${Peg.name(k)}($k) peg=$peg phase=${st.phase.id} sel=${st.selected?.let { Peg.name(it) }}")
        if (locked()) return
        when (st.phase) {
            Phase.IDLE, Phase.SELECTED -> Unit
            else -> return
        }
        if (st.phase == Phase.SELECTED) {
            val sel = st.selected!!
            st.targets.firstOrNull { it.to == k }?.let { commit(it); return }
            if (k == sel) { cancelSelection(); return }
            if (peg) { select(k); return }
            alert(AlertKind.NOT_LIT, listOf(k))
            return
        }
        if (peg) { select(k); return }
        alert(if (st.welcome) AlertKind.WELCOME_PICK else AlertKind.PICK_FIRST, listOf(k))
    }

    /** A pinch off the holes (board rim, tray, air): drop the selection. */
    fun tapEmpty() {
        if (locked()) return
        log("TAP empty phase=${s.phase.id}")
        if (s.phase == Phase.SELECTED) cancelSelection()
    }

    private fun select(k: Int) {
        val st = s
        val targets = Peg.jumpsFrom(st.pos, k)
        if (targets.isEmpty()) {
            alert(if (st.welcome) AlertKind.WELCOME_PICK else AlertKind.CANT_JUMP, listOf(k))
            if (st.phase == Phase.SELECTED) set(s.copy(phase = Phase.IDLE, selected = null, targets = emptyList()))
            return
        }
        if (st.welcome) welcomeDone()
        set(s.copy(phase = Phase.SELECTED, selected = k, targets = targets, alert = null, restored = false,
            coach = if (s.coach == 1 || s.coach == 3) (if (s.jumps.isEmpty()) 2 else 0) else s.coach))
        // D51 (2026-11-06): selecting is silent
        log("SELECT ${Peg.name(k)} targets=${targets.joinToString(",") { Peg.name(it.to) }} S04")
    }

    fun cancelSelection() {
        if (s.phase != Phase.SELECTED) return
        set(s.copy(phase = Phase.IDLE, selected = null, targets = emptyList(), coach = if (s.coach == 2) 1 else s.coach))
        log("CANCEL -> S03")
    }

    private fun alert(kind: AlertKind, holes: List<Int>) {
        val at = clock()
        set(s.copy(alert = Alert(kind, holes, at)))
        onSound(Sound.ILLEGAL)
        log("ALERT $kind holes=${holes.map { Peg.name(it) }}")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            awaitUnfrozen()
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    /** Debug `jump <from> <to>` and the panel's hint shortcut: the same as tapping the peg, then the lit hole. */
    fun jump(from: Int, to: Int): Boolean {
        if (s.phase == Phase.SELECTED && s.selected != from) cancelSelection()
        if (s.phase == Phase.IDLE) tapHole(from)
        if (s.phase != Phase.SELECTED || s.selected != from) return false
        tapHole(to)
        return s.phase == Phase.JUMPING
    }

    // ---------------------------------------------------------------- jumping

    private fun commit(j: Peg.Jump) {
        val st = s
        val before = st.placement
        val after = Peg.apply(st.pos, j)
        val now = clock()
        val dur = if (reduced) REDUCED_MS else TRAY_END_MS
        set(st.copy(
            phase = Phase.JUMPING, jumps = st.jumps + j, positions = st.positions + after, selected = null, targets = emptyList(),
            anim = Anim(AnimKind.JUMP, j, before, now, dur), alert = null, restored = false,
            hint = null, coach = if (st.coach == 2) 3 else if (st.coach == 3) 0 else st.coach,
        ))
        log("JUMP ${j.text()} over ${Peg.name(j.over)} left=${Peg.count(after)} jumps=${s.jumps.size} moves=${s.moves} S05")
        // committed and saved before the animation: a process death mid-hop resumes on the landed board
        persist()
        hintJob?.cancel()
        animJob?.cancel()
        animJob = scope.launch {
            if (reduced) {
                delay(REDUCED_MS); awaitUnfrozen()
                onSound(Sound.LAND); land(); onSound(Sound.TRAY); flown()
                return@launch
            }
            delay(JUMP_MS)
            if (s.freezeMs?.let { it < JUMP_MS } == true) awaitUnfrozen()
            onSound(Sound.LAND)
            land()
            delay(TRAY_END_MS - JUMP_MS)
            awaitUnfrozen()
            onSound(Sound.TRAY)
            flown()
        }
    }

    /** 260 ms: the ball is in its hole; input opens again unless nothing can hop (then the result waits for the tray). */
    private fun land() {
        val st = s
        if (st.phase != Phase.JUMPING) return
        if (Peg.hasMove(st.pos)) {
            set(st.copy(phase = Phase.IDLE))
            log("LANDED -> S03 legal=${Peg.legalJumps(st.pos).size}")
            refreshHint()
        }
    }

    /** 460 ms: the jumped ball is in the tray. */
    private fun flown() {
        set(s.copy(anim = null))
        if (s.phase == Phase.JUMPING) finishIfOver()
    }

    private fun finishAnimNow() {
        val a = s.anim ?: return
        set(s.copy(anim = null))
        if (a.kind == AnimKind.JUMP && s.phase == Phase.JUMPING) { if (Peg.hasMove(s.pos)) set(s.copy(phase = Phase.IDLE)) else finishIfOver() }
        if (a.kind == AnimKind.UNDO && s.phase == Phase.JUMPING) set(s.copy(phase = Phase.IDLE))
    }

    private fun finishIfOver(): Boolean {
        val st = s
        val res = Peg.result(st.pos) ?: return false
        val now = clock()
        set(st.copy(phase = Phase.RESULT, result = res, resultAt = now, selected = null, targets = emptyList(), alert = null,
            elapsedMs = st.elapsedMs + (now - st.resumedAt), resumedAt = now, coach = 0, hint = null))
        // debug `load` boards are not real games: no records
        val real = st.positions.first() == st.start.pos
        val rec = records.get(st.start).after(st.left, res == Peg.Result.FULL, st.moves, st.usedHint, countPlayed = !countedPlayed, countSolve = !countedSolve)
        if (real) {
            countedPlayed = true
            if (res == Peg.Result.FULL) countedSolve = true
            records.put(st.start, rec)
            onRecords()
        }
        // the ending sound waits for the tray click to fade (render_mix: tray + chime together would clip at +0.7 dBFS)
        val endSound = when (res) { Peg.Result.FULL -> Sound.SOLVED; Peg.Result.ONE -> Sound.ONE_LEFT; Peg.Result.STUCK -> Sound.STUCK }
        alertJob?.cancel()
        scope.launch { delay(RESULT_SOUND_DELAY_MS); if (s.phase == Phase.RESULT && s.resultAt == now) onSound(endSound) }
        log("RESULT $res left=${st.left} jumps=${st.jumps.size} moves=${st.moves} hinted=${st.usedHint} record=$rec S09")
        persist()
        return true
    }

    // ---------------------------------------------------------------- undo

    /** [悔一步]: the last jump back (tray top -> its hole, then the jumper back), also from the result (S09 -> S03). */
    fun undo() {
        val st = s
        if (locked()) return
        if (st.jumps.isEmpty()) { alert(AlertKind.NOTHING_TO_UNDO, emptyList()); return }
        if (!st.canUndo) { log("UNDO not now phase=${st.phase.id}"); return }
        cancelJobs()
        val j = st.jumps.last()
        val before = st.placement
        val now = clock()
        val dur = if (reduced) REDUCED_MS else 2 * UNDO_STEP_MS
        set(st.copy(
            phase = Phase.JUMPING, jumps = st.jumps.dropLast(1), positions = st.positions.dropLast(1), selected = null, targets = emptyList(),
            anim = Anim(AnimKind.UNDO, j, before, now, dur), alert = null, result = null, hint = null, restored = false, coach = 0,
            resumedAt = if (st.phase == Phase.RESULT) now else st.resumedAt,
        ))
        log("UNDO ${j.text()} -> jumps=${s.jumps.size} left=${s.left}")
        persist()
        animJob = scope.launch {
            delay(dur); awaitUnfrozen()
            set(s.copy(anim = null, phase = Phase.IDLE))
            log("STATE S03 after undo")
            refreshHint()
        }
    }

    /** [悔 n 步回到还能全解]: back to the last board the solver called solvable, in one motion. */
    fun undoTo(n: Int) {
        val st = s
        if (locked() || n <= 0 || n > st.jumps.size || !st.canUndo) return
        cancelJobs()
        val before = st.placement
        val now = clock()
        val keep = st.jumps.size - n
        set(st.copy(
            phase = Phase.JUMPING, jumps = st.jumps.take(keep), positions = st.positions.take(keep + 1), selected = null, targets = emptyList(),
            anim = Anim(AnimKind.RESET, null, before, now, if (reduced) REDUCED_MS else RESET_MS), alert = null, result = null, hint = null,
        ))
        log("UNDO $n -> jumps=$keep")
        persist()
        animJob = scope.launch {
            delay(if (reduced) REDUCED_MS else RESET_MS); awaitUnfrozen()
            set(s.copy(anim = null, phase = Phase.IDLE))
            refreshHint()
        }
    }

    // ---------------------------------------------------------------- hint (solver)

    fun setHint(on: Boolean) {
        if (locked()) return
        set(s.copy(hintOn = on, hint = if (on) s.hint else null, usedHint = s.usedHint || (on && s.inGame)))
        log("HINT ${if (on) "on" else "off"}")
        if (!on) hintJob?.cancel()
        refreshHint()
        persist()
    }

    private fun refreshHint() {
        val st = s
        hintJob?.cancel()
        if (!st.hintOn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED)) return
        val ply = st.jumps.size
        val have = st.hint?.takeIf { it.ply == ply && !it.running }
        if (have != null) {
            // QA q2-1: a cancelled look-back (going to the background, a page switch) must start again, or the undo-n key
            // never appears; every other finished answer for this ply stays as it is
            if (have.kind == Solver.Kind.IMPOSSIBLE && have.backN == null) startLookBack(st.pos, st.positions, ply, have.since)
            return
        }
        if (!st.usedHint) set(s.copy(usedHint = true))
        val pos = st.pos
        val positions = st.positions
        val t0 = clock()
        set(s.copy(hint = Hint(null, null, ply, since = t0)))
        log("SOLVE start ply=$ply S06")
        hintJob = scope.launch {
            val job = coroutineContext[Job]
            val answer = verdicts[pos]?.takeIf { it.kind == Solver.Kind.IMPOSSIBLE || it.jump != null }
                ?: withContext(solverDispatcher) { solverFactory().solve(pos, cancelled = { job?.isActive != true }) }
            if (s.pos != pos || !s.hintOn) return@launch
            remember(pos, answer)
            // QA q1-1: show "impossible" the moment it is proven; the undo-n button follows when the look-back is done
            // (boards already proven this session -- e.g. with the hint on all along -- answer from the cache at once)
            val looking = answer.kind == Solver.Kind.IMPOSSIBLE
            set(s.copy(hint = Hint(answer.kind, answer.jump, ply, null, t0, clock() - t0, backRunning = looking)))
            log("SOLVE ${answer.kind} ${answer.jump?.text()} nodes=${answer.nodes} ms=${answer.ms} failed=${answer.failed} shownAfter=${clock() - t0}")
            if (looking) startLookBack(pos, positions, ply, t0)
        }
    }

    /** The undo-n look-back for an IMPOSSIBLE board (its own job: it is restarted whenever it was cancelled, QA q2-1). */
    private fun startLookBack(pos: Long, positions: List<Long>, ply: Int, t0: Long) {
        hintJob?.cancel()
        if (s.hint?.backRunning != true) set(s.copy(hint = s.hint?.copy(backRunning = true)))
        log("SOLVE look-back start ply=$ply")
        val proven = HashMap<Long, Boolean>().apply { verdicts.forEach { (k, v) -> put(k, v.kind == Solver.Kind.SURE) } }
        hintJob = scope.launch {
            val job = coroutineContext[Job]
            val back = withContext(solverDispatcher) {
                nearestSolvable(positions, cancelled = { job?.isActive != true }) { p -> proven[p] }
            }
            if (s.pos != pos || !s.hintOn || s.hint?.ply != ply) return@launch
            back?.let { remember(positions[positions.size - 1 - it], Solver.Answer(Solver.Kind.SURE, null, emptyList(), 0, 0, 0)) }
            set(s.copy(hint = s.hint?.copy(backN = back, backRunning = false)))
            log("SOLVE back=$back after ${clock() - t0} ms")
        }
    }

    /** Solver answers per board for this session (a board's verdict never changes; only proofs are kept, never UNSURE). */
    private val verdicts = HashMap<Long, Solver.Answer>()

    private fun remember(pos: Long, a: Solver.Answer) {
        if (a.kind == Solver.Kind.UNSURE) return
        if (verdicts.size > 4096) verdicts.clear()
        // a SURE answer is only reused as a hint when it carries its first hop; look-back proofs carry none
        if (a.kind == Solver.Kind.SURE && a.jump == null && verdicts[pos] != null) return
        verdicts[pos] = a
    }

    // ---------------------------------------------------------------- confirm

    fun requestConfirm(kind: ConfirmKind) { set(s.copy(confirm = kind)); log("CONFIRM $kind S13") }

    fun cancelConfirm() { set(s.copy(confirm = null, confirmStart = null)); log("CONFIRM cancel") }

    fun confirm() {
        val kind = s.confirm ?: return
        val target = s.confirmStart
        set(s.copy(confirm = null, confirmStart = null))
        log("CONFIRM yes $kind")
        when (kind) {
            ConfirmKind.RESTART -> again()
            ConfirmKind.CHANGE_START -> newGame(target ?: s.start)
        }
    }

    fun clearRecords() {
        records.clear()
        onRecords()
        log("RECORDS cleared")
    }

    fun setCoach(on: Boolean) {
        set(s.copy(coach = if (on && s.jumps.isEmpty() && s.inGame) (if (s.phase == Phase.SELECTED) 2 else 1) else if (on) s.coach else 0))
    }

    // ---------------------------------------------------------------- debug

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms, freezeAt = clock())); log("FREEZE ${ms ?: "off"}") }

    private suspend fun awaitUnfrozen() { while (coroutineContext.isActive && s.freezeMs != null) delay(50) }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    /** Debug `load <hex>`: a custom board (not saved, records not touched). */
    fun loadBoard(pos: Long) {
        cancelJobs()
        set(PlayState(phase = Phase.IDLE, start = s.start, positions = listOf(pos), resumedAt = clock(), hintOn = s.hintOn, freezeMs = s.freezeMs, freezeAt = s.freezeAt))
        log("GAME load ${Peg.hex(pos)} left=${Peg.count(pos)}")
        if (!Peg.hasMove(pos)) finishIfOver() else refreshHint()
    }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} start=${st.start.id} jumps=${st.jumps.size} moves=${st.moves} left=${st.left} sel=${st.selected?.let { Peg.name(it) }} " +
            "targets=${st.targets.map { Peg.name(it.to) }} result=${st.result} hintOn=${st.hintOn} hint=${st.hint?.kind}/${st.hint?.jump?.text()}/back=${st.hint?.backN} " +
            "usedHint=${st.usedHint} alert=${st.alert?.kind} confirm=${st.confirm} welcome=${st.welcome} coach=${st.coach} restored=${st.restored} " +
            "anim=${st.anim?.kind} freeze=${st.freezeMs} pos=${Peg.hex(st.pos)}"
    }

    private fun persist() {
        val st = s
        if (st.phase == Phase.LOADING) return
        // debug `load` boards are not saved (the save replays from a start board)
        if (st.positions.first() != st.start.pos) return
        store.save(SavedGame(st.start, st.jumps, st.hintOn, st.usedHint, st.elapsedMs + (clock() - st.resumedAt)))
    }

    private fun cancelJobs() {
        animJob?.cancel(); alertJob?.cancel(); hintJob?.cancel()
        if (s.anim != null) set(s.copy(anim = null))
    }
}
