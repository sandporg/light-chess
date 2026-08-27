package com.thelightphone.chess.engine

import kotlin.math.exp
import kotlin.random.Random

/**
 * Distinct personalities, not the same search with different clocks.
 * - Easy (~250): often random, otherwise noisy material-only 1-ply.
 * - Novice (~500): less random, still material-only 1-ply.
 * - Medium (~750): 1-ply with placement — greedy captures, hangs pieces.
 * - Intermediate (~1100): depth-1 plus quiescence — does not hang pieces to a recapture.
 * - Hard (~1300): depth-2 alpha-beta, no quiescence.
 * - Expert (~1900): iterative deepening with quiescence, no opening book.
 * - Grand master (~2500): book plus the full search. No randomness.
 */
object Search {
    fun pickMove(
        board: Board,
        level: BotLevel,
        remainingMs: Long?,
        rng: Random,
        cancelled: () -> Boolean = { false },
    ): Int {
        val moves = IntArray(256)
        val n = board.generateLegalMoves(moves)
        if (n == 0) return 0
        if (n == 1) return moves[0]

        return when (level) {
            BotLevel.EASY -> WeakBot.pick(board, moves, n, Personality.EASY, rng)
            BotLevel.NOVICE -> WeakBot.pick(board, moves, n, Personality.NOVICE, rng)
            BotLevel.MEDIUM -> WeakBot.pick(board, moves, n, Personality.MEDIUM, rng)
            BotLevel.INTERMEDIATE -> Engine.go(board, Profile.INTERMEDIATE, remainingMs, rng, cancelled)
            BotLevel.HARD -> Engine.go(board, Profile.HARD, remainingMs, rng, cancelled)
            BotLevel.EXPERT -> Engine.go(board, Profile.EXPERT, remainingMs, rng, cancelled)
            BotLevel.GRAND_MASTER -> {
                OpeningBook.probe(board)?.let { return it }
                Engine.go(board, Profile.GRAND_MASTER, remainingMs, rng = null, cancelled)
            }
        }
    }

    fun pickHint(board: Board, cancelled: () -> Boolean): Int {
        val moves = IntArray(256)
        val n = board.generateLegalMoves(moves)
        if (n == 0) return 0
        if (n == 1) return moves[0]
        OpeningBook.probe(board)?.let { return it }
        return Engine.go(
            board = board,
            profile = Profile.GRAND_MASTER,
            remainingMs = null,
            rng = null,
            cancelled = cancelled,
            thinkMs = 1_800,
        )
    }

    /** Search a reply while the opponent is thinking. Expert / Grand Master only. */
    fun pickPonder(board: Board, level: BotLevel, cancelled: () -> Boolean): Int {
        val moves = IntArray(256)
        val n = board.generateLegalMoves(moves)
        if (n == 0) return 0
        if (n == 1) return moves[0]
        if (level == BotLevel.GRAND_MASTER) {
            OpeningBook.probe(board)?.let { return it }
        }
        val profile = when (level) {
            BotLevel.EXPERT -> Profile.EXPERT
            BotLevel.GRAND_MASTER -> Profile.GRAND_MASTER
            else -> return moves[0]
        }
        return Engine.go(
            board = board,
            profile = profile,
            remainingMs = null,
            rng = null,
            cancelled = cancelled,
            thinkMs = 8_000,
            ponder = true,
        )
    }
}

private enum class Personality(
    val randomChance: Float,
    val usePlacement: Boolean,
    val noise: Int,
    val temperature: Double,
    val captureBonus: Int,
) {
    EASY(randomChance = 0.42f, usePlacement = false, noise = 240, temperature = 0.0, captureBonus = 0),
    NOVICE(randomChance = 0.22f, usePlacement = false, noise = 150, temperature = 48.0, captureBonus = 50),
    MEDIUM(randomChance = 0.08f, usePlacement = true, noise = 90, temperature = 36.0, captureBonus = 140),
}

private object WeakBot {
    fun pick(board: Board, moves: IntArray, n: Int, personality: Personality, rng: Random): Int {
        if (rng.nextFloat() < personality.randomChance) {
            return moves[rng.nextInt(n)]
        }
        val scores = IntArray(n)
        for (i in 0 until n) {
            board.makeMove(moves[i])
            var s = if (personality.usePlacement) {
                -evaluate(board, full = false)
            } else {
                -evaluateMaterial(board)
            }
            if (board.isCheckmate()) s = EVAL_MATE
            else if (board.inCheck()) s += 25
            board.unmakeMove(moves[i])
            if (personality.noise > 0) {
                s += rng.nextInt(-personality.noise, personality.noise + 1)
            }
            if (moveFlags(moves[i]) and FLAG_CAPTURE != 0) s += personality.captureBonus
            scores[i] = s
        }
        return if (personality.temperature > 0) {
            softmax(moves, n, scores, personality.temperature, rng)
        } else {
            var best = 0
            for (i in 1 until n) if (scores[i] > scores[best]) best = i
            moves[best]
        }
    }
}

