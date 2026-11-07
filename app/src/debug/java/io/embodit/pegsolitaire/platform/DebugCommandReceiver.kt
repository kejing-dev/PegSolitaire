package io.embodit.pegsolitaire.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.pegsolitaire.domain.Language
import io.embodit.pegsolitaire.domain.Peg
import io.embodit.pegsolitaire.domain.Phase
import io.embodit.pegsolitaire.domain.Settings
import io.embodit.pegsolitaire.domain.Start
import io.embodit.pegsolitaire.domain.TILTS
import io.embodit.pegsolitaire.ui.Page
import io.embodit.pegsolitaire.ui.Ui
import io.embodit.pegsolitaire.ui.board.BoardNames
import io.embodit.pegsolitaire.ui.board.BoardTargets
import io.embodit.pegsolitaire.ui.board.SceneHolder
import io.embodit.pegsolitaire.ui.board.input.BoardInputLog
import io.embodit.pegsolitaire.ui.board.input.InputSource
import io.embodit.pegsolitaire.ui.board.input.resolveUp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.pegsolitaire.DEBUG -p io.embodit.pegsolitaire --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00|S01|S03|S04|S05 [ms]|S06|S09 full|one|stuck|S11|S12|S13 restart|change|exit|clear|S16
 *   tap <d4 | 0..32 | empty>          a pinch on that hole (BoardNames.dispatch, like the volume listener)
 *   replay <hole> [hand-ray|eye-hand|controller]   real scene entities -> resolveUp -> the template listener's onTap
 *   jump <from> <to> · script bergholt|stuck6|offcentre [ms between hops] · undo · undoto N · restart · again
 *   start <standard|diamond|plus|fireplace|pyramid|arrow> (chooseStart: asks first mid-game) · new <id> (no question)
 *   hint on|off · lang zh|en|system · tilt 0|30|45|60|90 · sound on|off · volume 0-2 · reduced on|off · coach on|off
 *   load <hex> · freeze MS|off · s00hold on|off · welcome · page play|rules|settings · history on|off
 *   confirm · no · exitask · clearask · reopen · closepanel · hover <hole> · dump|state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    companion object {
        /** Bergholt 1912, 18 moves (Wikipedia notation e-x l-j c-k …, converted to hole indices; RulesTest checks it). */
        const val BERGHOLT = "4-16;11-9;2-10;17-5;29-17;26-24;23-25;12-26;26-24;8-10;6-8;21-23;23-25;25-11;11-9;9-7;30-22;15-27;32-30;30-22;20-6;6-8;0-2;2-10;10-24;3-15;15-27;27-29;29-17;17-15;14-16"
        /** The shortest way to get stuck from the standard opening (6 hops, 26 left). */
        const val STUCK6 = "4-16;23-9;14-16;17-15;19-17;31-23"
        /** A full line ending with one peg on d7 (one left, off centre). */
        const val OFF_CENTRE = "4-16;7-9;0-8;2-0;21-7;10-2;12-10;26-12;25-11;17-5;12-10;9-11;2-10;11-9;23-25;32-24;25-23;23-21;20-22;6-20;31-23;23-21;30-22;15-27;20-22;27-15;15-3;0-8;16-4;7-9;9-1"

        fun pairs(s: String): List<Pair<Int, Int>> = s.split(";").map { p -> p.split("-").let { it[0].toInt() to it[1].toInt() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun hole(s: String): Int? = Peg.parse(s)
        fun fresh(start: Start = Start.STANDARD) { flow.freeze(null); Ui.page = Page.PLAY; flow.newGame(start, animate = false) }
        /** Replays from-to pairs instantly (no motion) through the real jump path, then lets the last hop animate. */
        fun play(list: List<Pair<Int, Int>>, gapMs: Long = 0L, then: () -> Unit = {}) {
            graph.scope.launch {
                for ((f, t) in list) {
                    while (flow.state.value.phase == Phase.JUMPING) delay(20)
                    if (!flow.jump(f, t)) { Log.w(TAG, "SCRIPT stop at $f-$t: ${flow.dump()}"); break }
                    if (gapMs > 0) delay(gapMs)
                }
                while (flow.state.value.phase == Phase.JUMPING) delay(20)
                then()
            }
        }
        when (cmd) {
            "goto" -> {
                val f = a.split(" ")
                Ui.exitConfirm = false; Ui.clearConfirm = false
                when (f[0].uppercase()) {
                    "S00" -> { context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", true).apply(); Log.i(TAG, "S00: force-stop + launch now, capture within 10 s, then s00hold off") }
                    "S01" -> { settings { it.copy(firstRunDone = false) }; flow.debugState { it.copy(welcome = false) }; fresh(); flow.debugState { it.copy(welcome = true, coach = if (graph.settings.value.coach) 1 else 0) }; Ui.page = Page.RULES }
                    "S03" -> fresh()
                    "S04" -> { fresh(); flow.tapHole(Peg.parse("d6")!!) }
                    "S05" -> { fresh(); flow.freeze(f.getOrNull(1)?.toLongOrNull() ?: 150L); flow.jump(Peg.parse("d6")!!, Peg.parse("d4")!!) }
                    "S06" -> { fresh(); flow.setHint(true) }
                    "S09" -> {
                        fresh()
                        when (f.getOrNull(1) ?: "full") {
                            "stuck" -> play(pairs(STUCK6))
                            "one" -> play(pairs(OFF_CENTRE))
                            else -> play(pairs(BERGHOLT))
                        }
                    }
                    "S11" -> Ui.page = Page.SETTINGS
                    "S12" -> Ui.page = Page.RULES
                    "S13" -> when (f.getOrNull(1) ?: "restart") {
                        "exit" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
                        "clear" -> { Ui.page = Page.SETTINGS; Ui.clearConfirm = true }
                        "change" -> { fresh(); play(pairs(BERGHOLT).take(5)) { flow.chooseStart(Start.DIAMOND) } }
                        else -> { fresh(); play(pairs(BERGHOLT).take(12)) { flow.restart() } }
                    }
                    "S16" -> Log.i(TAG, "S16: make some hops, then force-stop and relaunch")
                    else -> Log.w(TAG, "goto ? $a")
                }
            }
            "tap" -> if (a == "empty") flow.tapEmpty() else hole(a)?.let { BoardNames.dispatch(flow, BoardNames.Hole(it)) }
            "replay" -> {
                // build.md 命中目标 -> 格子: resolve the real entities a ray lands on for that hole (holder, marker, floating
                // target, ball, its mesh) with the template's resolveUp, log the square, then hand the deepest one to the
                // same BoardInputListener.onTap the template calls -- not the dispatch shortcut above.
                val f = a.split(" ")
                val k = hole(f[0]) ?: return
                val scene = SceneHolder.scene; val listener = SceneHolder.listener
                if (scene == null || listener == null) { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: BoardNames.Target? = null
                for ((what, e) in scene.replayEntities(k)) {
                    val (t, path) = resolveUp(e, BoardTargets)
                    val sq = BoardNames.squareFor(t, flow.state.value.placement.holeOf)
                    BoardInputLog.write(TAG, "REPLAY ${f[0]} src=${src.label} $what path=$path hit=$t -> square=${sq?.let { Peg.name(it) } ?: "-"}")
                    if (t != null) last = t
                }
                listener.onTap(last, src, null)
            }
            "jump" -> { val f = a.split(" ", "-"); val x = hole(f[0]); val y = f.getOrNull(1)?.let { hole(it) }; if (x != null && y != null) flow.jump(x, y) }
            "script" -> {
                val f = a.split(" ")
                val gap = f.getOrNull(1)?.toLongOrNull() ?: 0L
                fresh()
                play(pairs(when (f[0]) { "stuck6" -> STUCK6; "offcentre", "lastOffCentre" -> OFF_CENTRE; else -> BERGHOLT }), gap)
            }
            "undo" -> flow.undo()
            "undoto" -> a.toIntOrNull()?.let { flow.undoTo(it) }
            "restart" -> flow.restart()
            "again" -> flow.again()
            "start" -> flow.chooseStart(Start.of(a))
            "new" -> { flow.freeze(null); flow.newGame(Start.of(a.ifBlank { null })) }
            "hint" -> flow.setHint(on)
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "tilt" -> a.toIntOrNull()?.takeIf { it in TILTS }?.let { t -> settings { it.copy(tilt = t) } }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> { settings { it.copy(coach = on) }; flow.setCoach(on) }
            "load" -> Peg.parsePos(a)?.let { flow.loadBoard(it) }
            "freeze" -> flow.freeze(a.toLongOrNull())
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "welcome" -> { settings { it.copy(firstRunDone = false) }; fresh(); flow.debugState { it.copy(welcome = true, coach = 1) }; Ui.page = Page.RULES }
            "page" -> Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "history" -> Ui.historyOpen = on
            "confirm" -> when { Ui.exitConfirm -> Log.i(TAG, "exit confirm: tap it for real"); Ui.clearConfirm -> { flow.clearRecords(); Ui.clearConfirm = false }; else -> flow.confirm() }
            "no" -> { Ui.exitConfirm = false; Ui.clearConfirm = false; flow.cancelConfirm() }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "clearask" -> { Ui.page = Page.SETTINGS; Ui.clearConfirm = true }
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "hover" -> SceneHolder.scene?.setHover(hole(a))
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} settings=${graph.settings.value}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence()}")
            else -> Log.w(TAG, "CMD unknown $cmd")
        }
    }
}
