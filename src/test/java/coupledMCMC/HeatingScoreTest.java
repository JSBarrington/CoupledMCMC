package coupledMCMC;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Score contracts only; proposal-kernel and XML-construction integration remain planned. */
class HeatingScoreTest {
    static class WholePosteriorChain extends HeatedChain {
        void posterior(double value) { oldLogLikelihood = value; }
    }
    static class LikelihoodOnlyChain extends HeatedChainLikelihoodOnly {
        void scores(double prior, double likelihoodValue) {
            oldLogLikelihood = prior + likelihoodValue;
            oldLogP = likelihoodValue;
        }
    }

    @Test void rankZeroBetaOneKeepsTheWholePosteriorInBothHeatingModes() {
        WholePosteriorChain whole = new WholePosteriorChain();
        LikelihoodOnlyChain likelihood = new LikelihoodOnlyChain();
        whole.posterior(-11.0);
        likelihood.scores(-3.0, -8.0);
        for (HeatedChain chain : new HeatedChain[] {whole, likelihood}) {
            chain.setChainNr(0);
            chain.setTemperature(0, 0.0);
            assertEquals(1.0, chain.getBeta(), 0.0);
            assertEquals(-11.0, chain.getCurrentLogLikelihood(), 0.0);
            assertEquals(-11.0, chain.getScaledLogLikelihood(1.0), 0.0);
        }
    }

    @Test void heatedNegativeControlDistinguishesHeatingModes() {
        WholePosteriorChain whole = new WholePosteriorChain();
        LikelihoodOnlyChain likelihood = new LikelihoodOnlyChain();
        whole.posterior(-11.0);
        likelihood.scores(-3.0, -8.0);
        whole.setBeta(0.25);
        likelihood.setBeta(0.25);
        assertEquals(-2.75, whole.getCurrentLogLikelihood(), 0.0);
        assertEquals(-5.0, likelihood.getCurrentLogLikelihood(), 0.0);
    }
}