private enum class Profile(
    val maxDepth: Int,
    val maxTimeMs: Long,
    val nodeCap: Int,
    val quiesce: Boolean,
    val temperature: Double,
    val stableStop: Boolean = false,
) {
    INTERMEDIATE(maxDepth = 1, maxTimeMs = 450, nodeCap = 20_000, quiesce = true, temperature = 22.0),
    HARD(maxDepth = 2, maxTimeMs = 700, nodeCap = 40_000, quiesce = false, temperature = 28.0),
    EXPERT(maxDepth = 5, maxTimeMs = 2_800, nodeCap = 450_000, quiesce = true, temperature = 10.0, stableStop = true),
    GRAND_MASTER(maxDepth = 12, maxTimeMs = 3_500, nodeCap = 3_500_000, quiesce = true, temperature = 0.0, stableStop = true),
}

private class TTSlot {
    var key: Long = 0
    var move: Int = 0
    var score: Int = 0
    var depth: Int = -1
    var flag: Int = 0
}

private object Engine {
    private const val TT_SIZE = 1 shl 16
    private const val EXACT = 0
    private const val LOWER = 1
    private const val UPPER = 2
    private val tt = Array(TT_SIZE) { TTSlot() }

    fun go(
        board: Board,
        profile: Profile,
        remainingMs: Long?,
        rng: Random?,
        cancelled: () -> Boolean,
        thinkMs: Long? = null,
        ponder: Boolean = false,
    ): Int {
        val budget = when {
            ponder -> thinkMs ?: 8_000L
            thinkMs != null -> minOf(profile.maxTimeMs, thinkMs)
            remainingMs != null -> timedMoveBudget(profile.maxTimeMs, remainingMs)
            else -> profile.maxTimeMs
        }
        val job = Job(board.copy(), profile, budget, cancelled)
        return job.search(rng)
    }

