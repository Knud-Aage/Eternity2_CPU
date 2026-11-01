package dk.puzzle.blackwood;

import dk.puzzle.util.PieceUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The label of a saved board (ErrorsNN_...) is BlackwoodSolver.countConflicts. It has to count a coloured edge
 * pointing out of the board's rim exactly like a mismatch between two pieces, as HoleSolver does. Counting only the inner edges
 * let a badly filled corner be saved under a label lower than the board's real conflict count.
 */
class BlackwoodSolverCountConflictsTest {

    private static final int GREY = PieceUtils.BORDER_COLOR;
    private static final int OTHER = 9; // a colour that never occurs in perfectBoard()

    /** Colour of the edge between (r,c) and the cell below it. */
    private static int below(int r, int c) {
        return 1 + (r + 2 * c) % 5;
    }

    /** Colour of the edge between (r,c) and the cell to its right. */
    private static int rightOf(int r, int c) {
        return 1 + (3 * r + c) % 5;
    }

    /** Every inner edge matches and the whole rim is grey: no conflicts at all. */
    private static int[] perfectBoard() {
        int[] board = new int[256];
        for (int r = 0; r < 16; r++) {
            for (int c = 0; c < 16; c++) {
                int north = r == 0 ? GREY : below(r - 1, c);
                int east = c == 15 ? GREY : rightOf(r, c);
                int south = r == 15 ? GREY : below(r, c);
                int west = c == 0 ? GREY : rightOf(r, c - 1);
                board[r * 16 + c] = PieceUtils.pack(north, east, south, west);
            }
        }
        return board;
    }

    /** Replaces the given edge colours of one cell; a negative value keeps that side as it is. */
    private static void set(int[] board, int r, int c, int north, int east, int south, int west) {
        int p = board[r * 16 + c];
        board[r * 16 + c] = PieceUtils.pack(
                north < 0 ? PieceUtils.getNorth(p) : north,
                east < 0 ? PieceUtils.getEast(p) : east,
                south < 0 ? PieceUtils.getSouth(p) : south,
                west < 0 ? PieceUtils.getWest(p) : west);
    }

    @Test
    void aBoardWithMatchingInnerEdgesAndAGreyRimHasNoConflicts() {
        assertEquals(0, BlackwoodSolver.countConflicts(perfectBoard()));
    }

    @Test
    void anInnerMismatchCountsOnce() {
        int[] board = perfectBoard();
        set(board, 7, 7, -1, OTHER, -1, -1); // its east edge no longer matches the west edge of (7,8)
        assertEquals(1, BlackwoodSolver.countConflicts(board));
    }

    @Test
    void aColouredEdgePointingOutOfTheRimCounts() {
        int[] board = perfectBoard();
        set(board, 0, 5, OTHER, -1, -1, -1);
        assertEquals(1, BlackwoodSolver.countConflicts(board));
    }

    @Test
    void aCornerWithTwoColouredEdgesPointingOutCountsTwo() {
        int[] board = perfectBoard();
        set(board, 0, 15, OTHER, OTHER, -1, -1);
        assertEquals(2, BlackwoodSolver.countConflicts(board));
    }

    @Test
    void allFourSidesOfTheRimAreChecked() {
        int[] board = perfectBoard();
        set(board, 0, 5, OTHER, -1, -1, -1);  // top rim
        set(board, 5, 15, -1, OTHER, -1, -1); // right rim
        set(board, 15, 5, -1, -1, OTHER, -1); // bottom rim
        set(board, 5, 0, -1, -1, -1, OTHER);  // left rim
        assertEquals(4, BlackwoodSolver.countConflicts(board));
    }

    @Test
    void everyOutwardSideOfTheRimIsCounted() {
        int[] board = perfectBoard();
        for (int i = 0; i < 16; i++) {
            set(board, 0, i, OTHER, -1, -1, -1);
            set(board, 15, i, -1, -1, OTHER, -1);
            set(board, i, 0, -1, -1, -1, OTHER);
            set(board, i, 15, -1, OTHER, -1, -1);
        }
        assertEquals(64, BlackwoodSolver.countConflicts(board));
    }

    @Test
    void aGreyEdgePointingIntoTheBoardIsOneInnerMismatchNotAlsoAFrameError() {
        int[] board = perfectBoard();
        set(board, 4, 10, -1, -1, GREY, -1); // meets the coloured north edge of (5,10)
        assertEquals(1, BlackwoodSolver.countConflicts(board));
    }

    @Test
    void anEdgePieceInTheCornerWithTheCornerPieceBelowItIsCountedByItsTwoOutwardEdges() {
        // The shape HoleSolver left in the GPU board Errors13_Base252_034441_378: an ordinary edge piece (one grey side) in the
        // corner cell and the corner piece (two grey sides) one cell too low. The two grey sides touch each other, which is not
        // an inner mismatch, so only the two coloured edges pointing out of the board give the board away.
        int[] board = perfectBoard();
        set(board, 0, 15, OTHER, OTHER, GREY, -1);
        set(board, 1, 15, GREY, GREY, -1, -1);
        assertEquals(2, BlackwoodSolver.countConflicts(board));
    }
}
