package coupledMCMC;

import beast.base.inference.OperatorSchedule;
import beast.base.inference.State;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggerRoutingRegressionTest {
    private static final class RoutingChain extends HeatedChain {
        RoutingChain(String filename, CoupledLogger... loggers) {
            state = new State();
            operatorSchedule = new OperatorSchedule();
            coupledLoggersInput.get().addAll(Arrays.asList(loggers));
            setStateFileName(filename);
        }
    }

    private static CoupledLogger logger(String id, boolean suppressed) {
        CoupledLogger logger = new CoupledLogger();
        logger.setID(id);
        logger.setSuppressLogging(suppressed);
        logger.setM_out(new PrintStream(new ByteArrayOutputStream()));
        return logger;
    }

    private static void closeStreams(CoupledLogger... loggers) {
        for (CoupledLogger logger : loggers) logger.getM_out().close();
    }

    @Test void permutedLoggerIdsExchangeSuppressionStreamsAndStateFilenames() throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        CoupledLogger ax = logger("x", false), ay = logger("y", true), az = logger("z", false);
        CoupledLogger by = logger("y", false), bz = logger("z", true), bx = logger("x", true);
        PrintStream axs = ax.getM_out(), ays = ay.getM_out(), azs = az.getM_out();
        PrintStream bys = by.getM_out(), bzs = bz.getM_out(), bxs = bx.getM_out();
        RoutingChain a = new RoutingChain("A.state", ax, ay, az);
        RoutingChain b = new RoutingChain("B.state", by, bz, bx);
        CoupledMCMC controller = new CoupledMCMC();
        controller.logHeatedChainsInput.setValue(false, controller);
        try {
            controller.swapLoggers(a, b);
            assertAll(
                () -> assertTrue(ax.getSuppressLogging(), "A.x receives B.x suppression"),
                () -> assertFalse(ay.getSuppressLogging(), "A.y receives B.y suppression"),
                () -> assertTrue(az.getSuppressLogging(), "A.z receives B.z suppression"),
                () -> assertTrue(by.getSuppressLogging(), "B.y receives A.y suppression"),
                () -> assertFalse(bz.getSuppressLogging(), "B.z receives A.z suppression"),
                () -> assertFalse(bx.getSuppressLogging(), "B.x receives A.x suppression"),
                () -> assertSame(bxs, ax.getM_out(), "A.x stream"),
                () -> assertSame(bys, ay.getM_out(), "A.y stream"),
                () -> assertSame(bzs, az.getM_out(), "A.z stream"),
                () -> assertSame(ays, by.getM_out(), "B.y stream"),
                () -> assertSame(azs, bz.getM_out(), "B.z stream"),
                () -> assertSame(axs, bx.getM_out(), "B.x stream"),
                () -> assertEquals("B.state", a.getStateFileName()),
                () -> assertEquals("A.state", b.getStateFileName()),
                () -> assertEquals(Arrays.asList(ax, ay, az), a.coupledLoggersInput.get()),
                () -> assertEquals(Arrays.asList(by, bz, bx), b.coupledLoggersInput.get()));
        } finally {
            closeStreams(ax, ay, az, by, bz, bx);
        }
    }

    @Test void unequalLoggerListsRouteMatchedIdAndLeaveUnmatchedLoggerUntouched() throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        CoupledLogger ax = logger("x", true), extra = logger("extra", true), bx = logger("x", false);
        PrintStream axs = ax.getM_out(), extras = extra.getM_out(), bxs = bx.getM_out();
        RoutingChain a = new RoutingChain("A.state", ax);
        RoutingChain b = new RoutingChain("B.state", extra, bx);
        CoupledMCMC controller = new CoupledMCMC();
        controller.logHeatedChainsInput.setValue(false, controller);
        try {
            assertDoesNotThrow(() -> controller.swapLoggers(a, b),
                    "B[1] x must match A[0] x without indexing A[1]");
            assertAll(
                () -> assertFalse(ax.getSuppressLogging(), "A.x receives B.x suppression"),
                () -> assertTrue(bx.getSuppressLogging(), "B.x receives A.x suppression"),
                () -> assertSame(bxs, ax.getM_out(), "A.x stream"),
                () -> assertSame(axs, bx.getM_out(), "B.x stream"),
                () -> assertEquals("extra", extra.getID()),
                () -> assertTrue(extra.getSuppressLogging(), "unmatched suppression"),
                () -> assertSame(extras, extra.getM_out(), "unmatched stream"),
                () -> assertEquals("B.state", a.getStateFileName()),
                () -> assertEquals("A.state", b.getStateFileName()),
                () -> assertEquals(Arrays.asList(ax), a.coupledLoggersInput.get()),
                () -> assertEquals(Arrays.asList(extra, bx), b.coupledLoggersInput.get()));
        } finally {
            closeStreams(ax, extra, bx);
        }
    }
}
