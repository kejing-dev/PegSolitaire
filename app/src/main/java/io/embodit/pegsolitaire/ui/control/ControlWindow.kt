package io.embodit.pegsolitaire.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.pegsolitaire.BuildConfig
import io.embodit.pegsolitaire.R
import io.embodit.pegsolitaire.domain.ConfirmKind
import io.embodit.pegsolitaire.domain.Language
import io.embodit.pegsolitaire.domain.Peg
import io.embodit.pegsolitaire.domain.Phase
import io.embodit.pegsolitaire.domain.PlayState
import io.embodit.pegsolitaire.domain.Record
import io.embodit.pegsolitaire.domain.Settings
import io.embodit.pegsolitaire.domain.Solver
import io.embodit.pegsolitaire.domain.Start
import io.embodit.pegsolitaire.domain.TILTS
import io.embodit.pegsolitaire.platform.Graph
import io.embodit.pegsolitaire.platform.TAG
import io.embodit.pegsolitaire.ui.Page
import io.embodit.pegsolitaire.ui.Ui
import io.embodit.pegsolitaire.ui.startName
import io.embodit.pegsolitaire.ui.statusText
import io.embodit.pegsolitaire.ui.theme.PsColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer 560 x 600 dp to the right of the board): all 2D UI, three pages switched by
 * the native SpatialUI TabBar (D8): 对局 / 规则 / 设置. Every page fills the window (blank <= 25 %); confirmations are a
 * whole-page card and lock the volume. Every core action has a key here (D43): undo, hint, restart, the start boards.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val records by graph.records.all.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the ready board; a tap on a peg that can hop ends the welcome (-> game page).
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase, state.welcome) {
        if (!state.welcome && Ui.page == Page.RULES && state.phase == Phase.SELECTED && state.jumps.isEmpty()) Ui.page = Page.PLAY
    }
    LaunchedEffect(Ui.exitConfirm, Ui.clearConfirm) { graph.flow.uiLocked = Ui.exitConfirm || Ui.clearConfirm }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) { Ui.exitConfirm = false; Ui.clearConfirm = false }
        if (Ui.page != Page.RULES) Ui.historyOpen = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, records, graph)
            Page.RULES -> RulesPage(state, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: it does not see this window's localized context, so the labels are
    // resolved here (app language) and passed in as plain strings (ui_gates tabbar-lang, build.md 2026-11-05 ②).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

/** Every page: first row at the top, last row at the bottom, blocks share the height in between (blank <= 25 %). */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, records: Map<Start, Record>, graph: Graph) {
    val flow = graph.flow
    if (state.confirm != null) {
        val (q, yes) = when (state.confirm) {
            ConfirmKind.RESTART -> stringResource(R.string.confirm_restart, state.jumps.size) to stringResource(R.string.confirm_yes_restart)
            ConfirmKind.CHANGE_START -> stringResource(R.string.confirm_change, startName(state.confirmStart ?: state.start), state.jumps.size) to stringResource(R.string.confirm_yes_change)
        }
        ConfirmPage(q, stringResource(R.string.confirm_lose_line), yes, R.drawable.ic_replay, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() })
        return
    }
    val result = state.phase == Phase.RESULT
    PageColumn {
        // the one status sentence (D38) + a spinner while the solver runs long (S06)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(statusText(state), "play/status", style = PicoTheme.typography.titleLarge, color = if (state.alert != null) PsColors.ErrorText else PsColors.Ink,
                maxLines = 2, modifier = Modifier.weight(1f))
        }
        CountCard(state, records[state.start] ?: Record())
        StartStrip(state, records, graph)
        HintCard(state, graph)
        if (result) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.btn_again), "play/again", onClick = { flow.again() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_next_start), "play/next-start", onClick = { flow.nextStart() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f), dim = state.jumps.isEmpty())
                ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (result) SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_unmute), "play/mute",
                onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, icon = if (settings.sound) R.drawable.ic_volume_off else R.drawable.ic_volume_up, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = { flow.restart() }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
        }
    }
}

/** 「剩 26 · 跳 6 · 步 5」 + this start's record (design gameSpec.records). */
@Composable
private fun CountCard(state: PlayState, rec: Record) {
    Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(PsColors.TagFace).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(R.string.count_line, state.left, state.jumps.size, state.moves), "play/count", style = PicoTheme.typography.titleMedium, color = PsColors.Ink)
        Spacer(Modifier.weight(1f))
        val line = when {
            state.usedHint -> stringResource(R.string.rec_hinted)
            rec.fullSolves > 0 -> stringResource(R.string.rec_solved, rec.fullSolves, rec.bestMoves)
            rec.bestLeft > 0 -> stringResource(R.string.rec_best_left, rec.bestLeft)
            else -> stringResource(R.string.rec_none)
        }
        Txt(line, "play/record", style = PicoTheme.typography.bodyMedium, color = PsColors.InkSecondary, align = TextAlign.End)
    }
}

