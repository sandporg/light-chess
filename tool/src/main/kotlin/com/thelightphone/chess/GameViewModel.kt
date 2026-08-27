package com.thelightphone.chess

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.thelightphone.chess.engine.BISHOP
import com.thelightphone.chess.engine.Board
import com.thelightphone.chess.engine.BotLevel
import com.thelightphone.chess.engine.ChessMove
import com.thelightphone.chess.engine.EMPTY
import com.thelightphone.chess.engine.GameResult
import com.thelightphone.chess.engine.KNIGHT
import com.thelightphone.chess.engine.QUEEN
import com.thelightphone.chess.engine.ROOK
import com.thelightphone.chess.engine.Search
import com.thelightphone.chess.engine.StartColor
import com.thelightphone.chess.engine.WHITE
import com.thelightphone.chess.engine.colorOf
import com.thelightphone.chess.engine.moveFrom
import com.thelightphone.chess.engine.movePromo
import com.thelightphone.chess.engine.moveTo
import com.thelightphone.chess.engine.parseSquare
import com.thelightphone.chess.engine.sq64
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class GameViewModel(
    private val store: GameStore,
    private val launch: GameLaunch,
) : LightViewModel<Unit>() {

    private var gameId: String = when (launch) {
        is GameLaunch.Continue -> launch.gameId
        is GameLaunch.New -> GameStore.newId()
    }
    private val board = Board.start()
    private val rng = Random.Default
    private val cancelSearch = AtomicBoolean(false)
    private var playerIsWhite: Boolean = true
    private var botLevel: BotLevel = BotLevel.MEDIUM
    private var timerMs: Long? = null
    private var whiteTimeMs: Long = 0
    private var blackTimeMs: Long = 0
    private var startFen: String = Board.START_FEN
    private val movesUci = mutableListOf<String>()
    private var result: GameResult? = null
    private var lastFrom: Int? = null
    private var lastTo: Int? = null
    private var hintFrom: Int? = null
    private var hintTo: Int? = null
    private var lastTickElapsed: Long = 0
    private var clockJob: Job? = null
    private var hintJob: Job? = null
    private var ponderJob: Job? = null
    private var hinting: Boolean = false
    private var readyHint: Int = 0
    private var hintPly: Int = -1
    private var readyPonder: Int = 0
    private var ponderForHint: Int = 0
    private var ponderPly: Int = -1
    private var started: Boolean = false
    private var searchGen: Int = 0

    private val _ui = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!started) {
            started = true
            viewModelScope.launch { startGame() }
        } else if (result == null) {
            lastTickElapsed = SystemClock.elapsedRealtime()
            startClock()
            publish()
            prefetchHint()
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        clockJob?.cancel()
        hintJob?.cancel()
        ponderJob?.cancel()
        hinting = false
        viewModelScope.launch { persist() }
    }

    override fun onAppPause() {
        super.onAppPause()
        clockJob?.cancel()
        hintJob?.cancel()
        ponderJob?.cancel()
        hinting = false
        viewModelScope.launch { persist() }
    }

    override fun onCleared() {
        cancelSearch.set(true)
        hintJob?.cancel()
        ponderJob?.cancel()
        hinting = false
        clockJob?.cancel()
        super.onCleared()
    }

    fun onSquare(sq: Int) {
        val state = _ui.value
        if (!state.inProgress || state.thinking) return
        if (state.overlay !is GameOverlay.None) return
        if (board.whiteToMove != playerIsWhite) return

        val selected = state.selected
        if (hintFrom != null && hintTo != null && sq == hintTo) {
            val from = hintFrom ?: return
            val options = board.legalMovesFrom(from).filter { it.to == sq }
            if (options.size > 1 && options.any { it.isPromotion }) {
                _ui.update { it.copy(overlay = GameOverlay.Promotion(from, sq), selected = from) }
                return
            }
            val move = options.firstOrNull() ?: return
            playUserMove(move)
            return
        }
        if (hintFrom != null && sq == hintFrom) return

        hintFrom = null
        hintTo = null

        if (selected != null && sq in state.targets) {
            val options = board.legalMovesFrom(selected).filter { it.to == sq }
            if (options.size > 1 && options.any { it.isPromotion }) {
                _ui.update { it.copy(overlay = GameOverlay.Promotion(selected, sq), selected = selected) }
                return
            }
            val move = options.firstOrNull() ?: return
            playUserMove(move)
            return
        }

        val piece = board.squares[sq]
        val own = piece != EMPTY && (colorOf(piece) == WHITE) == playerIsWhite
        if (own) {
            val targets = board.legalMovesFrom(sq).map { it.to }.toSet()
            _ui.update { it.copy(selected = sq, targets = targets, hintFrom = null, hintTo = null) }
        } else {
            _ui.update { it.copy(selected = null, targets = emptySet(), hintFrom = null, hintTo = null) }
        }
    }

    fun promote(pieceType: Int) {
        val overlay = _ui.value.overlay as? GameOverlay.Promotion ?: return
        val move = board.legalMovesFrom(overlay.from).firstOrNull {
            it.to == overlay.to && it.promotion == pieceType
        } ?: return
        _ui.update { it.copy(overlay = GameOverlay.None) }
        playUserMove(move)
    }

    fun cancelPromotion() {
        _ui.update { it.copy(overlay = GameOverlay.None, selected = null, targets = emptySet()) }
    }

    fun requestHint() {
        val state = _ui.value
        if (!state.inProgress || state.thinking || state.overlay !is GameOverlay.None) return
        if (board.whiteToMove != playerIsWhite) return
        if (revealReadyHint()) return
        if (hintJob?.isActive != true) prefetchHint()
        hinting = true
        publish()
        val pending = hintJob
        viewModelScope.launch {
            try {
                pending?.join()
                revealReadyHint()
            } finally {
                hinting = false
                publish()
            }
        }
    }

    private fun prefetchHint() {
        if (result != null || board.whiteToMove != playerIsWhite) return
        if (readyHint != 0 && hintPly == movesUci.size) {
            startPonderIfReady()
            return
        }
        hintJob?.cancel()
        ponderJob?.cancel()
        readyHint = 0
        hintPly = -1
        readyPonder = 0
        ponderForHint = 0
        ponderPly = -1
        val ply = movesUci.size
        val snapshot = board.copy()
        hintJob = viewModelScope.launch {
            val move = withLowPriority {
                Search.pickHint(snapshot, cancelled = { !isActive })
            }
            if (!isActive) return@launch
            if (ply != movesUci.size || result != null || board.whiteToMove != playerIsWhite) return@launch
            if (move != 0) {
                readyHint = move
                hintPly = ply
                startPonderIfReady()
            }
        }
    }

    private fun startPonderIfReady() {
        if (!botLevel.ponders) return
        if (result != null || board.whiteToMove != playerIsWhite) return
        if (readyHint == 0 || hintPly != movesUci.size) return
        if (ponderJob?.isActive == true && ponderForHint == readyHint && ponderPly == movesUci.size) return
        ponderJob?.cancel()
        readyPonder = 0
        ponderForHint = readyHint
        ponderPly = movesUci.size
        val assumed = readyHint
        val ply = movesUci.size
        val snapshot = board.copy()
        snapshot.makeMove(assumed)
        if (snapshot.gameResult(playerIsWhite) != null) return
        val level = botLevel
        ponderJob = viewModelScope.launch {
            val reply = withLowPriority {
                Search.pickPonder(snapshot, level, cancelled = { !isActive })
            }
            if (!isActive) return@launch
            if (ply != movesUci.size || assumed != readyHint) return@launch
            if (reply != 0) {
                readyPonder = reply
                ponderForHint = assumed
                ponderPly = ply
            }
        }
    }

    private suspend fun withLowPriority(block: suspend () -> Int): Int = withContext(Dispatchers.Default) {
        val tid = android.os.Process.myTid()
        val previous = android.os.Process.getThreadPriority(tid)
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            block()
        } finally {
            android.os.Process.setThreadPriority(previous)
        }
    }

    private fun revealReadyHint(): Boolean {
        if (readyHint == 0 || hintPly != movesUci.size) return false
        hintFrom = moveFrom(readyHint)
        hintTo = moveTo(readyHint)
        publish()
        return true
    }

    private fun discardIdleWork() {
        hintJob?.cancel()
        ponderJob?.cancel()
        hinting = false
        readyHint = 0
        hintPly = -1
        readyPonder = 0
        ponderForHint = 0
        ponderPly = -1
        hintFrom = null
        hintTo = null
    }

    private fun sameMove(a: Int, b: Int): Boolean =
        moveFrom(a) == moveFrom(b) && moveTo(a) == moveTo(b) && movePromo(a) == movePromo(b)

    private fun isLegal(encoded: Int): Boolean {
        val moves = IntArray(256)
        val n = board.generateLegalMoves(moves)
        for (i in 0 until n) {
            if (sameMove(moves[i], encoded)) return true
        }
        return false
    }

    fun undoUserMove() {
        val overlay = _ui.value.overlay
        if (overlay is GameOverlay.Promotion) {
            cancelPromotion()
            return
        }
        if (overlay is GameOverlay.ResignConfirm || overlay is GameOverlay.GameOver) return
        val index = lastUserMoveIndex()
        if (index < 0 || result != null) return
        cancelSearch.set(true)
        discardIdleWork()
        val kept = movesUci.take(index)
        board.loadFen(startFen)
        kept.forEach { board.applyUci(it) }
        movesUci.clear()
        movesUci.addAll(kept)
        result = null
        searchGen++
        cancelSearch.set(false)
        if (kept.isNotEmpty()) {
            val last = kept.last()
            lastFrom = parseSquare(last.substring(0, 2))
            lastTo = parseSquare(last.substring(2, 4))
        } else {
            lastFrom = null
            lastTo = null
        }
        hintFrom = null
        hintTo = null
        lastTickElapsed = SystemClock.elapsedRealtime()
        startClock()
        viewModelScope.launch { persist() }
        publish(overlay = GameOverlay.None)
        prefetchHint()
    }

    private fun lastUserMoveIndex(): Int {
        if (movesUci.isEmpty()) return -1
        val index = if (playerIsWhite) {
            if (movesUci.size % 2 == 1) movesUci.lastIndex else movesUci.lastIndex - 1
        } else {
            if (movesUci.size % 2 == 0) movesUci.lastIndex else movesUci.lastIndex - 1
        }
        val userPly = if (playerIsWhite) index % 2 == 0 else index % 2 == 1
        return if (index >= 0 && userPly) index else -1
    }

    fun requestResign() {
        if (!_ui.value.inProgress) return
        clockJob?.cancel()
        _ui.update { it.copy(overlay = GameOverlay.ResignConfirm) }
    }

    fun confirmResign() {
        result = GameResult.LOSS
        viewModelScope.launch {
            store.remove(gameId)
            publish(overlay = GameOverlay.GameOver("Resigned"))
        }
    }

    fun cancelResign() {
        _ui.update { it.copy(overlay = GameOverlay.None) }
        lastTickElapsed = SystemClock.elapsedRealtime()
        startClock()
        publish()
    }

    fun dismissGameOver() {
        _ui.update { it.copy(overlay = GameOverlay.None) }
    }

    private suspend fun startGame() {
        when (launch) {
            is GameLaunch.Continue -> {
                val saved = store.load(launch.gameId)
                if (saved == null || !saved.isInProgress) {
                    result = GameResult.DRAW
                    publish(overlay = GameOverlay.GameOver("No saved game"))
                    return
                }
                restore(saved)
            }
            is GameLaunch.New -> {
                playerIsWhite = when (launch.color) {
                    StartColor.WHITE -> true
                    StartColor.BLACK -> false
                    StartColor.RANDOM -> rng.nextBoolean()
                }
                botLevel = launch.bot
                timerMs = launch.timer.durationMs
                whiteTimeMs = timerMs ?: 0
                blackTimeMs = timerMs ?: 0
                startFen = Board.START_FEN
                board.loadFen(startFen)
                movesUci.clear()
                result = null
                persist()
            }
        }
        lastTickElapsed = SystemClock.elapsedRealtime()
        startClock()
        publish()
        maybeComputerMove()
        prefetchHint()
    }

    private fun restore(saved: SavedGame) {
        gameId = saved.id.ifBlank { gameId }
        board.loadFen(saved.startFen)
        saved.movesUci.forEach { board.applyUci(it) }
        playerIsWhite = saved.playerIsWhite
        botLevel = saved.bot()
        timerMs = saved.timerMs
        whiteTimeMs = saved.whiteTimeMs
        blackTimeMs = saved.blackTimeMs
        startFen = saved.startFen
        movesUci.clear()
        movesUci.addAll(saved.movesUci)
        result = saved.outcome()
        if (saved.movesUci.isNotEmpty()) {
            val last = saved.movesUci.last()
            lastFrom = com.thelightphone.chess.engine.parseSquare(last.substring(0, 2))
            lastTo = com.thelightphone.chess.engine.parseSquare(last.substring(2, 4))
        }
        if (saved.clockRunningForWhite != null && timerMs != null && result == null) {
            val elapsed = SystemClock.elapsedRealtime() - saved.savedAtElapsedMs
            if (saved.clockRunningForWhite) {
                whiteTimeMs = (whiteTimeMs - elapsed).coerceAtLeast(0)
            } else {
                blackTimeMs = (blackTimeMs - elapsed).coerceAtLeast(0)
            }
            if (whiteTimeMs == 0L || blackTimeMs == 0L) {
                applyFlag()
            }
        }
    }

    private fun playUserMove(move: ChessMove) {
        val ponderHit = readyPonder != 0 &&
            sameMove(ponderForHint, move.encoded) &&
            ponderPly == movesUci.size
        val reply = if (ponderHit) readyPonder else 0
        discardIdleWork()
        applyMove(move.encoded)
        if (result != null) return
        if (reply != 0 && isLegal(reply)) {
            applyMove(reply)
        } else {
            maybeComputerMove()
        }
    }

    private fun applyMove(encoded: Int) {
        board.makeMove(encoded)
        movesUci += ChessMove.decode(encoded).uci()
        lastFrom = moveFrom(encoded)
        lastTo = moveTo(encoded)
        result = board.gameResult(playerIsWhite)
        viewModelScope.launch { persist() }
        if (result != null) {
            clockJob?.cancel()
            publish(overlay = GameOverlay.GameOver(resultMessage(result!!)))
        } else {
            publish()
            prefetchHint()
        }
    }

    private fun maybeComputerMove() {
        if (result != null) return
        if (board.whiteToMove == playerIsWhite) return
        discardIdleWork()
        cancelSearch.set(false)
        _ui.update { it.copy(thinking = true, selected = null, targets = emptySet()) }
        val gen = ++searchGen
        viewModelScope.launch {
            val remaining = timerMs?.let {
                if (board.whiteToMove) whiteTimeMs else blackTimeMs
            }
            val level = botLevel
            val snapshot = board.copy()
            val move = withContext(Dispatchers.Default) {
                Search.pickMove(
                    board = snapshot,
                    level = level,
                    remainingMs = remaining,
                    rng = rng,
                    cancelled = { cancelSearch.get() || gen != searchGen },
                )
            }
            if (gen != searchGen || cancelSearch.get()) return@launch
            if (move != 0 && result == null) {
                applyMove(move)
            } else {
                publish()
            }
        }
    }

    private fun startClock() {
        clockJob?.cancel()
        if (timerMs == null || result != null) return
        lastTickElapsed = SystemClock.elapsedRealtime()
        clockJob = viewModelScope.launch {
            while (isActive && result == null) {
                delay(200)
                val now = SystemClock.elapsedRealtime()
                val dt = now - lastTickElapsed
                lastTickElapsed = now
                if (_ui.value.overlay is GameOverlay.ResignConfirm) continue
                if (board.whiteToMove) {
                    whiteTimeMs = (whiteTimeMs - dt).coerceAtLeast(0)
                    if (whiteTimeMs == 0L) applyFlag()
                } else {
                    blackTimeMs = (blackTimeMs - dt).coerceAtLeast(0)
                    if (blackTimeMs == 0L) applyFlag()
                }
                publish()
            }
        }
    }

    private fun applyFlag() {
        if (result != null) return
        val whiteFlagged = whiteTimeMs == 0L && board.whiteToMove
        val blackFlagged = blackTimeMs == 0L && !board.whiteToMove
        if (!whiteFlagged && !blackFlagged) return
        val whiteLost = whiteFlagged
        result = if ((whiteLost && playerIsWhite) || (!whiteLost && !playerIsWhite)) {
            GameResult.FLAG_LOSS
        } else {
            GameResult.FLAG_WIN
        }
        clockJob?.cancel()
        viewModelScope.launch {
            store.remove(gameId)
            publish(overlay = GameOverlay.GameOver(resultMessage(result!!)))
        }
    }

    private suspend fun persist() {
        if (result != null) {
            store.remove(gameId)
            return
        }
        store.save(
            SavedGame(
                id = gameId,
                fen = board.toFen(),
                startFen = startFen,
                movesUci = movesUci.toList(),
                playerIsWhite = playerIsWhite,
                timerMs = timerMs,
                whiteTimeMs = whiteTimeMs,
                blackTimeMs = blackTimeMs,
                clockRunningForWhite = if (timerMs != null) board.whiteToMove else null,
                savedAtElapsedMs = SystemClock.elapsedRealtime(),
                botLevel = botLevel.name,
                result = null,
            ),
        )
    }

    private fun publish(overlay: GameOverlay = _ui.value.overlay) {
        val occupancy = IntArray(64)
        for (sq in 0 until 128) {
            if (sq and 0x88 == 0) occupancy[sq64(sq)] = board.squares[sq]
        }
        val selected = _ui.value.selected
        val playerTurn = result == null && board.whiteToMove == playerIsWhite
        _ui.value = GameUiState(
            occupancy = occupancy,
            playerIsWhite = playerIsWhite,
            selected = selected,
            targets = if (selected != null && playerTurn) {
                board.legalMovesFrom(selected).map { it.to }.toSet()
            } else {
                emptySet()
            },
            lastFrom = lastFrom,
            lastTo = lastTo,
            hintFrom = hintFrom,
            hintTo = hintTo,
            whiteTimeMs = whiteTimeMs,
            blackTimeMs = blackTimeMs,
            hasTimer = timerMs != null,
            thinking = hinting || (result == null && board.whiteToMove != playerIsWhite),
            overlay = overlay,
            inProgress = result == null,
            botLabel = botLevel.label,
            canUndo = result == null && lastUserMoveIndex() >= 0,
        )
    }

    private fun resultMessage(outcome: GameResult): String = when (outcome) {
        GameResult.WIN -> "Checkmate. You win!"
        GameResult.LOSS -> "Checkmate. You lose."
        GameResult.DRAW -> "Draw"
        GameResult.FLAG_WIN -> "Out of time. You win!"
        GameResult.FLAG_LOSS -> "Out of time. You lose."
    }

    companion object {
        val PROMOTION_PIECES = listOf(
            QUEEN to "Queen",
            ROOK to "Rook",
            BISHOP to "Bishop",
            KNIGHT to "Knight",
        )
    }
}
