package coupledMCMC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Scheduler-produced histories and genuine public writer/reader executions. */
class H6RestartHistoryRuntimeTest {
    private static final String HEADER =
            "case\tphase\tobservation\toptimise\tdelay\ttotalSwaps\thistory"
            + "\tlocalSample\tabsoluteSample\tdescriptor\tdescriptorSha256\tdetail";

    @Test void h6RuntimeOptimiseOffCheckpointResumesEmptyHistory() throws Exception {
        runCase("off", false, 2, 1, true);
    }

    @Test void h6RuntimeNegativeDelayCheckpointResumesEmptyHistory() throws Exception {
        runCase("negative", true, -2, 1, true);
    }

    @Test void h6RuntimeZeroDelayCheckpointResumesEmptyHistory() throws Exception {
        runCase("zero", true, 0, 1, true);
    }

    @Test void h6RuntimePositiveDelayBeforeBoundaryResumesReachableHistory() throws Exception {
        runCase("positive-before", true, 2, 1, false);
    }

    @Test void h6RuntimePositiveDelayAtBoundaryResumesReachableHistory() throws Exception {
        runCase("positive-at", true, 2, 2, false);
    }

    @Test void h6RuntimePositiveDelayAfterBoundaryResumesReachableHistory() throws Exception {
        runCase("positive-after", true, 2, 3, false);
    }

    private void runCase(String suffix, boolean optimise, int delay,
                         int writerExchanges, boolean runtime) throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        String tag = "H6-runtime-" + suffix;
        Path stateFile = directory(tag + "-state").resolve(tag + ".state");
        Probe writer = configured(tag, "writer", optimise, delay, runtime,
                0, writerExchanges, stateFile);
        execute(writer);
        assertEquals(writerExchanges, writer.totalSwaps);
        assertEquals(expectedLength(optimise, delay, writerExchanges), writer.acceptedSwaps.size());
        Path writerDescriptor = writer.lastDescriptorPath;
        String writerDescriptorHash = hash(writerDescriptor);

