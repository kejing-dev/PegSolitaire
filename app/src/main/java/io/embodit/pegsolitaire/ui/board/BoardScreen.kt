package io.embodit.pegsolitaire.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.pegsolitaire.R
import io.embodit.pegsolitaire.domain.Layout
import io.embodit.pegsolitaire.domain.Peg
import io.embodit.pegsolitaire.domain.Phase
import io.embodit.pegsolitaire.domain.PlayState
import io.embodit.pegsolitaire.platform.Graph
import io.embodit.pegsolitaire.platform.TAG
import io.embodit.pegsolitaire.ui.board.input.BoardInputConfig
import io.embodit.pegsolitaire.ui.board.input.BoardInputListener
import io.embodit.pegsolitaire.ui.board.input.InputSource
import io.embodit.pegsolitaire.ui.board.input.TargetResolver
import io.embodit.pegsolitaire.ui.board.input.boardInput
import io.embodit.pegsolitaire.ui.theme.PsColors
import io.embodit.pegsolitaire.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    @Volatile var listener: BoardListener? = null
}

private const val PLATE = "counterPlate"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board,
 * and routes every pinch / trigger on a hole / ball through the board-input template to [GameFlow].
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    val listener = remember(graph) { BoardListener(graph, scene) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        SceneHolder.listener = listener
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // D50: the factory board-input template (taps + short drags the system reports as drags, targets resolved up
            // the parent chain) for hand ray, controller ray and eye-hand alike.
            .boardInput(BoardTargets, listener, BoardInputConfig(logTag = TAG)),
        attachments = {
            AttachmentPanel(PLATE) { PlateRoot(graph) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle 「首帧兜底 平圆盘」), removed when board.glb is in
                    val disc = MeshResource.createCylinder(radius = Layout.BOARD_R, height = Layout.BOARD_T)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.03f, 0.18f, 0.09f, 1f)); setRoughness(0.5f) }
                    disc to mat
                }.getOrNull()
            }
            quick?.let { (s, m) -> scene.showFallback(s, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                Models(load("board"), load("tray"), (0 until 6).map { load("ball_$it") })
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            if (models != null) scene.install(resources, models, attachments.entity(PLATE))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Hit entity (or an ancestor) -> the target the game knows, by name only (BoardNames; replayed by BoardTargetTest). */
internal val BoardTargets: TargetResolver<BoardNames.Target> = TargetResolver.byName { BoardNames.resolveName(it) }

/** Every resolved tap goes through [BoardNames.dispatch] (the same call the debug `tap` / `replay` make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<BoardNames.Target> {
    override fun onTap(target: BoardNames.Target?, source: InputSource, position: Offset3D?) {
        val k = BoardNames.squareFor(target, graph.flow.state.value.placement.holeOf)
        io.embodit.pegsolitaire.ui.board.input.BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${k?.let { Peg.name(it) } ?: "-"}")
        BoardNames.dispatch(graph.flow, target)
    }

    /** D50 hover: a light ring on the hole the pointer rests on (never changes state). */
    override fun onHover(target: BoardNames.Target?, source: InputSource) =
        scene.setHover(BoardNames.squareFor(target, graph.flow.state.value.placement.holeOf))
}

/** The plate is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun PlateRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { CounterPlate(st, graph) }
    }
}

@Composable
private fun plateText(text: String, tag: String, size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = PsColors.Ink) {
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/**
 * Counter plate (design spatialLayout.attachments, 100 x 40 mm): only 「剩 n」 / "n left" (CH3; moves / jumps / records live
 * in the window). No buttons except "open panel" after the system ×.
 */
@Composable
private fun CounterPlate(st: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        Modifier.size(Layout.PLATE_W_DP.dp, Layout.PLATE_H_DP.dp).clip(RoundedCornerShape(12.dp)).background(PsColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> plateText(stringResource(R.string.level_hint), "plate/level", 14)
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("plate") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 14.sp)
            }
            st.phase == Phase.LOADING -> Unit
            else -> plateText(stringResource(R.string.plate_left, st.left), "plate/left", 26, FontWeight.Bold)
        }
    }
}
