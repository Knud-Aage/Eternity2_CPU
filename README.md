# Eternity II — CPU port of Blackwood's solver

A Java port of [Joshua Blackwood's Eternity II solver](https://github.com/jblackwood345/EternityII_Solver),
running his chronological-backtracking search across all available cores.

The [Eternity II puzzle](https://en.wikipedia.org/wiki/Eternity_II_puzzle) is a 16×16
edge-matching puzzle with 480 internal edges. It has never been solved. The $2,000,000 prize
went unclaimed when the competition closed in 2010, and no complete solution has been published
since.

Pure Java, no native dependencies — runs anywhere a JDK 21 does. A CUDA version of the same
algorithm is at [Eternity2_GPU](https://github.com/Knud-Aage/Eternity2_GPU).

## Results

| | |
|---|---|
| Blackwood's own record (CPU) | 470/480 (10 conflicts) |
| A perfect solution | 480/480 (0 conflicts) |

Boards in the 13–15 conflict range appear regularly. Getting below that is rare, and the
remaining gap is not a matter of more compute — see
[eternity2.dev on why a faster computer doesn't help](https://eternity2.dev/research/why/prune-vs-speed/).

## Requirements

- JDK 21+
- Maven

## Build and run

```bash
mvn clean package

# Run from the repository root (pieces.csv is read by relative path).
./run-cpu.sh          # Linux / macOS
run-cpu.cmd           # Windows
```

or directly:

```bash
java -cp "target/classes:$(cat cp.txt)" dk.puzzle.blackwood.BlackwoodSolver
```

(On Windows the classpath separator is `;` rather than `:`.)

Saved boards land in `~/EternitySolutions_JavaCPU/` as three files per board: a bucas-linked
raw board, a physical piece layout, and a Blackwood-numbered baseboard.

## Configuration

| Variable | Default | Effect |
|---|---|---|
| `ETERNITY_NODE_CAP` | `50000000000` | Nodes before an attempt is abandoned and restarted from a fresh seed. Blackwood's own value; 25B/50B/100B measured indistinguishable in an 8-hour-per-arm A/B. |
| `ETERNITY_DRIVE_UPLOAD` | enabled | `false` disables Google Drive mirroring (see below). |

Worker threads default to `availableProcessors() - 1`, leaving one core free. Each batch queues
5 attempts per worker onto a shared work queue.

## Google Drive mirroring (optional)

`dk.puzzle.io.drive` mirrors each saved board to Google Drive as a small text record, so a long
unattended run can be checked remotely. It is entirely optional and deliberately easy to remove:

1. **Off:** set `ETERNITY_DRIVE_UPLOAD=false`.
2. **Out:** delete the single `DriveUploader.uploadRecord(...)` call in `BlackwoodSolver`.
3. **Gone:** delete the `dk.puzzle.io.drive` package, that call, and the three `com.google.*`
   dependencies in `pom.xml`.

No credentials are committed. Without them the first upload fails, logs one warning, and disables
itself for the rest of the run — so a fresh clone works out of the box with no Drive setup.

## How it works

Place pieces in a fixed board order, drawing at each step from candidate tables keyed by the left
and bottom edge colours of the cell being filled. Two mechanisms shape the search:

- **The break schedule** — how many mismatched edges are permitted by each depth.
- **A heuristic gate** — a floor on how many rare-colour sides must have been consumed by each
  depth, which stops the search from saving scarce pieces until it is too late to place them.

Each worker thread runs independent attempts, restarting from a fresh random seed when an attempt
exhausts its subtree or hits `ETERNITY_NODE_CAP`.

When a board beats the depth threshold, the partial board's remaining holes are completed by
`HoleSolver`: exact MRV (minimum-remaining-values) backtracking per connected region, falling
back to a heuristic refill only for regions proven to have no conflict-free completion.

### Break schedule

`BwUtil.BREAK_INDEXES_ALLOWED` is a cumulative budget: mismatches are forbidden below depth 201,
and one more becomes permitted at each listed depth. Its length is the maximum final conflict
count reachable. This port ships a 9-entry schedule:

```
201, 206, 211, 216, 221, 225, 229, 233, 237
```

9 rather than Blackwood's 10 because 471/480 (9 conflicts) requires it: a search granted 10
breaks can spend all 10, so its best possible product is 470. The cost is real — a leave-one-out
sweep found 9-break configurations reach depth 248 far more rarely than 10-break.

## Layout

```
pieces.csv                           the 256-piece set (TheSil numbering)
src/main/resources/
  JBlackwood_Pieces.txt              the same set in Blackwood's own numbering
src/main/java/dk/puzzle/
  blackwood/BlackwoodSolver          main entry point: tables, search, batch loop, saving
  blackwood/BwUtil, BwPiece, ...     Blackwood's tables, break schedule, board encoding
  tools/HoleSolver                   exact MRV completion + scoring of partial boards
  ai/ConflictReducer                 heuristic refill, used only where the exact search proves
                                     no conflict-free completion exists
  io/drive/                          optional Google Drive mirroring
```

## Credit and licence

The search algorithm, candidate tables, break schedule and heuristic gate are
**Joshua Blackwood's** work, from
[jblackwood345/EternityII_Solver](https://github.com/jblackwood345/EternityII_Solver)
(GPL-3.0). This repository is a derivative work: a port of that algorithm to Java plus the
tooling around it. See `LICENSE`.
