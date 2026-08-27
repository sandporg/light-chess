# light-chess

Chess for the [Light Phone III](https://www.thelightphone.com/). Play a game against the phone, with LightOS chrome and a grayscale board that matches the rest of the device.

This repository is a Light SDK tool. The game lives in [`tool/`](./tool); package id `com.thelightphone.chess`.

<p>
  <img src="docs/screenshots/home.png" width="240" alt="Home: play a game against the phone">
  <img src="docs/screenshots/new-game.png" width="240" alt="New game: timer and color">
  <img src="docs/screenshots/new-game-bot.png" width="240" alt="New game: bot difficulty">
</p>
<p>
  <img src="docs/screenshots/board-selected.png" width="240" alt="Board with a pawn selected and legal-move dots">
  <img src="docs/screenshots/board-in-play.png" width="240" alt="Board after 1.e4 Nc6, last move highlighted">
</p>

## What you can do

**Home.** If nothing is in progress, the home screen is just a prompt and **NEW GAME**. Active games are listed with color, timer, bot, and move number; tap one to continue. You can have more than one game going at a time.

**New game.** Before the first move you choose:

| Setting    | Options                          | Default  |
| ---------- | -------------------------------- | -------- |
| Timer      | No timer, 5 min, 10 min, 30 min  | No timer |
| Your color | White, Black, Random             | White    |
| Difficulty | Easy, Novice, Medium, Intermediate, Hard, Expert, Grand master | Medium   |

**The board.** Tap a piece, then a highlighted square. Empty targets get a dot; captures get a ring. The last move is outlined. Rank and file labels sit on the near edges, and the board flips if you are playing Black.

On a timed game the top bar is `Medium - 9:42` (bot name plus _your_ remaining clock). Untimed games show only the bot name.

**During a game** the bottom bar is:

- **Star** - hint. The engine looks at the position at Grand master strength and outlines a suggested from/to. Tap the destination to play it.
- **Trash** - resign, with a confirm screen.
- **Rewind** - undo your last move (and the bot’s reply). Disabled until you have moved.

Pawns that reach the last rank open a **Promote** screen (Queen, Rook, Bishop, Knight). Games end with a full-screen result: checkmate, draw, flag, or resign.

Leaving the board, pausing the app, or killing the process saves an in-progress game. Finished games are cleared.

## Bot

Each level is a different way of thinking, not the same search with a longer clock. Target ratings: Easy ~250, Novice ~500, Medium ~750, Intermediate ~1100, Hard ~1300, Expert ~1900, Grand master ~2500.

| Level        | How it thinks                                                                                          |
| ------------ | ------------------------------------------------------------------------------------------------------ |
| Easy         | About 40% completely random. Otherwise a noisy material-only 1-ply look — no piece-square tables.      |
| Novice       | About 20% random. Still material-only 1-ply, but calmer than Easy.                                     |
| Medium       | 1-ply with placement eval. Greedy captures, hangs pieces, does not see recaptures.                     |
| Intermediate | Depth 1 plus quiescence. Takes hanging pieces, does not hang its own to a one-move recapture.          |
| Hard         | 2-ply alpha-beta, no quiescence. Sees one-move tactics, misses longer combinations.                    |
| Expert       | Iterative deepening (up to 5 ply) with quiescence. No opening book; occasional slight inaccuracy. Ponders on your time. |
| Grand master | Opening book, iterative deepening (up to 12 ply), transposition table, null move, check extensions, quiescence, and the full eval. No randomness. Ponders on your time. |

On a clock the bot uses about 1/40 of its remaining time per move (never more than two-thirds of what’s left), and Expert / Grand master stop once the best move is stable. Untimed Grand master is capped at 3.5 s. Expert and Grand master also think on your time: they prefetch a hint, then ponder the reply, so a matching move can be answered immediately.

Hints always search at Grand master strength.

Eval for Intermediate, Hard, Expert, and Grand master is Michniewski’s Simplified Evaluation Function ([Chess Programming Wiki](https://www.chessprogramming.org/Simplified_Evaluation_Function)), plus pawn structure, mobility, and king safety on the stronger levels.

## Engine and pieces

Move generation is a 0x88 mailbox, with make/unmake, FEN, castling, en passant, promotion, threefold, fifty-move, and insufficient material. Unit tests cover start-position perft, Kiwipete, castling, and en passant.

Piece drawings are from [Wikimedia Commons SVG chess pieces](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces).

## Run it

You can sideload the APK onto a Light Phone III, or run it on an Android emulator that looks like an LP3:

- 1080 × 1240, 3.92" display
- Android API 34
- No Google Play

```bash
./gradlew :tool:installDebug
adb shell am start -n com.thelightphone.chess/com.thelightphone.sdk.LightActivity
```

For LightOS-as-a-system-app (toolbox, theme, the way a real phone launches tools), follow [Using the LightOS Emulator](docs/system_app). The tool’s `serverPackage` in [`tool/lighttool.toml`](tool/lighttool.toml) is set to `com.thelightphone.sdk.emulator` for that setup; switch it to `com.lightos` for hardware.

Open the repo in Android Studio (or IntelliJ) and run the `:tool` configuration if you prefer a GUI.

The UI is Compose on top of the SDK’s `LightScreen` / `LightViewModel` pair, using `LightTopBar`, `LightBottomBar`, `LightScrollView`, `LightText`, and `LightIcons`. Game state is a DataStore JSON blob.

## Layout

| Path                                                                                                           | What it is                                   |
| -------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| [`tool/src/main/kotlin/com/thelightphone/chess/`](tool/src/main/kotlin/com/thelightphone/chess/)               | Screens, view models, board widget           |
| [`tool/src/main/kotlin/com/thelightphone/chess/engine/`](tool/src/main/kotlin/com/thelightphone/chess/engine/) | Rules + search                               |
| [`sdk/`](sdk/)                                                                                                 | Light SDK (client, UI, emulator)             |
| [`docs/`](docs/)                                                                                               | SDK docs, including the emulator walkthrough |

This tree still includes the upstream Light SDK so the tool can build and run against it. Chess-specific code is the `tool` module.
