package coupledMCMC;

import beast.base.inference.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class H1RegressionTest {
    private static final String TOKEN = "coupledMCMC.CoupledMCMC";
    private static final String PARAMETER_ID = "literal-" + TOKEN + "-parameter";

    @Test void wholePosteriorInitRunPreservesLiteralFilenameAndId() throws Exception {
        verifyLiteralPreservation(false);
    }

    @Test void likelihoodOnlyInitRunPreservesLiteralFilenameAndId() throws Exception {
        verifyLiteralPreservation(true);
    }

    static void assertLocalClassOrigin() throws Exception {
        Path expected = Path.of("..", "classes").toRealPath();
        for (Class<?> type : new Class<?>[]{CoupledMCMC.class, HeatedChain.class,
                HeatedChainLikelihoodOnly.class, CoupledLogger.class}) {
            assertEquals(expected, Path.of(type.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toRealPath(), "wrong class origin: " + type.getName());
        }
    }

    private void verifyLiteralPreservation(boolean likelihoodOnly) throws Exception {
        assertLocalClassOrigin();
        String tag = likelihoodOnly ? "H1-likelihood-only" : "H1-whole-posterior";
        CoupledMCMC c = model(new CoupledMCMC(), tag, "quadratic", 0.0, 0.0, 4);
        Path expectedFile = directory(tag).resolve(TOKEN + ".log");
        Logger original = c.loggersInput.get().get(0);
        original.fileNameInput.setValue(expectedFile.toString(), original);
        c.startStateInput.get().stateNodeInput.get().get(0).setID(PARAMETER_ID);
        c.nrOfChainsInput.setValue(2, c);
        c.resampleEveryInput.setValue(2, c);
        c.preScheduleInput.setValue(false, c);
        c.optimiseInput.setValue(false, c);
        c.deltaTemperatureInput.setValue(1.0, c);
        c.heatLikelihoodOnlyInput.setValue(likelihoodOnly, c);
        c.setStateFile(directory(tag).resolve(tag + ".state").toString(), false);
        c.initAndValidate();
        try {
            control(c, "preflightRestart");
            control(c, "initRun");
            assertEquals(2, c.chains.length);
            for (int physical = 0; physical < c.chains.length; physical++) {
                HeatedChain chain = c.chains[physical];
                assertEquals(likelihoodOnly ? HeatedChainLikelihoodOnly.class : HeatedChain.class,
                        chain.getClass(), "XML cloning selected the wrong actual chain class");
                // A no-op proposal initializes likelihood-only bookkeeping at x=0:
                // prior=-1, likelihood=-6, posterior=-7.
                chain.runTillResample(0);
                double beta = physical == 0 ? 1.0 : 0.5;
                assertEquals(beta, chain.getBeta(), 0.0);
                assertEquals(likelihoodOnly ? -1.0 - 6.0 * beta : -7.0 * beta,
                        chain.getCurrentLogLikelihood(), 0.0, "actual heating semantics");
                row("H1_XML_LITERAL_PRESERVATION.tsv",
                        "case\tphysical\tclass\texpected_parameter_id\tactual_parameter_id"
                                + "\texpected_cold_filename\tactual_filename\tbeta\theated_score",
                        tag, physical, chain.getClass().getName(), PARAMETER_ID, x(chain).getID(),
                        expectedFile, chain.coupledLoggersInput.get().get(0).fileNameInput.get(),
                        chain.getBeta(), chain.getCurrentLogLikelihood());
            }
            assertAll(
                    () -> assertEquals(expectedFile.toString(),
                            c.chains[0].coupledLoggersInput.get().get(0).fileNameInput.get(),
                            "H1_LITERAL_FILENAME: initRun rewrote unrelated controller-name text"),
                    () -> assertEquals(PARAMETER_ID, x(c.chains[0]).getID(),
                            "H1_LITERAL_ID: initRun rewrote the cold model's unrelated ID"),
                    () -> assertEquals(PARAMETER_ID, x(c.chains[1]).getID(),
                            "H1_LITERAL_ID: initRun rewrote the hot model's unrelated ID"),
                    () -> assertTrue(Files.exists(expectedFile),
                            "H1_LITERAL_OUTPUT: the cold logger did not create its requested path"));
        } finally {
            closeChains(c);
        }
    }
}
