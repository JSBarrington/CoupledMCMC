package coupledMCMC;

import beast.base.internal.json.JSONObject;
import beast.base.util.Randomizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.DocumentBuilderFactory;

import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for prospective adaptation stops; every model executes public run(). */
@Timeout(30)
class AdaptationBarrierRegressionTest {
    enum Mode { ADAPTIVE, CAPPED, DISABLED, WRITER, READER }

    @Test void adaptiveBoundaryIsProspective() throws Exception {
        fresh("adaptiveBoundaryIsProspective", Mode.ADAPTIVE);
    }

    @Test void cappedAdaptationResumesEveryChain() throws Exception {
        fresh("cappedAdaptationResumesEveryChain", Mode.CAPPED);
    }

    @Test void disabledOptimisationPreservesAsynchrony() throws Exception {
        fresh("disabledOptimisationPreservesAsynchrony", Mode.DISABLED);
    }

    @Test @Timeout(45) void restoredCountControlsFirstBarrier() throws Exception {
        String tag = "ABR-restoredCountControlsFirstBarrier";
        Path stateFile = directory(tag + "-state").resolve(tag + ".state");
        Probe writer = configured(tag, tag + "-writer", Mode.WRITER, stateFile);
        execute(writer);
        assertEquals(List.of(true, true), writer.acceptedSwaps);
        assertEquals("4", writer.lastDescriptor.get("absolute_checkpoint_sample"));
        assertEquals("2", writer.lastDescriptor.get("totalSwaps"));

        Probe reader = configured(tag, tag + "-reader", Mode.READER, stateFile);
        execute(reader);
        // Local sample 6 is not a checkpoint: B remains the publication at local 4.
        assertEquals("B", getUnchecked(reader, "committedSlot"));
        assertEquals("4", reader.lastDescriptor.get("prior_sample_offset"));
        assertEquals("4", reader.lastDescriptor.get("segment_controller_sample"));
        assertEquals("8", reader.lastDescriptor.get("absolute_checkpoint_sample"));
        assertEquals("4", reader.lastDescriptor.get("totalSwaps"));
        assertEquals(0.202, Double.parseDouble(reader.lastDescriptor.get("deltaTemperature")), 1e-15);
        assertEquals(0.203, reader.deltaTemperature, 1e-15);
    }

    private void fresh(String method, Mode mode) throws Exception {
        String tag = "ABR-" + method;
        execute(configured(tag, tag, mode, directory(tag).resolve(tag + ".state")));
    }

    private Probe configured(String methodTag, String controllerTag, Mode mode, Path stateFile) throws Exception {
        Probe c = new Probe(methodTag, controllerTag, mode);
        long length = mode == Mode.WRITER ? 4 : mode == Mode.READER ? 6 : 7;
        model(c, controllerTag, "linear", 0.0, 0.0, length);
        c.nrOfChainsInput.setValue(3, c);
        c.resampleEveryInput.setValue(2, c);
        c.deltaTemperatureInput.setValue(0.2, c);
        c.useBetaDistributionInput.setValue(false, c);
        c.optimiseInput.setValue(mode != Mode.DISABLED, c);
        c.optimiseDelayInput.setValue(mode == Mode.WRITER || mode == Mode.READER ? 2 : 1, c);
        c.preScheduleInput.setValue(true, c);
        c.neighbourSwappingInput.setValue(false, c);
        c.heatLikelihoodOnlyInput.setValue(false, c);
        c.targetAcceptanceProbabilityInput.setValue(0.234, c);
        c.maxAdaptationInput.setValue(0.001, c);
        c.storeEveryInput.setValue(mode == Mode.WRITER || mode == Mode.READER ? 4 : -1, c);
        if (mode == Mode.CAPPED) c.maxTemperatureInput.setValue(0.4, c);
        else assertNull(c.maxTemperatureInput.get());
        c.setStateFile(stateFile.toString(), mode == Mode.READER);
        c.initAndValidate();
        c.event("CONFIGURATION", "chains", 3, "length", length, "resampleEvery", 2,
                "optimise", mode != Mode.DISABLED, "delay", c.optimiseDelayInput.get(),
                "storeEvery", c.storeEveryInput.get(), "initialSpacing", c.deltaTemperature,
                "maximum", mode == Mode.CAPPED ? "0.4" : "absent", "entry", "public run()",
                "java", System.getProperty("java.version"), "javaHome", System.getProperty("java.home"),
                "home", System.getProperty("user.home"), "tmp", System.getProperty("java.io.tmpdir"),
                "artifacts", ROOT.toString(), "pid", ProcessHandle.current().pid(),
                "perMethodTimeoutSeconds", mode == Mode.WRITER || mode == Mode.READER ? 45 : 30,
                "fixedTerminalSleepMillisPerNormalRun", 5000);
        return c;
    }

