package io.embodit.pegsolitaire

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.pegsolitaire.ui.Containers
import io.embodit.pegsolitaire.ui.board.BoardScreen
import io.embodit.pegsolitaire.ui.control.ControlWindow
import io.embodit.pegsolitaire.ui.theme.PegTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the board volume (default
 * container, 0.70 x 0.60 x 0.48 m from the manifest) and the control window (Planar 560 x 600 dp) to its right.
 * First open and every reopen use Placement.right(board) (profile D44: no "in front of you" in Shared Space).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PegTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 600.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            PegTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 560.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
