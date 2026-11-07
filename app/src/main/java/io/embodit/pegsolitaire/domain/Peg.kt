package io.embodit.pegsolitaire.domain

/**
 * The English 33-hole board (design gameSpec.board / coords). Pure Kotlin, unit tested by RulesTest.
 *
 * A 7 x 7 grid without the 2 x 2 corners; (col, row) with row 0 at the far edge, +row toward the user. Holes are numbered
 * 0..32 in row-major order (row 0: 0..2, row 1: 3..5, rows 2-4: 6..26, row 5: 27..29, row 6: 30..32); the centre is 16.
 * A position is a 33-bit Long: bit k set = a peg in hole k. Names: column a..g (left to right) + row 1..7 (near to far),
 * like a chess board seen from the player, so the centre is "d4".
 */
object Peg {
    const val N = 33
    const val CENTER = 16
    const val ALL: Long = (1L shl N) - 1

    val COL = IntArray(N)
    val ROW = IntArray(N)
    private val INDEX = Array(7) { IntArray(7) { -1 } }

    fun inCross(c: Int, r: Int): Boolean = c in 0..6 && r in 0..6 && (c in 2..4 || r in 2..4)

    init {
        var k = 0
        for (r in 0..6) for (c in 0..6) if (inCross(c, r)) { COL[k] = c; ROW[k] = r; INDEX[r][c] = k; k++ }
        check(k == N)
    }

    fun at(c: Int, r: Int): Int = if (inCross(c, r)) INDEX[r][c] else -1

    fun bit(k: Int): Long = 1L shl k

    fun has(pos: Long, k: Int): Boolean = (pos ushr k) and 1L == 1L

    fun count(pos: Long): Int = java.lang.Long.bitCount(pos)

    fun name(k: Int): String = "${'a' + COL[k]}${7 - ROW[k]}"

    /** "d4" / "16" -> hole index, or null. */
    fun parse(s: String): Int? {
        val t = s.trim().lowercase()
        t.toIntOrNull()?.let { return it.takeIf { k -> k in 0 until N } }
        if (t.length != 2) return null
        val c = t[0] - 'a'
        val r = 7 - (t[1] - '0')
        return at(c, r).takeIf { it >= 0 }
    }

    /** One hop: the peg in [from] jumps over [over] into the empty [to]. */
    data class Jump(val from: Int, val over: Int, val to: Int) {
        val mask: Long get() = bit(from) or bit(over) or bit(to)
        fun text(): String = "${name(from)}-${name(to)}"
    }

    private val DC = intArrayOf(1, 0, -1, 0)
    private val DR = intArrayOf(0, 1, 0, -1)

    /** The 76 jumps of the English board (every hole, four directions, both landing holes on the board). */
    val JUMPS: List<Jump> = buildList {
        for (k in 0 until N) for (d in 0..3) {
            val o = at(COL[k] + DC[d], ROW[k] + DR[d])
            val t = at(COL[k] + 2 * DC[d], ROW[k] + 2 * DR[d])
            if (o >= 0 && t >= 0) add(Jump(k, o, t))
        }
    }

    /** The jumps that start in hole k. */
    val FROM: Array<List<Jump>> = Array(N) { k -> JUMPS.filter { it.from == k } }

    private val FROM_BIT = LongArray(JUMPS.size) { bit(JUMPS[it].from) }
    private val OVER_BIT = LongArray(JUMPS.size) { bit(JUMPS[it].over) }
    private val TO_BIT = LongArray(JUMPS.size) { bit(JUMPS[it].to) }

    fun legal(pos: Long, j: Jump): Boolean = has(pos, j.from) && has(pos, j.over) && !has(pos, j.to)

    fun legalJumps(pos: Long): List<Jump> = JUMPS.filterIndexed { i, _ -> legalAt(pos, i) }

    internal fun legalAt(pos: Long, i: Int): Boolean = (pos and FROM_BIT[i]) != 0L && (pos and OVER_BIT[i]) != 0L && (pos and TO_BIT[i]) == 0L

    fun jumpsFrom(pos: Long, from: Int): List<Jump> = FROM[from].filter { legal(pos, it) }

    fun hasMove(pos: Long): Boolean {
        for (i in JUMPS.indices) if (legalAt(pos, i)) return true
        return false
    }

    fun jumpOf(pos: Long, from: Int, to: Int): Jump? = FROM[from].firstOrNull { it.to == to && legal(pos, it) }

    fun apply(pos: Long, j: Jump): Long = pos xor j.mask

    /** The standard opening: every hole but the centre. */
    val STANDARD: Long = ALL and bit(CENTER).inv()

    // ---------------------------------------------------------------- results

    enum class Result { FULL, ONE, STUCK }

