package io.embodit.pegsolitaire.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * design acceptance.mustPass M2 (corrections C2): the standard opening solves in <= 0.5 s; mid-game samples each answer
 * within the 2 s budget (SURE / IMPOSSIBLE, or UNSURE when the budget runs out) and never later than 2.5 s; every start
 * board is solvable; answers are deterministic. Times are JVM on the build machine (the headset is slower: see ui-audit).
 */
class SolverTest {
    @Test fun standardOpeningIsSolvedFast() {
        Solver().solve(Peg.STANDARD) // warm up the JIT
        val a = Solver().solve(Peg.STANDARD)
        println("BENCH standard ${a.kind} nodes=${a.nodes} ms=${a.ms} failed=${a.failed}")
        assertEquals(Solver.Kind.SURE, a.kind)
        assertEquals(31, a.line.size)
        assertTrue("${a.ms} ms", a.ms <= 500)
        val end = a.line.fold(Peg.STANDARD) { p, j -> assertTrue(Peg.legal(p, j)); Peg.apply(p, j) }
        assertEquals(Peg.Result.FULL, Peg.result(end))
    }

    @Test fun everyStartBoardIsSolvable() {
        for (s in Start.entries) {
            val a = Solver().solve(s.pos)
            println("BENCH start ${s.id} pegs=${s.pegs} ${a.kind} nodes=${a.nodes} ms=${a.ms}")
            assertEquals(s.id, Solver.Kind.SURE, a.kind)
        }
    }

    @Test fun midGameSamplesAnswerWithinBudget() {
        val kinds = HashMap<Solver.Kind, Int>()
        var worst = 0L
        for (seed in 1..10) {
            val r = Random(seed)
            var p = Peg.STANDARD
            repeat(4 + seed) { val l = Peg.legalJumps(p); if (l.isNotEmpty()) p = Peg.apply(p, l[r.nextInt(l.size)]) }
            val a = Solver().solve(p)
            println("BENCH mid seed=$seed left=${Peg.count(p)} ${a.kind} nodes=${a.nodes} ms=${a.ms} failed=${a.failed} hex=${Peg.hex(p)}")
            kinds[a.kind] = (kinds[a.kind] ?: 0) + 1
            worst = maxOf(worst, a.ms)
            assertTrue(a.ms <= 2500)
            if (a.kind != Solver.Kind.IMPOSSIBLE) assertNotNull(a.jump)
        }
        println("BENCH mid worst=$worst kinds=$kinds")
    }

    @Test fun deterministicAndCancellable() {
        assertEquals(Solver().solve(Peg.STANDARD).line, Solver().solve(Peg.STANDARD).line)
        val c = Solver().solve(Peg.STANDARD) { true }
        assertTrue(c.kind == Solver.Kind.UNSURE || c.kind == Solver.Kind.SURE)
    }

    @Test fun provesADeadBoard() {
        // two pegs far apart: no full solve
        val p = Peg.bit(0) or Peg.bit(32)
        assertEquals(Solver.Kind.IMPOSSIBLE, Solver().solve(p).kind)
    }
}
