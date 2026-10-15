package coupledMCMC;

import beast.base.util.Randomizer;
import beast.pkgmgmt.BEASTClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** H5 lifecycle contracts exercised through real models and public multi-chain run(). */
@Timeout(25)
public class WorkerLifecycleRegressionTest {
    private static volatile Probe active;
    private enum Mode { FIRST_FAILS, LATER_FAILS, INTERRUPT, LOGGER_FAILS, BOUNDARY, NONCOOPERATIVE, NORMAL }

    @Test void failedWorkerDrainsCooperativeSibling() throws Exception {
        try (Probe c = configured("failedWorkerDrainsCooperativeSibling", Mode.FIRST_FAILS, false, 2)) {
            c.start();
            c.awaitHolders();
            c.awaitFailureRecorded(0);
            assertTrue(c.done.await(3, TimeUnit.SECONDS), "H5_WORKER_FAILURE_NOT_PROPAGATED");
            c.event("BEFORE_TEST_CLEANUP", "physicalFailure", 0);
            c.assertWorkerPrimary(0);
            assertFalse(c.threads[1].isAlive(), "H5_LEAK_COOPERATIVE_SIBLING: controller returned while physical 1 was alive");
            assertTrue(c.interruptions.get() >= 1, "sibling must receive cancellation");
            c.assertStopped();
        }
    }

    @Test void laterWorkerFailureIsDetectedBehindEarlierBlockedWorker() throws Exception {
        try (Probe c = configured("laterWorkerFailureIsDetectedBehindEarlierBlockedWorker", Mode.LATER_FAILS, false, 2)) {
            c.start();
            c.awaitHolders();
            c.awaitFailureRecorded(1);
            // A recorded child failure and entered proposal latches precede this bounded observation.
            boolean propagated = c.done.await(1500, TimeUnit.MILLISECONDS);
            c.event("BEFORE_TEST_CLEANUP", "physicalFailure", 1, "propagated", propagated,
                    "earlierWorkerAlive", c.threads[0].isAlive(), "observationMillis", 1500,
                    "controllerInJoin", waitingInJoin(c.controller), "controllerStack", Arrays.toString(c.controller.getStackTrace()));
            if (!propagated) {
                assertTrue(c.threads[0].isAlive(), "SETUP_FAILED: earlier worker was not still held");
                assertTrue(waitingInJoin(c.controller), "SETUP_FAILED: controller was not awaiting earlier worker");
            }
            assertTrue(propagated, "H5_LATER_FAILURE_HIDDEN: physical 1 recorded failure while controller still waited for physical 0");
            c.assertWorkerPrimary(1);
            assertTrue(c.interruptions.get() >= 1);
            c.assertStopped();
        }
    }

    @Test void controllerInterruptionDrainsWorkersAndPreservesInterrupt() throws Exception {
        try (Probe c = configured("controllerInterruptionDrainsWorkersAndPreservesInterrupt", Mode.INTERRUPT, false, 2)) {
            c.start();
            c.awaitHolders();
            await(() -> waitingInJoin(c.controller), 3, "controller did not reach worker join");
            c.event("CONTROLLER_INTERRUPT_REQUEST", "allHoldersEntered", true);
            c.controller.interrupt();
            assertTrue(c.done.await(3, TimeUnit.SECONDS), "H5_CONTROLLER_INTERRUPT_NOT_PROPAGATED");
            c.event("BEFORE_TEST_CLEANUP", "interruptAtCatch", c.interruptAtCatch);
            assertNotNull(c.failure.get());
            assertInstanceOf(InterruptedException.class, c.failure.get().getCause());
            assertTrue(c.interruptAtCatch, "H5_CONTROLLER_INTERRUPT_STATUS_LOST");
            assertTrue(c.noLiveWorkers(), "H5_CONTROLLER_INTERRUPT_LEAK: controller returned with live workers");
            assertEquals(2, c.cancelledWorkers());
        }
    }