/** Six start boards (gate B q1 default): a small drawing, the name, its record; switching mid-game asks first (S13). */
@Composable
private fun StartStrip(state: PlayState, records: Map<Start, Record>, graph: Graph) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(stringResource(R.string.starts_label), "play/starts-label", style = PicoTheme.typography.labelMedium, color = PsColors.InkSecondary)
        Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Start.entries.forEach { s ->
                val selected = s == state.start
                val rec = records[s] ?: Record()
                // a whole tile is the touch target (104 dp tall); no Button padding so English names fit (OVERFLOW log, v0.1.0)
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                        .background(if (selected) PsColors.PrimaryContainer else PicoTheme.colorScheme.fillSecondary)
                        .then(if (selected) Modifier.border(2.dp, PsColors.Primary, RoundedCornerShape(16.dp)) else Modifier)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = startName(s)) { graph.flow.chooseStart(s) }
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MiniBoard(s.pos, Modifier.size(42.dp))
                    Txt(startName(s), "play/start-name", style = PicoTheme.typography.labelMedium, color = PsColors.Ink, align = TextAlign.Center)
                    val r = when { rec.fullSolves > 0 -> stringResource(R.string.start_rec_solved); rec.bestLeft > 0 -> stringResource(R.string.start_rec_left, rec.bestLeft); else -> stringResource(R.string.start_rec_none) }
                    Txt(r, "play/start-rec", style = PicoTheme.typography.bodySmall.copy(fontSize = 11.sp), color = PsColors.InkSecondary, align = TextAlign.Center)
                }
            }
        }
    }
}

/** A 7 x 7 cross of dots (pegs filled, holes ringed). */
@Composable
internal fun MiniBoard(pos: Long, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / 7f
        for (k in 0 until Peg.N) {
            val c = Offset((Peg.COL[k] + 0.5f) * cell, (Peg.ROW[k] + 0.5f) * cell)
            if (Peg.has(pos, k)) drawCircle(PsColors.Malachite, cell * 0.40f, c)
            else drawCircle(PsColors.InkSecondary, cell * 0.30f, c, style = Stroke(cell * 0.12f))
        }
    }
}

