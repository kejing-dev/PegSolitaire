package io.embodit.pegsolitaire.ui.board

import io.embodit.pegsolitaire.domain.GameFlow
import io.embodit.pegsolitaire.domain.Peg
import io.embodit.pegsolitaire.domain.Placement

/**
 * Entity names of the board volume and what a hit on them means (pure Kotlin, unit tested by BoardTargetTest; build.md
 * 命中目标 -> 格子). Names come only from here (never "$id" inside Entity().apply {}: that is the ECS id, 斗兽棋 v0.1.3).
 *   "hole:<k>"  a hole holder (collider + interactable + hover, the 5 mm marker "mk:hole:<k>:0" under it) or a lit target's
 *               floating ring; a hit on the marker walks up to the holder (resolveUp)
 *   "peg:<i>"   ball i (the GLB clone carrying collider + interactable + hover); it means the hole it stands in
 * Anything else (board model, tray, plate) is not a target: a tap there drops the selection.
 */
object BoardNames {
    fun hole(k: Int) = "hole:$k"
    fun peg(i: Int) = "peg:$i"

    sealed interface Target
    data class Hole(val k: Int) : Target
    data class Ball(val id: Int) : Target

    /** Name -> target, null when the name is not ours or out of range (a naming bug shows up in the log instead of mapping wrong). */
    fun resolveName(name: String): Target? {
        val f = name.split(":")
        if (f.size != 2) return null
        val n = f[1].toIntOrNull() ?: return null
        return when (f[0]) {
            "hole" -> if (n in 0 until Peg.N) Hole(n) else null
            "peg" -> if (n in 0 until Placement.BALLS) Ball(n) else null
            else -> null
        }
    }

    /** Target -> hole (the one function onTap / onHover / the replay test share); a ball in the tray -> null. */
    fun squareFor(target: Target?, holeOf: IntArray): Int? = when (target) {
        is Hole -> target.k
        is Ball -> holeOf.getOrNull(target.id)?.takeIf { it >= 0 }
        null -> null
    }

    /** The one flow call for a resolved hit (the volume listener and the debug `tap` / `replay` both come through here). */
    fun dispatch(flow: GameFlow, target: Target?) {
        val st = flow.state.value
        when (val k = squareFor(target, st.placement.holeOf)) {
            null -> flow.tapEmpty()
            else -> flow.tapHole(k)
        }
    }
}