    private class Job(
        private val board: Board,
        private val profile: Profile,
        private val budgetMs: Long,
        private val cancelled: () -> Boolean,
    ) {
        private val started = System.nanoTime()
        private var nodes = 0
        private var abort = false
        private var completedDepth = 0
        private val killers = Array(64) { IntArray(2) }
        private val history = Array(2) { IntArray(64 * 64) }
        private val rootMoves = IntArray(256)
        private var rootCount = 0

        fun search(rng: Random?): Int {
            rootCount = board.generateLegalMoves(rootMoves)
            if (rootCount == 0) return 0
            if (rootCount == 1) return rootMoves[0]
            var best = rootMoves[0]
            var lastScores = IntArray(0)
            var stableHits = 0
            for (depth in 1..profile.maxDepth) {
                if (completedDepth > 0 && timedOut()) break
                var alpha = -EVAL_INF
                var depthBest = best
                val depthScores = IntArray(rootCount)
                order(rootMoves, rootCount, 0, best)
                var completed = true
                for (i in 0 until rootCount) {
                    val move = rootMoves[i]
                    board.makeMove(move)
                    val score = when {
                        profile.temperature > 0 ->
                            -alphaBeta(depth - 1, 1, -EVAL_INF, EVAL_INF)
                        i == 0 ->
                            -alphaBeta(depth - 1, 1, -EVAL_INF, EVAL_INF)
                        else -> {
                            var s = -alphaBeta(depth - 1, 1, -alpha - 1, -alpha)
                            if (!abort && s > alpha) {
                                s = -alphaBeta(depth - 1, 1, -EVAL_INF, EVAL_INF)
                            }
                            s
                        }
                    }
                    board.unmakeMove(move)
                    depthScores[i] = score
                    if (score > alpha) {
                        alpha = score
                        depthBest = move
                    }
                    if (abort) {
                        if (completedDepth == 0) {
                            best = depthBest
                            completedDepth = 1
                            lastScores = IntArray(0)
                        }
                        completed = false
                        break
                    }
                }
                if (completed) {
                    if (depthBest == best && completedDepth >= 1) stableHits++ else stableHits = 0
                    best = depthBest
                    lastScores = depthScores
                    completedDepth = depth
                    if (profile.stableStop && stableHits >= 2 && depth >= 3) break
                }
                if (completedDepth > 0 && timedOut()) break
            }
            if (profile.temperature > 0 && rng != null && lastScores.size == rootCount) {
                return softmax(rootMoves, rootCount, lastScores, profile.temperature, rng)
            }
            return best
        }

        private fun alphaBeta(depth: Int, ply: Int, alpha0: Int, beta: Int): Int {
            if (timedOut()) {
                abort = true
                return evaluate(board, full = false)
            }
            nodes++
            var alpha = alpha0
            if (board.isThreefold() || board.halfmove >= 100 || board.isInsufficientMaterial()) {
                return 0
            }

            val inCheck = board.inCheck()
            var searchDepth = depth
            if (inCheck && searchDepth < 12) searchDepth++

            if (ply >= 60) return evaluate(board, full = profile.quiesce)
            if (searchDepth <= 0) {
                return if (profile.quiesce) quiesce(ply, alpha, beta) else evaluate(board, full = false)
            }

            val key = board.hash()
            val slot = tt[ttIndex(key)]
            if (slot.key == key && slot.depth >= searchDepth) {
                val ttScore = fromTt(slot.score, ply)
                when (slot.flag) {
                    EXACT -> return ttScore
                    LOWER -> if (ttScore >= beta) return ttScore
                    UPPER -> if (ttScore <= alpha) return ttScore
                }
            }

            if (profile.quiesce &&
                searchDepth >= 3 &&
                ply > 0 &&
                !inCheck &&
                nonPawnMaterial(board) >= 500
            ) {
                board.makeNullMove()
                val nm = -alphaBeta(searchDepth - 3, ply + 1, -beta, -beta + 1)
                board.unmakeNullMove()
                if (abort) return evaluate(board, full = false)
                if (nm >= beta) return nm
            }

            val moves = IntArray(256)
            val n = board.generateLegalMoves(moves)
            if (n == 0) {
                return if (inCheck) -EVAL_MATE + ply else 0
            }
            order(moves, n, ply, slot.takeIf { it.key == key }?.move ?: 0)

            var bestScore = -EVAL_INF
            var bestMove = moves[0]
            var flag = UPPER
            for (i in 0 until n) {
                val move = moves[i]
                val quiet = moveFlags(move) and (FLAG_CAPTURE or FLAG_PROMO) == 0
                board.makeMove(move)
                val givesCheck = board.inCheck()
                var reduction = 0
                if (profile.quiesce &&
                    i >= 3 &&
                    searchDepth >= 3 &&
                    quiet &&
                    !givesCheck &&
                    !inCheck
                ) {
                    reduction = 1 + searchDepth / 6
                }
                val nextDepth = searchDepth - 1 - reduction
                val score = if (i == 0 || !profile.quiesce) {
                    -alphaBeta(searchDepth - 1, ply + 1, -beta, -alpha)
                } else {
                    var s = -alphaBeta(nextDepth, ply + 1, -alpha - 1, -alpha)
                    if (!abort && s > alpha) {
                        s = -alphaBeta(searchDepth - 1, ply + 1, -beta, -alpha)
                    }
                    s
                }
                board.unmakeMove(move)
                if (abort) break
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
                if (score > alpha) {
                    alpha = score
                    flag = EXACT
                }
                if (alpha >= beta) {
                    flag = LOWER
                    if (quiet) {
                        val k = killers[ply.coerceIn(0, 63)]
                        if (k[0] != move) {
                            k[1] = k[0]
                            k[0] = move
                        }
                        val hist = historyIndex(move)
                        val side = if (board.side == WHITE) 0 else 1
                        history[side][hist] = (history[side][hist] + searchDepth * searchDepth)
                            .coerceAtMost(8_000)
                    }
                    break
                }
            }
            if (!abort) {
                slot.key = key
                slot.move = bestMove
                slot.score = toTt(bestScore, ply)
                slot.depth = searchDepth
                slot.flag = flag
            }
            return bestScore
        }

        private fun quiesce(ply: Int, alpha0: Int, beta: Int): Int {
            if (ply >= 24 || timedOut() || nodes > profile.nodeCap) {
                abort = timedOut() || nodes > profile.nodeCap
                return evaluate(board, full = false)
            }
            nodes++
            val inCheck = board.inCheck()
            var alpha = alpha0
            val stand = evaluate(board, full = false)
            if (!inCheck) {
                if (stand >= beta) return stand
                if (stand > alpha) alpha = stand
            }
            val moves = IntArray(256)
            val n = board.generateLegalMoves(moves)
            if (n == 0) {
                return if (inCheck) -EVAL_MATE + ply else 0
            }
            order(moves, n, ply)
            var searched = 0
            for (i in 0 until n) {
                val move = moves[i]
                val flags = moveFlags(move)
                if (!inCheck) {
                    if (flags and FLAG_CAPTURE == 0 && flags and FLAG_PROMO == 0) continue
                    if (flags and FLAG_CAPTURE != 0 && flags and FLAG_PROMO == 0) {
                        val victim = captureValue(board, move)
                        if (stand + victim + 180 < alpha) continue
                    }
                }
                board.makeMove(move)
                val score = -quiesce(ply + 1, -beta, -alpha)
                board.unmakeMove(move)
                searched++
                if (abort) break
                if (score >= beta) return score
                if (score > alpha) alpha = score
            }
            if (inCheck && searched == 0) return -EVAL_MATE + ply
            return alpha
        }

        private fun order(moves: IntArray, n: Int, ply: Int, ttMove: Int = 0) {
            val scores = IntArray(n)
            val k0 = killers[ply.coerceIn(0, 63)][0]
            val k1 = killers[ply.coerceIn(0, 63)][1]
            val side = if (board.side == WHITE) 0 else 1
            for (i in 0 until n) {
                val move = moves[i]
                var s = 0
                if (ttMove != 0 && move == ttMove) s += 1_000_000
                if (moveFlags(move) and FLAG_PROMO != 0) s += 800 + movePromo(move) * 10
                if (moveFlags(move) and FLAG_CAPTURE != 0) {
                    s += 10_000 + captureValue(board, move) * 16 -
                        typeOf(board.squares[moveFrom(move)])
                } else {
                    if (move == k0) s += 900
                    else if (move == k1) s += 800
                    s += history[side][historyIndex(move)]
                }
                scores[i] = s
            }
            insertionSort(moves, scores, n)
        }

        private fun timedOut(): Boolean {
            if (cancelled()) return true
            if (nodes > profile.nodeCap) return true
            val elapsed = (System.nanoTime() - started) / 1_000_000
            return elapsed >= budgetMs
        }
    }

