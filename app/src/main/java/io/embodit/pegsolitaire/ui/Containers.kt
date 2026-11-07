package io.embodit.pegsolitaire.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "PegBoard"
    const val CONTROL = "PegControl"
    const val CONTROL_TAG = "control"
}

/** The three TabBar pages (design panels: 对局 / 规则 / 设置) and the design state each one is (S11 settings, S12 rules). */
enum class Page(val stateId: String) { PLAY("S03"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
    var clearConfirm by mutableStateOf(false)
    var historyOpen by mutableStateOf(false)
}
