package io.embodit.pegsolitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.embodit.pegsolitaire.R
import io.embodit.pegsolitaire.domain.AlertKind
import io.embodit.pegsolitaire.domain.PlayState
import io.embodit.pegsolitaire.domain.Start
import io.embodit.pegsolitaire.domain.StatusKey

@Composable
fun alertText(k: AlertKind): String = stringResource(when (k) {
    AlertKind.PICK_FIRST -> R.string.al_pick_first
    AlertKind.CANT_JUMP -> R.string.al_cant_jump
    AlertKind.NOT_LIT -> R.string.al_not_lit
    AlertKind.NOTHING_TO_UNDO -> R.string.al_nothing_to_undo
    AlertKind.WELCOME_PICK -> R.string.al_welcome_pick
})

@Composable
fun startName(s: Start): String = stringResource(when (s) {
    Start.STANDARD -> R.string.start_standard
    Start.DIAMOND -> R.string.start_diamond
    Start.PLUS -> R.string.start_plus
    Start.FIREPLACE -> R.string.start_fireplace
    Start.PYRAMID -> R.string.start_pyramid
    Start.ARROW -> R.string.start_arrow
})

/** The one status sentence of the window (D38: read from [PlayState.status], never written per screen). */
@Composable
fun statusText(st: PlayState): String {
    val s = st.status()
    return when (s.key) {
        StatusKey.NONE -> ""
        StatusKey.PICK, StatusKey.COACH_1 -> stringResource(R.string.st_pick)
        StatusKey.TAP_LIT, StatusKey.COACH_2 -> stringResource(R.string.st_tap_lit)
        StatusKey.HOP -> stringResource(R.string.st_hop)
        StatusKey.COACH_3 -> stringResource(R.string.st_coach3)
        StatusKey.RESTORED -> stringResource(R.string.st_restored)
        StatusKey.ALERT -> s.alert?.let { alertText(it) } ?: ""
        StatusKey.FULL -> if (s.n == 18) stringResource(R.string.res_full_18) else stringResource(R.string.res_full, s.n)
        StatusKey.ONE -> stringResource(R.string.res_one)
        StatusKey.STUCK -> stringResource(R.string.res_stuck, s.n)
    }
}
