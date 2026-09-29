package coupledMCMC;

import beast.base.inference.MCMC;
import beast.base.inference.Operator;
import beast.base.inference.State;
import beast.base.util.Randomizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded behavioral integration through unchanged production paths; no equation-only substitutes. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(180)
class IntegrationRegressionTest {
    private static final String ROUTING_HEADER = "requirement\tfixture\tphysical\tbefore\tafter\tresult";

    private void exchange(boolean accept) throws Exception {
        String ir = accept ? "IR01" : "IR02";
        Observer c = initialized(ir, 2, false, true, 1.0, 2, -1);
        try {
            scoreAndStop(c.chains[0], 0, accept ? 0 : 2000, 0, 1.0, 2);
            scoreAndStop(c.chains[1], 1, accept ? 1 : -2000, 1, 1.0, 2);
            Map<String, Object> a = snapshot(c.chains[0]), b = snapshot(c.chains[1]);
            control(c, "runRuntimeScheduled");
            Map<String, Object> aa = snapshot(c.chains[0]), bb = snapshot(c.chains[1]);
            row("EXCHANGE_ROUTING.tsv", ROUTING_HEADER, ir, "actual runRuntimeScheduled", 0, a, aa, "observed");
            row("EXCHANGE_ROUTING.tsv", ROUTING_HEADER, ir, "actual runRuntimeScheduled", 1, b, bb, "observed");
            for (String key : List.of("physical", "stateIdentity", "modelIdentity", "x", "logger", "rejectIv", "rejectOp")) {
                assertEquals(a.get(key), aa.get(key), key); assertEquals(b.get(key), bb.get(key), key);
            }
            for (String key : List.of("rank", "beta", "stream", "suppressed", "stateFile", "payload")) {
                assertEquals((accept ? b : a).get(key), aa.get(key), key);
                assertEquals((accept ? a : b).get(key), bb.get(key), key);
            }
            assertEquals(1, c.totalSwaps); assertEquals(accept ? 1 : 0, c.successfullSwaps);
            assertEquals(accept ? 1 : 0, c.successfullSwaps0); ladder(c);
            for (HeatedChain chain : c.chains) { chain.log(3); if (chain.coupledLoggersInput.get().get(0).getM_out() != null) chain.coupledLoggersInput.get().get(0).getM_out().flush(); }
            List<String> lines = semanticLog(directory(ir).resolve("cold.log"));
            String last = lines.get(lines.size() - 1);
            double logged = Double.parseDouble(last.split("\\s+")[1]);
            assertEquals(accept ? 1.0 : 2000.0, logged, 0.0);
            row("EXCHANGE_ROUTING.tsv", ROUTING_HEADER, ir, "cold output sample 3", accept ? 1 : 0, "rank0 identity", last, "PASS");
        } finally { closeChains(c); }
    }
    @Test @Order(1) void ir01AcceptedExchangeMovesRankRoutingAndPayloadOnly() throws Exception { exchange(true); }
    @Test @Order(2) void ir02RejectedExchangePreservesRankRoutingAndPayload() throws Exception { exchange(false); }

    private void counter(boolean cold) throws Exception {
        String tag = cold ? "IR03-cold" : "IR03-hot";
        Observer c = initialized(tag, 4, false, false, 0.25, 2, -1);
        HeatedChain[] original = c.chains.clone();
        c.chains = new HeatedChain[] {original[2], original[0], original[3], original[1]};
        try {
            for (int i = 0; i < 4; i++) scoreAndStop(c.chains[i], i, 0, c.chains[i].getChainNr(), 0.25, 2);
            int rankA = cold ? 0 : 2, rankB = cold ? 1 : 3;
            long seed = choosePair(c, rankA, rankB);
            String before = ranks(c);
            control(c, "runRuntimeScheduled");
            assertEquals(1, c.successfullSwaps); assertEquals(cold ? 1 : 0, c.successfullSwaps0);
            ladder(c);
            row("EXCHANGE_ROUTING.tsv", ROUTING_HEADER, "IR03", tag, cold ? "1,3" : "0,2",
                    "ranks=" + before + ";selected=" + rankA + "," + rankB + ";seed=" + seed,
                    "success=" + c.successfullSwaps + ";cold=" + c.successfullSwaps0, "PASS");
        } finally { closeChains(c); }
    }
    @Test @Order(3) void ir03ColdCounterUsesRankZeroOutsidePhysicalZero() throws Exception { counter(true); }
    @Test @Order(4) void ir03HotRanksTwoAndThreeDoNotIncrementColdCounter() throws Exception { counter(false); }

