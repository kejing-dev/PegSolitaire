package io.embodit.pegsolitaire.domain

/**
 * The hint solver (design gameSpec.solver, corrections C2): depth-first search over 33-bit boards for a line of jumps that
 * ends with one peg in [target] (the centre). Deterministic (fixed jump order: landing nearest the centre first), cancellable,
 * budgeted (nodes, wall time, failure-table size). Pruning: the position-class invariant (Peg.classAllows, exact and free)
 * and a table of positions already proven dead, keyed by their canonical symmetric image (8 transforms).
 *
 * Answers: SURE (a full solve exists from here; [jump] is its first hop, [line] the whole line), IMPOSSIBLE (the search
 * finished without one), UNSURE (budget ran out; [jump] is the first legal hop in the search order).
 * Pure Kotlin, no Android: run it on Dispatchers.Default, cancel through [cancelled].
 */
class Solver(
    val maxNodes: Long = 3_000_000,
    val maxMs: Long = 2000,
    val maxFailed: Int = 1_000_000,
    val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class Kind { SURE, IMPOSSIBLE, UNSURE }

    data class Answer(val kind: Kind, val jump: Peg.Jump?, val line: List<Peg.Jump>, val nodes: Long, val ms: Long, val failed: Int)

    private class Budget : RuntimeException(null, null, false, false)

    /** Jump indices in search order: pegs furthest from the centre move first, then landing nearest it, ties by index (deterministic; JVM bench in RulesTest / AGENTS.md). */
    private val ORDER: IntArray = Peg.JUMPS.indices.sortedWith(compareBy({ -dist(Peg.JUMPS[it].from) }, { dist(Peg.JUMPS[it].to) }, { it })).toIntArray()

    private fun dist(k: Int): Int = Math.abs(Peg.COL[k] - 3) + Math.abs(Peg.ROW[k] - 3)

    fun solve(start: Long, target: Int = Peg.CENTER, cancelled: () -> Boolean = { false }): Answer {
        val t0 = clock()
        val failed = LongOpenHashSet(maxFailed)
        var nodes = 0L
        val line = ArrayList<Peg.Jump>()
        val symmetric = target == Peg.CENTER

        fun key(p: Long) = if (symmetric) Peg.canonical(p) else p

        fun dfs(pos: Long): Boolean {
            if (Peg.count(pos) == 1) return Peg.has(pos, target)
            if (!Peg.classAllows(pos, target)) return false
            val k = key(pos)
            if (failed.contains(k)) return false
            nodes++
            if (nodes and 4095L == 0L && (cancelled() || clock() - t0 > maxMs)) throw Budget()
            if (nodes > maxNodes) throw Budget()
            for (i in ORDER) {
                if (!Peg.legalAt(pos, i)) continue
                val j = Peg.JUMPS[i]
                line.add(j)
                if (dfs(pos xor j.mask)) return true
                line.removeAt(line.size - 1)
            }
            if (!failed.add(k)) throw Budget()
            return false
        }

        val kind = try {
            if (dfs(start)) Kind.SURE else Kind.IMPOSSIBLE
        } catch (_: Budget) {
            Kind.UNSURE
        } catch (_: StackOverflowError) {
            Kind.UNSURE
        }
        val ms = clock() - t0
        val first = when (kind) {
            Kind.SURE -> line.firstOrNull()
            Kind.UNSURE -> ORDER.firstOrNull { Peg.legalAt(start, it) }?.let { Peg.JUMPS[it] }
            Kind.IMPOSSIBLE -> null
        }
        return Answer(kind, first, if (kind == Kind.SURE) line.toList() else emptyList(), nodes, ms, failed.size)
    }
}

/**
 * A primitive-long open-addressing set (no boxing, design solver.kind): linear probing, 0 reserved as "empty" (a board with no
 * pegs is never stored). [add] returns false once [capacity] entries are in (the solver then answers UNSURE).
 */
class LongOpenHashSet(val capacity: Int) {
    private val table: LongArray
    private val mask: Int
    var size = 0
        private set

    init {
        var n = 16
        while (n < capacity * 2) n = n shl 1
        table = LongArray(n)
        mask = n - 1
    }

    private fun slot(v: Long): Int {
        var h = v * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h.toInt()) and mask
    }

    fun contains(v: Long): Boolean {
        var i = slot(v)
        while (true) {
            val x = table[i]
            if (x == 0L) return false
            if (x == v) return true
            i = (i + 1) and mask
        }
    }

    fun add(v: Long): Boolean {
        if (v == 0L) return true
        var i = slot(v)
        while (true) {
            val x = table[i]
            if (x == v) return true
            if (x == 0L) {
                if (size >= capacity) return false
                table[i] = v
                size++
                return true
            }
            i = (i + 1) and mask
        }
    }
}