    private void execute(Probe c) throws Exception {
        Throwable failure = null;
        long start = System.nanoTime();
        boolean returned = false;
        try {
            c.isolateRandomizerThreadMap();
            c.run();
            returned = true;
            c.verifyComplete();
            c.event("NORMAL_RUN_RETURN", "elapsedNanos", System.nanoTime() - start,
                    "exchanges", c.exchanges, "totalSwaps", c.totalSwaps,
                    "successfulSwaps", c.successfullSwaps, "adaptations", c.adaptations,
                    "checkpoints", c.checkpoints, "spacing", c.deltaTemperature,
                    "nextSamples", Arrays.stream(c.chains).map(HeatedChain::getCurrentSample).toList(),
                    "proposals", Arrays.stream(c.chains).map(v -> step(v).proposals).toList());
        } catch (Throwable t) {
            failure = t;
            try {
                c.event("EXECUTION_FAILURE", "type", t.getClass().getName(),
                        "message", String.valueOf(t.getMessage()),
                        "elapsedNanos", System.nanoTime() - start, "runReturnedNormally", returned);
            } catch (Throwable evidenceFailure) { t.addSuppressed(evidenceFailure); }
        } finally {
            try { c.cleanup(failure != null); }
            catch (Throwable cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure instanceof Exception e) throw e;
        if (failure instanceof Error e) throw e;
        if (failure != null) throw new AssertionError(failure);
        c.event("CONTROLLER_PASS", "diagnosticElapsedNanos", System.nanoTime() - start,
                "throughputBenchmark", false);
    }

    static final class Probe extends CoupledMCMC {
        final String methodTag, controllerTag;
        final Mode mode;
        final Path events;
        final CountDownLatch firstExchangeObserved = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicReference<Throwable> guardianFailure = new AtomicReference<>();
        final Set<Thread> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        volatile boolean emergency, cleaning;
        Thread guardian, initialHeldThread;
        HeatedChain heldChain;
        int exchanges, adaptations, checkpoints;
        long sequence, entryObservedNanos, releasedNanos;
        double heldInitialBeta;
        List<List<Long>> retainedSchedule = List.of();
        Map<String, String> lastDescriptor = Map.of();
        Field randomizerMapField;
        Map<?, ?> priorRandomizerThreadMap, isolatedRandomizerThreadMap;
        boolean randomizerMapInstalled;

        Probe(String methodTag, String controllerTag, Mode mode) {
            this.methodTag = methodTag;
            this.controllerTag = controllerTag;
            this.mode = mode;
            events = ROOT.resolve("reports").resolve(methodTag + "-events.jsonl");
        }

        synchronized void event(String type, Object... pairs) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("sequence", ++sequence);
            record.put("method", methodTag);
            record.put("controller", controllerTag);
            record.put("mode", mode.name());
            record.put("event", type);
            record.put("nanoTime", System.nanoTime());
            for (int i = 0; i < pairs.length; i += 2) record.put((String) pairs[i], pairs[i + 1]);
            try {
                Files.writeString(events, new JSONObject(record).toString() + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }

        void isolateRandomizerThreadMap() throws ReflectiveOperationException {
            assertTrue(threads == null || Arrays.stream(threads).allMatch(t -> t == null || !t.isAlive()));
            assertNull(guardian);
            // BEAST 2.8.0-beta5 seeds thread-name generators by HashMap iteration position.
            // Isolate this fixture before run; retain its RNG stream until every worker stops.
            randomizerMapField = Randomizer.class.getDeclaredField("randomMap");
            randomizerMapField.setAccessible(true);
            priorRandomizerThreadMap = (Map<?, ?>) randomizerMapField.get(null);
            isolatedRandomizerThreadMap = new HashMap<>();
            randomizerMapField.set(null, isolatedRandomizerThreadMap);
            randomizerMapInstalled = true;
            event("RANDOMIZER_THREAD_MAP_ISOLATED", "priorEntryCount", priorRandomizerThreadMap.size(),
                    "isolatedEntryCount", isolatedRandomizerThreadMap.size(), "beforePublicRun", true,
                    "thread", Thread.currentThread().getName());
        }

        void restoreRandomizerThreadMap() {
            if (!randomizerMapInstalled) return;
            try {
                boolean stillOwned = randomizerMapField.get(null) == isolatedRandomizerThreadMap;
                int isolatedEntries = isolatedRandomizerThreadMap.size();
                randomizerMapField.set(null, priorRandomizerThreadMap);
                randomizerMapInstalled = false;
                event("RANDOMIZER_THREAD_MAP_RESTORED", "isolatedEntryCount", isolatedEntries,
                        "restoredEntryCount", priorRandomizerThreadMap.size(), "samePriorMapObject", true,
                        "afterAllWorkerAndGuardianTermination", true, "fullRandomizerStateRestored", false);
                assertTrue(stillOwned, "SETUP_FAILED: fixture Randomizer thread map was replaced externally");
            } catch (IllegalAccessException e) { throw new AssertionError("Cannot restore Randomizer thread map", e); }
        }

        synchronized void rememberThreads() {
            if (threads != null) for (Thread t : threads) if (t != null) owned.add(t);
        }

        String executor() {
            return StackWalker.getInstance().walk(s -> s.map(StackWalker.StackFrame::getMethodName)
                    .filter(n -> n.equals("runPrescheduled") || n.equals("runRuntimeScheduled"))
                    .findFirst().orElse("NONE"));
        }

        @SuppressWarnings("unchecked")
        @Override void buildSchedule() {
            assertTrue(threads == null || Arrays.stream(threads).allMatch(t -> t == null || !t.isAlive()));
            assertTrue(randomizerMapInstalled);
            long initializedSeed = Randomizer.getSeed(); // Lazily create this controller's entry without drawing.
            event("SCHEDULE_SEED_INITIALIZED", "thread", Thread.currentThread().getName(),
                    "entryCount", isolatedRandomizerThreadMap.size(), "seedBeforeReset", initializedSeed);
            assertEquals(1, isolatedRandomizerThreadMap.size());
            Randomizer.setSeed(28);
            super.buildSchedule();
            long observedSeed = Randomizer.getSeed();
            assertEquals(28L, observedSeed, "SETUP_FAILED: controller RNG must use public seed 28");
            ArrayList<Long>[] actual = (ArrayList<Long>[]) getUnchecked(this, "runTillIteration");
            retainedSchedule = Arrays.stream(actual).map(List::copyOf).toList();
            List<Long> ticks = mode == Mode.WRITER ? List.of(2L, 4L) : List.of(2L, 4L, 6L);
            event("REAL_SCHEDULE", "seed", 28, "observedControllerSeed", observedSeed,
                    "scheduleByPhysical", retainedSchedule,
                    "superCalls", 1, "rngResetAfterRetention", false, "totalSwaps", totalSwaps,
                    "sampleOffset", sampleOffset, "history", List.copyOf(acceptedSwaps));
            assertEquals(List.of(ticks, ticks, List.of()), retainedSchedule, "SETUP_FAILED: unexpected seed-28 schedule");
            if (mode == Mode.READER) {
                assertEquals(2, totalSwaps, "reader must receive the writer's real completed count");
                assertEquals(2, successfullSwaps);
                assertEquals(4L, sampleOffset);
                assertEquals(List.of(true, true), acceptedSwaps);
                assertEquals(0.2, deltaTemperature, 0.0);
            } else {
                assertEquals(0, totalSwaps);
                assertTrue(acceptedSwaps.isEmpty());
            }
            if (mode != Mode.WRITER) arm();
        }

        void arm() {
            assertTrue(BLOCKED.isEmpty(), "SETUP_FAILED: shared fixture barrier was not reset");
            heldChain = chains[2];
            assertEquals(2, heldChain.getChainNr());
            assertEquals(0, step(heldChain).proposals);
            heldInitialBeta = heldChain.getBeta();
            BLOCKED.add(x(heldChain));
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            event("BARRIER_ARMED", "physical", 2, "rank", 2, "beta", heldInitialBeta,
                    "chainIdentity", System.identityHashCode(heldChain),
                    "parameterIdentity", System.identityHashCode(x(heldChain)),
                    "entryBudgetSeconds", 3, "releaseSignalBudgetSeconds", 8, "fixtureBudgetSeconds", 15);
            guardian = new Thread(() -> {
                try {
                    awaitEntry();
                    if (cleaning) return;
                    if (mode == Mode.READER) {
                        observeHeld("READER_INDEPENDENT_ENTRY");
                        if (!cleaning) releaseHeld("guardian: confirmed resumed proposal entry");
                    } else if (mode == Mode.DISABLED) {
                        if (!released.await(8, TimeUnit.SECONDS) && !cleaning) emergencyRelease();
                    } else {
                        if (!firstExchangeObserved.await(8, TimeUnit.SECONDS)) {
                            if (!cleaning) emergencyRelease();
                        } else if (!cleaning) releaseHeld("guardian: first pre-adaptation exchange observed");
                    }
                } catch (Throwable t) {
                    guardianFailure.compareAndSet(null, t);
                    emergency = true;
                    release.countDown();
                    released.countDown();
                }
            }, controllerTag + "-finite-guardian");
            guardian.start();
        }

        void awaitEntry() {
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS), "SETUP_FAILED: proposal entry timeout");
                synchronized (this) {
                    if (entryObservedNanos == 0) entryObservedNanos = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SETUP_FAILED: interrupted while observing entry", e);
            }
        }

        synchronized void releaseHeld(String reason) {
            event("NORMAL_BARRIER_RELEASE", "reason", reason, "releaseCountBefore", release.getCount(),
                    "actor", Thread.currentThread().getName(),
                    "entryObservationToReleaseNanos", System.nanoTime() - entryObservedNanos);
            releasedNanos = System.nanoTime();
            release.countDown();
            released.countDown();
        }

        void emergencyRelease() {
            emergency = true;
            event("EMERGENCY_RELEASE", "reason", "normal release signal deadline expired");
            release.countDown();
            released.countDown();
        }

        void observeHeld(String type) {
            awaitEntry();
            rememberThreads();
            Thread worker = threads[2];
            assertNotNull(worker);
            if (initialHeldThread == null) initialHeldThread = worker;
            List<String> stack = Arrays.stream(worker.getStackTrace())
                    .map(f -> f.getClassName() + "." + f.getMethodName()).toList();
            event(type, "executor", executor(), "physical", 2, "threadId", worker.threadId(),
                    "workerAlive", worker.isAlive(), "workerState", worker.getState().name(),
                    "workerStack", stack, "plannedStop", ((HeatedChainThread) worker).runUntil,
                    "rank", heldChain.getChainNr(), "beta", heldChain.getBeta(),
                    "spacing", deltaTemperature, "nextSample", heldChain.getCurrentSample(),
                    "proposalsCompleted", step(heldChain).proposals,
                    "enteredCount", entered.getCount(), "releaseCount", release.getCount(),
                    "adaptations", adaptations, "emergency", emergency);
            assertFalse(emergency, "SETUP_FAILED: emergency release");
            assertSame(initialHeldThread, worker);
            assertSame(heldChain, chains[2]);
            assertTrue(worker.isAlive(), "held proposal must remain active");
            assertEquals(0L, entered.getCount());
            assertEquals(1L, release.getCount());
            assertEquals(2, heldChain.getChainNr());
            assertEquals(heldInitialBeta, heldChain.getBeta(), 0.0);
            assertEquals(0.2, deltaTemperature, 0.0);
            assertEquals(0, adaptations);
            assertEquals(0, step(heldChain).proposals);
            assertEquals(0L, heldChain.getCurrentSample());
            assertTrue(stack.contains(Step.class.getName() + ".proposal"),
                    "held worker must remain inside the real fixture proposal");
        }

        @Override void swapLoggers(HeatedChain a, HeatedChain b) {
            rememberThreads();
            assertEquals("runPrescheduled", executor());
            exchanges++;
            long coordinate = Math.min(a.getCurrentSample(), b.getCurrentSample()) - 1;
            event("ACCEPTED_EXCHANGE_HOOK", "ordinal", exchanges, "coordinate", coordinate,
                    "executor", executor(), "totalBeforeIncrement", totalSwaps,
                    "successfulBeforeIncrement", successfullSwaps,
                    "ranksAfterRankSwap", List.of(a.getChainNr(), b.getChainNr()));
            assertEquals(2L * exchanges, coordinate);
            assertTrue(a != chains[2] && b != chains[2]);
            if ((mode == Mode.ADAPTIVE || mode == Mode.CAPPED) && exchanges == 1)
                observeHeld("FIRST_PAIR_ONLY_EXCHANGE");
            if (mode == Mode.DISABLED && (exchanges == 1 || exchanges == 3)) {
                observeHeld(exchanges == 1 ? "DISABLED_FIRST_HELD" : "DISABLED_THIRD_HELD");
                if (exchanges == 3) releaseHeld("controller: third disabled-optimisation exchange observed");
            }
            super.swapLoggers(a, b);
            if ((mode == Mode.ADAPTIVE || mode == Mode.CAPPED) && exchanges == 1) {
                event("FIRST_EXCHANGE_RELEASE_SIGNAL", "coordinate", coordinate);
                firstExchangeObserved.countDown();
            }
        }

        @Override double getDelta() {
            adaptations++;
            assertTrue(mode == Mode.ADAPTIVE || mode == Mode.CAPPED || mode == Mode.READER);
            assertEquals("runPrescheduled", executor());
            rememberThreads();
            int expectedTotal = mode == Mode.READER ? adaptations + 2 : adaptations + 1;
            long expectedNext = mode == Mode.READER ? 2L * adaptations + 1 : 2L * adaptations + 3;
            double expectedSpacing = mode == Mode.CAPPED ? 0.2 : 0.2 + (adaptations - 1) * 0.001;
            List<Map<String, Object>> snapshot = new ArrayList<>();
            for (int i = 0; i < chains.length; i++) {
                Thread t = threads[i];
                snapshot.add(Map.of("physical", i, "threadId", t.threadId(), "alive", t.isAlive(),
                        "threadState", t.getState().name(), "plannedStop", ((HeatedChainThread) t).runUntil,
                        "nextSample", chains[i].getCurrentSample(), "rank", chains[i].getChainNr(),
                        "beta", chains[i].getBeta(), "proposals", step(chains[i]).proposals));
            }
            event("ADAPTATION_BOUNDARY_OBSERVATION", "ordinal", adaptations, "executor", executor(),
                    "totalSwaps", totalSwaps, "expectedTotal", expectedTotal,
                    "expectedNextSample", expectedNext, "spacing", deltaTemperature,
                    "workers", snapshot, "releaseCount", release.getCount(),
                    "historyBeforeSuper", List.copyOf(acceptedSwaps));
            assertEquals(expectedTotal, totalSwaps);
            assertEquals(totalSwaps, successfullSwaps);
            assertEquals(expectedSpacing, deltaTemperature, 1e-15);
            assertFalse(emergency, "SETUP_FAILED: emergency release");
            for (int i = 0; i < chains.length; i++) {
                assertFalse(threads[i].isAlive(), "N1 contract: live worker at global adaptation, physical=" + i);
                assertEquals(expectedNext, chains[i].getCurrentSample(),
                        "N1 contract: exact prospective stop required, physical=" + i);
            }
            if (mode == Mode.CAPPED) assertEquals(heldInitialBeta, heldChain.getBeta(), 0.0);
            double actual = super.getDelta();
            event("DELEGATED_ADAPTATION_RESULT", "ordinal", adaptations, "returnedDelta", actual,
                    "historyAfterSuper", List.copyOf(acceptedSwaps), "superCalls", 1);
            assertEquals(0.001, actual, 1e-15);
            return actual;
        }

        @Override void storeStateToFile(long sample) throws IOException {
            super.storeStateToFile(sample);
            checkpoints++;
            try { inspectCommitted(sample); }
            catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("Could not inspect actual committed generation", e); }
        }

