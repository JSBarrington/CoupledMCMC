package coupledMCMC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** H9: observe genuine initRun and public writer/reader runs, without production seams. */
class H9WorkingStateFilenameTest {
    private static final String NAMING_HEADER =
            "case\tcontroller\texpected\tobserved\tdistinct\tobservation";
    private static final String RUNTIME_HEADER =
            "case\tphase\tobservation\tcontroller\tphysical\trank\tworking\tx\tproposals\tdetail";

    @Test void h9InitRunCanonicalStatePreservesHistoricalNames() throws Exception {
        direct("canonical", Path.of("run.state"), List.of("run.0state", "run.1state"),
                "H9_CANONICAL_CONTROL");
    }

    @Test void h9InitRunSuffixlessCheckpointHasDistinctNames() throws Exception {
        direct("suffixless", Path.of("run.checkpoint"),
                List.of("run.checkpoint.0state", "run.checkpoint.1state"), "H9_SUFFIXLESS_COLLISION");
    }

    @Test void h9InitRunRepeatedStateReplacesOnlyTerminalSuffix() throws Exception {
        direct("repeated", Path.of("run.state.backup.state"),
                List.of("run.state.backup.0state", "run.state.backup.1state"), "H9_REPEATED_STATE_MANGLING");
    }

    @Test void h9InitRunNativeNestedPathKeepsBasenameOnly() throws Exception {
        direct("native-nested", Path.of("parent", "child", "run.state"),
                List.of("run.0state", "run.1state"), "H9_NATIVE_PATH_CONTROL");
    }

    @Test void h9InitRunNonterminalStateAppendsMarker() throws Exception {
        direct("nonterminal", Path.of("run.state.backup"),
                List.of("run.state.backup.0state", "run.state.backup.1state"), "H9_NONTERMINAL_STATE_MANGLING");
    }

    @Test void h9RuntimeSuffixlessCheckpointAndRankRestore() throws Exception {
        runtimeRestart("suffixless", "run.checkpoint",
                List.of("run.checkpoint.0state", "run.checkpoint.1state"));
    }

    @Test void h9RuntimeRepeatedStateCheckpointAndRankRestore() throws Exception {
        runtimeRestart("repeated", "run.state.backup.state",
                List.of("run.state.backup.0state", "run.state.backup.1state"));
    }

    @Test void h9RuntimeCanonicalStateCheckpointAndRankRestore() throws Exception {
        runtimeRestart("canonical", "run.state",
                List.of("run.0state", "run.1state"));
    }