    @Test @Order(5) void ir04AllFourRealSchedulerExecutions() throws Exception {
        for (boolean pre : new boolean[] {true, false}) for (boolean neighbour : new boolean[] {false, true}) {
            String tag = "IR04-" + pre + "-" + neighbour;
            Observer c = controller(tag, 3, pre, neighbour, false, 0.25, 4, 2, directory(tag).resolve(tag + ".state"), false);
            Randomizer.setSeed(876543);
            try {
                c.run(); ladder(c);
                assertEquals(2, c.totalSwaps); assertEquals(2, c.exchanges); assertEquals(2, c.successfullSwaps);
                assertEquals(pre ? 1 : 0, c.schedules);
                String expectedExecutor = pre && !neighbour ? "runPrescheduled" : "runRuntimeScheduled";
                assertEquals(List.of(expectedExecutor, expectedExecutor), c.observedExecutors);
                assertEquals(pre ? List.of("buildSchedule", expectedExecutor, expectedExecutor)
                        : List.of(expectedExecutor, expectedExecutor), c.schedulerOrder);
                if (pre) {
                    assertEquals(3, c.constructedSchedule.size());
                    assertEquals(c.constructedSchedule, c.firstExchangeSchedule);
                    for (long tick : new long[] {2, 4})
                        assertEquals(neighbour ? 3L : 2L, c.constructedSchedule.stream().filter(v -> v.contains(tick)).count());
                    assertEquals(neighbour ? 6 : 4, c.constructedSchedule.stream().mapToInt(List::size).sum());
                    if (neighbour) {
                        assertEquals(List.of(List.of(2L, 4L), List.of(2L, 4L), List.of(2L, 4L)), c.constructedSchedule);
                        assertEquals(c.constructedSchedule, c.scheduleSnapshot(), "runtime neighbour path leaves the upfront schedule unused");
                    } else assertTrue(c.scheduleSnapshot().stream().allMatch(List::isEmpty));
                } else {
                    assertTrue(c.constructedSchedule.isEmpty());
                    assertTrue(c.firstExchangeSchedule.isEmpty());
                    assertTrue(c.scheduleSnapshot().isEmpty());
                }
                for (HeatedChain chain : c.chains) assertEquals(5, chain.getCurrentSample());
                for (Thread t : c.threads) {
                    assertFalse(t.isAlive());
                    assertNull(get(t, "failure"), "child-thread failure");
                }
                assertEquals(2, c.barrierCount);
                row("IR04_SCHEDULE_CONSTRUCTION.tsv", "fixture\tconstructed_schedule\tfirst_exchange_schedule\tfinal_schedule\tobserved_order\tobserved_executors\tresult",
                        tag, c.constructedSchedule, c.firstExchangeSchedule, c.scheduleSnapshot(), c.schedulerOrder, c.observedExecutors, "PASS");
                row("SCHEDULER_MODE_MATRIX.tsv", "preSchedule\tneighbourSwapping\texecutor\tswap_opportunities\texchanges\tranks\trank0_beta\tfinal_sample\tthread_failures\tupfront_schedule_calls\trng_consumed\tcheckpoint_barriers\tresult",
                        pre, neighbour, c.observedExecutors.get(0), c.totalSwaps, c.exchanges,
                        ranks(c), 1.0, 4, "none", c.schedules, "NOT_MEASURED", c.barrierCount, "PASS");
            } finally { closeChains(c); }
        }
    }

