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
        val c = Solver().solve(Peg.STANDARD, cancelled = { true })
        assertTrue(c.kind == Solver.Kind.UNSURE || c.kind == Solver.Kind.SURE)
    }

    @Test fun provesADeadBoard() {
        // two pegs far apart: no full solve
        val p = Peg.bit(0) or Peg.bit(32)
        assertEquals(Solver.Kind.IMPOSSIBLE, Solver().solve(p).kind)
    }

    /** QA round 1 q1-1: the two boards QA played (standard opening, 8 and 18 hops); nearest solvable = 1 and 11 back. */
    private fun qaBoard(s: String): List<Long> {
        var p = Peg.STANDARD
        val out = arrayListOf(p)
        for (t in s.split(",")) { val (a, b) = t.split("-"); p = Peg.apply(p, Peg.jumpOf(p, Peg.parse(a)!!, Peg.parse(b)!!)!!); out += p }
        return out
    }

    @Test fun qaBoardsImpossibleFastAndLookBackExact() {
        val b8 = qaBoard(QA8)
        val b18 = qaBoard(QA18)
        Solver().solve(Peg.STANDARD) // warm up
        for ((name, ps, want) in listOf(Triple("8", b8, 1), Triple("18", b18, 11))) {
            val t0 = System.nanoTime()
            val a = Solver().solve(ps.last())
            val t1 = System.nanoTime()
            val n1 = nearestSolvable(ps)
            val t2 = System.nanoTime()
            val n2 = nearestSolvable(ps)
            val verdictMs = (t1 - t0) / 1_000_000; val backMs = (t2 - t1) / 1_000_000
            println("BENCH qa$name verdict=${a.kind} ${verdictMs} ms nodes=${a.nodes}; back=$n1 in $backMs ms (again: $n2)")
            assertEquals(Solver.Kind.IMPOSSIBLE, a.kind)
            assertTrue("verdict $verdictMs ms", verdictMs <= 2500)
            assertEquals(want, n1)
            assertEquals(n1, n2)                                  // deterministic: node budgets, no wall clock
            assertTrue("verdict + look-back $verdictMs + $backMs ms", verdictMs + backMs <= 2500)
            // the board n back really is solvable and the one before it (n - 1) is not
            assertEquals(Solver.Kind.SURE, Solver().solve(ps[ps.size - 1 - want]).kind)
            if (want > 1) assertEquals(Solver.Kind.IMPOSSIBLE, Solver().solve(ps[ps.size - want]).kind)
        }
    }

    companion object {
        const val QA8 = "b4-d4,c2-c4,e2-c2,d4-d2,f3-d3,d2-d4,a3-c3,d4-b4"
        const val QA18 = "b4-d4,c2-c4,e2-c2,d4-d2,f3-d3,d2-d4,a3-c3,d4-b4,f5-f3,a5-a3,g3-e3,c6-c4,e6-c6,e4-e6,c7-c5,g5-g3,e7-e5,c4-a4"
    }
}
