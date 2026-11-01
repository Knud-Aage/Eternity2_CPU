package dk.puzzle.blackwood;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A depth record is completed with HoleSolver and scored (5000 trials each) from saveThreshold up. 240 was chosen for the search with the
 * four clue hints, whose attempts land at 238-246; without hints it only burns CPU on boards nothing downstream wants, so there the
 * threshold is 248 again. The mode is read when the solver is constructed, so the tests flip the same switch the hint tests flip.
 */
class BlackwoodSolverSaveThresholdTest {

    private static int thresholdOfADefaultSolver() throws Exception {
        Field f = BlackwoodSolver.class.getDeclaredField("saveThreshold");
        f.setAccessible(true);
        return f.getInt(new BlackwoodSolver());
    }

    @Test
    void withoutTheClueHintsDepthRecordsAreScoredFrom248() throws Exception {
        boolean before = BlackwoodSolver.NON_CENTER_HINTS_ENABLED;
        try {
            BlackwoodSolver.NON_CENTER_HINTS_ENABLED = false;
            assertEquals(248, BlackwoodSolver.defaultSaveThreshold());
            assertEquals(248, thresholdOfADefaultSolver(), "the default constructor must use it");
        } finally {
            BlackwoodSolver.NON_CENTER_HINTS_ENABLED = before;
        }
    }

    @Test
    void withTheClueHintsDepthRecordsAreScoredFrom240() throws Exception {
        boolean before = BlackwoodSolver.NON_CENTER_HINTS_ENABLED;
        try {
            BlackwoodSolver.NON_CENTER_HINTS_ENABLED = true;
            assertEquals(240, BlackwoodSolver.defaultSaveThreshold());
            assertEquals(240, thresholdOfADefaultSolver(), "the default constructor must use it");
        } finally {
            BlackwoodSolver.NON_CENTER_HINTS_ENABLED = before;
        }
    }
}