    @SuppressWarnings("unchecked")
    @Test @Order(6) void ir04PairSynchronizationDiffersFromCommittedAllChainBarrier() throws Exception {
        Observer c = initialized("IR04-barrier", 4, true, false, 0.25, 6, 4);
        try {
            ArrayList<Long>[] plan = null;
            long selectedSeed = -1;
            for (long seed = 1; seed <= 1000; seed++) {
                Randomizer.setSeed(seed); c.buildSchedule();
                ArrayList<Long>[] proposed = (ArrayList<Long>[]) get(c, "runTillIteration");
                long late = Arrays.stream(proposed).filter(v -> v.isEmpty() || v.get(0) > 4).count();
                if (late == 2) { plan = proposed; selectedSeed = seed; break; }
            }
            assertNotNull(plan, "bounded search for a real schedule with checkpoint before participation");
            for (int i = 0; i < 4; i++) if (plan[i].isEmpty() || plan[i].get(0) > 4) BLOCKED.add(x(c.chains[i]));
            assertEquals(2, BLOCKED.size());
            release = new CountDownLatch(1); entered = new CountDownLatch(2);
            String actualPlan = Arrays.toString(plan);
            control(c, "runPrescheduled");
            assertTrue(c.pairWhileOtherAlive, "pair exchange should complete while two nonparticipants are blocked");
            assertEquals(1, c.barrierCount); assertEquals(3, c.totalSwaps); ladder(c);
            row("SCHEDULER_MODE_MATRIX.tsv", "preSchedule\tneighbourSwapping\texecutor\tswap_opportunities\texchanges\tranks\trank0_beta\tfinal_sample\tthread_failures\tupfront_schedule_calls\trng_consumed\tcheckpoint_barriers\tresult",
                    true, false, "runPrescheduled;seed=" + selectedSeed + ";plan=" + actualPlan + ";pairOnlyObserved=true", 3, c.exchanges, ranks(c), 1.0, 6, "none", c.schedules, true, 1, "PASS");
        } finally { release.countDown(); BLOCKED.clear(); closeChains(c); }
    }

    @Test @Order(7) void ir05SingleChainMatchesIndependentlyInstantiatedOrdinaryMCMC() throws Exception {
        MCMC plain = model(new MCMC(), "IR05-plain", "quadratic", 0.125, 0.0, 8);
        CoupledMCMC single = model(new CoupledMCMC(), "IR05-single", "quadratic", 0.125, 0.0, 8);
        single.nrOfChainsInput.setValue(1, single);
        plain.initAndValidate(); single.initAndValidate();
        plain.setStateFile(directory("IR05-plain").resolve("plain.state").toString(), false);
        single.setStateFile(directory("IR05-single").resolve("single.state").toString(), false);
        assertNotSame(state(plain), state(single)); assertNotSame(plain.posteriorInput.get(), single.posteriorInput.get());
        Randomizer.setSeed(5555); plain.run();
        Randomizer.setSeed(5555); single.run();
        assertEquals(x(plain).getValue(), x(single).getValue());
        assertEquals(payload(step(plain)), payload(step(single)));
        assertEquals(step(plain).proposals, step(single).proposals); assertEquals(9, step(single).proposals);
        assertEquals(semanticLog(directory("IR05-plain").resolve("cold.log")), semanticLog(directory("IR05-single").resolve("cold.log")));
        row("ONE_CHAIN_COMPARISON.tsv", "fixture\tfinal_state\tpayload\tproposals\tsemantic_log\tresult", "ordinary MCMC", x(plain).getValue(), payload(step(plain)), step(plain).proposals, semanticLog(directory("IR05-plain").resolve("cold.log")), "PASS");
        row("ONE_CHAIN_COMPARISON.tsv", "fixture\tfinal_state\tpayload\tproposals\tsemantic_log\tresult", "CoupledMCMC chains=1", x(single).getValue(), payload(step(single)), step(single).proposals, semanticLog(directory("IR05-single").resolve("cold.log")), "PASS");
    }

    @Test @Order(8) void ir06ActualConstructionSelectsLikelihoodOnlyAndWholePosteriorClasses() throws Exception {
        for (boolean only : new boolean[] {true, false}) {
            String tag = "IR06-" + only;
            Observer c = controller(tag, 2, false, true, only, 0.25, 2, -1, directory(tag).resolve(tag + ".state"), false);
            try {
                c.run();
                for (HeatedChain chain : c.chains) assertEquals(only ? HeatedChainLikelihoodOnly.class : HeatedChain.class, chain.getClass());
                assertEquals(1, c.totalSwaps); ladder(c);
                row("CONSTRUCTION.tsv", "heatLikelihoodOnly\tconstructed_classes\texchanges\tresult", only, Arrays.toString(Arrays.stream(c.chains).map(v -> v.getClass().getName()).toArray()), c.totalSwaps, "PASS");
            } finally { closeChains(c); }
        }
    }

