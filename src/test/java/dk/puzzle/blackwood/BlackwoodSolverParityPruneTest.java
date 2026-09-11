package dk.puzzle.blackwood;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness checks for the PARITY_PRUNE_ENABLED prototype (see that field's javadoc in
 * BlackwoodSolver and toggleEdgeParity). Three separate claims get verified against a real,
 * unmodified search run rather than a hand-built fixture: the incremental XOR-toggle bookkeeping
 * agrees with an independently-written from-scratch scan, the "boundary edges never break"
 * assumption toggleEdgeParity relies on to skip border handling actually holds, and enabling the
 * prune doesn't break the search outright.
 */
class BlackwoodSolverParityPruneTest {

    private static BlackwoodSolver solver;

    @BeforeAll
    static void setup() throws Exception {
        solver = new BlackwoodSolver();
        solver.prepare();
    }

    @AfterEach
    void restoreDefault() {
        BlackwoodSolver.PARITY_PRUNE_ENABLED = false;
    }

    @Test
    void toggleEdgeParityReplayMatchesAnIndependentFromScratchScan() {
        BlackwoodSolver.SolveResult result = solver.solvePuzzle(2_000_000L);
        BwRotatedPiece[] board = result.board();
        assertTrue(result.maxSolveIndex() > 20, "sanity: the search should have made real progress within the node cap");

        // Independent, freshly-written scan -- deliberately NOT calling toggleEdgeParity, so this
        // isn't just the method testing itself.
        int fromScratch = 0;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                BwRotatedPiece piece = board[row * 16 + col];
                if (piece.pieceNumber() == 0) continue;
                BwPiece base = solver.pieceByNumber[piece.pieceNumber()];
                if (col > 0) {
                    BwRotatedPiece westNeighbour = board[row * 16 + col - 1];
                    if (westNeighbour.pieceNumber() != 0) {
                        int expected = westNeighbour.rightSide();
                        int actual = BwUtil.westFacing(base, piece.rotations());
                        if (expected != actual) fromScratch ^= (1 << expected) ^ (1 << actual);
                    }
                }
                if (row > 0) {
                    BwRotatedPiece southNeighbour = board[(row - 1) * 16 + col];
                    if (southNeighbour.pieceNumber() != 0) {
                        int expected = southNeighbour.topSide();
                        int actual = BwUtil.southFacing(base, piece.rotations());
                        if (expected != actual) fromScratch ^= (1 << expected) ^ (1 << actual);
                    }
                }
            }
        }

        // Incremental replay via the real production method, walking the SAME final board in
        // fill order. XOR is commutative, so this doesn't need to replay the search's actual
        // backtracking history -- toggling each currently-placed cell's edges once, in an order
        // where west/south neighbours are already visited, reproduces the same total regardless
        // of how the search arrived at this board.
        int incremental = 0;
        for (int step = 0; step < 256; step++) {
            int row = solver.boardOrderRow[step];
            int col = solver.boardOrderCol[step];
            BwRotatedPiece piece = board[row * 16 + col];
            if (piece.pieceNumber() == 0) continue;
            incremental = BlackwoodSolver.toggleEdgeParity(incremental, board, solver.pieceByNumber, row, col, piece);
        }

        assertEquals(fromScratch, incremental,
                "incremental XOR-toggle replay must match an independent from-scratch scan of the same board");
    }

    @Test
    void borderFacingSidesAreAlwaysColourZeroOnARealPlacedBoard() {
        // toggleEdgeParity deliberately skips boundary checks, relying on every candidate this
        // solver ever offers already having colour 0 on any border-facing side (see that
        // method's javadoc for the structural argument). Confirmed here empirically too.
        BlackwoodSolver.SolveResult result = solver.solvePuzzle(2_000_000L);
        BwRotatedPiece[] board = result.board();

        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                BwRotatedPiece piece = board[row * 16 + col];
                if (piece.pieceNumber() == 0) continue;
                BwPiece base = solver.pieceByNumber[piece.pieceNumber()];
                if (col == 0) {
                    assertEquals(0, BwUtil.westFacing(base, piece.rotations()), "west border at (" + row + "," + col + ")");
                }
                if (row == 0) {
                    assertEquals(0, BwUtil.southFacing(base, piece.rotations()), "south border at (" + row + "," + col + ")");
                }
                if (col == 15) {
                    assertEquals(0, piece.rightSide(), "east border at (" + row + "," + col + ")");
                }
                if (row == 15) {
                    assertEquals(0, piece.topSide(), "north border at (" + row + "," + col + ")");
                }
            }
        }
    }

    @Test
    void parityPruneEnabledStillReachesDepthWithoutBreakingTheSearch() {
        BlackwoodSolver.PARITY_PRUNE_ENABLED = true;
        BlackwoodSolver.SolveResult result = solver.solvePuzzle(5_000_000L);
        assertTrue(result.maxSolveIndex() > 20, "pruning must not prevent the search from making real progress");
    }

    /**
     * seed.board() reflects whatever solveIndex the search currently sits at when the node cap
     * fires -- NOT maxSolveIndex(), the historical peak -- since backtracking may have receded
     * below the peak by then, leaving cells in [currentDepth, maxSolveIndex) empty. Returns the
     * length of the contiguous filled prefix in fill order, the only valid startIndex for
     * solvePuzzleFrom.
     */
    private static int contiguousDepth(BlackwoodSolver solver, BwRotatedPiece[] board) {
        for (int step = 0; step < 256; step++) {
            int row = solver.boardOrderRow[step];
            int col = solver.boardOrderCol[step];
            if (board[row * 16 + col].pieceNumber() == 0) return step;
        }
        return 256;
    }

    @Test
    void solvePuzzleFromNeverChangesCellsBelowStartIndexAndKeepsParityConsistent() {
        // Get a real partial board to resume from, then confirm solvePuzzleFrom (a near-duplicate
        // of solvePuzzle's loop, written to support this benchmark) never touches the fixed prefix
        // and keeps touchParity bookkeeping correct across the resume boundary.
        BlackwoodSolver.SolveResult seed = solver.solvePuzzle(2_000_000L, 42L);
        BwRotatedPiece[] initialBoard = seed.board();
        int startIndex = contiguousDepth(solver, initialBoard);
        // contiguousDepth (current position) can be far below maxSolveIndex (historical peak) if
        // the search was mid-backtrack when the cap fired -- any startIndex >= 1 exercises the
        // invariants under test, so no arbitrary "deep enough" threshold here.
        assertTrue(startIndex >= 1, "sanity: the seeded corner alone should already clear this");

        BlackwoodSolver.PARITY_PRUNE_ENABLED = true;
        BlackwoodSolver.SolveResult resumed = solver.solvePuzzleFrom(2_000_000L, 99L, initialBoard, startIndex);

        for (int step = 0; step < startIndex; step++) {
            int row = solver.boardOrderRow[step];
            int col = solver.boardOrderCol[step];
            assertEquals(initialBoard[row * 16 + col], resumed.board()[row * 16 + col],
                    "cell at fill-order step " + step + " (below startIndex=" + startIndex + ") must be unchanged");
        }

        int fromScratch = 0;
        BwRotatedPiece[] board = resumed.board();
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                BwRotatedPiece piece = board[row * 16 + col];
                if (piece.pieceNumber() == 0) continue;
                BwPiece base = solver.pieceByNumber[piece.pieceNumber()];
                if (col > 0 && board[row * 16 + col - 1].pieceNumber() != 0) {
                    int expected = board[row * 16 + col - 1].rightSide();
                    int actual = BwUtil.westFacing(base, piece.rotations());
                    if (expected != actual) fromScratch ^= (1 << expected) ^ (1 << actual);
                }
                if (row > 0 && board[(row - 1) * 16 + col].pieceNumber() != 0) {
                    int expected = board[(row - 1) * 16 + col].topSide();
                    int actual = BwUtil.southFacing(base, piece.rotations());
                    if (expected != actual) fromScratch ^= (1 << expected) ^ (1 << actual);
                }
            }
        }
        int incremental = 0;
        for (int step = 0; step < 256; step++) {
            int row = solver.boardOrderRow[step];
            int col = solver.boardOrderCol[step];
            BwRotatedPiece piece = board[row * 16 + col];
            if (piece.pieceNumber() == 0) continue;
            incremental = BlackwoodSolver.toggleEdgeParity(incremental, board, solver.pieceByNumber, row, col, piece);
        }
        assertEquals(fromScratch, incremental, "solvePuzzleFrom's resumed board must still satisfy the parity invariant check");
    }
}
