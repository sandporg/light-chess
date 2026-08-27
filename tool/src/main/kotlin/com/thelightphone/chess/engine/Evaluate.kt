package com.thelightphone.chess.engine

internal const val EVAL_MATE = 32_000
internal const val EVAL_INF = 32_001

internal val MATERIAL = intArrayOf(0, 100, 320, 330, 500, 900, 20_000)

/** Side-to-move material only. Used by the weakest bots so they ignore placement. */
internal fun evaluateMaterial(board: Board): Int {
    var score = 0
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY) continue
        val value = MATERIAL[typeOf(p)]
        score += if (colorOf(p) == WHITE) value else -value
    }
    return if (board.side == WHITE) score else -score
}

/**
 * Full evaluation from the side to move.
 * Material and piece-square tables follow Michniewski, plus pawn structure,
 * bishop pair, rooks on files, and a middlegame/endgame king blend.
 */
internal fun evaluate(board: Board, full: Boolean = true): Int {
    var mg = 0
    var eg = 0
    var npm = 0
    val pawns = Array(2) { IntArray(8) }

    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY) continue
        val type = typeOf(p)
        val white = colorOf(p) == WHITE
        val ci = if (white) 0 else 1
        val idx = if (white) (7 - rankOf(sq)) * 8 + fileOf(sq) else rankOf(sq) * 8 + fileOf(sq)
        val mat = MATERIAL[type]
        val pst = PST_MG[type]?.get(idx) ?: 0
        val pstEg = PST_EG[type]?.get(idx) ?: pst
        if (white) {
            mg += mat + pst
            eg += mat + pstEg
        } else {
            mg -= mat + pst
            eg -= mat + pstEg
        }
        if (type != PAWN && type != KING) npm += mat
        if (type == PAWN) {
            val f = fileOf(sq)
            pawns[ci][f]++
        }
    }

    val phase = (npm.coerceIn(0, 4_800)).toFloat() / 4_800f
    var score = (mg * phase + eg * (1f - phase)).toInt()
    if (full) {
        score += pawnStructure(board, pawns) + pieceMobility(board)
        score += ((kingShield(board, 0) - kingShield(board, 1)) * phase).toInt()
    }
    if (board.side != WHITE) score = -score
    score += 12
    if (full && score > 220) score += 30 + (score - 220) / 14
    return score
}

private fun pawnStructure(board: Board, pawns: Array<IntArray>): Int {
    var structure = 0
    for (color in 0..1) {
        val sign = if (color == 0) 1 else -1
        val mine = pawns[color]
        for (file in 0..7) {
            if (mine[file] > 1) structure -= 14 * (mine[file] - 1) * sign
            if (mine[file] > 0) {
                val neighbors = (if (file > 0) mine[file - 1] else 0) +
                    (if (file < 7) mine[file + 1] else 0)
                if (neighbors == 0) structure -= 10 * sign
            }
        }
        structure += passedPawnBonus(board, color) * sign
    }

    val bishops = intArrayOf(0, 0)
    var rookFiles = 0
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY) continue
        val type = typeOf(p)
        val ci = colorIndex(p)
        val sign = if (ci == 0) 1 else -1
        if (type == BISHOP) bishops[ci]++
        if (type == ROOK) {
            val file = fileOf(sq)
            val myPawns = pawns[ci][file]
            val theirPawns = pawns[1 - ci][file]
            if (myPawns == 0 && theirPawns == 0) rookFiles += 18 * sign
            else if (myPawns == 0) rookFiles += 8 * sign
            val rank = rankOf(sq)
            if ((ci == 0 && rank == 6) || (ci == 1 && rank == 1)) rookFiles += 16 * sign
        }
    }
    if (bishops[0] >= 2) structure += 35
    if (bishops[1] >= 2) structure -= 35
    structure += rookFiles
    return structure
}

