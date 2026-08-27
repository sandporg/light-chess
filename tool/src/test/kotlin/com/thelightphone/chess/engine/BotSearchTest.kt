package com.thelightphone.chess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BotSearchTest {
    @Test
    fun hintReturnsLegalMoveQuickly() {
        val board = Board.start()
        val start = System.nanoTime()
        val move = Search.pickHint(board, cancelled = { false })
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals("e2e4", ChessMove.decode(move).uci())
        assertTrue(elapsedMs < 500, "opening hint took ${elapsedMs}ms")
    }

    @Test
    fun grandMasterOpeningIsE4() {
        val board = Board.start()
        val move = Search.pickMove(board, BotLevel.GRAND_MASTER, remainingMs = null, rng = Random(0))
        assertEquals("e2e4", ChessMove.decode(move).uci())
    }

    @Test
    fun hintTakesHangingQueen() {
        val board = Board()
        board.loadFen("4k3/8/8/8/3q4/8/8/3RK3 w - - 0 1")
        val move = Search.pickHint(board, cancelled = { false })
        assertEquals("d1d4", ChessMove.decode(move).uci())
    }

    @Test
    fun grandMasterTakesHangingQueen() {
        val board = Board()
        board.loadFen("4k3/8/8/8/3q4/8/8/3RK3 w - - 0 1")
        val move = Search.pickMove(board, BotLevel.GRAND_MASTER, remainingMs = 1_500, rng = Random(0))
        assertEquals("d1d4", ChessMove.decode(move).uci())
    }

    @Test
    fun grandMasterMatesInOne() {
        val board = Board()
        board.loadFen("6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1")
        val move = Search.pickMove(board, BotLevel.GRAND_MASTER, remainingMs = 1_500, rng = Random(0))
        assertEquals("e1e8", ChessMove.decode(move).uci())
    }

    @Test
    fun grandMasterDoesNotTakeProtectedPawnWithQueen() {
        val board = Board()
        board.loadFen("4k3/8/8/2p5/3p4/8/8/3QK3 w - - 0 1")
        val move = Search.pickMove(board, BotLevel.GRAND_MASTER, remainingMs = 2_000, rng = Random(0))
        assertNotEquals("d1d4", ChessMove.decode(move).uci())
    }

    @Test
    fun hardDoesNotTakeProtectedPawnWithQueen() {
        val board = Board()
        board.loadFen("4k3/8/8/2p5/3p4/8/8/3QK3 w - - 0 1")
        val move = Search.pickMove(board, BotLevel.HARD, remainingMs = null, rng = Random(0))
        assertNotEquals("d1d4", ChessMove.decode(move).uci())
    }

    @Test
    fun intermediateDoesNotTakeProtectedPawnWithQueen() {
        val board = Board()
        board.loadFen("4k3/8/8/2p5/3p4/8/8/3QK3 w - - 0 1")
        val move = Search.pickMove(board, BotLevel.INTERMEDIATE, remainingMs = null, rng = Random(0))
        assertNotEquals("d1d4", ChessMove.decode(move).uci())
    }

    @Test
    fun expertDoesNotTakeProtectedPawnWithQueen() {
        val board = Board()
        board.loadFen("4k3/8/8/2p5/3p4/8/8/3QK3 w - - 0 1")
        val move = Search.pickMove(board, BotLevel.EXPERT, remainingMs = 1_200, rng = Random(0))
        assertNotEquals("d1d4", ChessMove.decode(move).uci())
    }

    @Test
    fun mediumUsuallyTakesProtectedPawnWithQueen() {
        val fen = "4k3/8/8/2p5/3p4/8/8/3QK3 w - - 0 1"
        var takes = 0
        val played = mutableListOf<String>()
        for (seed in 0 until 24) {
            val board = Board()
            board.loadFen(fen)
            val move = Search.pickMove(board, BotLevel.MEDIUM, remainingMs = null, rng = Random(seed.toLong()))
            val uci = ChessMove.decode(move).uci()
            played += uci
            if (uci == "d1d4") takes++
        }
        assertTrue(takes >= 16, "medium took protected pawn $takes/24 times: $played")
    }

    @Test
    fun easyOftenPlaysSomethingBesidesTheCapture() {
        val fen = "4k3/8/8/8/3q4/8/8/3RK3 w - - 0 1"
        var other = 0
        for (seed in 0 until 40) {
            val board = Board()
            board.loadFen(fen)
            val move = Search.pickMove(board, BotLevel.EASY, remainingMs = null, rng = Random(seed.toLong()))
            if (ChessMove.decode(move).uci() != "d1d4") other++
        }
        assertTrue(other >= 10, "easy played a non-capture $other/40 times")
    }

    @Test
    fun timedBudgetUsesFortiethOfRemaining() {
        assertEquals(3_500L, timedMoveBudget(3_500, 300_000))
        assertEquals(200L, timedMoveBudget(3_500, 8_000))
        assertEquals(80L, timedMoveBudget(3_500, 2_000))
    }

    @Test
    fun ponderAfterE4PlaysE5FromBook() {
        val board = Board.start()
        board.applyUci("e2e4")
        val start = System.nanoTime()
        val move = Search.pickPonder(board, BotLevel.GRAND_MASTER, cancelled = { false })
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals("e7e5", ChessMove.decode(move).uci())
        assertTrue(elapsedMs < 500, "ponder book took ${elapsedMs}ms")
    }
}