    private fun ttIndex(key: Long): Int = ((key ushr 32).toInt() xor key.toInt()) and (TT_SIZE - 1)

    private fun toTt(score: Int, ply: Int): Int = when {
        score > EVAL_MATE - 512 -> score + ply
        score < -EVAL_MATE + 512 -> score - ply
        else -> score
    }

    private fun fromTt(score: Int, ply: Int): Int = when {
        score > EVAL_MATE - 512 -> score - ply
        score < -EVAL_MATE + 512 -> score + ply
        else -> score
    }
}

private fun captureValue(board: Board, move: Int): Int {
    if (moveFlags(move) and FLAG_EP != 0) return MATERIAL[PAWN]
    val victim = board.squares[moveTo(move)]
    return if (victim == EMPTY) 0 else MATERIAL[typeOf(victim)]
}

private fun historyIndex(move: Int): Int = sq64(moveFrom(move)) * 64 + sq64(moveTo(move))

internal fun timedMoveBudget(maxTimeMs: Long, remainingMs: Long): Long {
    val share = remainingMs / 40
    val cap = remainingMs * 2 / 3
    return minOf(maxTimeMs, share, cap).coerceAtLeast(80L)
}

private fun nonPawnMaterial(board: Board): Int {
    var n = 0
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY) continue
        val t = typeOf(p)
        if (t != PAWN && t != KING) n += MATERIAL[t]
    }
    return n
}

private fun insertionSort(moves: IntArray, scores: IntArray, n: Int) {
    for (i in 1 until n) {
        val m = moves[i]
        val sc = scores[i]
        var j = i
        while (j > 0 && scores[j - 1] < sc) {
            moves[j] = moves[j - 1]
            scores[j] = scores[j - 1]
            j--
        }
        moves[j] = m
        scores[j] = sc
    }
}

private fun softmax(moves: IntArray, n: Int, scores: IntArray, temperature: Double, rng: Random): Int {
    var peak = scores[0]
    for (i in 1 until n) if (scores[i] > peak) peak = scores[i]
    val t = temperature.coerceAtLeast(1.0)
    var sum = 0.0
    val weights = DoubleArray(n)
    for (i in 0 until n) {
        val w = exp((scores[i] - peak) / t)
        weights[i] = w
        sum += w
    }
    var r = rng.nextDouble() * sum
    for (i in 0 until n) {
        r -= weights[i]
        if (r <= 0) return moves[i]
    }
    return moves[n - 1]
}