private fun pieceMobility(board: Board): Int {
    var white = 0
    var black = 0
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY) continue
        val type = typeOf(p)
        val n = when (type) {
            KNIGHT -> countDeltas(board, sq, KNIGHT_DELTAS, slide = false)
            BISHOP -> countDeltas(board, sq, BISHOP_DELTAS, slide = true)
            ROOK -> countDeltas(board, sq, ROOK_DELTAS, slide = true)
            QUEEN -> countDeltas(board, sq, KING_DELTAS, slide = true)
            else -> 0
        }
        val weighted = when (type) {
            KNIGHT -> n * 4
            BISHOP -> n * 3
            ROOK -> n * 2
            QUEEN -> n
            else -> 0
        }
        if (colorOf(p) == WHITE) white += weighted else black += weighted
    }
    return white - black
}

private fun countDeltas(board: Board, from: Int, deltas: IntArray, slide: Boolean): Int {
    var n = 0
    val us = colorOf(board.squares[from])
    for (delta in deltas) {
        var to = from + delta
        while (to and 0x88 == 0) {
            val cap = board.squares[to]
            if (cap == EMPTY) {
                n++
            } else {
                if (colorOf(cap) != us) n++
                break
            }
            if (!slide) break
            to += delta
        }
    }
    return n
}

private fun kingShield(board: Board, color: Int): Int {
    val king = board.kingSq[color]
    if (king < 0) return 0
    val white = color == 0
    val file = fileOf(king)
    val pawnRank = rankOf(king) + if (white) 1 else -1
    if (pawnRank !in 0..7) return 0
    var s = 0
    for (f in (file - 1).coerceAtLeast(0)..(file + 1).coerceAtMost(7)) {
        val sq = pawnRank * 16 + f
        val p = board.squares[sq]
        if (p != EMPTY && typeOf(p) == PAWN && colorIndex(p) == color) s += 14
    }
    return s
}

private fun passedPawnBonus(board: Board, color: Int): Int {
    var bonus = 0
    val white = color == 0
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY || typeOf(p) != PAWN) continue
        if (colorIndex(p) != color) continue
        val file = fileOf(sq)
        val rank = rankOf(sq)
        var blocked = false
        val start = if (white) rank + 1 else 0
        val end = if (white) 7 else rank - 1
        if (start > end) {
            bonus += PASSED[if (white) rank else 7 - rank]
            continue
        }
        for (r in start..end) {
            for (f in (file - 1).coerceAtLeast(0)..(file + 1).coerceAtMost(7)) {
                val ahead = r * 16 + f
                val q = board.squares[ahead]
                if (q != EMPTY && typeOf(q) == PAWN && colorIndex(q) != color) {
                    blocked = true
                    break
                }
            }
            if (blocked) break
        }
        if (!blocked) bonus += PASSED[if (white) rank else 7 - rank]
    }
    return bonus
}

private val PASSED = intArrayOf(0, 8, 12, 20, 35, 60, 100, 0)

private val KNIGHT_DELTAS = intArrayOf(-33, -31, -18, -14, 14, 18, 31, 33)
private val KING_DELTAS = intArrayOf(-17, -16, -15, -1, 1, 15, 16, 17)
private val BISHOP_DELTAS = intArrayOf(-17, -15, 15, 17)
private val ROOK_DELTAS = intArrayOf(-16, -1, 1, 16)

private val PST_MG: Array<IntArray?> = arrayOf(
    null,
    intArrayOf( // pawn
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0,
    ),
    intArrayOf( // knight
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -40, -40, -50,
    ),
    intArrayOf( // bishop
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20,
    ),
    intArrayOf( // rook
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0,
    ),
    intArrayOf( // queen
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20,
    ),
    intArrayOf( // king middlegame
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20,
    ),
)

private val PST_EG: Array<IntArray?> = arrayOf(
    null,
    PST_MG[PAWN],
    PST_MG[KNIGHT],
    PST_MG[BISHOP],
    PST_MG[ROOK],
    PST_MG[QUEEN],
    intArrayOf( // king endgame
        -50, -40, -30, -20, -20, -30, -40, -50,
        -30, -20, -10, 0, 0, -10, -20, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -30, 0, 0, 0, 0, -30, -30,
        -50, -30, -30, -30, -30, -30, -30, -50,
    ),
)