    private Map<String, Object> kernel(MCMC c, String tag, double beta, double hastings) throws Exception {
        model(c, tag, "quadratic", 1.0, hastings, 2); c.initAndValidate();
        if (c instanceof HeatedChain hc) {
            hc.setStateFile(directory(tag).resolve("kernel.state").toString(), false); hc.setBeta(beta); hc.run();
        } else {
            state(c).initAndValidate(); state(c).setEverythingDirty(true);
            set(c, "posterior", c.posteriorInput.get());
            set(c, "oldLogLikelihood", state(c).robustlyCalcPosterior(c.posteriorInput.get()));
            state(c).storeCalculationNodes(); set(c, "loggers", new ArrayList<>());
        }
        double old = (Double) get(c, "oldLogLikelihood");
        Randomizer.setSeed(991);
        Class<?> owner = c instanceof HeatedChainLikelihoodOnly ? HeatedChainLikelihoodOnly.class : c instanceof HeatedChain ? HeatedChain.class : MCMC.class;
        call(c, owner, "propagateState", new Class<?>[] {long.class}, 1L);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("old", old); result.put("new", get(c, "newLogLikelihood")); result.put("H", hastings);
        result.put("logAlpha", get(c, "logAlpha")); result.put("x", x(c).getValue());
        result.put("accepted", step(c).get_m_nNrAccepted()); result.put("rejected", step(c).get_m_nNrRejected());
        row("KERNEL_EQUIVALENCE.tsv", "fixture\tclass\tbeta\told_target\tnew_target\tH\tlogAlpha\taccepted\trejected\tstate\tresult", tag, c.getClass().getName(), beta,
                old, result.get("new"), hastings, result.get("logAlpha"), result.get("accepted"), result.get("rejected"), result.get("x"), "observed");
        if (c instanceof HeatedChain hc) for (CoupledLogger l : hc.coupledLoggersInput.get()) l.close();
        return result;
    }
    @Test @Order(9) void ir07ActualProposalKernelsAgreeAtBetaOneAndDetectHeating() throws Exception {
        for (double h : new double[] {0.0, -1000.0}) {
            String suffix = h == 0 ? "accept" : "reject";
            Map<String, Object> plain = kernel(new MCMC(), "IR07-plain-" + suffix, 1.0, h);
            Map<String, Object> whole = kernel(new HeatedChain(), "IR07-whole-" + suffix, 1.0, h);
            Map<String, Object> only = kernel(new HeatedChainLikelihoodOnly(), "IR07-only-" + suffix, 1.0, h);
            assertEquals(plain, whole); assertEquals(plain, only);
            assertEquals(-7.0, (Double) plain.get("old"), 0.0); assertEquals(-4.5, (Double) plain.get("new"), 0.0);
            assertEquals(2.5 + h, (Double) plain.get("logAlpha"), 0.0);
            assertEquals(h == 0 ? 1 : 0, plain.get("accepted")); assertEquals(h == 0 ? 0 : 1, plain.get("rejected"));
            assertEquals(h == 0 ? 1.0 : 0.0, plain.get("x"));
        }
        Map<String, Object> whole = kernel(new HeatedChain(), "IR07-heated-whole", 0.5, 0);
        Map<String, Object> only = kernel(new HeatedChainLikelihoodOnly(), "IR07-heated-only", 0.5, 0);
        assertEquals(1.25, (Double) whole.get("logAlpha"), 0.0);
        assertEquals(1.0, (Double) only.get("logAlpha"), 0.0);
        assertNotEquals(2.5, whole.get("logAlpha")); assertNotEquals(2.5, only.get("logAlpha"));
    }

