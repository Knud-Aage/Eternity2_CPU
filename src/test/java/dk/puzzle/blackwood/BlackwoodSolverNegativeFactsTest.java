package dk.puzzle.blackwood;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the 18 negative-fact exclusions, ported 2026-09-07 from Eternity2_GPU's
 * BlackwoodSolverNegativeFactsTest (see the javadoc on NEGATIVE_FACTS / applyNegativeFacts() in
 * BlackwoodSolver for the source and the verification method). Unlike the GPU repo, prepare()
 * calls applyNegativeFacts() directly here -- this repo has no GPU table layer to break.
 *
 * <p>Asserts purely through the observable package-private masterPieceLookup / middlesNoBreak /
 * middlesWithBreak fields, matching BlackwoodSolverHintPinsTest's own style, so a regression in
 * the conversion formulas (cell or rotation) has something independent to fail against.
 */
class BlackwoodSolverNegativeFactsTest {

    private static final String PIECES_PATH = "src/main/resources/JBlackwood_Pieces.txt";
    private static BlackwoodSolver solver;

    @BeforeAll
    static void prepareSolver() throws Exception {
        BlackwoodSolver.NON_CENTER_HINTS_ENABLED = true;
        solver = new BlackwoodSolver(190, Path.of("build", "test-output"), 1, PIECES_PATH);
        solver.prepare();
    }

    @AfterAll
    static void restoreDefault() {
        BlackwoodSolver.NON_CENTER_HINTS_ENABLED = false;
    }

    private static boolean cellOffersPieceAtRotation(BwRotatedPiece[][] table, int piece, int rotation) {
        for (BwRotatedPiece[] bucket : table) {
            if (bucket == null) continue;
            for (BwRotatedPiece p : bucket) {
                if (p.pieceNumber() == piece && p.rotations() == rotation) {
                    return true;
                }
            }
        }
        return false;
    }

    /** mplIndex = (15 - yFromTop) * 16 + x, matching NegativeFact.masterPieceLookupIndex(). */
    private static int mplIndex(int x, int yFromTop) {
        return (15 - yFromTop) * 16 + x;
    }

    @Test
    void excludedPlacementIsGoneButThePieceAndCellRemainOtherwiseUsable() {
        // Fact 8 (candidate 137037): piece 131 at (x=3, y_from_top=1), paper orientation 1 ->
        // requiredRotation = (4-1)%4 = 3.
        int cell = mplIndex(3, 1);
        BwRotatedPiece[][] table = solver.masterPieceLookup[cell];

        assertTrue(cellOffersPieceAtRotation(solver.middlesNoBreak, 131, 3)
                        || cellOffersPieceAtRotation(solver.middlesWithBreak, 131, 3),
                "sanity check: piece 131 at rotation 3 must exist in the unfiltered pool, "
                        + "otherwise this test would pass vacuously");
        assertFalse(cellOffersPieceAtRotation(table, 131, 3),
                "piece 131 at rotation 3 is Fact 8 -- proven impossible at this cell, must be excluded");
        assertTrue(cellOffersPieceAtRotation(table, 131, 0)
                        || cellOffersPieceAtRotation(table, 131, 1)
                        || cellOffersPieceAtRotation(table, 131, 2),
                "only rotation 3 of piece 131 is excluded here -- the piece must still be usable at another rotation");
    }

    @Test
    void allSixFactsAtTheSameCellAreExcludedTogether() {
        // Facts 8-13 all sit at (x=3, y_from_top=1): pieces 131/161/177/192/244/250.
        int cell = mplIndex(3, 1);
        BwRotatedPiece[][] table = solver.masterPieceLookup[cell];
        assertFalse(cellOffersPieceAtRotation(table, 131, 3), "Fact 8");
        assertFalse(cellOffersPieceAtRotation(table, 161, 3), "Fact 9");
        assertFalse(cellOffersPieceAtRotation(table, 177, 1), "Fact 10");
        assertFalse(cellOffersPieceAtRotation(table, 192, 3), "Fact 11");
        assertFalse(cellOffersPieceAtRotation(table, 244, 0), "Fact 12");
        assertFalse(cellOffersPieceAtRotation(table, 250, 1), "Fact 13");
    }

    @Test
    void twoDifferentPiecesExcludedAtTheSameCellWithDifferentRotations() {
        // Facts 5-6 sit at (x=14, y_from_top=12): piece 149 (orientation 2 -> rotation 2)
        // and piece 212 (orientation 1 -> rotation 3).
        int cell = mplIndex(14, 12);
        BwRotatedPiece[][] table = solver.masterPieceLookup[cell];
        assertFalse(cellOffersPieceAtRotation(table, 149, 2), "Fact 5");
        assertFalse(cellOffersPieceAtRotation(table, 212, 3), "Fact 6");
        // piece 149 at some other rotation must still be usable here -- only rotation 2 is excluded.
        assertTrue(cellOffersPieceAtRotation(table, 149, 0)
                || cellOffersPieceAtRotation(table, 149, 1)
                || cellOffersPieceAtRotation(table, 149, 3));
    }

    @Test
    void unrelatedCellsKeepSharingTheOriginalUnfilteredTable() {
        // (row=10, col=5) is an ordinary middle cell not among the 8 affected by NEGATIVE_FACTS;
        // it must still point at the exact same shared table object other untouched cells use --
        // proving applyNegativeFacts() clones only the 8 affected cells, not the shared tables.
        int untouchedCell = 10 * 16 + 5;
        BwRotatedPiece[][] table = solver.masterPieceLookup[untouchedCell];
        assertTrue(table == solver.middlesNoBreak || table == solver.middlesWithBreak,
                "an unrelated ordinary middle cell must still reference the shared, unfiltered table");
    }

    @Test
    void factsDoNotDisablePruningWhenHintsAreOff() throws Exception {
        BlackwoodSolver.NON_CENTER_HINTS_ENABLED = false;
        try {
            BlackwoodSolver noHints = new BlackwoodSolver(190, Path.of("build", "test-output"), 1, PIECES_PATH);
            noHints.prepare();
            int cell = mplIndex(3, 1);
            // Without all five clues fixed at their official position, the proof's premise doesn't
            // hold -- the fact-8 placement must NOT be excluded here.
            assertTrue(cellOffersPieceAtRotation(noHints.masterPieceLookup[cell], 131, 3),
                    "negative facts must not apply when the non-centre hints are disabled");
        } finally {
            BlackwoodSolver.NON_CENTER_HINTS_ENABLED = true;
        }
    }
}