        Probe reader = configured(tag, "reader", optimise, delay, runtime,
                writerExchanges, 1, stateFile);
        execute(reader);
        assertTrue(reader.observedRestoredBeforeWorkers);
        assertEquals(writerExchanges + 1, reader.totalSwaps);
        assertEquals(expectedLength(optimise, delay, writerExchanges + 1), reader.acceptedSwaps.size());
        assertEquals(1, reader.commits);
        assertEquals(Integer.toString(writerExchanges + 1), reader.lastFields.get("totalSwaps"));
        assertEquals(Long.toString(2L * writerExchanges), reader.lastFields.get("prior_sample_offset"));
        assertEquals("2", reader.lastFields.get("segment_controller_sample"));
        assertEquals(Long.toString(2L * (writerExchanges + 1)), reader.lastFields.get("absolute_checkpoint_sample"));
        // The reader publishes the alternate slot and preserves its selected writer generation.
        assertEquals(writerDescriptorHash, hash(writerDescriptor));
    }

    private Probe configured(String tag, String phase, boolean optimise, int delay,
                             boolean runtime, int initialTotal, int exchanges,
                             Path stateFile) throws Exception {
        Probe c = new Probe(tag, phase, optimise, delay, runtime, initialTotal, exchanges);
        model(c, tag + "-" + phase, "linear", 0.0, 0.0, 2L * exchanges);
        c.nrOfChainsInput.setValue(2, c);
        c.resampleEveryInput.setValue(2, c);
        c.storeEveryInput.setValue(2, c);
        c.preScheduleInput.setValue(true, c);
        c.neighbourSwappingInput.setValue(runtime, c);
        c.heatLikelihoodOnlyInput.setValue(false, c);
        c.useBetaDistributionInput.setValue(false, c);
        c.deltaTemperatureInput.setValue(0.2, c);
        c.optimiseInput.setValue(optimise, c);
        c.optimiseDelayInput.setValue(delay, c);
        c.targetAcceptanceProbabilityInput.setValue(0.234, c);
        c.maxAdaptationInput.setValue(0.001, c);
        c.setStateFile(stateFile.toString(), phase.equals("reader"));
        c.initAndValidate();
        return c;
    }

    private void execute(Probe c) throws Exception {
        try {
            assertTrue(BLOCKED.isEmpty(), "Shared proposal barrier must be clear");
            c.run();
            assertEquals(c.requestedExchanges, c.exchanges);
            assertEquals(c.requestedExchanges, c.commits);
            assertEquals(c.initialTotal + c.requestedExchanges, c.totalSwaps);
            assertEquals(c.totalSwaps, c.successfullSwaps);
            assertEquals(c.totalSwaps, c.successfullSwaps0);
            assertEquals(expectedLength(c.configuredOptimise, c.delay, c.totalSwaps), c.acceptedSwaps.size());
            for (HeatedChain chain : c.chains) {
                assertEquals(2L * c.requestedExchanges + 1, chain.getCurrentSample());
                assertEquals(2 * c.requestedExchanges + 1, step(chain).proposals);
            }
            ladder(c);
        } finally {
            c.cleanup();
        }
    }

    private static int expectedLength(boolean optimise, int delay, int total) {
        return optimise ? (int) Math.min((long) total, Math.max(0L, (long) delay)) : 0;
    }

    private static String expectedSerializedHistory(int length) {
        return String.join(",", Collections.nCopies(length, "true"));
    }

    static final class Probe extends CoupledMCMC {
        final String tag, phase;
        final boolean configuredOptimise, runtime;
        final int delay, initialTotal, requestedExchanges;
        final Set<Thread> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        int exchanges, commits;
        boolean observedRestoredBeforeWorkers;
        Path lastDescriptorPath;
        Map<String, String> lastFields = Map.of();

        Probe(String tag, String phase, boolean optimise, int delay,
              boolean runtime, int initialTotal, int exchanges) {
            this.tag = tag;
            this.phase = phase;
            this.configuredOptimise = optimise;
            this.delay = delay;
            this.runtime = runtime;
            this.initialTotal = initialTotal;
            this.requestedExchanges = exchanges;
        }

        private void rememberWorkers() {
            if (threads != null)
                for (Thread worker : threads)
                    if (worker != null) owned.add(worker);
        }

        private String executor() {
            return StackWalker.getInstance().walk(s -> s
                    .map(StackWalker.StackFrame::getMethodName)
                    .filter(n -> n.equals("runRuntimeScheduled") || n.equals("runPrescheduled"))
                    .findFirst().orElse("NONE"));
        }

        @Override void buildSchedule() {
            assertEquals(initialTotal, totalSwaps);
            assertEquals(initialTotal, successfullSwaps);
            assertEquals(initialTotal, successfullSwaps0);
            assertEquals(2L * initialTotal, sampleOffset);
            assertEquals(expectedLength(configuredOptimise, delay, initialTotal), acceptedSwaps.size());
            assertTrue(threads == null || Arrays.stream(threads).allMatch(t -> t == null));
            for (HeatedChain chain : chains) {
                assertEquals(0L, chain.getCurrentSample());
                assertEquals(0, step(chain).proposals);
            }
            super.buildSchedule();
            observedRestoredBeforeWorkers = phase.equals("reader");
            try {
                row("H6_RUNTIME.tsv", HEADER, tag, phase,
                        phase.equals("reader") ? "RESTORED_BEFORE_WORKERS" : "FRESH_BEFORE_WORKERS",
                        configuredOptimise, delay, totalSwaps, List.copyOf(acceptedSwaps),
                        0, sampleOffset, "-", "-",
                        "public run; genuine initRun; zero proposals; history not injected");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override void swapLoggers(HeatedChain a, HeatedChain b) {
            rememberWorkers();
            assertEquals(runtime ? "runRuntimeScheduled" : "runPrescheduled", executor());
            // This accepted-exchange hook precedes the production append/increment.
            assertEquals(initialTotal + exchanges, totalSwaps);
            assertEquals(expectedLength(configuredOptimise, delay, totalSwaps), acceptedSwaps.size());
            assertEquals(2L * (exchanges + 1), Math.min(a.getCurrentSample(), b.getCurrentSample()) - 1);
            super.swapLoggers(a, b);
            exchanges++;
        }

        @Override void storeStateToFile(long sample) throws IOException {
            rememberWorkers();
            for (int k = 0; k < chains.length; k++) {
                assertFalse(threads[k].isAlive());
                assertEquals(sample + 1, chains[k].getCurrentSample());
            }
            super.storeStateToFile(sample);
            commits++;
            try {
                assertEquals(initialTotal + commits, totalSwaps);
                assertEquals(2L * commits, sample);
                int expected = expectedLength(configuredOptimise, delay, totalSwaps);
                assertEquals(expected, acceptedSwaps.size());
                String slot = (String) getUnchecked(this, "committedSlot");
                Path path = Path.of(stateFileName + ".mc3." + slot + ".commit");
                Map<String, String> fields = descriptor(path);
                assertEquals("CoupledMCMC-checkpoint-v1", fields.get("format"));
                assertEquals("POST_EXCHANGE_POST_ADAPTATION", fields.get("epoch"));
                assertEquals(Integer.toString(totalSwaps), fields.get("totalSwaps"));
                assertEquals(Integer.toString(totalSwaps), fields.get("sequence"));
                assertEquals(expectedSerializedHistory(expected), fields.get("acceptedSwaps"));
                assertEquals(Boolean.toString(configuredOptimise), fields.get("config.optimise"));
                assertEquals(Integer.toString(delay), fields.get("config.optimiseDelay"));
                assertEquals(Long.toString(sample), fields.get("segment_controller_sample"));
                assertEquals(Long.toString(2L * initialTotal), fields.get("prior_sample_offset"));
                assertEquals(Long.toString(2L * initialTotal + sample), fields.get("absolute_checkpoint_sample"));
                List<String> rankEvidence = new ArrayList<>();
                for (int rank = 0; rank < chains.length; rank++) {
                    Path file = path.getParent().resolve(fields.get("rank." + rank + ".file"));
                    assertTrue(Files.isRegularFile(file));
                    assertFalse(Files.isSymbolicLink(file));
                    assertTrue(Files.size(file) > 0);
                    String actualHash = hash(file);
                    assertEquals(fields.get("rank." + rank + ".sha256"), actualHash);
                    rankEvidence.add(rank + ":" + file + ":" + actualHash);
                }
                lastDescriptorPath = path;
                lastFields = Map.copyOf(fields);
                row("H6_RUNTIME.tsv", HEADER, tag, phase, "COMMITTED_POST_ADAPTATION",
                        configuredOptimise, delay, totalSwaps, fields.get("acceptedSwaps"),
                        sample, 2L * initialTotal + sample, path, hash(path),
                        "executor=" + executor() + ";rankFiles=" + rankEvidence);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Could not inspect genuine H6 committed generation", e);
            }
        }

        void cleanup() throws Exception {
            rememberWorkers();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            for (Thread worker : owned) {
                while (worker.isAlive() && System.nanoTime() < deadline) {
                    long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    worker.join(millis);
                }
            }
            boolean allStopped = owned.stream().noneMatch(Thread::isAlive);
            row("H6_RUNTIME.tsv", HEADER, tag, phase, "OWNED_WORKER_CLEANUP",
                    configuredOptimise, delay, totalSwaps, List.copyOf(acceptedSwaps),
                    "-", sampleOffset, "-", "-", "allStopped=" + allStopped + ";observedWorkers=" + owned.size());
            assertTrue(allStopped, "H6 fixture worker survived bounded cleanup");
            try {
                for (Thread worker : owned)
                    if (worker instanceof HeatedChainThread h)
                        assertNull(h.failure, "H6 fixture worker failed");
            } finally {
                closeChains(this);
            }
        }
    }
}
