package coupledMCMC;

import beast.base.util.Randomizer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Quiescent observations only; no replacement or mutation of Randomizer's map. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(40)
class WorkerRngLifetimeRegressionTest {
    private static final int N = 3;
    private static final String PREFIX = "CoupledMCMC-rng-exec-";
    private static final IdentityHashMap<Object, Integer> objectNumbers = new IdentityHashMap<>();

    @Test @Order(1)
    void ordinarySeededInitializationAndReservation() throws Exception {
        assertNoWorkers();
        assertTrue(rngMap().isEmpty(), "Run this class in a fresh Surefire fork (-DreuseForks=false)");
        for (long seed : new long[]{101L, 1L}) {
            String tag = "initialization-seed-" + seed;
            String original = Thread.currentThread().getName();
            Randomizer.getSeed();
            Randomizer.setSeed(seed);
            Map<String, Object> before = rngMap();
            Object controller = before.get(original);
            assertNotNull(controller);
            RngState controllerState = RngState.read(controller);
            CoupledMCMC c = bare();
            assertThrows(IllegalStateException.class, () -> c.new HeatedChainThread(0, 1));
            initialize(c);
            assertEquals(original, Thread.currentThread().getName());
            assertNoWorkers();
            assertTrue(c.threads == null || Arrays.stream(c.threads).allMatch(t -> t == null));
            Registration registration = registration(c);
            Map<String, Object> after = rngMap();
            assertEquals(before.size() + N + 1, after.size());
            assertSame(controller, after.get(original));
            controllerState.assertSameState(RngState.read(controller), "registration consumed controller RNG state");
            evidence(tag, "controller-restored", -1, Thread.currentThread().threadId(), original, controller,
                    seedOf(controller), RngState.read(controller).digest(), "mapBefore=" + before.size() + ",mapAfter=" + after.size());
            Set<Long> previousSeeds = new HashSet<>();
            for (Object generator : before.values()) previousSeeds.add(seedOf(generator));
            Set<Long> workerSeeds = new HashSet<>();
            Set<Object> workers = identities();
            for (int physical = 0; physical < N; physical++) {
                Object generator = after.get(registration.names.get(physical));
                assertNotNull(generator);
                assertTrue(workers.add(generator), "worker generators alias");
                assertNotSame(controller, generator);
                assertTrue(workerSeeds.add(seedOf(generator)), "worker initialization seeds alias");
                assertFalse(previousSeeds.contains(seedOf(generator)), "worker initialization seed aliases a pre-existing entry");
                evidence(tag, "registered", physical, "NONE", registration.names.get(physical), generator,
                        seedOf(generator), RngState.read(generator).digest(), "samplingWorkerStarted=false");
            }
            Object reservation = after.get(registration.reservation);
            assertNotNull(reservation);
            assertFalse(workers.contains(reservation));
            evidence(tag, "reservation", -1, "NONE", registration.reservation, reservation,
                    seedOf(reservation), RngState.read(reservation).digest(), "non-sampling");
        }
    }

    @Test @Order(2)
    void boundedGrowthAndPhysicalContinuity() throws Exception {
        Probe shortRun = execute("bounded-short", 4, true, 1001);
        Probe longRun = execute("bounded-long", 40, true, 1001);
        assertTrue(longRun.workers.size() > shortRun.workers.size(), "fixture did not increase actual segment Threads");
        assertEquals(6, shortRun.workers.size());
        assertEquals(60, longRun.workers.size());
        row("WORKER_RNG_SUMMARY.tsv", "case\tworkers\tdistinct_names\texchanges",
                shortRun.tag, shortRun.workers.size(), shortRun.allNames.size(), shortRun.observations.size());
        row("WORKER_RNG_SUMMARY.tsv", "case\tworkers\tdistinct_names\texchanges",
                longRun.tag, longRun.workers.size(), longRun.allNames.size(), longRun.observations.size());
        // Deliberately first contract assertion: baseline red is real per-segment name growth.
        assertEquals(N, longRun.allNames.size(), "WORKER_RNG_LIFETIME: real replacement Threads must reuse exactly n sampling names");
        assertEquals(N, shortRun.allNames.size());
        verifyExecution(shortRun);
        verifyExecution(longRun);
    }

