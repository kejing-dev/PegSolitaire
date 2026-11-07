package io.embodit.pegsolitaire.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance.mustPass M1: 76 jumps, 4 opening jumps, three endings, hop / move counting (Bergholt 31 / 18), 8 symmetries. */
class RulesTest {
    private fun pairs(s: String) = s.split(";").map { p -> p.split("-").let { it[0].toInt() to it[1].toInt() } }

    @Test fun board() {
        assertEquals(33, Peg.N)
        assertEquals("d4", Peg.name(Peg.CENTER))
        assertEquals(Peg.CENTER, Peg.parse("d4"))
        assertEquals(76, Peg.JUMPS.size)
        assertEquals(32, Peg.count(Peg.STANDARD))
        assertEquals(4, Peg.legalJumps(Peg.STANDARD).size)
        assertTrue(Peg.legalJumps(Peg.STANDARD).all { it.to == Peg.CENTER })
    }

    @Test fun onlyStraightOverAPegIntoAHole() {
        val p = Peg.STANDARD
        assertNull(Peg.jumpOf(p, Peg.parse("c2")!!, Peg.parse("d4")!!))           // not a line
        assertNotNull(Peg.jumpOf(p, Peg.parse("b4")!!, Peg.parse("d4")!!))
        assertNull(Peg.jumpOf(p, Peg.parse("d1")!!, Peg.parse("d4")!!))           // three apart
        val after = Peg.apply(p, Peg.jumpOf(p, Peg.parse("d6")!!, Peg.parse("d4")!!)!!)
        assertNull(Peg.jumpOf(after, Peg.parse("d7")!!, Peg.parse("d5")!!))       // over an empty hole
        assertNotNull(Peg.jumpOf(after, Peg.parse("d3")!!, Peg.parse("d5")!!))    // over the peg that just landed into the hole it left
    }

    @Test fun bergholtIs31Hops18MovesAndAFullSolve() {
        val j = SaveCodec.replay(Start.STANDARD.pos, pairs(BERGHOLT))!!
        assertEquals(31, j.size)
        assertEquals(18, Peg.moves(j))
        val end = j.fold(Start.STANDARD.pos) { p, x -> Peg.apply(p, x) }
        assertEquals(Peg.Result.FULL, Peg.result(end))
    }

    @Test fun threeEndings() {
        val stuck = SaveCodec.replay(Start.STANDARD.pos, pairs(STUCK6))!!.fold(Start.STANDARD.pos) { p, x -> Peg.apply(p, x) }
        assertEquals(Peg.Result.STUCK, Peg.result(stuck)); assertEquals(26, Peg.count(stuck))
        val one = SaveCodec.replay(Start.STANDARD.pos, pairs(OFF_CENTRE))!!.fold(Start.STANDARD.pos) { p, x -> Peg.apply(p, x) }
        assertEquals(Peg.Result.ONE, Peg.result(one))
        assertNull(Peg.result(Peg.STANDARD))
    }

    @Test fun movesCountTheSamePegInARowOnce() {
        val p0 = Peg.STANDARD
        val a = Peg.jumpOf(p0, Peg.parse("d6")!!, Peg.parse("d4")!!)!!
        val p1 = Peg.apply(p0, a)
        val b = Peg.jumpOf(p1, Peg.parse("f5")!!, Peg.parse("d5")!!)!!
        val p2 = Peg.apply(p1, b)
        val c = Peg.jumpOf(p2, Peg.parse("d5")!!, Peg.parse("d7")!!) ?: Peg.legalJumps(p2).first()
        assertEquals(1, Peg.moves(listOf(a)))
        assertEquals(2, Peg.moves(listOf(a, b)))
        if (c.from == b.to) assertEquals(2, Peg.moves(listOf(a, b, c)))
    }

    @Test fun eightSymmetries() {
        val seen = HashSet<Long>()
        for (t in 0 until 8) {
            assertTrue((0 until Peg.N).all { Peg.PERM[t][it] >= 0 })
            assertEquals(Peg.CENTER, Peg.PERM[t][Peg.CENTER])
            assertEquals(33, Peg.PERM[t].toSet().size)
            val corner = Peg.transform(Peg.bit(0), t)
            seen += corner
            // jumps map to jumps
            for (j in Peg.JUMPS) assertTrue(Peg.JUMPS.any { it.from == Peg.PERM[t][j.from] && it.over == Peg.PERM[t][j.over] && it.to == Peg.PERM[t][j.to] })
        }
        assertEquals(8, seen.size)
        val p = SaveCodec.replay(Start.STANDARD.pos, pairs(STUCK6))!!.fold(Start.STANDARD.pos) { q, x -> Peg.apply(q, x) }
        val c = Peg.canonical(p)
        for (t in 0 until 8) assertEquals(c, Peg.canonical(Peg.transform(p, t)))
    }

    @Test fun positionClassMatchesTheCentreForEveryStart() {
        for (s in Start.entries) assertTrue(s.id, Peg.classAllows(s.pos, Peg.CENTER))
        assertFalse(Peg.classAllows(Peg.bit(0), Peg.CENTER))
    }

    @Test fun placementTracksBallsAndTray() {
        val j = SaveCodec.replay(Start.STANDARD.pos, pairs(BERGHOLT))!!
        val p = Placement.of(Start.STANDARD.pos, j)
        assertEquals(31, p.tray.size)
        assertEquals(1, p.holeOf.count { it >= 0 })
        assertEquals(Peg.CENTER, p.holeOf.first { it >= 0 })
        val d = Placement.of(Start.DIAMOND.pos, emptyList())
        assertEquals(32 - 24, d.tray.size)
        assertTrue(Layout.PILE.size >= 31)
    }

    @Test fun saveCodecRoundTripsAndRejectsIllegal() {
        val j = SaveCodec.replay(Start.STANDARD.pos, pairs(BERGHOLT).take(9))!!
        val g = SavedGame(Start.STANDARD, j, hintOn = true, usedHint = true, elapsedMs = 1234)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("ps1|standard|0|0|0|16-4"))
        assertNull(SaveCodec.decode("garbage"))
    }

    companion object {
        const val BERGHOLT = "4-16;11-9;2-10;17-5;29-17;26-24;23-25;12-26;26-24;8-10;6-8;21-23;23-25;25-11;11-9;9-7;30-22;15-27;32-30;30-22;20-6;6-8;0-2;2-10;10-24;3-15;15-27;27-29;29-17;17-15;14-16"
        const val STUCK6 = "4-16;23-9;14-16;17-15;19-17;31-23"
        const val OFF_CENTRE = "4-16;7-9;0-8;2-0;21-7;10-2;12-10;26-12;25-11;17-5;12-10;9-11;2-10;11-9;23-25;32-24;25-23;23-21;20-22;6-20;31-23;23-21;30-22;15-27;20-22;27-15;15-3;0-8;16-4;7-9;9-1"
    }
}
