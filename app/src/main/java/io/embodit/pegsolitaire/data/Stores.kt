package io.embodit.pegsolitaire.data

import android.content.Context
import android.util.Log
import io.embodit.pegsolitaire.domain.DEFAULT_TILT
import io.embodit.pegsolitaire.domain.GameStore
import io.embodit.pegsolitaire.domain.Language
import io.embodit.pegsolitaire.domain.Record
import io.embodit.pegsolitaire.domain.RecordStore
import io.embodit.pegsolitaire.domain.SaveCodec
import io.embodit.pegsolitaire.domain.SavedGame
import io.embodit.pegsolitaire.domain.Settings
import io.embodit.pegsolitaire.domain.Start
import io.embodit.pegsolitaire.domain.TILTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt (start + jump list), written atomically after every jump: a temp file, a read-back check, then a rename;
 * the previous good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText())?.let { it.jumps == game.jumps && it.start == game.start } == true) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("PegSolitaire", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** Records per start board in SharedPreferences, exposed as a StateFlow so the window recomposes after each game. */
class PrefsRecordStore(context: Context) : RecordStore {
    private val prefs = context.getSharedPreferences("records", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(readAll())
    val all: StateFlow<Map<Start, Record>> = _all.asStateFlow()

    private fun read(s: Start) = Record(
        played = prefs.getInt("${s.id}.played", 0),
        bestLeft = prefs.getInt("${s.id}.bestLeft", 0),
        fullSolves = prefs.getInt("${s.id}.fullSolves", 0),
        bestMoves = prefs.getInt("${s.id}.bestMoves", 0),
    )

    private fun readAll(): Map<Start, Record> = Start.entries.associateWith { read(it) }

    override fun get(start: Start): Record = _all.value[start] ?: Record()

    override fun put(start: Start, r: Record) {
        prefs.edit().putInt("${start.id}.played", r.played).putInt("${start.id}.bestLeft", r.bestLeft)
            .putInt("${start.id}.fullSolves", r.fullSolves).putInt("${start.id}.bestMoves", r.bestMoves).apply()
        _all.value = _all.value + (start to r)
    }

    override fun clear() {
        prefs.edit().clear().apply()
        _all.value = readAll()
    }

    fun refresh() { _all.value = readAll() }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putInt("tilt", s.tilt)
            .putBoolean("coach", s.coach)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putString("lastStart", s.lastStart)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            tilt = prefs.getInt("tilt", DEFAULT_TILT).takeIf { it in TILTS } ?: DEFAULT_TILT,
            coach = prefs.getBoolean("coach", true),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            lastStart = prefs.getString("lastStart", Start.STANDARD.id)!!,
        )
    }.getOrElse { Settings() }
}
