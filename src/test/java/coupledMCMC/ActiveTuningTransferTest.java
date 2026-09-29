package coupledMCMC;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static coupledMCMC.PortTestSupport.*;

class ActiveTuningTransferTest {
    @Test void counterOrderingIsPreservedWhenInputAndResidentInventoriesAgree() {
        ScalarOperator a = new ScalarOperator("move", 10), b = new ScalarOperator("move", 30);
        ResidentChain left = new ResidentChain(0, a), right = new ResidentChain(1, b);
        // Also populate the legacy inventory so the stock counter-order defect is
        // discriminated independently of its separate active-inventory defect.
        left.schedule.operatorsInput.get().add(a);
        right.schedule.operatorsInput.get().add(b);
        new CoupledMCMC().swapOperatorTuning(left, right);
        assertPayload(a, 30);
        assertPayload(b, 10);
    }

    private static void assertPayload(ScalarOperator operator, int base) {
        assertEquals(base + 1, operator.get_m_nNrAccepted());
        assertEquals(base + 2, operator.get_m_nNrRejected());
        assertEquals(base + 3, operator.get_m_nNrAcceptedForCorrection());
        assertEquals(base + 4, operator.get_m_nNrRejectedForCorrection());
        assertEquals(base + 0.5, operator.scalar, 0.0);
    }

    @Test void transfersActivePayloadsByIdDespiteReorderedInventories() {
        ScalarOperator ax = new ScalarOperator("x", 10), ay = new ScalarOperator("y", 20);
        ScalarOperator bx = new ScalarOperator("x", 30), by = new ScalarOperator("y", 40);
        new CoupledMCMC().swapOperatorTuning(new ResidentChain(0, ax, ay), new ResidentChain(1, by, bx));
        assertPayload(ax, 30);
        assertPayload(ay, 40);
        assertPayload(bx, 10);
        assertPayload(by, 20);
        // Diagnostic rejection subcounts are expressly outside the five-field transfer.
        assertEquals(15, ax.invalidRejections());
        assertEquals(16, ax.operatorRejections());
        assertEquals(35, bx.invalidRejections());
        assertEquals(36, bx.operatorRejections());
    }

    @Test void repeatedObjectInInventoryTransfersExactlyOnce() {
        ScalarOperator a = new ScalarOperator("x", 10), b = new ScalarOperator("x", 30);
        new CoupledMCMC().swapOperatorTuning(new ResidentChain(0, a, a), new ResidentChain(1, b));
        assertPayload(a, 30);
        assertPayload(b, 10);
        assertEquals(1, a.writes);
        assertEquals(1, b.writes);
    }

    @Test void distinctObjectsWithDuplicateIdFailBeforeMutation() {
        ScalarOperator a = new ScalarOperator("x", 10), duplicate = new ScalarOperator("x", 20);
        ScalarOperator b = new ScalarOperator("x", 30);
        assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(
                new ResidentChain(0, a, duplicate), new ResidentChain(1, b)));
        assertPayload(a, 10);
        assertPayload(b, 30);
        assertEquals(0, a.writes + b.writes + duplicate.writes);
    }

    @Test void missingOrUnusableIdFailsBeforeMutation() {
        for (String id : new String[] {null, "", " \t", "unknown"}) {
            ScalarOperator a = new ScalarOperator(id, 10), b = new ScalarOperator("x", 30);
            assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(
                    new ResidentChain(0, a), new ResidentChain(1, b)));
            assertPayload(a, 10);
            assertPayload(b, 30);
        }
    }

    @Test void unequalIdSetsFailBeforeMutation() {
        ScalarOperator a = new ScalarOperator("x", 10), b = new ScalarOperator("y", 30);
        assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(
                new ResidentChain(0, a), new ResidentChain(1, b)));
        assertPayload(a, 10);
        assertPayload(b, 30);
    }

    @Test void matchingIdRequiresExactClassIdentity() {
        ScalarOperator a = new ScalarOperator("x", 10), b = new OtherOperator("x", 30);
        assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(
                new ResidentChain(0, a), new ResidentChain(1, b)));
        assertPayload(a, 10);
        assertPayload(b, 30);
    }

    @Test void crossChainObjectAliasFailsBeforeMutation() {
        ScalarOperator a = new ScalarOperator("x", 10);
        assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(
                new ResidentChain(0, a), new ResidentChain(1, a)));
        assertPayload(a, 10);
    }

    @Test void laterPayloadReadFailureLeavesAllEarlierPairsUntouched() {
        ScalarOperator ax = new ScalarOperator("x", 10), ay = new ScalarOperator("y", 20);
        ScalarOperator bx = new ScalarOperator("x", 30), by = new ScalarOperator("y", 40);
        by.failRead = true;
        assertThrows(IllegalStateException.class, () -> new CoupledMCMC().swapOperatorTuning(
                new ResidentChain(0, ax, ay), new ResidentChain(1, bx, by)));
        assertPayload(ax, 10);
        assertPayload(ay, 20);
        assertPayload(bx, 30);
        assertPayload(by, 40);
        assertEquals(0, ax.writes + ay.writes + bx.writes + by.writes);
    }

    @Test void nullAndEmptyActiveInventoriesFailClosed() {
        ResidentChain empty = new ResidentChain(0);
        ResidentChain other = new ResidentChain(1, new ScalarOperator("x", 30));
        assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(empty, other));
        empty.schedule.operators.add(null);
        assertThrows(IllegalArgumentException.class, () -> new CoupledMCMC().swapOperatorTuning(empty, other));
    }
}
