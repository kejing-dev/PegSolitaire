package io.embodit.pegsolitaire.ui.board

import io.embodit.pegsolitaire.domain.FlowTest
import io.embodit.pegsolitaire.domain.GameFlow
import io.embodit.pegsolitaire.domain.Peg
import io.embodit.pegsolitaire.domain.Phase
import io.embodit.pegsolitaire.domain.Placement
import io.embodit.pegsolitaire.domain.Settings
import io.embodit.pegsolitaire.domain.Start
import io.embodit.pegsolitaire.ui.board.input.parseLoggedPath
import io.embodit.pegsolitaire.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * build.md「命中目标 -> 格子必须有回放单测」(斗兽棋 v0.1.3: every piece tap resolved to square=-). Real hit chains (the hit
 * entity's name first, then its parents, as the `path=` of a BoardInput TAP line) for every kind of target -- a ball's GLB
 * mesh, the ball, a hole holder, its 5 mm marker, a floating target ring, the board -- go through the template's pure
 * resolver and the same dispatch the volume uses, and must reach the right hole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardTargetTest {
    private val boardParents = listOf("boardRoot", "tiltRoot", "sceneRoot", "root")
    private val sceneParents = listOf("sceneRoot", "root")
    private fun target(chain: List<String>) = resolvePath(chain, BoardNames::resolveName)
    private fun square(chain: List<String>, p: Placement) = BoardNames.squareFor(target(chain), p.holeOf)

    private fun TestScope.flow(): GameFlow = GameFlow(backgroundScope, StandardTestDispatcher(testScheduler), { testScheduler.currentTime },
        FlowTest.MemStore(), FlowTest.MemRecords(), { Settings(firstRunDone = true, coach = false) })

    @Test fun everyHitOfTheOpeningResolvesToItsHole() {
        val p = Placement.of(Start.STANDARD.pos, emptyList())
        for (k in 0 until Peg.N) {
            assertEquals(k, square(listOf(BoardNames.hole(k)) + boardParents, p))
            assertEquals(k, square(listOf("mk:${BoardNames.hole(k)}:0", BoardNames.hole(k)) + boardParents, p))
            assertEquals(k, square(listOf("targetRing:2", BoardNames.hole(k)) + boardParents, p))
            val id = p.idAt[k]
            if (id < 0) continue
            assertEquals(k, square(listOf(BoardNames.peg(id)) + sceneParents, p))
            assertEquals(k, square(listOf("ball_${id % 6}", BoardNames.peg(id)) + sceneParents, p))
            assertEquals(k, square(listOf("Mesh_0", "ball_${id % 6}", BoardNames.peg(id)) + sceneParents, p))
        }
    }

    @Test fun ballsFollowTheirHopsAndTrayBallsAreNoTarget() {
        val jumps = listOf(Peg.jumpOf(Start.STANDARD.pos, Peg.parse("d6")!!, Peg.parse("d4")!!)!!)
        val p = Placement.of(Start.STANDARD.pos, jumps)
        val mover = Placement.of(Start.STANDARD.pos, emptyList()).idAt[Peg.parse("d6")!!]
        val hopped = Placement.of(Start.STANDARD.pos, emptyList()).idAt[Peg.parse("d5")!!]
        assertEquals(Peg.parse("d4"), square(listOf("Mesh_0", "ball_0", BoardNames.peg(mover)) + sceneParents, p))
        assertNull(square(listOf(BoardNames.peg(hopped)) + sceneParents, p))
    }

    @Test fun boardTrayPlateAndBadNamesAreNotTargets() {
        for (chain in listOf(listOf("boardModel") + boardParents, listOf("tray", "sceneRoot"), listOf("counterPlate"), listOf("hole:33"), listOf("peg:32"), listOf("hole:-1"))) {
            assertNull(chain.toString(), target(chain))
        }
    }

    /** Paths in the exact `path=` format the template logs (a headset input-log would be pasted here). */
    @Test fun replayLoggedPaths() {
        val p = Placement.of(Start.STANDARD.pos, emptyList())
        val logged = mapOf(
            "Mesh_0 < ball_3 < peg:5 < sceneRoot < root" to p.holeOf[5],
            "mk:hole:16:0 < hole:16 < boardRoot < tiltRoot < sceneRoot < root" to 16,
            "hole:9 < boardRoot < tiltRoot < sceneRoot < root" to 9,
        )
        for ((path, k) in logged) assertEquals(path, k, square(parseLoggedPath(path), p))
    }

    /** The dispatch entry the volume uses: tap a ball (its mesh), then the floating target ring -> a real hop. */
    @Test fun dispatchedHitsMakeAHop() = runTest {
        val f = flow(); f.start(); runCurrent()
        val p = f.state.value.placement
        val d6 = Peg.parse("d6")!!; val d4 = Peg.parse("d4")!!
        BoardNames.dispatch(f, target(listOf("Mesh_0", "ball_1", BoardNames.peg(p.idAt[d6])) + sceneParents))
        assertEquals(Phase.SELECTED, f.state.value.phase)
        BoardNames.dispatch(f, target(listOf("targetRing:0", BoardNames.hole(d4)) + boardParents))
        assertEquals(Phase.JUMPING, f.state.value.phase)
        advanceTimeBy(600); runCurrent()
        assertEquals(31, f.state.value.left)
    }
}