        void inspectCommitted(long sample) throws Exception {
            assertTrue(mode == Mode.WRITER || mode == Mode.READER);
            assertEquals(4L, sample);
            assertEquals(1, checkpoints);
            assertEquals(mode == Mode.WRITER ? 0 : 2, adaptations);
            assertEquals(mode == Mode.WRITER ? 2 : 4, totalSwaps);
            String slot = (String) getUnchecked(this, "committedSlot");
            assertEquals(mode == Mode.WRITER ? "A" : "B", slot);
            Path path = Path.of(stateFileName + ".mc3." + slot + ".commit");
            Map<String, String> fields = descriptor(path);
            lastDescriptor = Map.copyOf(fields);
            long absolute = mode == Mode.WRITER ? 4 : 8;
            double savedDelta = mode == Mode.WRITER ? 0.2 : 0.202;
            assertEquals("CoupledMCMC-checkpoint-v1", fields.get("format"));
            assertEquals("POST_EXCHANGE_POST_ADAPTATION", fields.get("epoch"));
            assertEquals(mode == Mode.WRITER ? "1" : "2", fields.get("sequence"));
            assertEquals(mode == Mode.WRITER ? "0" : "4", fields.get("prior_sample_offset"));
            assertEquals("4", fields.get("segment_controller_sample"));
            assertEquals(Long.toString(absolute), fields.get("absolute_checkpoint_sample"));
            assertEquals(Integer.toString(totalSwaps), fields.get("totalSwaps"));
            assertEquals("true,true", fields.get("acceptedSwaps"));
            assertEquals(savedDelta, Double.parseDouble(fields.get("deltaTemperature")), 1e-15);
            Set<Integer> physicals = new HashSet<>();
            List<Map<String, Object>> rankFiles = new ArrayList<>();
            for (int rank = 0; rank < 3; rank++) {
                String prefix = "rank." + rank + ".";
                assertEquals(Integer.toString(rank), fields.get(prefix + "rank"));
                int physical = Integer.parseInt(fields.get(prefix + "physical_at_commit"));
                assertTrue(physical >= 0 && physical < 3 && physicals.add(physical));
                assertEquals(rank, chains[physical].getChainNr());
                double savedBeta = Double.parseDouble(fields.get(prefix + "beta"));
                assertEquals(chains[physical].getBeta(), savedBeta, 0.0);
                assertEquals(1.0 / (1.0 + rank * savedDelta), savedBeta, 1e-15);
                assertEquals(5L, chains[physical].getCurrentSample());
                Path relative = Path.of(fields.get(prefix + "file"));
                assertFalse(relative.isAbsolute());
                assertEquals(1, relative.getNameCount());
                Path file = path.getParent().resolve(relative);
                assertTrue(Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) > 0);
                assertEquals(fields.get(prefix + "sha256"), hash(file));
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var document = factory.newDocumentBuilder().parse(file.toFile());
                assertEquals("itsabeastystatewerein", document.getDocumentElement().getTagName());
                assertEquals("2.0", document.getDocumentElement().getAttribute("version"));
                assertEquals(Long.toString(absolute), document.getDocumentElement().getAttribute("sample"));
                String bytes = Files.readString(file);
                String tail = bytes.substring(bytes.indexOf("</itsabeastystatewerein>") + "</itsabeastystatewerein>".length()).trim();
                assertTrue(tail.startsWith("<!--") && tail.endsWith("-->"));
                assertEquals(1, new JSONObject(tail.substring(4, tail.length() - 3).trim()).getJSONArray("operators").length());
                rankFiles.add(Map.of("rank", rank, "physical", physical, "file", file.toString(),
                        "sha256", hash(file), "beta", savedBeta));
            }
            event("COMMITTED_GENERATION", "localSample", sample, "absoluteSample", absolute,
                    "descriptor", path.toString(), "fields", fields, "rankFiles", rankFiles);
        }

        void verifyComplete() {
            int expectedExchanges = mode == Mode.WRITER ? 2 : 3;
            int expectedTotal = mode == Mode.READER ? 5 : expectedExchanges;
            int expectedAdaptations = mode == Mode.WRITER || mode == Mode.DISABLED ? 0 : mode == Mode.READER ? 3 : 2;
            long expectedNext = mode == Mode.WRITER ? 5 : mode == Mode.READER ? 7 : 8;
            double expectedDelta = mode == Mode.ADAPTIVE ? 0.202 : mode == Mode.READER ? 0.203 : 0.2;
            assertEquals(expectedExchanges, exchanges);
            assertEquals(expectedTotal, totalSwaps);
            assertEquals(expectedTotal, successfullSwaps);
            assertEquals(expectedAdaptations, adaptations);
            assertEquals(expectedDelta, deltaTemperature, 1e-15);
            assertEquals(mode == Mode.WRITER || mode == Mode.READER ? 1 : 0, checkpoints);
            ladder(this);
            assertArrayEquals(new int[]{0, 1, 2}, Arrays.stream(chains).mapToInt(HeatedChain::getChainNr).sorted().toArray());
            for (HeatedChain chain : chains) {
                assertEquals(expectedNext, chain.getCurrentSample());
                assertEquals((int) expectedNext, step(chain).proposals);
            }
            if (mode == Mode.CAPPED || mode == Mode.DISABLED) assertEquals(heldInitialBeta, heldChain.getBeta(), 0.0);
            if (mode == Mode.READER) assertEquals(4L, sampleOffset);
        }

        boolean joinUntil(Thread thread, long deadline) {
            boolean interrupted = false;
            while (thread != null && thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
                catch (InterruptedException e) { interrupted = true; }
            }
            return interrupted;
        }

        void cleanup(boolean failed) {
            cleaning = true;
            long releaseCountBefore = release.getCount();
            release.countDown();
            released.countDown();
            firstExchangeObserved.countDown();
            Throwable releaseEvidenceFailure = null;
            try { event("CLEANUP_RELEASE", "afterFailure", failed, "releaseCountBefore", releaseCountBefore); }
            catch (Throwable t) { releaseEvidenceFailure = t; }
            boolean interrupted = Thread.interrupted();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                interrupted |= joinUntil(guardian, deadline);
                rememberThreads();
                List<Thread> workers;
                synchronized (this) { workers = new ArrayList<>(owned); }
                for (Thread worker : workers) interrupted |= joinUntil(worker, deadline);
                boolean stopped = workers.stream().noneMatch(Thread::isAlive) && (guardian == null || !guardian.isAlive());
                List<Map<String, Object>> states = new ArrayList<>();
                for (Thread worker : workers) {
                    Throwable failure = worker instanceof HeatedChainThread h ? h.failure : null;
                    states.add(Map.of("id", worker.threadId(), "name", worker.getName(),
                            "alive", worker.isAlive(), "state", worker.getState().name(),
                            "failure", failure == null ? "NONE" : failure.toString()));
                }
                try {
                    event("FINAL_WORKER_TERMINATION", "allStopped", stopped, "workerCreationCountObserved", workers.size(),
                            "workerTerminationCountObserved", workers.stream().filter(t -> !t.isAlive()).count(),
                            "workers", states, "guardianAlive", guardian != null && guardian.isAlive(),
                            "guardianFailure", String.valueOf(guardianFailure.get()), "emergency", emergency,
                            "entryObservationToNormalReleaseNanos", releasedNanos == 0 ? -1 : releasedNanos - entryObservedNanos,
                            "cleanupDeadlineSeconds", 4);
                    assertTrue(stopped, "SETUP_FAILED: worker survived bounded cleanup");
                } finally {
                    if (stopped) {
                        try { closeChains(this); }
                        finally {
                            BLOCKED.clear();
                            entered = new CountDownLatch(0);
                            release = new CountDownLatch(0);
                            restoreRandomizerThreadMap();
                        }
                    }
                }
                for (Thread worker : workers)
                    if (worker instanceof HeatedChainThread h) assertNull(h.failure, "SETUP_FAILED: worker failed");
                assertNull(guardianFailure.get(), "SETUP_FAILED: guardian failed");
                assertFalse(emergency, "SETUP_FAILED: emergency release occurred");
                if (releaseEvidenceFailure != null) throw new AssertionError("Could not record cleanup release", releaseEvidenceFailure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