    @Test void controllerLoggerFailureDrainsActiveNonparticipant() throws Exception {
        try (Probe c = configured("controllerLoggerFailureDrainsActiveNonparticipant", Mode.LOGGER_FAILS, true, 3)) {
            c.start();
            c.awaitHolders();
            assertTrue(c.controllerFailureTriggered.await(3, TimeUnit.SECONDS), "logger failure hook not reached");
            assertTrue(c.done.await(3, TimeUnit.SECONDS), "controller logger failure not propagated");
            c.event("BEFORE_TEST_CLEANUP", "exactControllerPrimary", c.failure.get() == c.controllerPrimary);
            assertSame(c.controllerPrimary, c.failure.get(), "controller RuntimeException object must remain primary");
            assertTrue(c.nonparticipantAliveAtFailure, "fixture must prove an active nonparticipant at the controller failure");
            assertFalse(c.threads[2].isAlive(), "H5_CONTROLLER_FAILURE_LEAK: nonparticipating physical 2 survived controller failure");
            assertTrue(c.cancelled[2].get() > 0);
            c.assertStopped();
        }
    }

    @Test void cancellationStopsBeforeNextProposal() throws Exception {
        try (Probe c = configured("cancellationStopsBeforeNextProposal", Mode.BOUNDARY, false, 2)) {
            c.start();
            c.awaitHolders();
            c.awaitFailureRecorded(0);
            assertTrue(c.cancellationObserved.await(3, TimeUnit.SECONDS), "cooperative proposal did not observe cancellation");
            assertEquals(1, c.proposals[1].get());
            assertEquals(0, c.completed[1].get());
            assertTrue(c.threads[1].isAlive(), "current proposal must still be allowed to complete");
            c.finishProposal.countDown();
            assertTrue(c.done.await(3, TimeUnit.SECONDS), "boundary cancellation did not drain");
            c.event("BETWEEN_PROPOSAL_OBSERVATION", "proposals", c.proposals[1].get(),
                    "completed", c.completed[1].get(), "workerFailure", c.workerFailure(1));
            c.assertWorkerPrimary(0);
            assertEquals(1, c.completed[1].get(), "current proposal should finish without custom rollback");
            assertEquals(1, c.proposals[1].get(), "H5_CANCELLATION_STARTED_ANOTHER_PROPOSAL");
            assertInstanceOf(InterruptedException.class, c.workerFailure(1));
            c.assertStopped();
        }
    }

    @Test void noncooperativeWorkersShareOneDrainDeadline() throws Exception {
        try (Probe c = configured("noncooperativeWorkersShareOneDrainDeadline", Mode.NONCOOPERATIVE, false, 3)) {
            c.start();
            c.awaitHolders();
            c.awaitFailureRecorded(0);
            assertTrue(c.done.await(9, TimeUnit.SECONDS), "H5_NONCOOPERATIVE_CONTROLLER_HUNG");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(c.finishedNanos - c.failureTriggeredNanos);
            c.event("NONCOOPERATIVE_DEADLINE_OBSERVATION", "failureToReturnMillis", elapsedMillis,
                    "suppressed", Arrays.toString(c.failure.get().getSuppressed()));
            c.assertWorkerPrimary(0);
            assertTrue(elapsedMillis >= 4800 && elapsedMillis <= 8500,
                    "H5_SHARED_DRAIN_DEADLINE: expected one approximately 5000ms deadline for two workers, observed " + elapsedMillis);
            assertTrue(c.threads[1].isAlive() && c.threads[2].isAlive(), "both intentional noncooperative workers must remain held");
            assertEquals(2, c.cancelledWorkers());
            assertTrue(c.interruptions.get() >= 4, "drain must reissue interruption to both live workers");
            Throwable diagnostic = Arrays.stream(c.failure.get().getSuppressed())
                    .filter(t -> t.getMessage() != null && t.getMessage().toLowerCase().contains("drain")
                            && t.getMessage().toLowerCase().contains("deadline") && t.getMessage().contains("5000"))
                    .findFirst().orElseThrow(() -> new AssertionError("H5_MISSING_UNDRAINED_DIAGNOSTIC"));
            assertInstanceOf(IllegalStateException.class, diagnostic);
            for (int physical : new int[]{1, 2}) {
                assertTrue(diagnostic.getMessage().contains(Integer.toString(physical)), "diagnostic missing physical index");
                assertTrue(Arrays.stream(Thread.State.values()).anyMatch(state -> diagnostic.getMessage().contains(
                                "physical " + physical + " [" + c.threads[physical].getName() + ", " + state.name() + "]")),
                        "diagnostic must associate this physical worker with its name and state");
            }
            System.out.println("H5_NONCOOPERATIVE_DRAIN_MILLIS=" + elapsedMillis + " DIAGNOSTIC=" + diagnostic.getMessage());
        }
    }

