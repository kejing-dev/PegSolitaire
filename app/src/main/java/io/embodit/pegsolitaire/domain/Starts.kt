package io.embodit.pegsolitaire.domain

/**
 * The start boards (gate B question 1, recommended default: v1 ships >= 6 classic starts on the same board; each keeps its
 * own records; the main loop, solver and panels are unchanged). Every one finishes in the centre; RulesTest proves each is
 * solvable with the hint solver. Drawings: row 0 = the far edge, "o" = a peg, "." = an empty hole.
 */
enum class Start(val id: String, val rows: List<String>) {
    STANDARD("standard", listOf(
        "  ooo  ",
        "  ooo  ",
        "ooooooo",
        "ooo.ooo",
        "ooooooo",
        "  ooo  ",
        "  ooo  ",
    )),
    DIAMOND("diamond", listOf(
        "  .o.  ",
        "  ooo  ",
        ".ooooo.",
        "ooo.ooo",
        ".ooooo.",
        "  ooo  ",
        "  .o.  ",
    )),
    PLUS("plus", listOf(
        "  ...  ",
        "  .o.  ",
        "...o...",
        ".ooooo.",
        "...o...",
        "  .o.  ",
        "  ...  ",
    )),
    FIREPLACE("fireplace", listOf(
        "  ooo  ",
        "  ooo  ",
        "..ooo..",
        "..o.o..",
        ".......",
        "  ...  ",
        "  ...  ",
    )),
    PYRAMID("pyramid", listOf(
        "  ...  ",
        "  .o.  ",
        "..ooo..",
        ".ooooo.",
        "ooooooo",
        "  ...  ",
        "  ...  ",
    )),
    ARROW("arrow", listOf(
        "  .o.  ",
        "  ooo  ",
        ".ooooo.",
        "...o...",
        "...o...",
        "  ooo  ",
        "  ooo  ",
    ));

    val pos: Long = Peg.fromDrawing(rows)
    val pegs: Int get() = Peg.count(pos)

    companion object {
        fun of(id: String?): Start = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}