    private Observer realCommit(String tag, long offset) throws Exception {
        Observer c = initialized(tag, 2, false, true, 0.2, 4, 2);
        c.chains = new HeatedChain[] {c.chains[1], c.chains[0]};
        for (int i = 0; i < 2; i++) scoreAndStop(c.chains[i], i, 10 - i, c.chains[i].getChainNr(), 0.2, 2);
        c.sampleOffset = offset; c.totalSwaps = 7; c.successfullSwaps = 3; c.successfullSwaps0 = 2;
        c.acceptedSwaps = new ArrayList<>(List.of(true, false)); c.storeStateToFile(2);
        return c;
    }
    private Observer restore(Observer writer, String tag, double configuredDelta, long length) throws Exception {
        Observer reader = controller(tag, 2, false, true, false, configuredDelta, length, 2,
                Path.of((String) get(writer, "stateFileName")), true);
        control(reader, "preflightRestart"); control(reader, "initRun"); return reader;
    }
    @Test @Order(10) void ir08RealStateAndOperatorScheduleBytesRestoreByRank() throws Exception {
        Observer writer = realCommit("IR08-writer", 100);
        try {
            Map<String, String> fields = descriptor(descriptor(writer, "A"));
            assertEquals("102", fields.get("absolute_checkpoint_sample"));
            assertEquals("2", fields.get("segment_controller_sample")); assertEquals("100", fields.get("prior_sample_offset"));
            assertEquals("POST_EXCHANGE_POST_ADAPTATION", fields.get("epoch"));
            for (int rank = 0; rank < 2; rank++) {
                Path rankFile = descriptor(writer, "A").getParent().resolve(fields.get("rank." + rank + ".file"));
                String bytes = Files.readString(rankFile);
                assertTrue(bytes.contains("<itsabeastystatewerein")); assertTrue(bytes.contains("operators")); assertTrue(bytes.contains("acceptFC"));
                assertEquals(hash(rankFile), fields.get("rank." + rank + ".sha256"));
                assertEquals(Integer.toString(1 - rank), fields.get("rank." + rank + ".physical_at_commit"));
                row("CHECKPOINT_RESTORE.tsv", "requirement\tphase\trank\tstate\tpayload\tbeta\tcoordinate\tevidence\tresult", "IR08", "actual serialization", rank,
                        x(writer.chains[1 - rank]).getValue(), payload(step(writer.chains[1 - rank])), writer.chains[1 - rank].getBeta(), 102, rankFile + ";sha256=" + hash(rankFile), "observed");
            }
            assertTrue(writer.stages.contains("BEFORE_PUBLICATION"));
            closeChains(writer);
            Observer reader = restore(writer, "IR08-reader", 0.9, 2);
            try {
                assertEquals(0.2, reader.deltaTemperature, 0.0); assertEquals(102, reader.sampleOffset);
                assertEquals(7, reader.totalSwaps); assertEquals(3, reader.successfullSwaps); assertEquals(2, reader.successfullSwaps0);
                assertEquals(writer.acceptedSwaps, reader.acceptedSwaps); ladder(reader);
                for (int rank = 0; rank < 2; rank++) {
                    assertEquals(rank, reader.chains[rank].getChainNr());
                    assertEquals(x(writer.chains[1 - rank]).getValue(), x(reader.chains[rank]).getValue());
                    assertEquals(payload(step(writer.chains[1 - rank])), payload(step(reader.chains[rank])));
                    row("CHECKPOINT_RESTORE.tsv", "requirement\tphase\trank\tstate\tpayload\tbeta\tcoordinate\tevidence\tresult", "IR08", "fresh restore", rank,
                            x(reader.chains[rank]).getValue(), payload(step(reader.chains[rank])), reader.chains[rank].getBeta(), reader.sampleOffset, "actual State.restoreFromFile + OperatorSchedule.restoreFromFile", "PASS");
                }
            } finally { closeChains(reader); }
        } finally { closeChains(writer); }
    }

    @Test @Order(11) void ir09FirstResumedPropagationUsesSavedDeltaAndAbsoluteCoordinate() throws Exception {
        Observer writer = realCommit("IR09-writer", 100); closeChains(writer);
        Observer reader = restore(writer, "IR09-reader", 0.9, 2);
        try {
            assertEquals(0.2, reader.deltaTemperature, 0.0); assertEquals(102, reader.sampleOffset); ladder(reader);
            for (HeatedChain c : reader.chains) { assertEquals(0, c.getCurrentSample()); assertEquals(0, step(c).proposals); }
            int oldTotal = reader.totalSwaps;
            control(reader, "runRuntimeScheduled");
            assertEquals(List.of(2L), reader.exchangeCoordinates); assertEquals(oldTotal + 1, reader.totalSwaps);
            for (HeatedChain c : reader.chains) assertEquals(3, step(c).proposals);
            Map<String, String> fields = descriptor(descriptor(reader, "B"));
            assertEquals("104", fields.get("absolute_checkpoint_sample")); assertEquals("102", fields.get("prior_sample_offset"));
            assertEquals("2", fields.get("segment_controller_sample")); ladder(reader);
            double coldX = Arrays.stream(reader.chains).filter(c -> c.getChainNr() == 0).mapToDouble(c -> x(c).getValue()).findFirst().orElseThrow();
            for (HeatedChain c : reader.chains) { c.log(3); if (c.coupledLoggersInput.get().get(0).getM_out() != null) c.coupledLoggersInput.get().get(0).getM_out().flush(); }
            List<String> rows = semanticLog(directory("IR09-reader").resolve("cold.log"));
            assertEquals(coldX, Double.parseDouble(rows.get(rows.size() - 1).split("\\s+")[1]), 0.0);
            row("CHECKPOINT_RESTORE.tsv", "requirement\tphase\trank\tstate\tpayload\tbeta\tcoordinate\tevidence\tresult", "IR09", "first resumed exchange", 0, coldX,
                    "3 proposals per replica;first exchange local=2", 1.0, "102+2=104", descriptor(reader, "B"), "PASS");
        } finally { closeChains(reader); }
    }

