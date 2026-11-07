package io.embodit.pegsolitaire.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D16 / D35 / D44 (and the 2026-11-06 tilt rule): at every tilt stop 0 / 30 / 45 / 60 / 90° everything stays inside the
 * volume; from every eye (the emulator's measured reset eyes + the design's sit / far / stand) no ball is hidden behind
 * another, every floating landing target is visible, and the counter plate never covers a hole or a ball.
 */
class LayoutTest {
    @Test fun everythingInsideTheVolumeAtEveryTilt() {
        for (t in TILTS) {
            val g = Geo(t); val v = g.volume()
            assertTrue("board $t", v.containsBox(g.boardBox(), 1e-3f))
            assertTrue("envelope $t ${g.envelopeBox()}", v.containsBox(g.envelopeBox(), 1e-3f))
            assertTrue("plate $t", v.containsBox(g.plateBox(), 1e-3f))
            assertTrue("tray $t", v.containsBox(g.trayBox(), 1e-3f))
            for (i in 0 until 31) assertTrue(v.contains(Layout.traySeat(i), 1e-3f))
        }
    }

    @Test fun noOcclusionFromAnyEyeAtAnyTilt() {
        val table = StringBuilder("tilt | eye | hidden balls | hidden targets | plate over hole/ball\n")
        for (t in TILTS) {
            val g = Geo(t)
            for ((name, eye) in Geo.eyes()) {
                val balls = g.hiddenBalls(eye, Peg.STANDARD).size
                val targets = g.hiddenTargets(eye).size
                val plate = (0 until Peg.N).count { g.plateBlocks(eye, g.hole(it)) || g.plateBlocks(eye, g.ball(it)) }
                table.append("$t | $name | $balls | $targets | $plate\n")
                assertEquals("balls t=$t $name", 0, balls)
                assertEquals("targets t=$t $name", 0, targets)
                assertEquals("plate t=$t $name", 0, plate)
            }
        }
        println(table)
    }

    @Test fun plateStandsAboveTheFarRimNeverOverAHole() {
        for (t in TILTS) {
            val g = Geo(t)
            val bottom = g.plateCentre().y - Layout.PLATE_H / 2
            assertTrue(bottom - g.farRim().y >= Layout.PLATE_GAP - 1e-4f)
            val farHoleTop = (0 until Peg.N).maxOf { g.ball(it, Layout.BALL_R).y }
            assertTrue("t=$t plate bottom above every resting ball", bottom > farHoleTop || t >= 60)
        }
    }

    @Test fun roundTripAndHoles() {
        val g = Geo(30)
        for (k in 0 until Peg.N) {
            val l = Layout.hole(k, 0.01f)
            val back = g.toLocal(g.toScene(l))
            assertTrue((back - l).len() < 1e-5f)
        }
        assertEquals(0.052f * 6, Layout.hole(Peg.parse("g4")!!).x - Layout.hole(Peg.parse("a4")!!).x, 1e-5f)
        assertTrue(Layout.HOLE_HIT < Layout.P)        // click areas never overlap (D17)
        assertTrue(Layout.MARKER <= 0.005f)          // 2026-11-06: markers <= 5 mm
    }
}