    /** null while a jump is left; FULL = one peg in the centre, ONE = one peg elsewhere, STUCK = two or more and no jump. */
    fun result(pos: Long): Result? {
        if (hasMove(pos)) return null
        return when {
            count(pos) == 1 && has(pos, CENTER) -> Result.FULL
            count(pos) == 1 -> Result.ONE
            else -> Result.STUCK
        }
    }

    /** Moves the conventional way (Bergholt 18): hops by the same peg in a row are one move. */
    fun moves(jumps: List<Jump>): Int {
        var n = 0
        for (i in jumps.indices) if (i == 0 || jumps[i].from != jumps[i - 1].to) n++
        return n
    }

    // ---------------------------------------------------------------- symmetry (8 transforms of the square)

    /** perm[t][k] = where hole k goes under transform t (t = 0 identity; rotations and reflections about the centre). */
    val PERM: Array<IntArray> = Array(8) { t ->
        IntArray(N) { k ->
            val x = COL[k] - 3
            val y = ROW[k] - 3
            val (a, b) = when (t) {
                0 -> x to y
                1 -> -y to x
                2 -> -x to -y
                3 -> y to -x
                4 -> -x to y
                5 -> x to -y
                6 -> y to x
                else -> -y to -x
            }
            at(a + 3, b + 3)
        }
    }

    /** Lookup tables: 5 chunks of 7 bits -> transformed bits, per transform (fast canonical form for the solver). */
    private val CHUNK = Array(8) { t ->
        Array(5) { ch ->
            LongArray(128) { v ->
                var out = 0L
                for (b in 0 until 7) {
                    val k = ch * 7 + b
                    if (k < N && (v ushr b) and 1 == 1) out = out or bit(PERM[t][k])
                }
                out
            }
        }
    }

    fun transform(pos: Long, t: Int): Long {
        val tb = CHUNK[t]
        return tb[0][(pos and 127).toInt()] or tb[1][((pos ushr 7) and 127).toInt()] or tb[2][((pos ushr 14) and 127).toInt()] or
            tb[3][((pos ushr 21) and 127).toInt()] or tb[4][((pos ushr 28) and 127).toInt()]
    }

    /** The smallest of the 8 symmetric images (the centre is fixed by all of them). */
    fun canonical(pos: Long): Long {
        var best = pos
        for (t in 1 until 8) { val v = transform(pos, t); if (v < best) best = v }
        return best
    }

    // ---------------------------------------------------------------- position class (Conway's three-colouring)

    /**
     * Colour every hole by (c + r) mod 3 and by (c - r) mod 3. A jump takes one peg off each of the three colours of a
     * colouring or puts one on, so the six count parities all flip together. Hence "pos can end as a single peg in [target]"
     * needs class(pos) == class(target) after flipping by (pegs - 1) jumps: an exact, free test the solver uses to prune.
     */
    private val COLOUR_A = IntArray(N) { (COL[it] + ROW[it]) % 3 }
    private val COLOUR_B = IntArray(N) { ((COL[it] - ROW[it]) % 3 + 3) % 3 }
    private val MASK_A = LongArray(3) { c -> (0 until N).filter { COLOUR_A[it] == c }.fold(0L) { m, k -> m or bit(k) } }
    private val MASK_B = LongArray(3) { c -> (0 until N).filter { COLOUR_B[it] == c }.fold(0L) { m, k -> m or bit(k) } }

    fun positionClass(pos: Long): Int {
        var v = 0
        for (c in 0..2) {
            v = v or ((java.lang.Long.bitCount(pos and MASK_A[c]) and 1) shl c)
            v = v or ((java.lang.Long.bitCount(pos and MASK_B[c]) and 1) shl (3 + c))
        }
        return v
    }

    /** Can [pos] still end as one peg in [target] as far as the colouring invariant says? (necessary, not sufficient) */
    fun classAllows(pos: Long, target: Int): Boolean {
        val n = count(pos)
        if (n == 0) return false
        val flips = (n - 1) and 1
        val want = positionClass(bit(target))
        val have = positionClass(pos) xor (if (flips == 1) 0b111111 else 0)
        return have == want
    }

    fun parsePos(hex: String): Long? = hex.trim().removePrefix("0x").toLongOrNull(16)?.takeIf { it and ALL.inv() == 0L }

    fun hex(pos: Long): String = java.lang.Long.toHexString(pos)

    /** 7 lines of "o" / "." for logs and tests. */
    fun draw(pos: Long): String = buildString {
        for (r in 0..6) {
            for (c in 0..6) { val k = at(c, r); append(if (k < 0) ' ' else if (has(pos, k)) 'o' else '.') }
            if (r < 6) append('\n')
        }
    }

    fun fromDrawing(lines: List<String>): Long {
        var pos = 0L
        for (r in 0..6) for (c in 0..6) { val k = at(c, r); if (k >= 0 && lines.getOrNull(r)?.getOrNull(c) == 'o') pos = pos or bit(k) }
        return pos
    }
}