    @Test @Order(3)
    void initializationFailureRestoresNameAndDoesNotPublish() throws Exception {
        assertNoWorkers();
        String original = Thread.currentThread().getName();
        Randomizer.getSeed();
        Randomizer.setSeed(101);
        CoupledMCMC c = bare();
        initialize(c);
        assertNotNull(get(c, "workerRngNames"));
        int size = rngMap().size();
        // Default becomes -(size+2): reservation=-2, physical0=-1, physical1=0 (rejected).
        Randomizer.setSeed(-(size + 1L));
        long beforeId = executionCounter();
        Map<String, Object> before = rngMap();
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> initialize(c));
            assertTrue(failure.getMessage().contains("Non zero random seed"));
            assertEquals(original, Thread.currentThread().getName(), "failed registration changed controller name");
            assertNull(get(c, "workerRngNames"), "partial or previously published names remain prepared");
            assertTrue(c.threads == null || Arrays.stream(c.threads).allMatch(t -> t == null));
            assertNoWorkers();
            long failedId = executionCounter();
            assertTrue(failedId > beforeId, "failed execution ID was not spent");
            Map<String, Object> after = rngMap();
            Set<String> added = new HashSet<>(after.keySet());
            added.removeAll(before.keySet());
            assertEquals(2, added.size(), "failure did not occur after the intended partial registration");
            assertEquals(1, added.stream().filter(n -> n.endsWith("-reservation")).count());
            assertEquals(1, added.stream().filter(n -> n.endsWith("-physical-0")).count());
            for (String name : added) evidence("initialization-failure", "retained-partial-entry", -1,
                    "NONE", name, after.get(name), seedOf(after.get(name)), RngState.read(after.get(name)).digest(), failure.toString());
            Randomizer.setSeed(101);
            initialize(c);
            assertEquals(original, Thread.currentThread().getName());
            assertTrue(executionCounter() > failedId, "retry recycled the failed execution ID");
            Registration next = registration(c);
            assertTrue(Collections.disjoint(added, next.allNames()));
            row("WORKER_RNG_FAILURE.tsv", "before_id\tfailed_id\tretry_id\tpartial_entries\tname_restored\tpublication_after_failure",
                    beforeId, failedId, executionCounter(), added, true, "null");
            assertNoWorkers();
        } finally {
            // Preserve the failed observation above; keep the JUnit controller usable if it failed.
            Thread.currentThread().setName(original);
            Randomizer.setSeed(101);
        }
    }

    @Test @Order(4)
    void sequentialExecutionNamespaces() throws Exception {
        Probe first = execute("sequential-first", 6, false, 2001);
        verifyExecution(first);
        Registration a = registration(first);
        Map<String, Object> retained = rngMap();
        Probe second = execute("sequential-second", 6, false, 2002);
        verifyExecution(second);
        Registration b = registration(second);
        assertTrue(Collections.disjoint(a.allNames(), b.allNames()), "sequential executions alias names");
        Map<String, Object> after = rngMap();
        for (String oldName : a.allNames()) {
            assertSame(retained.get(oldName), after.get(oldName), "old execution entry was removed/replaced");
            for (String newName : b.allNames()) assertNotSame(after.get(oldName), after.get(newName));
        }
        row("WORKER_RNG_SUMMARY.tsv", "case\tworkers\tdistinct_names\texchanges",
                "sequential-disjoint-retained", first.workers.size() + second.workers.size(),
                a.allNames() + " / " + b.allNames(), first.observations.size() + second.observations.size());
    }

    @Test @Order(5)
    void absentControllerNameUsesReservationFirst() throws Exception {
        final String tag = "absent-controller-reservation-order";
        Thread current = Thread.currentThread();
        String savedName = current.getName();
        assertNoWorkers();
        CoupledMCMC c = bare();
        assertNull(get(c, "workerRngNames"));
        Randomizer.getSeed();
        Randomizer.setSeed(301);
        Map<String, Object> before = rngMap();
        assertFalse(before.isEmpty());
        Map<String, Long> beforeSeeds = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            long seed = seedOf(entry.getValue());
            beforeSeeds.put(entry.getKey(), seed);
            evidence(tag, "pre-existing", -1, "NONE", entry.getKey(), entry.getValue(),
                    seed, RngState.read(entry.getValue()).digest(), "before-helper");
        }
        Set<Long> previousSeeds = new HashSet<>(beforeSeeds.values());
        String absentName = "WRNG-absent-controller-" + executionCounter();
        assertFalse(before.containsKey(absentName), "fixture controller name must be absent");
        try {
            // Do not call Randomizer under this missing name before the helper.
            current.setName(absentName);
            initialize(c);
            String returnedName = current.getName();
            Map<String, Object> after = rngMap();
            Registration registered = registration(c);
            Object controller = after.get(absentName);
            Object reservation = after.get(registered.reservation);
            assertNotNull(controller);
            assertNotNull(reservation);
            long controllerSeed = seedOf(controller);
            long reservationSeed = seedOf(reservation);
            evidence(tag, "controller", -1, current.threadId(), absentName, controller,
                    controllerSeed, RngState.read(controller).digest(),
                    "returnedName=" + returnedName + ",mapBefore=" + before.size() + ",mapAfter=" + after.size());
            evidence(tag, "reservation", -1, "NONE", registered.reservation, reservation,
                    reservationSeed, RngState.read(reservation).digest(), "non-sampling");
            for (int physical = 0; physical < N; physical++) {
                String name = registered.names.get(physical);
                Object generator = after.get(name);
                assertNotNull(generator);
                evidence(tag, "registered", physical, "NONE", name, generator,
                        seedOf(generator), RngState.read(generator).digest(), "samplingWorkerStarted=false");
            }
            assertEquals(absentName, returnedName, "helper did not restore its caller's name");
            assertNoWorkers();
            assertTrue(c.threads == null || Arrays.stream(c.threads).allMatch(t -> t == null));
            // First seed-contract assertion: ATTEMPT1 must fail specifically here.
            assertFalse(previousSeeds.contains(controllerSeed),
                    "WORKER_RNG_RESERVATION_ORDER: newly registered controller duplicates a pre-existing initialization seed");
            assertTrue(previousSeeds.contains(reservationSeed),
                    "the nonsampling reservation must absorb the ordinary duplicate-next allocation");
            assertEquals(before.size() + N + 2, after.size());
            assertFalse(registered.names.contains(registered.reservation));
            assertNotSame(controller, reservation);
            Set<Object> allocated = identities();
            allocated.add(controller);
            allocated.add(reservation);
            Set<Long> workerSeeds = new HashSet<>();
            for (int physical = 0; physical < N; physical++) {
                Object generator = after.get(registered.names.get(physical));
                long seed = seedOf(generator);
                assertTrue(allocated.add(generator), "new generator objects alias");
                assertFalse(previousSeeds.contains(seed), "worker seed duplicates a pre-existing seed");
                assertNotEquals(controllerSeed, seed, "worker seed duplicates the controller seed");
                assertTrue(workerSeeds.add(seed), "physical worker seeds duplicate");
            }
            for (Map.Entry<String, Object> entry : before.entrySet()) {
                assertSame(entry.getValue(), after.get(entry.getKey()), "pre-existing entry was replaced");
                assertEquals(beforeSeeds.get(entry.getKey()).longValue(), seedOf(entry.getValue()),
                        "registration changed a pre-existing initialization seed");
                for (Object generator : allocated) assertNotSame(entry.getValue(), generator);
            }
        } finally {
            current.setName(savedName);
            row("WORKER_RNG_ABSENT_CONTROLLER_CLEANUP.tsv", "case\tsaved_name\trestored_name\tname_restored",
                    tag, savedName, current.getName(), savedName.equals(current.getName()));
        }
    }

    private static CoupledMCMC bare() {
        CoupledMCMC c = new CoupledMCMC();
        c.nrOfChainsInput.setValue(N, c);
        c.initAndValidate();
        return c;
    }

    private static void initialize(CoupledMCMC c) throws Exception {
        control(c, "initializeWorkerRngNames");
    }

    private static long executionCounter() throws Exception {
        return field(CoupledMCMC.class, "nextWorkerRngExecutionId").getLong(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rngMap() throws Exception {
        assertNoWorkers();
        return new LinkedHashMap<>((Map<String, Object>) field(Randomizer.class, "randomMap").get(null));
    }

    private static void assertNoWorkers() {
        for (Thread thread : Thread.getAllStackTraces().keySet())
            assertFalse(thread instanceof CoupledMCMC.HeatedChainThread && thread.isAlive(),
                    "RNG observation requires stopped sampling workers: " + thread.getName());
    }

    private static long seedOf(Object generator) throws Exception {
        return field(generator.getClass(), "initializationSeed").getLong(generator);
    }

    private static Set<Object> identities() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void evidence(String tag, String phase, int physical, Object thread,
                                 String name, Object generator, long seed, String state, Object extra) throws Exception {
        int object = generator == null ? 0 : objectNumbers.computeIfAbsent(generator, key -> objectNumbers.size() + 1);
        row("WORKER_RNG_EVENTS.tsv", "case\tphase\tphysical\tthread_id\tname\tgenerator_ref\tinitial_seed\tstate_sha256\tdetail",
                tag, phase, physical, thread, name, "generator-" + object, seed, state, extra);
    }

    private record Registration(List<String> names, String reservation) {
        Set<String> allNames() {
            Set<String> result = new HashSet<>(names);
            result.add(reservation);
            return result;
        }
    }

    private static Registration registration(CoupledMCMC c) throws Exception {
        String[] names = (String[]) get(c, "workerRngNames");
        assertNotNull(names);
        assertEquals(N, names.length);
        assertTrue(names[0].startsWith(PREFIX));
        String stem = names[0].substring(0, names[0].lastIndexOf("-physical-"));
        for (int i = 0; i < N; i++) assertEquals(stem + "-physical-" + i, names[i]);
        String reservation = stem + "-reservation";
        Map<String, Object> map = rngMap();
        Set<String> namespace = new HashSet<>();
        for (String key : map.keySet()) if (key.startsWith(stem + "-")) namespace.add(key);
        Registration result = new Registration(List.of(names.clone()), reservation);
        assertEquals(result.allNames(), namespace, "namespace must have n sampling entries plus one reservation");
        return result;
    }

    private record Seen(Thread thread, String name, Object generator, long seed, RngState state,
                        int proposals, int rank, double beta) { }

    private static final class Probe extends CoupledMCMC {
        final String tag;
        final boolean prescheduled;
        final List<List<Seen>> observations = new ArrayList<>();
        final Set<Object> workers = identities();
        final Set<String> allNames = new HashSet<>();
        boolean scheduleBuilt;
        Probe(String tag, boolean prescheduled) { this.tag = tag; this.prescheduled = prescheduled; }

        @Override void buildSchedule() {
            super.buildSchedule();
            scheduleBuilt = true;
        }

        @Override void swapLoggers(HeatedChain a, HeatedChain b) {
            try {
                assertNoWorkers();
                Map<String, Object> map = rngMap();
                List<Seen> point = new ArrayList<>();
                for (int physical = 0; physical < N; physical++) {
                    Thread thread = threads[physical];
                    assertNotNull(thread);
                    assertFalse(thread.isAlive());
                    workers.add(thread);
                    allNames.add(thread.getName());
                    Object generator = map.get(thread.getName());
                    Seen seen = new Seen(thread, thread.getName(), generator,
                            generator == null ? Long.MIN_VALUE : seedOf(generator),
                            generator == null ? null : RngState.read(generator),
                            step(chains[physical]).proposals, chains[physical].getChainNr(), chains[physical].getBeta());
                    point.add(seen);
                    evidence(tag, "stopped-exchange-" + (observations.size() + 1), physical, thread.threadId(),
                            seen.name, generator, seen.seed, seen.state == null ? "MISSING" : seen.state.digest(),
                            "rank=" + seen.rank + ",beta=" + seen.beta + ",proposals=" + seen.proposals);
                }
                observations.add(point);
                super.swapLoggers(a, b);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    private static Probe execute(String tag, long length, boolean pre, long seed) throws Exception {
        assertNoWorkers();
        Randomizer.getSeed();
        Randomizer.setSeed(seed);
        Probe c = model(new Probe("WRNG-" + tag, pre), "WRNG-" + tag, "linear", 0.0, 0.0, length);
        c.nrOfChainsInput.setValue(N, c);
        c.resampleEveryInput.setValue(2, c);
        c.preScheduleInput.setValue(pre, c);
        c.neighbourSwappingInput.setValue(false, c);
        c.optimiseInput.setValue(true, c);
        c.optimiseDelayInput.setValue(0, c);
        c.storeEveryInput.setValue(-1, c);
        c.setStateFile(directory("WRNG-" + tag).resolve(tag + ".state").toString(), false);
        c.initAndValidate();
        String original = Thread.currentThread().getName();
        try {
            c.run();
            assertEquals(original, Thread.currentThread().getName());
            assertEquals(pre, c.scheduleBuilt);
            assertEquals(length / 2, c.totalSwaps);
            assertEquals(c.totalSwaps, c.successfullSwaps, "fixture must include genuine accepted rank exchanges");
            assertEquals(length / 2, c.observations.size());
            for (HeatedChain chain : c.chains) {
                assertEquals(length + 1, chain.getCurrentSample());
                assertEquals(length + 1, step(chain).proposals);
            }
            return c;
        } finally {
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            if (c.threads != null) for (Thread thread : c.threads) {
                while (thread != null && thread.isAlive() && System.nanoTime() < deadline) {
                    try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
            boolean stopped = c.threads == null || Arrays.stream(c.threads).allMatch(t -> t == null || !t.isAlive());
            row("WORKER_RNG_CLEANUP.tsv", "case\tall_stopped\tworker_threads_observed", c.tag, stopped, c.workers.size());
            if (stopped) closeChains(c);
            if (interrupted) Thread.currentThread().interrupt();
            assertTrue(stopped, "sampling worker survived bounded test cleanup");
        }
    }

    private static void verifyExecution(Probe c) throws Exception {
        Registration registration = registration(c);
        Map<String, Object> map = rngMap();
        Set<Object> distinct = identities();
        boolean rankMoved = false;
        for (int physical = 0; physical < N; physical++) {
            Object generator = map.get(registration.names.get(physical));
            assertNotNull(generator);
            assertTrue(distinct.add(generator), "physical generator alias");
            long streamSeed = c.observations.get(0).get(physical).seed;
            Constructor<?> constructor = generator.getClass().getDeclaredConstructor(long.class);
            constructor.setAccessible(true);
            Object oracle = constructor.newInstance(streamSeed);
            assertNotSame(generator, oracle);
            Method draw = oracle.getClass().getDeclaredMethod("nextDouble");
            draw.setAccessible(true);
            int drawn = 0;
            Thread priorThread = null;
            for (List<Seen> point : c.observations) {
                Seen seen = point.get(physical);
                assertEquals(registration.names.get(physical), seen.name);
                assertSame(generator, seen.generator, "physical RNG object changed between segments");
                assertEquals(streamSeed, seen.seed, "physical RNG was reseeded");
                assertNotEquals(registration.reservation, seen.name, "reservation used by a sampling worker");
                if (priorThread != null) assertNotSame(priorThread, seen.thread, "fixture must replace actual segment Threads");
                priorThread = seen.thread;
                rankMoved |= seen.rank != physical;
                while (drawn < seen.proposals) { draw.invoke(oracle); drawn++; }
                RngState.read(oracle).assertSameState(seen.state,
                        "physical " + physical + " stream restarted or consumed unexpected draws at proposal " + drawn);
            }
        }
        assertTrue(rankMoved, "no accepted rank change was observed");
        assertEquals(N, c.allNames.size());
    }

    private record RngState(int[] mt, int mti, long gaussianBits, boolean gaussianAvailable) {
        static RngState read(Object generator) throws Exception {
            Class<?> type = generator.getClass();
            return new RngState(((int[]) field(type, "mt").get(generator)).clone(),
                    field(type, "mti").getInt(generator),
                    Double.doubleToLongBits(field(type, "nextNextGaussian").getDouble(generator)),
                    field(type, "haveNextNextGaussian").getBoolean(generator));
        }
        void assertSameState(RngState other, String context) {
            assertNotNull(other, context);
            assertArrayEquals(mt, other.mt, context);
            assertEquals(mti, other.mti, context);
            assertEquals(gaussianBits, other.gaussianBits, context);
            assertEquals(gaussianAvailable, other.gaussianAvailable, context);
        }
        String digest() throws Exception {
            ByteBuffer bytes = ByteBuffer.allocate(mt.length * Integer.BYTES + Integer.BYTES + Long.BYTES + 1);
            for (int value : mt) bytes.putInt(value);
            bytes.putInt(mti).putLong(gaussianBits).put((byte) (gaussianAvailable ? 1 : 0));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        }
    }
}
