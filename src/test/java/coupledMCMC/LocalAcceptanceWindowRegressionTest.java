package coupledMCMC;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalAcceptanceWindowRegressionTest {
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
}