    @Test void prescheduledTerminalSleepPreservesInterruption() throws Exception {
        terminalInterruption(true);
    }

    @Test void runtimeTerminalSleepPreservesInterruption() throws Exception {
        terminalInterruption(false);
    }

    @Test void normalPrescheduledRunCompletesWithoutCancellation() throws Exception {
        normalSuccess(true);
    }

    @Test void normalRuntimeRunCompletesWithoutCancellation() throws Exception {
        normalSuccess(false);
    }

    private void terminalInterruption(boolean prescheduled) throws Exception {
        String method = prescheduled ? "prescheduledTerminalSleepPreservesInterruption" : "runtimeTerminalSleepPreservesInterruption";
        try (Probe c = configured(method, Mode.NORMAL, prescheduled, 2)) {
            c.start();
            String scheduler = prescheduled ? "runPrescheduled" : "runRuntimeScheduled";
            await(() -> terminalSleep(c.controller, scheduler) && c.noLiveWorkers(), 4, "terminal sleep not reached");
            c.event("TERMINAL_SLEEP_INTERRUPT_REQUEST", "scheduler", scheduler, "workersStopped", c.noLiveWorkers());
            c.controller.interrupt();
            assertTrue(c.done.await(3, TimeUnit.SECONDS), "terminal interruption did not return");
            c.event("BEFORE_TEST_CLEANUP", "interruptAtCatch", c.interruptAtCatch);
            assertNotNull(c.failure.get(), "H5_TERMINAL_INTERRUPTION_SWALLOWED: run returned normally after terminal sleep interruption");
            assertInstanceOf(InterruptedException.class, c.failure.get().getCause());
            assertTrue(c.interruptAtCatch, "terminal interruption status not preserved");
            c.assertStopped();
        }
    }

    private void normalSuccess(boolean prescheduled) throws Exception {
        String method = prescheduled ? "normalPrescheduledRunCompletesWithoutCancellation" : "normalRuntimeRunCompletesWithoutCancellation";
        try (Probe c = configured(method, Mode.NORMAL, prescheduled, 2)) {
            c.start();
            assertTrue(c.done.await(9, TimeUnit.SECONDS), "normal short run did not complete");
            c.event("NORMAL_SUCCESS", "prescheduled", prescheduled);
            assertNull(c.failure.get());
            assertFalse(c.interruptAtCatch);
            assertEquals(0, c.interruptions.get());
            assertEquals(1, c.totalSwaps);
            assertEquals(1, c.successfullSwaps);
            for (int i = 0; i < 2; i++) {
                assertEquals(3, c.proposals[i].get());
                assertEquals(3, c.completed[i].get());
                assertEquals(3, c.chains[i].getCurrentSample());
                assertNull(c.workerFailure(i));
            }
            ladder(c);
            c.assertStopped();
        }
    }

