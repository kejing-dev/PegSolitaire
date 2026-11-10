package io.embodit.pegsolitaire.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The middle layer: taps, hops, timing (input locked 260 ms only), undo (also from S09), confirms, records, save / restore, hint. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    class MemRecords : RecordStore {
        val map = HashMap<Start, Record>()
        override fun get(start: Start) = map[start] ?: Record()
        override fun put(start: Start, r: Record) { map[start] = r }
        override fun clear() = map.clear()
    }

    private var settings = Settings(firstRunDone = true, coach = false)
    private val sounds = ArrayList<Sound>()
    private val logs = ArrayList<String>()

    private fun TestScope.flow(store: MemStore = MemStore(), records: MemRecords = MemRecords()) = GameFlow(
        backgroundScope, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, store, records, { settings }, { settings = it }, { sounds += it }, log = { logs += it },
    )

    private fun h(n: String) = Peg.parse(n)!!
    private fun pairs(s: String) = s.split(";").map { p -> p.split("-").let { it[0].toInt() to it[1].toInt() } }

    private fun TestScope.playAll(f: GameFlow, list: List<Pair<Int, Int>>) {
        for ((a, b) in list) { assertTrue("$a-$b ${f.dump()}", f.jump(a, b)); advanceTimeBy(GameFlow.TRAY_END_MS + 10); runCurrent() }
        advanceTimeBy(GameFlow.RESULT_SOUND_DELAY_MS + 10); runCurrent()
    }

    @Test fun selectHopAndTiming() = runTest {
        val f = flow(); f.start(); runCurrent()
        f.tapHole(h("d6"))
        assertEquals(Phase.SELECTED, f.state.value.phase)
        assertEquals(listOf(h("d4")), f.state.value.targets.map { it.to })
        f.tapHole(h("d4"))
        assertEquals(Phase.JUMPING, f.state.value.phase)
        f.tapHole(h("b4")); assertEquals(Phase.JUMPING, f.state.value.phase)      // locked during the hop
        advanceTimeBy(GameFlow.JUMP_MS + 5); runCurrent()
        assertEquals(Phase.IDLE, f.state.value.phase)                              // open again while the hopped ball flies
        assertNotNull(f.state.value.anim)
        f.tapHole(h("b5")); assertEquals(Phase.SELECTED, f.state.value.phase)
        advanceTimeBy(300); runCurrent()
        assertNull(f.state.value.anim)
        assertEquals(listOf(Sound.LAND, Sound.TRAY), sounds)                       // selecting is silent
    }

    @Test fun illegalTapsExplainAndMoveNothing() = runTest {
        val f = flow(); f.start(); runCurrent()
        f.tapHole(h("d4")); assertEquals(AlertKind.PICK_FIRST, f.state.value.alert?.kind)
        f.tapHole(h("a4")); assertEquals(AlertKind.CANT_JUMP, f.state.value.alert?.kind); assertEquals(Phase.IDLE, f.state.value.phase)
        f.tapHole(h("d6")); f.tapHole(h("f4"))
        assertEquals(h("f4"), f.state.value.selected)                               // another peg that can hop: switch selection
        f.tapHole(h("d6")); f.tapHole(h("d6")); assertEquals(Phase.IDLE, f.state.value.phase) // tap again = cancel
        f.undo(); assertEquals(AlertKind.NOTHING_TO_UNDO, f.state.value.alert?.kind)
        assertEquals(32, f.state.value.left)
    }

    @Test fun bergholtFullSolveRecordsAndUndoFromResult() = runTest {
        val rec = MemRecords()
        val f = flow(records = rec); f.start(); runCurrent()
        playAll(f, pairs(RulesTest.BERGHOLT))
        val st = f.state.value
        assertEquals(Phase.RESULT, st.phase); assertEquals(Peg.Result.FULL, st.result); assertEquals(18, st.moves)
        assertEquals(StatusKey.FULL, st.status().key)
        assertEquals(Sound.SOLVED, sounds.last())
        assertEquals(Record(1, 1, 1, 18), rec.get(Start.STANDARD))
        f.undo(); advanceTimeBy(500); runCurrent()
        assertEquals(Phase.IDLE, f.state.value.phase); assertEquals(30, f.state.value.jumps.size)
        playAll(f, pairs(RulesTest.BERGHOLT).takeLast(1))
        assertEquals(Record(1, 1, 1, 18), rec.get(Start.STANDARD))                // the same game counts once
    }

    @Test fun stuckAndOneLeft() = runTest {
        val f = flow(); f.start(); runCurrent()
        playAll(f, pairs(RulesTest.STUCK6))
        assertEquals(Peg.Result.STUCK, f.state.value.result); assertEquals(Sound.STUCK, sounds.last())
        assertEquals(StatusKey.STUCK, f.state.value.status().key); assertEquals(26, f.state.value.status().n)
        f.again(); advanceTimeBy(500); runCurrent()
        playAll(f, pairs(RulesTest.OFF_CENTRE))
        assertEquals(Peg.Result.ONE, f.state.value.result); assertEquals(Sound.ONE_LEFT, sounds.last())
    }

    @Test fun restartAndChangeStartAskFirstMidGame() = runTest {
        val f = flow(); f.start(); runCurrent()
        f.restart(); assertNull(f.state.value.confirm)                              // nothing to lose: straight away
        advanceTimeBy(500); runCurrent()
        playAll(f, pairs(RulesTest.BERGHOLT).take(3))
        f.restart(); assertEquals(ConfirmKind.RESTART, f.state.value.confirm)
        f.tapHole(h("b4")); assertEquals(Phase.IDLE, f.state.value.phase)         // the board is locked
        f.cancelConfirm(); assertEquals(3, f.state.value.jumps.size)
        f.chooseStart(Start.DIAMOND); assertEquals(ConfirmKind.CHANGE_START, f.state.value.confirm)
        f.confirm(); advanceTimeBy(500); runCurrent()
        assertEquals(Start.DIAMOND, f.state.value.start); assertEquals(24, f.state.value.left)
        assertEquals("diamond", settings.lastStart)
    }

    @Test fun saveAndRestoreMidGameAndResult() = runTest {
        val store = MemStore()
        val f = flow(store); f.start(); runCurrent()
        playAll(f, pairs(RulesTest.BERGHOLT).take(7))
        val g = flow(store); g.start(); runCurrent()
        assertEquals(7, g.state.value.jumps.size); assertTrue(g.state.value.restored); assertEquals(Phase.IDLE, g.state.value.phase)
        playAll(g, pairs(RulesTest.BERGHOLT).drop(7))
        val k = flow(store); k.start(); runCurrent()
        assertEquals(Phase.RESULT, k.state.value.phase); assertEquals(Peg.Result.FULL, k.state.value.result)
    }

    @Test fun midHopProcessDeathResumesLanded() = runTest {
        val store = MemStore()
        val f = flow(store); f.start(); runCurrent()
        f.jump(h("d6"), h("d4"))
        assertEquals(1, store.saved?.jumps?.size)                                   // committed before the animation
    }

    @Test fun hintAnswersAndMarksTheGame() = runTest {
        val f = flow(); f.start(); runCurrent()
        f.setHint(true); repeat(10) { runCurrent(); advanceTimeBy(10) }
        val hint = f.state.value.hint
        assertEquals(Solver.Kind.SURE, hint?.kind); assertNotNull(hint?.jump)
        assertTrue(f.state.value.usedHint)
        f.setHint(false); assertNull(f.state.value.hint)
    }

    @Test fun welcomeEndsOnAPegThatCanHop() = runTest {
        settings = Settings(firstRunDone = false, coach = true)
        val f = flow(); f.start(); runCurrent()
        assertTrue(f.state.value.welcome); assertEquals(1, f.state.value.coach)
        f.tapHole(h("a4")); assertEquals(AlertKind.WELCOME_PICK, f.state.value.alert?.kind); assertTrue(f.state.value.welcome)
        f.tapHole(h("d6")); assertFalse(f.state.value.welcome); assertTrue(settings.firstRunDone); assertEquals(2, f.state.value.coach)
        f.tapHole(h("d4")); assertEquals(3, f.state.value.coach)
    }

    /** q1-1 in the flow: hint switched on mid-game on QA's 8-hop board -> IMPOSSIBLE shown first, then the undo-1 key. */
    @Test fun hintOnMidGameShowsImpossibleThenTheNearestUndo() = runTest {
        val f = flow(); f.start(); runCurrent()
        playAll(f, SolverTest.QA8.split(",").map { t -> t.split("-").let { h(it[0]) to h(it[1]) } })
        f.setHint(true)
        repeat(40) { runCurrent(); advanceTimeBy(10) }
        // the verdict is published (state set + logged) before the look-back starts; the key comes with the second update
        val verdict = logs.indexOfFirst { it.startsWith("SOLVE IMPOSSIBLE") }
        val back = logs.indexOfFirst { it.startsWith("SOLVE back=") }
        assertTrue("IMPOSSIBLE ($verdict) before the look-back ($back): $logs", verdict in 0 until back)
        assertEquals(1, f.state.value.hint?.backN)
        f.undoTo(1); advanceTimeBy(500); runCurrent()
        assertEquals(7, f.state.value.jumps.size)
    }

    @Test fun undoIsDimButAnswersAtZeroJumps() = runTest {
        val f = flow(); f.start(); runCurrent()
        assertFalse(f.state.value.jumps.isNotEmpty())
        f.undo(); assertEquals(AlertKind.NOTHING_TO_UNDO, f.state.value.alert?.kind)
    }
}
