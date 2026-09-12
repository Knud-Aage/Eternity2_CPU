package dk.puzzle.blackwood;

import java.util.Arrays;

/**
 * Throwaway A/B comparison driver -- not a test, deleted after use. Like fill order (and unlike
 * parity pruning), double-break doesn't add a soundness-sensitive rejection -- it only ADDS
 * candidates that weren't offered before -- so "how deep did it get within a bounded budget" is
 * the right metric directly, no resume-to-exhaustion machinery needed.
 */
public class TmpDoubleBreakBenchmark {
    public static void main(String[] args) throws Exception {
        long nodeCap = Long.parseLong(args.length > 0 ? args[0] : "50000000");
        int trials = Integer.parseInt(args.length > 1 ? args[1] : "20");

        BlackwoodSolver solver = new BlackwoodSolver();

        BwUtil.DOUBLE_BREAK_ENABLED = false;
        solver.prepare();
        int[] normalDepths = runTrials(solver, nodeCap, trials, 2000);

        BwUtil.DOUBLE_BREAK_ENABLED = true;
        solver.prepare();
        int[] doubleBreakDepths = runTrials(solver, nodeCap, trials, 9000);
        BwUtil.DOUBLE_BREAK_ENABLED = false;

        System.out.println("nodeCap=" + nodeCap + " trials=" + trials);
        report("single-break (current)", normalDepths);
        report("double-break (step " + BwUtil.DOUBLE_BREAK_STEP + ")", doubleBreakDepths);
    }

    private static int[] runTrials(BlackwoodSolver solver, long nodeCap, int trials, long seedBase) throws Exception {
        int[] depths = new int[trials];
        for (int i = 0; i < trials; i++) {
            BlackwoodSolver.SolveResult r = solver.solvePuzzle(nodeCap, seedBase + i);
            depths[i] = r.maxSolveIndex();
        }
        return depths;
    }

    private static void report(String label, int[] depths) {
        int[] sorted = depths.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(depths).average().orElse(0);
        int median = sorted[sorted.length / 2];
        System.out.printf("%s: mean=%.1f median=%d min=%d max=%d all=%s%n",
                label, mean, median, sorted[0], sorted[sorted.length - 1], Arrays.toString(depths));
    }
}
