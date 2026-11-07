package io.embodit.pegsolitaire.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.pegsolitaire.domain.Layout
import io.embodit.pegsolitaire.ui.board.input.GazeTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The abstract marks of the volume (rings, the floating target rings and their thin light posts, the hint ghost, trail
 * dots, the contact shadow), the 5 mm hole markers and the collider shapes. The visible objects (board, tray, balls) are
 * GLBs from tools/build_models.py. Creating a resource waits on the renderer, so everything is created concurrently on
 * Dispatchers.IO. Colours: design theme (3D rings: ice cyan #9AF0FF select, amber #FFB238 target, pink #FF8A7A jumped,
 * red #FF4A3D illegal / stuck, gilt #FFD27A landing glow).
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var ring: MeshResource; private set
    lateinit var ringWide: MeshResource; private set
    lateinit var post: MeshResource; private set
    lateinit var ghost: MeshResource; private set
    lateinit var dot: MeshResource; private set
    lateinit var shadow: MeshResource; private set
    lateinit var markerParts: List<GazeTargets.Part>; private set

    lateinit var select: Material; private set
    lateinit var target: Material; private set
    lateinit var over: Material; private set
    lateinit var red: Material; private set
    lateinit var glow: Material; private set
    lateinit var hover: Material; private set
    lateinit var ghostSure: Material; private set
    lateinit var ghostUnsure: Material; private set
    lateinit var dotGold: Material; private set
    lateinit var dotSure: Material; private set
    lateinit var dotUnsure: Material; private set
    lateinit var trail: Material; private set
    lateinit var shade: Material; private set
    lateinit var marker: Material; private set

    lateinit var holeShape: ShapeResource; private set
    lateinit var targetShape: ShapeResource; private set
    lateinit var ballShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                async(io) { r.ring = MeshResource.createTorus(outerRingRadius = Layout.RING_OUT, innerRingRadius = Layout.RING_IN).keep() },
                async(io) { r.ringWide = MeshResource.createTorus(outerRingRadius = 0.031f, innerRingRadius = 0.0235f).keep() },
                // gate:allow geometry thin light post under a floating target ring (abstract indicator, template 「细光柱连到格心」)
                async(io) { r.post = MeshResource.createCylinder(radius = 0.0012f, height = 1f).keep() },
                // gate:allow geometry hint ghost ball: translucent abstract indicator (design entities3d.ghostPeg), not a visible object
                async(io) { r.ghost = MeshResource.createSphere(Layout.BALL_R).keep() },
                // gate:allow geometry trajectory dots of the hop / hint arc (abstract dashed line, design theme.signature)
                async(io) { r.dot = MeshResource.createSphere(0.0016f).keep() },
                // gate:allow geometry contact shadow disc under a ball in the air (abstract, design 「盘面影子随高度放大变淡」)
                async(io) { r.shadow = MeshResource.createCylinder(radius = Layout.BALL_R * 0.9f, height = 0.0004f).keep() },
                async(io) {
                    r.markerParts = GazeTargets.markerMeshes(Layout.P, GazeTargets.Style(fraction = Layout.MARKER / Layout.P, thick = Layout.MARKER_T, corner = Layout.MARKER * 0.45f))
                    r.markerParts.forEach { it.mesh.keep() }
                },
                async(io) { r.select = unlit(Color(0xFF9AF0FF), 0.95f).keep() },
                async(io) { r.target = unlit(Color(0xFFFFB238), 0.95f).keep() },
                async(io) { r.over = unlit(Color(0xFFFF8A7A), 0.85f).keep() },
                async(io) { r.red = unlit(Color(0xFFFF4A3D), 0.95f).keep() },
                async(io) { r.glow = unlit(Color(0xFFFFD27A), 0.85f).keep() },
                async(io) { r.hover = unlit(Color(0xFFFFFFFF), 0.55f).keep() },
                async(io) { r.ghostSure = unlit(Color(0xFF7FE3EE), 0.42f).keep() },
                async(io) { r.ghostUnsure = unlit(Color(0xFFC8C8C8), 0.36f).keep() },
                async(io) { r.dotGold = unlit(Color(0xFFFFD27A), 0.9f).keep() },
                async(io) { r.dotSure = unlit(Color(0xFF7FE3EE), 0.95f).keep() },
                async(io) { r.dotUnsure = unlit(Color(0xFFBDBDBD), 0.75f).keep() },
                async(io) { r.trail = unlit(Color(0xFF2E8A55), 0.35f).keep() },
                async(io) { r.shade = unlit(Color(0xFF000000), 0.30f).keep() },
                // the 5 mm hole marker: ivory a half step darker than the cup (#ECDAB6 -> #DCCAA4), opaque PBR (README 通用模式)
                async(io) { r.marker = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.71f, 0.62f, 0.45f, 1f)); setRoughness(0.45f) }.keep() },
                async(io) { r.holeShape = ShapeResource.createBox(Vector3(Layout.HOLE_HIT, 0.006f, Layout.HOLE_HIT)).keep() },
                async(io) { r.targetShape = ShapeResource.createBox(Vector3(0.040f, 0.008f, 0.040f)).keep() },
                async(io) { r.ballShape = ShapeResource.createSphere(Layout.BALL_R + 0.0015f).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