/** The hint conclusion line (design gameSpec.hint, three answers) / the first-game coach line / the result note. */
@Composable
private fun HintCard(state: PlayState, graph: Graph) {
    val hint = state.hint?.takeIf { state.hintOn && it.ply == state.jumps.size }
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(PsColors.TagFace.copy(alpha = 0.7f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val coach = when (state.coach) { 1 -> R.string.coach_1; 2 -> R.string.coach_2; 3 -> R.string.coach_3; else -> null }
        when {
            state.phase == Phase.RESULT -> Txt(stringResource(R.string.hint_result), "play/hint-line", style = PicoTheme.typography.bodyMedium, color = PsColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            coach != null && !state.hintOn -> Txt(stringResource(R.string.coach_line, state.coach, stringResource(coach)), "play/coach", style = PicoTheme.typography.bodyMedium, color = PsColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
            !state.hintOn -> Txt(stringResource(R.string.hint_idle), "play/hint-line", style = PicoTheme.typography.bodyMedium, color = PsColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            hint == null || hint.running -> {
                // S06: 「在算…」 only after 200 ms of solving
                val long = hint != null && android.os.SystemClock.uptimeMillis() - hint.since > 200
                if (long) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Txt(if (long) stringResource(R.string.hint_running) else "", "play/hint-line", style = PicoTheme.typography.bodyMedium, color = PsColors.InkSecondary, modifier = Modifier.weight(1f))
            }
            hint.kind == Solver.Kind.SURE -> { Dot(Color(0xFF2FA9B8)); Txt(stringResource(R.string.hint_sure), "play/hint-line", style = PicoTheme.typography.bodyMedium, color = PsColors.Ink, maxLines = 2, modifier = Modifier.weight(1f)) }
            hint.kind == Solver.Kind.UNSURE -> { Dot(Color(0xFF8C8C8C)); Txt(stringResource(R.string.hint_unsure), "play/hint-line", style = PicoTheme.typography.bodyMedium, color = PsColors.Ink, maxLines = 2, modifier = Modifier.weight(1f)) }
            else -> {
                Txt(stringResource(R.string.hint_impossible), "play/hint-line", style = PicoTheme.typography.bodyMedium, color = PsColors.ErrorText, maxLines = 2, modifier = Modifier.weight(1f))
                // q1-1: the verdict shows at once; the undo-n key appears when the look-back has finished (deterministic)
                if (hint.backRunning) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                hint.backN?.let { n -> SecondaryButton(stringResource(R.string.hint_back, n), "play/hint-back", onClick = { graph.flow.undoTo(n) }) }
            }
        }
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(12.dp).clip(CircleShape).background(c))

/** S13: a whole-page confirmation card; the volume is locked while it is open. */
@Composable
private fun ConfirmPage(question: String, line: String, yes: String, icon: Int, onYes: () -> Unit, onNo: () -> Unit) {
    PageColumn {
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(PsColors.TagFace).padding(28.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(64.dp), tint = PsColors.Ink)
            Txt(question, "confirm/q", style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp, lineHeight = 40.sp), color = PsColors.Ink, maxLines = 3)
            Txt(line, "confirm/line", style = PicoTheme.typography.bodyLarge, color = PsColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(72.dp))
                SecondaryButton(yes, "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(72.dp))
            }
        }
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, graph: Graph) {
    PageColumn {
        Column {
            Txt(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules/title", style = PicoTheme.typography.titleLarge)
            if (state.welcome) Txt(stringResource(R.string.welcome_sub), "rules/sub", style = PicoTheme.typography.bodyMedium, color = PsColors.InkSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RulesDiagram(Modifier.size(width = 160.dp, height = 112.dp))
                Txt(stringResource(R.string.diagram_caption), "rules/caption", style = PicoTheme.typography.bodySmall, color = PsColors.InkSecondary, maxLines = 2, align = TextAlign.Center, modifier = Modifier.width(160.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(PsColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = PsColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodySmall, maxLines = 4, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (Ui.historyOpen) {
            // 「来历」 expanded: covers the mistakes and the source line (design panels.rules)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PsColors.TagFace).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.history_1, R.string.history_2, R.string.history_3, R.string.history_4).forEach { id ->
                    Txt(stringResource(id), "rules/history", style = PicoTheme.typography.bodySmall, color = PsColors.Ink, maxLines = 3)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
                listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3).forEach { id ->
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(PsColors.TagFace).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = PsColors.Ink, maxLines = 2)
                    }
                }
            }
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall.copy(fontSize = 11.sp), color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.history_title) + " · " + stringResource(if (Ui.historyOpen) R.string.history_hide else R.string.history_show), "rules/history-toggle",
                onClick = { Ui.historyOpen = !Ui.historyOpen }, modifier = Modifier.weight(1f))
            // S01 [开始]: the board is already set up; this only goes to the game page and ends the welcome.
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_start else R.string.btn_back_game), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY }, modifier = Modifier.weight(1f))
        }
    }
}

/** A row of three ivory cups on malachite: the left ball hops over the middle one (dashed gilt arc) into the empty cup; the hopped ball flies up and out. */
@Composable
private fun RulesDiagram(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(PsColors.Malachite)) {
        val cup = size.height * 0.30f
        val y = size.height * 0.66f
        val xs = listOf(size.width * 0.20f, size.width * 0.50f, size.width * 0.80f)
        xs.forEach { x -> drawRect(PsColors.Ivory, Offset(x - cup / 2, y - cup / 2), Size(cup, cup)) }
        val r = cup * 0.36f
        drawCircle(PsColors.MalachiteLight, r, Offset(xs[0], y))
        drawCircle(PsColors.Tertiary, r * 1.25f, Offset(xs[0], y), style = Stroke(r * 0.18f))
        drawCircle(Color(0xFFFFB238), r * 1.25f, Offset(xs[2], y), style = Stroke(r * 0.22f))
        // the middle ball lifted out, a pink ring where it was
        drawCircle(Color(0xFFFF8A7A), r * 1.1f, Offset(xs[1], y), style = Stroke(r * 0.16f))
        drawCircle(PsColors.MalachiteLight, r, Offset(xs[1] + cup * 0.5f, y - size.height * 0.48f))
        // the hop arc (dashed gilt)
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(xs[0], y - r); quadraticTo(xs[1], y - size.height * 0.75f, xs[2], y - r) }
        drawPath(path, PsColors.Tertiary, style = Stroke(width = r * 0.18f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.5f, r * 0.35f))))
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(stringResource(R.string.confirm_exit), stringResource(R.string.confirm_exit_line), stringResource(R.string.confirm_yes_exit), R.drawable.ic_exit,
            onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    if (Ui.clearConfirm) {
        ConfirmPage(stringResource(R.string.confirm_clear), stringResource(R.string.confirm_clear_line), stringResource(R.string.confirm_yes_clear), R.drawable.ic_delete,
            onYes = { graph.flow.clearRecords(); Ui.clearConfirm = false }, onNo = { Ui.clearConfirm = false })
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Txt(stringResource(R.string.settings_title), "settings/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.height(36.dp))
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 132.dp) {
            // gate:allow tabs-segment same-page option (language) in a settings row, not a page switch
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_sound), "settings/sound", settings.sound) { v -> upd { it.copy(sound = v) } }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 132.dp) {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_reduced), "settings/reduced", settings.reducedMotion) { v -> upd { it.copy(reducedMotion = v) } }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt", labelWidth = 132.dp) {
            // gate:allow tabs-segment same-page option (board tilt 0 / 30 / 45 / 60 / 90°) in a settings row, not a page switch
            Segments(TILTS.map { it to "$it°" }, settings.tilt, "settings/tilt", { t -> upd { it.copy(tilt = t) } }, Modifier.weight(1f), small = true)
        }
        SwitchRow(stringResource(R.string.settings_coach), "settings/coach", settings.coach) { v -> upd { it.copy(coach = v) }; graph.flow.setCoach(v) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.settings_clear), "settings/clear", onClick = { Ui.clearConfirm = true; Log.i(TAG, "CONFIRM CLEAR S13") }, icon = R.drawable.ic_delete, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(132.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card.
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300)
    }, 400)
}
