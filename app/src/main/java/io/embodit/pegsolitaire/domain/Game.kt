package io.embodit.pegsolitaire.domain

enum class Language { SYSTEM, ZH, EN }

/** Board tilt stops (2026-11-06 rule: 0 / 30 / 45 / 60 / 90; the design's 25° default maps to 30°). */
val TILTS = intArrayOf(0, 30, 45, 60, 90)
const val DEFAULT_TILT = 30

/** App settings (SharedPreferences). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val tilt: Int = DEFAULT_TILT,
    val coach: Boolean = true,
    val firstRunDone: Boolean = false,
    val lastStart: String = Start.STANDARD.id,
)

/** Local records of one start board (design gameSpec.records; games that used the hint do not count for the bests). */
data class Record(
    val played: Int = 0,
    val bestLeft: Int = 0,
    val fullSolves: Int = 0,
    val bestMoves: Int = 0,
) {
    /** One more finished game with [left] pegs, [full] = a full solve in [moves] moves; [hinted] games only count as played. */
    fun after(left: Int, full: Boolean, moves: Int, hinted: Boolean, countPlayed: Boolean, countSolve: Boolean): Record {
        var r = if (countPlayed) copy(played = played + 1) else this
        if (hinted) return r
        if (bestLeft == 0 || left < bestLeft) r = r.copy(bestLeft = left)
        if (full) {
            if (countSolve) r = r.copy(fullSolves = r.fullSolves + 1)
            if (r.bestMoves == 0 || moves < r.bestMoves) r = r.copy(bestMoves = moves)
        }
        return r
    }
}

interface RecordStore {
    fun get(start: Start): Record
    fun put(start: Start, r: Record)
    fun clear()
}

/** What a game looks like on disk (files/game.txt): the start, the jump list and the hint flags; the board is a replay. */
data class SavedGame(
    val start: Start,
    val jumps: List<Peg.Jump>,
    val hintOn: Boolean = false,
    val usedHint: Boolean = false,
    val elapsedMs: Long = 0,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Line codec `ps1|<start>|<hintOn>|<usedHint>|<elapsedMs>|<from-to;from-to;…>` (hole indices). Decoding replays the jumps
 * from the start board and stops at null on the first illegal one, so a bad file can't smuggle in an impossible board.
 */
object SaveCodec {
    fun encode(g: SavedGame): String = listOf(
        "ps1", g.start.id, if (g.hintOn) "1" else "0", if (g.usedHint) "1" else "0", g.elapsedMs.toString(),
        g.jumps.joinToString(";") { "${it.from}-${it.to}" },
    ).joinToString("|")

    /** Replays from-to pairs from [start]; null if any hop is illegal. */
    fun replay(start: Long, pairs: List<Pair<Int, Int>>): List<Peg.Jump>? {
        var pos = start
        val out = ArrayList<Peg.Jump>()
        for ((f, t) in pairs) {
            val j = Peg.jumpOf(pos, f, t) ?: return null
            out += j
            pos = Peg.apply(pos, j)
        }
        return out
    }

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 6 || f[0] != "ps1") return null
        val start = Start.entries.firstOrNull { it.id == f[1] } ?: return null
        val pairs = if (f[5].isBlank()) emptyList() else f[5].split(";").map { p -> p.split("-").let { it[0].toInt() to it[1].toInt() } }
        val jumps = replay(start.pos, pairs) ?: return null
        SavedGame(start, jumps, f[2] == "1", f[3] == "1", f[4].toLong())
    }.getOrNull()
}

/**
 * Which ball (0..31) is where (pure, unit tested): the [start] board's pegs get ids 0..m-1 in hole order, the spare balls
 * m..31 lie in the tray from the beginning; every jump moves the jumping ball and puts the jumped one on top of the tray.
 * The scene renders exactly this, so a restored game puts every ball back where it was, tray order included.
 */
class Placement(val holeOf: IntArray, val idAt: IntArray, val tray: List<Int>) {
    companion object {
        const val BALLS = 32

        fun of(start: Long, jumps: List<Peg.Jump>): Placement {
            val idAt = IntArray(Peg.N) { -1 }
            val holeOf = IntArray(BALLS) { -1 }
            val tray = ArrayList<Int>()
            var id = 0
            for (k in 0 until Peg.N) if (Peg.has(start, k)) { idAt[k] = id; holeOf[id] = k; id++ }
            for (i in id until BALLS) tray += i
            for (j in jumps) {
                val m = idAt[j.from]
                val o = idAt[j.over]
                if (m < 0 || o < 0) break
                idAt[j.from] = -1; idAt[j.over] = -1; idAt[j.to] = m
                holeOf[m] = j.to; holeOf[o] = -1
                tray += o
            }
            return Placement(holeOf, idAt, tray)
        }
    }
}