    private static Probe configured(String tag, Mode mode, boolean prescheduled, int count) throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        assertNull(active, "previous lifecycle fixture was not cleaned");
        Probe c = new Probe(tag, mode, count);
        model(c, "H5-" + tag, "linear", 0.0, 0.0, mode == Mode.LOGGER_FAILS ? 4 : 2);
        LifecycleStep op = new LifecycleStep();
        op.setID("lifecycleStep");
        op.initByName("x", c.startStateInput.get().stateNodeInput.get().get(0), "weight", 1.0, "step", 0.0, "h", 0.0, "scalar", 0.5);
        c.operatorsInput.get().clear();
        c.operatorsInput.setValue(op, c);
        BEASTClassLoader.classLoader.addServices("H5LifecycleFixtures",
                Map.of("beast.base.core.BEASTInterface", Set.of(LifecycleStep.class.getName())));
        c.nrOfChainsInput.setValue(count, c);
        c.resampleEveryInput.setValue(2, c);
        c.preScheduleInput.setValue(prescheduled, c);
        c.neighbourSwappingInput.setValue(false, c);
        c.optimiseInput.setValue(false, c);
        c.deltaTemperatureInput.setValue(0.2, c);
        c.storeEveryInput.setValue(-1, c);
        c.setStateFile(directory("H5-" + tag).resolve("lifecycle.state").toString(), false);
        c.initAndValidate();
        c.isolateRandomizer();
        active = c;
        c.event("CONFIGURATION", "publicRun", true, "prescheduled", prescheduled, "chainCount", count,
                "normalRuntimeTimeoutAdded", false, "frozenProductionPollMillis", 50, "failureDrainMillis", 5000,
                "java", System.getProperty("java.version"), "javaHome", System.getProperty("java.home"),
                "home", System.getProperty("user.home"), "tmp", System.getProperty("java.io.tmpdir"),
                "pid", ProcessHandle.current().pid(),
                "productionCodeSource", CoupledMCMC.class.getProtectionDomain().getCodeSource().getLocation(),
                "fixtureCodeSource", WorkerLifecycleRegressionTest.class.getProtectionDomain().getCodeSource().getLocation());
        return c;
    }

    public static class LifecycleStep extends IntegrationFixtures.Step {
        @Override public double proposal() {
            Probe c = active;
            if (c == null) throw new AssertionError("lifecycle fixture missing");
            int physical = -1;
            for (int i = 0; i < c.chains.length; i++) if (x(c.chains[i]) == xInput.get()) physical = i;
            if (physical < 0) throw new AssertionError("proposal is not in a real cloned heated chain");
            c.remember(Thread.currentThread());
            int ordinal = c.proposals[physical].incrementAndGet();
            if (ordinal == 1) {
                if (c.isHolder(physical)) c.holdProposal(physical);
                else if (c.isFailing(physical)) {
                    c.awaitHolders();
                    c.failureTriggeredNanos = System.nanoTime();
                    c.event("WORKER_FAILURE_TRIGGER", "physical", physical, "original", c.workerPrimary.toString());
                    throw c.workerPrimary;
                }
            }
            double result = super.proposal();
            c.completed[physical].incrementAndGet();
            return result;
        }
    }

    private static final class Probe extends CoupledMCMC implements AutoCloseable {
        final String tag;
        final Mode mode;
        final RuntimeException workerPrimary = new IllegalArgumentException("H5 deliberate original worker failure");
        final RuntimeException controllerPrimary = new IllegalArgumentException("H5 deliberate original controller logger failure");
        final CountDownLatch holdersEntered, release = new CountDownLatch(1), finishProposal = new CountDownLatch(1);
        final CountDownLatch cancellationObserved = new CountDownLatch(1), controllerFailureTriggered = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger interruptions = new AtomicInteger();
        final AtomicInteger[] proposals, completed, cancelled;
        final Set<Thread> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        volatile boolean interruptAtCatch, nonparticipantAliveAtFailure;
        volatile long failureTriggeredNanos, finishedNanos;
        Thread controller;
        Field rngField;
        Object priorRngMap, ownRngMap;

        Probe(String tag, Mode mode, int count) {
            this.tag = tag;
            this.mode = mode;
            holdersEntered = new CountDownLatch(mode == Mode.NORMAL ? 0 : mode == Mode.INTERRUPT || mode == Mode.NONCOOPERATIVE ? 2 : 1);
            proposals = counters(count); completed = counters(count); cancelled = counters(count);
        }

        void isolateRandomizer() throws ReflectiveOperationException {
            rngField = Randomizer.class.getDeclaredField("randomMap");
            rngField.setAccessible(true);
            priorRngMap = rngField.get(null);
            ownRngMap = new HashMap<>();
            rngField.set(null, ownRngMap);
        }

        @Override void buildSchedule() {
            Randomizer.getSeed(); // Create only the controller entry before resetting the public seed.
            Randomizer.setSeed(28);
            super.buildSchedule();
            if (mode == Mode.LOGGER_FAILS) {
                @SuppressWarnings("unchecked") ArrayList<Long>[] schedule = (ArrayList<Long>[]) getUnchecked(this, "runTillIteration");
                assertTrue(schedule[0].contains(2L) && schedule[1].contains(2L) && !schedule[2].contains(2L),
                        "SETUP_FAILED: seed 28 must schedule physical pair 0,1 first");
                event("REAL_PRESCHEDULE", "schedule", Arrays.stream(schedule).map(List::copyOf).toList());
            }
        }

        @Override void swapLoggers(HeatedChain a, HeatedChain b) {
            if (mode == Mode.LOGGER_FAILS) {
                awaitHolders();
                assertTrue(a != chains[2] && b != chains[2]);
                assertFalse(threads[0].isAlive());
                assertFalse(threads[1].isAlive());
                nonparticipantAliveAtFailure = threads[2].isAlive();
                event("CONTROLLER_LOGGER_FAILURE_TRIGGER", "nonparticipantPhysical", 2,
                        "nonparticipantAlive", nonparticipantAliveAtFailure);
                controllerFailureTriggered.countDown();
                throw controllerPrimary;
            }
            super.swapLoggers(a, b);
        }

        void start() {
            controller = new Thread(() -> {
                try { run(); }
                catch (Throwable t) { failure.set(t); }
                finally {
                    interruptAtCatch = Thread.currentThread().isInterrupted();
                    finishedNanos = System.nanoTime();
                    done.countDown();
                }
            }, "H5-controller-" + tag);
            controller.start();
        }

        boolean isFailing(int physical) {
            return mode == Mode.LATER_FAILS ? physical == 1
                    : (mode == Mode.FIRST_FAILS || mode == Mode.BOUNDARY || mode == Mode.NONCOOPERATIVE) && physical == 0;
        }

        boolean isHolder(int physical) {
            return switch (mode) {
                case NORMAL -> false;
                case LOGGER_FAILS -> physical == 2;
                case LATER_FAILS -> physical == 0;
                case INTERRUPT -> true;
                case FIRST_FAILS, BOUNDARY, NONCOOPERATIVE -> physical > 0;
            };
        }

        void holdProposal(int physical) {
            holdersEntered.countDown();
            boolean interrupted = false;
            try {
                if (mode == Mode.NONCOOPERATIVE || mode == Mode.BOUNDARY) {
                    CountDownLatch gate = mode == Mode.BOUNDARY ? finishProposal : release;
                    while (gate.getCount() != 0) {
                        try {
                            if (!gate.await(15, TimeUnit.SECONDS)) throw new AssertionError("SETUP_FAILED: fixture release watchdog");
                        } catch (InterruptedException e) {
                            interrupted = true;
                            interruptions.incrementAndGet(); cancelled[physical].incrementAndGet();
                            cancellationObserved.countDown();
                        }
                    }
                } else {
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("SETUP_FAILED: cooperative release watchdog");
                    } catch (InterruptedException e) {
                        interrupted = true;
                        interruptions.incrementAndGet(); cancelled[physical].incrementAndGet();
                        cancellationObserved.countDown();
                        throw new IllegalStateException("H5 cooperative proposal interrupted", e);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        void awaitHolders() {
            try { assertTrue(holdersEntered.await(3, TimeUnit.SECONDS), "SETUP_FAILED: holders did not enter proposal"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("fixture entry wait interrupted", e); }
        }

        void awaitFailureRecorded(int physical) {
            await(() -> workerFailure(physical) == workerPrimary, 3, "original worker failure was not published");
            event("WORKER_FAILURE_PUBLISHED", "physical", physical, "sameOriginal", workerFailure(physical) == workerPrimary);
        }

        Throwable workerFailure(int physical) {
            return threads != null && threads[physical] instanceof HeatedChainThread h ? h.failure : null;
        }

        void assertWorkerPrimary(int physical) {
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertSame(workerPrimary, failure.get().getCause(), "original child Throwable must remain exact cause");
            assertTrue(failure.get().getMessage().contains("Heated chain failed: " + physical), "failure must identify physical chain");
        }

        synchronized void remember(Thread t) { owned.add(t); }
        synchronized List<Thread> ownedSnapshot() {
            if (threads != null) for (Thread t : threads) if (t != null) owned.add(t);
            return new ArrayList<>(owned);
        }
        boolean noLiveWorkers() {
            return threads != null && ownedSnapshot().stream().noneMatch(Thread::isAlive);
        }
        void assertStopped() { assertTrue(noLiveWorkers(), "owned worker remains alive"); }
        int cancelledWorkers() { return (int) Arrays.stream(cancelled).filter(n -> n.get() > 0).count(); }

        synchronized void event(String event, Object... pairs) {
            List<String> details = new ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) details.add(pairs[i] + "=" + pairs[i + 1]);
            String states = threads == null ? "NOT_STARTED" : java.util.stream.IntStream.range(0, threads.length)
                    .mapToObj(i -> threads[i] == null ? i + ":null" : i + ":" + threads[i].getName() + ":alive=" + threads[i].isAlive()
                            + ":state=" + threads[i].getState() + ":failure=" + workerFailure(i)).toList().toString();
            try { row("H5_WORKER_LIFECYCLE_EVENTS.tsv", "method\tevent\tnanoTime\tdetails\tworkers\tprimary\tcontrollerInterrupt\tinterruptions\tproposalCounts\tcompletedCounts",
                    tag, event, System.nanoTime(), details, states, failure.get(), interruptAtCatch, interruptions.get(),
                    Arrays.stream(proposals).map(AtomicInteger::get).toList(), Arrays.stream(completed).map(AtomicInteger::get).toList()); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }

        @Override public void close() {
            boolean restoreInterrupt = Thread.interrupted();
            release.countDown(); finishProposal.countDown();
            List<Thread> workers = ownedSnapshot();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                // In the hidden-later-failure red, release A and let B propagate naturally first.
                joinUntil(controller, Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
                for (Thread worker : workers) if (worker.isAlive()) worker.interrupt();
                if (controller != null && controller.isAlive()) controller.interrupt();
                for (Thread worker : workers) joinUntil(worker, deadline);
                joinUntil(controller, deadline);
                workers = ownedSnapshot();
                for (Thread worker : workers) joinUntil(worker, deadline);
                boolean stopped = workers.stream().noneMatch(Thread::isAlive) && (controller == null || !controller.isAlive());
                event("FINAL_FIXTURE_CLEANUP", "allWorkersStopped", stopped, "workerCount", workers.size(),
                        "controllerAlive", controller != null && controller.isAlive(), "releaseCount", release.getCount());
                assertTrue(stopped, "SETUP_FAILED: owned thread survived fixture cleanup");
                closeChains(this);
                assertSame(ownRngMap, rngField.get(null), "fixture RNG map ownership changed");
                rngField.set(null, priorRngMap);
                active = null;
            } catch (InterruptedException e) {
                restoreInterrupt = true;
                throw new AssertionError("SETUP_FAILED: fixture cleanup interrupted", e);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("SETUP_FAILED: could not restore fixture RNG map", e);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
    }

    private static AtomicInteger[] counters(int count) {
        AtomicInteger[] values = new AtomicInteger[count];
        Arrays.setAll(values, i -> new AtomicInteger());
        return values;
    }

    private static boolean waitingInJoin(Thread thread) {
        return Arrays.stream(thread.getStackTrace()).anyMatch(f -> f.getClassName().equals(CoupledMCMC.class.getName()) && f.getMethodName().equals("joinChain"));
    }

    private static boolean terminalSleep(Thread thread, String scheduler) {
        List<StackTraceElement> stack = Arrays.asList(thread.getStackTrace());
        return thread.getState() == Thread.State.TIMED_WAITING
                && stack.stream().anyMatch(f -> f.getClassName().equals(Thread.class.getName()) && f.getMethodName().startsWith("sleep"))
                && stack.stream().anyMatch(f -> f.getClassName().equals(CoupledMCMC.class.getName()) && f.getMethodName().equals(scheduler));
    }

    private static void await(BooleanSupplier condition, int seconds, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        assertTrue(condition.getAsBoolean(), "SETUP_FAILED: " + message);
    }

    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        while (thread != null && thread.isAlive() && System.nanoTime() < deadline) {
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        }
    }
}