    private void direct(String tag, Path controllerPath, List<String> expected, String signature) throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        CoupledMCMC c = controller("H9-direct-" + tag, 2, false, true, false,
                0.0, 2, -1, controllerPath, false);
        try {
            control(c, "preflightRestart");
            control(c, "initRun");
            List<String> observed = names(c);
            boolean distinct = observed.stream().distinct().count() == 2;
            // Emit both observations before the assertions, including meaningful baseline reds.
            row("H9_NAMING.tsv", NAMING_HEADER, tag, get(c, "stateFileName"),
                    expected, observed, distinct, "genuine preflightRestart/initRun; zero proposals");
            assertEquals(controllerPath.toString(), get(c, "stateFileName"), "controller path changed");
            assertTrue(distinct, signature + ": mutable working filenames collide: " + observed);
            assertEquals(expected, observed, signature + ": terminal-suffix derivation");
            for (HeatedChain chain : c.chains) {
                assertEquals(1, Path.of(chain.getStateFileName()).getNameCount(), "basename-only placement");
                assertFalse(Path.of(chain.getStateFileName()).isAbsolute());
                assertEquals(0, chain.getCurrentSample());
                assertEquals(0, step(chain).proposals);
            }
        } finally {
            closeChains(c);
        }
    }

    private void runtimeRestart(String tag, String basename, List<String> expected) throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        assertTrue(BLOCKED.isEmpty(), "Shared proposal barrier must be clear");
        Path controllerPath = directory("H9-runtime-" + tag + "-state").resolve(basename);
        Probe writer = configured(tag, "writer", controllerPath, expected, Map.of());
        execute(writer);
        assertEquals(1, writer.commits);
        assertEquals(1, writer.exchanges);
        assertEquals("A", get(writer, "committedSlot"));
        assertFalse(Files.exists(Path.of(controllerPath + ".mc3.B.commit")));
        Map<Path, String> immutableA = new LinkedHashMap<>();
        immutableA.put(Path.of(controllerPath + ".mc3.A.commit"), hash(Path.of(controllerPath + ".mc3.A.commit")));
        for (Saved saved : writer.savedByRank.values()) immutableA.put(saved.file(), saved.sha256());

        // Only this run's mutable files in Surefire's target/test-classes cwd are poisoned.
        // A reader consulting one of these files instead of committed rank bytes cannot initialize.
        Path cwd = Path.of("").toAbsolutePath().normalize();
        assertEquals("test-classes", cwd.getFileName().toString());
        for (String name : expected) {
            Path mutable = Path.of(name).toAbsolutePath().normalize();
            assertEquals(cwd, mutable.getParent());
            assertTrue(Files.isRegularFile(mutable), "normal execution must write mutable state: " + mutable);
            Files.writeString(mutable, "H9_POISONED_MUTABLE_WORKING_FILE\n");
            row("H9_RUNTIME.tsv", RUNTIME_HEADER, tag, "between-runs", "MUTABLE_POISONED",
                    controllerPath, "-", "-", name, "-", "-", "path=" + mutable + ";sha256=" + hash(mutable));
        }
        assertImmutable(immutableA, "after mutable working-file poison");

        Probe reader = configured(tag, "reader", controllerPath, expected, writer.savedByRank);
        execute(reader);
        assertTrue(reader.observedRankRestore, "rank-restored values must be seen before workers");
        assertEquals(1, reader.commits);
        assertEquals(1, reader.exchanges);
        assertEquals("B", get(reader, "committedSlot"));
        assertEquals(2, reader.totalSwaps);
        assertEquals(2, reader.sampleOffset);
        assertImmutable(immutableA, "after reader normal execution and B publication");
        for (String name : expected)
            assertFalse(Files.readString(Path.of(name)).contains("H9_POISONED"), "normal reader working checkpoint missing");
        row("H9_RUNTIME.tsv", RUNTIME_HEADER, tag, "reader", "A_GENERATION_REMAINS_RESTORE_ONLY",
                controllerPath, "-", "-", expected, "-", "-", immutableA);
    }

    private static void assertImmutable(Map<Path, String> expected, String phase) throws Exception {
        for (Map.Entry<Path, String> file : expected.entrySet())
            assertEquals(file.getValue(), hash(file.getKey()), phase + ": " + file.getKey());
    }

    private Probe configured(String tag, String phase, Path path, List<String> names,
                             Map<Integer, Saved> restored) throws Exception {
        Probe c = new Probe(tag, phase, path, names, restored);
        model(c, "H9-runtime-" + tag + "-" + phase, "linear", 1.0, 0.0, 2);
        c.nrOfChainsInput.setValue(2, c);
        c.resampleEveryInput.setValue(2, c);
        c.storeEveryInput.setValue(2, c);
        c.preScheduleInput.setValue(true, c);
        c.neighbourSwappingInput.setValue(true, c);
        c.heatLikelihoodOnlyInput.setValue(false, c);
        c.useBetaDistributionInput.setValue(false, c);
        c.deltaTemperatureInput.setValue(0.0, c);
        c.optimiseInput.setValue(false, c);
        c.setStateFile(path.toString(), phase.equals("reader"));
        c.initAndValidate();
        return c;
    }

    private void execute(Probe c) throws Exception {
        try {
            c.run();
            assertEquals(1, c.commits);
            assertEquals(1, c.exchanges);
            assertEquals(c.phase.equals("reader") ? 2 : 1, c.totalSwaps);
            assertEquals(c.totalSwaps, c.successfullSwaps);
            for (HeatedChain chain : c.chains) {
                assertEquals(3, chain.getCurrentSample());
                assertEquals(3, step(chain).proposals);
            }
            c.observeWorkingNames("AFTER_PUBLIC_RUN");
            ladder(c);
        } finally {
            c.cleanup();
        }
    }

    private static List<String> names(CoupledMCMC c) {
        return Arrays.stream(c.chains).map(HeatedChain::getStateFileName).toList();
    }

    private record Saved(double value, List<Object> operatorPayload, Path file, String sha256) { }

    private static final class Probe extends CoupledMCMC {
        final String tag, phase;
        final Path controllerPath;
        final List<String> expectedNames;
        final Map<Integer, Saved> expectedRestore;
        final Map<Integer, Saved> savedByRank = new LinkedHashMap<>();
        final Set<Thread> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        int commits, exchanges;
        boolean observedRankRestore;

        Probe(String tag, String phase, Path path, List<String> names, Map<Integer, Saved> expectedRestore) {
            this.tag = tag;
            this.phase = phase;
            this.controllerPath = path;
            this.expectedNames = List.copyOf(names);
            this.expectedRestore = Map.copyOf(expectedRestore);
        }

        @Override void buildSchedule() {
            observeWorkingNames("AFTER_INITRUN_FINALLY_BEFORE_WORKERS");
            for (int physical = 0; physical < chains.length; physical++) {
                HeatedChain chain = chains[physical];
                assertEquals(physical, chain.getChainNr());
                assertEquals(expectedNames.get(physical), chain.getStateFileName());
                assertEquals(0, chain.getCurrentSample());
                assertEquals(0, step(chain).proposals);
                if (phase.equals("reader")) {
                    Saved expected = expectedRestore.get(physical);
                    assertNotNull(expected);
                    assertEquals(expected.value(), x(chain).getValue(), 0.0, "actual committed rank state restoration");
                    assertEquals(expected.operatorPayload(), payload(step(chain)), "actual committed operator restoration");
                    Object generation = getUnchecked(this, "restoreGeneration");
                    Path[] rankFiles = (Path[]) getUnchecked(generation, "rankFiles");
                    assertEquals(expected.file(), rankFiles[physical]);
                    assertEquals(2, sampleOffset);
                    evidence("COMMITTED_RANK_RESTORED", physical, chain,
                            "rankFile=" + rankFiles[physical] + ";sha256=" + expected.sha256()
                                    + ";payload=" + payload(step(chain)) + ";mutable files poisoned");
                } else {
                    // Distinct genuine states allow rank-addressed restore to be distinguished.
                    x(chain).setValue(10.0 + physical);
                    step(chain).scalar = physical + 0.25;
                    chain.calcCurrentLogLikelihoodRobustly();
                }
            }
            observedRankRestore = phase.equals("reader");
            super.buildSchedule();
        }

        @Override void swapLoggers(HeatedChain a, HeatedChain b) {
            rememberWorkers();
            String aName = a.getStateFileName(), bName = b.getStateFileName();
            super.swapLoggers(a, b);
            assertEquals(bName, a.getStateFileName(), "accepted exchange retains filename swapping");
            assertEquals(aName, b.getStateFileName(), "accepted exchange retains filename swapping");
            exchanges++;
            observeWorkingNames("AFTER_ACCEPTED_EXCHANGE");
        }

        @Override void storeStateToFile(long sample) throws IOException {
            rememberWorkers();
            List<String> before = names(this);
            for (int physical = 0; physical < chains.length; physical++) {
                assertFalse(threads[physical].isAlive());
                assertEquals(sample + 1, chains[physical].getCurrentSample());
            }
            super.storeStateToFile(sample);
            commits++;
            assertEquals(before, names(this), "committed-write finally must restore mutable names");
            observeWorkingNames("AFTER_COMMITTED_WRITE_FINALLY");
            try {
                String slot = (String) get(this, "committedSlot");
                assertEquals(phase.equals("reader") ? "B" : "A", slot);
                Path descriptorPath = Path.of(controllerPath + ".mc3." + slot + ".commit").toAbsolutePath();
                assertEquals(controllerPath.getFileName() + ".mc3." + slot + ".commit",
                        descriptorPath.getFileName().toString(), "unchanged A/B descriptor naming");
                Map<String, String> fields = descriptor(descriptorPath);
                assertEquals("CoupledMCMC-checkpoint-v1", fields.get("format"));
                assertEquals("POST_EXCHANGE_POST_ADAPTATION", fields.get("epoch"));
                assertEquals("2", fields.get("segment_controller_sample"));
                assertEquals(phase.equals("reader") ? "2" : "0", fields.get("prior_sample_offset"));
                assertEquals(phase.equals("reader") ? "4" : "2", fields.get("absolute_checkpoint_sample"));
                for (int physical = 0; physical < chains.length; physical++) {
                    HeatedChain chain = chains[physical];
                    int rank = chain.getChainNr();
                    String expectedName = controllerPath.getFileName() + ".mc3." + slot + ".commit.rank-" + rank + ".state";
                    assertEquals(expectedName, fields.get("rank." + rank + ".file"), "unchanged descriptor rank.*.file");
                    Path rankFile = descriptorPath.getParent().resolve(expectedName);
                    assertTrue(Files.isRegularFile(rankFile));
                    assertFalse(Files.isSymbolicLink(rankFile));
                    String sha256 = hash(rankFile);
                    assertEquals(sha256, fields.get("rank." + rank + ".sha256"));
                    assertNotEquals(rankFile, Path.of(chain.getStateFileName()).toAbsolutePath());
                    Saved saved = new Saved(x(chain).getValue(), List.copyOf(payload(step(chain))), rankFile, sha256);
                    savedByRank.put(rank, saved);
                    evidence("COMMITTED_GENERATION", physical, chain,
                            "descriptor=" + descriptorPath + ";descriptorSha256=" + hash(descriptorPath)
                                    + ";rank.file=" + expectedName + ";rankSha256=" + sha256
                                    + ";payload=" + saved.operatorPayload());
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Could not inspect H9 genuine committed generation", e);
            }
        }

        void observeWorkingNames(String observation) {
            List<String> actual = names(this);
            for (int physical = 0; physical < chains.length; physical++)
                evidence(observation, physical, chains[physical], "allWorkingNames=" + actual);
            assertEquals(controllerPath.toString(), stateFileName, "controller state path unchanged");
            assertEquals(2, actual.stream().distinct().count(), "mutable working filenames collide");
            for (HeatedChain chain : chains) {
                assertEquals(expectedNames.get(chain.getChainNr()), chain.getStateFileName(),
                        "working name follows unchanged rank/name exchange behavior");
                assertEquals(1, Path.of(chain.getStateFileName()).getNameCount());
                assertFalse(Path.of(chain.getStateFileName()).isAbsolute(), "working files remain basename-only");
            }
        }

        private void evidence(String observation, int physical, HeatedChain chain, String detail) {
            try {
                row("H9_RUNTIME.tsv", RUNTIME_HEADER, tag, phase, observation, controllerPath,
                        physical, chain.getChainNr(), chain.getStateFileName(), x(chain).getValue(),
                        step(chain).proposals, detail);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private void rememberWorkers() {
            if (threads != null)
                for (Thread worker : threads) if (worker != null) owned.add(worker);
        }

        void cleanup() throws Exception {
            rememberWorkers();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            for (Thread worker : owned)
                while (worker.isAlive() && System.nanoTime() < deadline)
                    worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            boolean allStopped = owned.stream().noneMatch(Thread::isAlive);
            try {
                row("H9_RUNTIME.tsv", RUNTIME_HEADER, tag, phase, "OWNED_WORKER_CLEANUP", controllerPath,
                        "-", "-", "-", "-", "-", "allStopped=" + allStopped + ";observedWorkers=" + owned.size());
                assertTrue(allStopped, "H9 fixture worker survived bounded cleanup");
                for (Thread worker : owned)
                    if (worker instanceof HeatedChainThread h) assertNull(h.failure, "H9 fixture worker failed");
            } finally {
                closeChains(this);
            }
        }
    }
}