    private Observer copiedReader(Observer writer, String tag) throws Exception {
        Path target = directory(tag); String filename = Path.of((String) get(writer, "stateFileName")).getFileName().toString();
        Path parent = descriptor(writer, "A").getParent();
        try (var stream = Files.list(parent)) {
            for (Path p : stream.filter(v -> v.getFileName().toString().startsWith(filename + ".mc3.")).toList())
                Files.copy(p, target.resolve(p.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
        }
        return controller(tag, 2, false, true, false, 0.2, 2, 2, target.resolve(filename), true);
    }
    @Test @Order(12) void ir10RealGenerationFailuresAreClosedAndPreviousPublicationSurvives() throws Exception {
        Observer writer = realCommit("IR10-writer", 0);
        try {
            Map<Path, String> prior = new HashMap<>();
            try (var s = Files.list(descriptor(writer, "A").getParent())) {
                for (Path p : s.filter(v -> v.getFileName().toString().contains(".mc3.A.commit")).toList()) prior.put(p, hash(p));
            }
            for (HeatedChain c : writer.chains) c.runTillResample(4);
            writer.failureStage = "BEFORE_PUBLICATION";
            assertThrows(IOException.class, () -> writer.storeStateToFile(4));
            for (var entry : prior.entrySet()) assertEquals(entry.getValue(), hash(entry.getKey()));
            assertFalse(Files.exists(descriptor(writer, "B")));
            Observer surviving = copiedReader(writer, "IR10-survival"); control(surviving, "preflightRestart"); assertEquals(2, surviving.sampleOffset);
            row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR10", "prepublication failure", "previous A bytes unchanged and selectable; inactive descriptor absent", "PASS");
            writer.failureStage = null; writer.storeStateToFile(4);
            Observer corrupt = copiedReader(writer, "IR10-corrupt");
            Map<String, String> newest = descriptor(descriptor(corrupt, "B"));
            Path file = descriptor(corrupt, "B").getParent().resolve(newest.get("rank.0.file"));
            Files.writeString(file, Files.readString(file) + "\n");
            IOException bad = assertThrows(IOException.class, () -> control(corrupt, "preflightRestart"));
            assertTrue(bad.getMessage().contains("SHA-256"));
            row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR10", "corrupt newest B", bad, "PASS");
            Observer equal = copiedReader(writer, "IR10-equal"); Path b = descriptor(equal, "B");
            Files.writeString(b, Files.readString(b).replace("sequence=2\n", "sequence=1\n"));
            IOException conflict = assertThrows(IOException.class, () -> control(equal, "preflightRestart"));
            assertTrue(conflict.getMessage().contains("equal"));
            row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR10", "equal A/B sequence", conflict, "PASS");
            Observer legacy = copiedReader(writer, "IR10-legacy"); Files.delete(descriptor(legacy, "A")); Files.delete(descriptor(legacy, "B"));
            IOException absent = assertThrows(IOException.class, () -> control(legacy, "preflightRestart"));
            assertTrue(absent.getMessage().contains("Legacy/unqualified"));
            row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR10", "no v1 descriptor", absent, "PASS");
            Observer orphan = copiedReader(writer, "IR10-orphan"); Files.delete(descriptor(orphan, "B"));
            Path orphanRank = descriptor(orphan, "B").getParent().resolve(newest.get("rank.0.file")); Files.writeString(orphanRank, "corrupt inactive orphan\n");
            control(orphan, "preflightRestart"); assertEquals(2, orphan.sampleOffset);
            row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR10", "orphan inactive B rank", "ignored; selected A at absolute=2", "PASS");
        } finally { closeChains(writer); }
    }

    @Test @Order(13) void ir11CorrespondenceFailuresPrecedeAcceptedExchangeMutation() throws Exception {
        for (String defect : List.of("ID", "class", "keyset")) {
            Observer c = initialized("IR11-preflight-" + defect, 2, false, true, 0.2, 2, -1);
            try {
                for (int i = 0; i < 2; i++) scoreAndStop(c.chains[i], i, 0, i, 0.2, 2);
                if (defect.equals("ID")) step(c.chains[1]).setID(null);
                if (defect.equals("class")) {
                    OtherStep replacement = new OtherStep(); replacement.setID("step");
                    replacement.initByName("x", x(c.chains[1]), "weight", 1.0);
                    c.chains[1].getOperatorSchedule().operators.set(0, replacement);
                }
                if (defect.equals("keyset")) {
                    Step extra = new Step(); extra.setID("extra"); extra.initByName("x", x(c.chains[1]), "weight", 1.0);
                    c.chains[1].getOperatorSchedule().operators.add(extra);
                }
                Map<String, Object> a = snapshot(c.chains[0]), b = snapshot(c.chains[1]);
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> control(c, "runRuntimeScheduled"));
                assertEquals(a, snapshot(c.chains[0])); assertEquals(b, snapshot(c.chains[1])); assertEquals(0, c.successfullSwaps);
                row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR11", defect + " preflight", e, "PASS;no accepted-exchange mutation");
            } finally { closeChains(c); }
        }
    }

    @Test @Order(14) void ir11SetterFailurePropagatesWithoutInventingRollback() throws Exception {
        Observer c = initialized("IR11-setter", 2, false, true, 0.2, 2, -1);
        try {
            for (int i = 0; i < 2; i++) scoreAndStop(c.chains[i], i, 0, i, 0.2, 2);
            step(c.chains[0]).failSetter = true;
            Map<String, Object> a = snapshot(c.chains[0]), b = snapshot(c.chains[1]);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> control(c, "runRuntimeScheduled"));
            assertTrue(failure.getMessage().contains("tuning commit failed"));
            assertTrue(failure.getCause().getMessage().contains("setter failure"));
            row("FAILURE_CASES.tsv", "requirement\tcase\tobservation\tresult", "IR11", "setter failure", failure + ";before=" + a + "/" + b + ";after=" + snapshot(c.chains[0]) + "/" + snapshot(c.chains[1]), "PASS;fatal partial state retained;no rollback asserted");
            assertEquals(a.get("rejectIv"), snapshot(c.chains[0]).get("rejectIv"));
            assertEquals(a.get("rejectOp"), snapshot(c.chains[0]).get("rejectOp"));
            assertEquals(b.get("rejectIv"), snapshot(c.chains[1]).get("rejectIv"));
            assertEquals(b.get("rejectOp"), snapshot(c.chains[1]).get("rejectOp"));
        } finally { closeChains(c); }
    }

