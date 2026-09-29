package coupledMCMC;

import beast.base.inference.Logger;
import beast.base.inference.MCMC;
import beast.base.util.Randomizer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-mode bounds and ordinary restart controls for the qualified contracts. */
@Timeout(180)
class BoundedContractRegressionTest {
    @Test void initialAndAdaptedMaximumHoldInEveryRealSchedulerAndSpacingMode() throws Exception {
        for (boolean beta : new boolean[] {false, true})
            for (boolean pre : new boolean[] {false, true})
                for (boolean near : new boolean[] {false, true}) {
                    String tag = "bounds-" + beta + "-" + pre + "-" + near;
                    Observer c = controller(tag, 3, pre, near, false, 100, 4, 2,
                            directory(tag).resolve("bounds.state"), false);
                    c.useBetaDistributionInput.setValue(beta, c);
                    c.maxTemperatureInput.setValue(1.0, c);
                    c.optimiseInput.setValue(true, c);
                    c.optimiseDelayInput.setValue(1, c);
                    c.targetAcceptanceProbabilityInput.setValue(0.0, c);
                    c.maxAdaptationInput.setValue(10.0, c);
                    c.initAndValidate();
                    double cap = beta ? Math.log1p(1.0) / Math.log(3) : 0.5;
                    assertEquals(cap, c.deltaTemperature, 0.0);
                    try {
                        Randomizer.setSeed(793); c.run();
                        assertEquals(2, c.totalSwaps); assertEquals(2, c.successfullSwaps);
                        assertEquals(cap, c.deltaTemperature, 0.0);
                        for (HeatedChain chain : c.chains) {
                            assertTrue(1.0 / chain.getBeta() - 1.0 <= 1.0 + 1e-12);
                            if (chain.getChainNr() == 0) assertEquals(1.0, chain.getBeta(), 0.0);
                        }
                        assertEquals(cap, Double.parseDouble(descriptor(descriptor(c, "B")).get("deltaTemperature")), 0.0);
                    } finally { closeChains(c); }
                }
    }

    @Test void negativeAdaptationClampsToZeroInEveryRealSchedulerAndSpacingMode() throws Exception {
        for (boolean beta : new boolean[] {false, true})
            for (boolean pre : new boolean[] {false, true})
                for (boolean near : new boolean[] {false, true}) {
                    String tag = "negative-" + beta + "-" + pre + "-" + near;
                    Observer c = controller(tag, 3, pre, near, false, 0.1, 4, 2,
                            directory(tag).resolve("bounds.state"), false);
                    c.useBetaDistributionInput.setValue(beta, c);
                    c.optimiseInput.setValue(true, c); c.optimiseDelayInput.setValue(1, c);
                    c.targetAcceptanceProbabilityInput.setValue(0.9, c);
                    c.maxAdaptationInput.setValue(10.0, c);
                    c.initAndValidate();
                    try {
                        control(c, "preflightRestart"); control(c, "initRun");
                        // Zero-displacement operators retain this rejection discriminator.
                        // Every pair has lower log density at the hotter rank, so both
                        // exchanges reject before adaptation proposes 0.1 - 0.9/2.
                        for (HeatedChain chain : c.chains) {
                            x(chain).setValue(1000000.0 * (3 - chain.getChainNr()));
                            chain.calcCurrentLogLikelihoodRobustly();
                        }
                        Randomizer.setSeed(619);
                        control(c, pre && !near ? "runPrescheduled" : "runRuntimeScheduled");
                        assertEquals(2, c.totalSwaps); assertEquals(0, c.successfullSwaps);
                        assertEquals(0.0, c.deltaTemperature, 0.0);
                        for (HeatedChain chain : c.chains) assertEquals(1.0, chain.getBeta(), 0.0);
                        assertEquals("0.0", descriptor(descriptor(c, "B")).get("deltaTemperature"));
                    } finally { closeChains(c); }
                }
    }

    @Test void oneChainFreshAndResumeMatchOrdinaryMCMCForAllFlagsDespiteIrrelevantTemperatureInputs() throws Exception {
        Logger.LogFileMode originalMode = Logger.FILE_MODE;
        try {
            for (boolean pre : new boolean[] {false, true}) for (boolean near : new boolean[] {false, true}) {
                String tag = "single-restart-" + pre + "-" + near;
                Path plainFile = directory(tag + "-plain").resolve("plain.state");
                Path singleFile = directory(tag + "-single").resolve("single.state");
                for (boolean resume : new boolean[] {false, true}) {
                    Logger.FILE_MODE = resume ? Logger.LogFileMode.resume : Logger.LogFileMode.overwrite;
                    MCMC plain = model(new MCMC(), tag + "-plain", "quadratic", 0.125, 0.0, 4);
                    CoupledMCMC single = model(new CoupledMCMC(), tag + "-single", "quadratic", 0.125, 0.0, 4);
                    single.nrOfChainsInput.setValue(1, single);
                    single.preScheduleInput.setValue(pre, single);
                    single.neighbourSwappingInput.setValue(near, single);
                    single.useBetaDistributionInput.setValue(true, single);
                    single.deltaTemperatureInput.setValue(Double.NaN, single);
                    single.maxTemperatureInput.setValue(-1.0, single);
                    plain.initAndValidate(); single.initAndValidate();
                    plain.setStateFile(plainFile.toString(), resume);
                    single.setStateFile(singleFile.toString(), resume);
                    Randomizer.setSeed(915); plain.run();
                    Randomizer.setSeed(915); single.run();
                    assertNull(single.chains);
                    assertEquals(x(plain).getValue(), x(single).getValue());
                    assertEquals(payload(step(plain)), payload(step(single)));
                    assertEquals(step(plain).proposals, step(single).proposals);
                    assertEquals(Files.readString(plainFile), Files.readString(singleFile));
                    assertEquals(semanticLog(directory(tag + "-plain").resolve("cold.log")),
                            semanticLog(directory(tag + "-single").resolve("cold.log")));
                    assertFalse(Files.exists(Path.of(singleFile + ".mc3.A.commit")));
                }
            }
        } finally { Logger.FILE_MODE = originalMode; }
    }
}
