package coupledMCMC;

import beast.base.util.Randomizer;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static coupledMCMC.PortTestSupport.*;

class TemperatureAndSchedulingTest {
    private static CoupledMCMC ladder(boolean beta, double delta, Double cap) {
        CoupledMCMC controller = new CoupledMCMC();
        controller.nrOfChainsInput.setValue(4, controller);
        controller.useBetaDistributionInput.setValue(beta, controller);
        controller.deltaTemperatureInput.setValue(delta, controller);
        controller.optimiseInput.setValue(false, controller);
        if (cap != null) controller.maxTemperatureInput.setValue(cap, controller);
        controller.initAndValidate();
        return controller;
    }

    private static double temperature(CoupledMCMC controller, int rank) throws Exception {
        return (Double) invoke(controller, "getTemperature", new Class<?>[] {int.class}, rank);
    }

    @Test void geometricCapAppliesAtInitializationWithAdaptationDisabled() throws Exception {
        CoupledMCMC controller = ladder(false, 100, 3.0);
        assertEquals(1.0, controller.deltaTemperature, 0.0);
        assertEquals(3.0, temperature(controller, 3), 0.0);
        assertEquals(0.0, temperature(controller, 0), 0.0);
    }

    @Test void betaCapBoundsTheHottestRankWithAdaptationDisabled() throws Exception {
        CoupledMCMC controller = ladder(true, 100, 3.0);
        assertEquals(1.0, controller.deltaTemperature, 1e-15);
        assertEquals(3.0, temperature(controller, 3), 1e-14);
        assertEquals(1.0 / 3.0, temperature(controller, 1), 1e-14);
    }

    @Test void zeroSpacingAndZeroCapsLeaveAllRanksUnheated() throws Exception {
        for (boolean beta : new boolean[] {false, true}) {
            for (CoupledMCMC controller : new CoupledMCMC[] {ladder(beta, 0, null), ladder(beta, 10, 0.0)}) {
                for (int rank = 0; rank < 4; rank++) assertEquals(0.0, temperature(controller, rank), 0.0);
            }
        }
    }

    @Test void absentCapKeepsConfiguredFiniteSpacing() throws Exception {
        for (boolean beta : new boolean[] {false, true}) {
            CoupledMCMC controller = ladder(beta, 2, null);
            assertEquals(2.0, controller.deltaTemperature, 0.0);
            assertEquals(beta ? 15.0 : 6.0, temperature(controller, 3), 1e-13);
        }
    }

    @Test void rejectsInvalidConfiguredSpacingAndCaps() {
        for (boolean beta : new boolean[] {false, true}) {
            for (double invalid : new double[] {-0.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> ladder(beta, invalid, null));
                assertThrows(IllegalArgumentException.class, () -> ladder(beta, 0.1, invalid));
            }
        }
    }

    @Test void adaptationBoundsClampNegativeProposalsAndApplyModeSpecificCap() throws Exception {
        for (boolean beta : new boolean[] {false, true}) {
            CoupledMCMC controller = ladder(beta, 0.1, 3.0);
            assertEquals(0.0, (Double) invoke(controller, "applySpacingBounds", new Class<?>[] {double.class}, -1.0), 0.0);
            assertEquals(1.0, (Double) invoke(controller, "applySpacingBounds", new Class<?>[] {double.class}, 100.0), 1e-15);
        }
    }

    @Test void localAcceptanceUsesOnlyTheTraversedWindow() {
        CoupledMCMC controller = new CoupledMCMC();
        controller.targetAcceptanceProbabilityInput.setValue(0.5, controller);
        controller.maxAdaptationInput.setValue(1.0, controller);
        controller.totalSwaps = 100;
        controller.successfullSwaps = 80;
        // After removing the oldest: size=20, start=2, numerator=9, denominator=18.
        // Correct local rate .5 permits the positive global update; obsolete 9/20 blocks it.
        controller.acceptedSwaps = new ArrayList<>(Collections.nCopies(21, false));
        for (int index = 3; index < 12; index++) controller.acceptedSwaps.set(index, true);
        assertEquals(0.003, controller.getDelta(), 1e-15);
    }

    @Test void oppositeLocalAndGlobalSidesStillBlockAdaptation() {
        CoupledMCMC controller = new CoupledMCMC();
        controller.targetAcceptanceProbabilityInput.setValue(0.5, controller);
        controller.totalSwaps = 100;
        controller.successfullSwaps = 80;
        controller.acceptedSwaps = new ArrayList<>(Collections.nCopies(21, false));
        assertEquals(0.0, controller.getDelta(), 0.0);
    }

    @Test void runtimeNeighbourSelectionResolvesAdjacentCurrentRanks() throws Exception {
        CoupledMCMC controller = ladder(false, 0.1, null);
        controller.neighbourSwappingInput.setValue(true, controller);
        controller.chains = new HeatedChain[] {new ResidentChain(3), new ResidentChain(1), new ResidentChain(0), new ResidentChain(2)};
        Randomizer.setSeed(19);
        for (int trial = 0; trial < 30; trial++) {
            int[] pair = (int[]) invoke(controller, "selectRuntimePair", new Class<?>[0]);
            assertEquals(controller.chains[pair[0]].getChainNr() + 1, controller.chains[pair[1]].getChainNr());
        }
    }

    @Test void runtimeNonNeighbourSelectionUsesDistinctPhysicalIndicesInRankOrder() throws Exception {
        CoupledMCMC controller = ladder(false, 0, null);
        controller.neighbourSwappingInput.setValue(false, controller);
        controller.chains = new HeatedChain[] {new ResidentChain(2), new ResidentChain(0), new ResidentChain(3), new ResidentChain(1)};
        Randomizer.setSeed(37);
        boolean sawNonNeighbour = false;
        for (int trial = 0; trial < 100; trial++) {
            int[] pair = (int[]) invoke(controller, "selectRuntimePair", new Class<?>[0]);
            assertNotEquals(pair[0], pair[1]);
            int difference = controller.chains[pair[1]].getChainNr() - controller.chains[pair[0]].getChainNr();
            assertTrue(difference > 0);
            sawNonNeighbour |= difference > 1;
        }
        assertTrue(sawNonNeighbour);
    }

    @Test void joiningAChildFailurePropagatesTheOriginalCause() throws Exception {
        CoupledMCMC controller = new CoupledMCMC();
        AssertionError cause = new AssertionError("deliberate child failure");
        controller.chains = new HeatedChain[] {new ResidentChain(0) {
            @Override protected long runTillResample(long until) { throw cause; }
        }};
        controller.finishTimes = new long[1];
        invoke(controller, "initializeWorkerRngNames", new Class<?>[0]);
        CoupledMCMC.HeatedChainThread child = controller.new HeatedChainThread(0, 1);
        controller.threads = new Thread[] {child};
        child.start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> invoke(controller, "joinChain", new Class<?>[] {int.class}, 0));
        assertSame(cause, failure.getCause());
    }
}