    @Test @Order(15) void ir12ZeroSpacingKeepsDistinctRanksAndUniqueColdOutput() throws Exception {
        Observer c = initialized("IR12", 2, false, true, 0.0, 2, -1);
        try {
            scoreAndStop(c.chains[0], 0, 5, 0, 0, 2); scoreAndStop(c.chains[1], 1, 9, 1, 0, 2);
            control(c, "runRuntimeScheduled"); ladder(c);
            assertEquals(1, c.successfullSwaps); assertEquals("[1, 0]", ranks(c));
            for (HeatedChain chain : c.chains) { assertEquals(1.0, chain.getBeta()); chain.log(3); if (chain.coupledLoggersInput.get().get(0).getM_out() != null) chain.coupledLoggersInput.get().get(0).getM_out().flush(); }
            List<String> rows = semanticLog(directory("IR12").resolve("cold.log"));
            assertEquals(9.0, Double.parseDouble(rows.get(rows.size() - 1).split("\\s+")[1]), 0.0);
            assertTrue(c.chains[0].coupledLoggersInput.get().get(0).getSuppressLogging()); assertFalse(c.chains[1].coupledLoggersInput.get().get(0).getSuppressLogging());
            row("EXCHANGE_ROUTING.tsv", ROUTING_HEADER, "IR12", "zero spacing", "both beta=1", "ranks=[0,1]", "ranks=[1,0];cold physical=1;logged x=9", "PASS");
        } finally { closeChains(c); }
    }
}
