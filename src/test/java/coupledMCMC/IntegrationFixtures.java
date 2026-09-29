package coupledMCMC;

import beast.base.core.Input;
import beast.base.inference.CompoundDistribution;
import beast.base.inference.Distribution;
import beast.base.inference.Logger;
import beast.base.inference.MCMC;
import beast.base.inference.Operator;
import beast.base.inference.State;
import beast.base.inference.parameter.RealParameter;
import beast.base.util.Randomizer;
import beast.pkgmgmt.BEASTClassLoader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only models and observers. Production methods are always delegated to unchanged. */
public final class IntegrationFixtures {
    private IntegrationFixtures() { }
    static final Path ROOT = evidenceRoot();
    private static Path evidenceRoot() {
        try {
            String configured = System.getProperty("integration.attempt");
            Path root = configured == null ? Files.createTempDirectory("coupled-mcmc-integration-") : Path.of(configured);
            Files.createDirectories(root.resolve("reports"));
            return root;
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static final Path ARTIFACTS = ROOT.resolve("fixtures/generated");
    static final Set<RealParameter> BLOCKED = Collections.newSetFromMap(new IdentityHashMap<>());
    static volatile CountDownLatch release = new CountDownLatch(0);
    static volatile CountDownLatch entered = new CountDownLatch(0);

    public static class Score extends Distribution {
        public final Input<RealParameter> xInput = new Input<>("x", "scalar state", Input.Validate.REQUIRED);
        public final Input<String> shapeInput = new Input<>("shape", "linear or quadratic", "linear");
        public final Input<Boolean> likelihoodPartInput = new Input<>("likelihoodPart", "likelihood component", false);
        @Override public void initAndValidate() { }
        @Override public double calculateLogP() {
            double x = xInput.get().getValue();
            if (shapeInput.get().equals("linear")) logP = likelihoodPartInput.get() ? x : 0.0;
            else logP = likelihoodPartInput.get() ? -2.0 - (x - 2.0) * (x - 2.0) : -1.0 - x * x / 2.0;
            return logP;
        }
        @Override public List<String> getArguments() { return List.of(xInput.get().getID()); }
        @Override public List<String> getConditions() { return List.of(); }
        @Override public void sample(State state, Random random) { throw new UnsupportedOperationException("deterministic fixture"); }
    }

    public static class Step extends Operator {
        public final Input<RealParameter> xInput = new Input<>("x", "scalar state", Input.Validate.REQUIRED);
        public final Input<Double> stepInput = new Input<>("step", "displacement", 0.0);
        public final Input<Double> hInput = new Input<>("h", "Hastings log ratio", 0.0);
        public final Input<Double> scalarInput = new Input<>("scalar", "initial coercable scalar", 0.5);
        public double scalar;
        public int proposals;
        public boolean failSetter;
        @Override public void initAndValidate() { scalar = scalarInput.get(); }
        @Override public double proposal() {
            if (BLOCKED.contains(xInput.get()) && proposals == 0) {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier was not released");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            proposals++;
            xInput.get().setValue(xInput.get().getValue() + stepInput.get());
            return hInput.get();
        }
        @Override public double getCoercableParameterValue() { return scalar; }
        @Override public void setCoercableParameterValue(double value) {
            if (failSetter) throw new IllegalStateException("deliberate test-only setter failure");
            scalar = value;
        }
        public void diagnostics(int a, int b) { m_nNrRejectedInvalid = a; m_nNrRejectedOperator = b; }
        public int invalid() { return m_nNrRejectedInvalid; }
        public int operatorReject() { return m_nNrRejectedOperator; }
    }
    public static class OtherStep extends Step { }

    static class Observer extends CoupledMCMC {
        String tag;
        int schedules;
        List<List<Long>> constructedSchedule = List.of();
        List<List<Long>> firstExchangeSchedule = List.of();
        final List<String> schedulerOrder = new ArrayList<>();
        final List<String> observedExecutors = new ArrayList<>();
        int exchanges;
        boolean pairWhileOtherAlive;
        int barrierCount;
        String failureStage;
        final List<String> stages = new ArrayList<>();
        final List<Long> exchangeCoordinates = new ArrayList<>();
        Observer(String tag) { this.tag = tag; }
        @Override void buildSchedule() {
            schedules++;
            super.buildSchedule();
            constructedSchedule = scheduleSnapshot();
            schedulerOrder.add("buildSchedule");
        }
        @SuppressWarnings("unchecked")
        List<List<Long>> scheduleSnapshot() {
            ArrayList<Long>[] schedule = (ArrayList<Long>[]) getUnchecked(this, "runTillIteration");
            if (schedule == null) return List.of();
            return Arrays.stream(schedule).map(v -> List.copyOf(v)).toList();
        }
        @Override void swapLoggers(HeatedChain a, HeatedChain b) {
            try {
                if (release.getCount() > 0) {
                    try { check(entered.await(15, TimeUnit.SECONDS), "nonparticipants did not enter controlled barrier"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                String executor = StackWalker.getInstance().walk(s -> s.map(StackWalker.StackFrame::getMethodName)
                        .filter(n -> n.equals("runPrescheduled") || n.equals("runRuntimeScheduled")).findFirst().orElse("NONE"));
                check(!executor.equals("NONE"), "exchange did not originate in the actual scheduler");
                if (observedExecutors.isEmpty()) firstExchangeSchedule = scheduleSnapshot();
                observedExecutors.add(executor);
                schedulerOrder.add(executor);
                int live = 0;
                for (Thread t : threads) if (t != null && t.isAlive()) live++;
                pairWhileOtherAlive |= live > 0;
                exchangeCoordinates.add(Math.min(a.getCurrentSample(), b.getCurrentSample()) - 1);
                row("SCHEDULER_EVENTS.tsv", "fixture\tevent\texecutor\tlive_threads\tchain_samples\tranks", tag, "exchange", executor, live,
                        Arrays.toString(Arrays.stream(chains).mapToLong(HeatedChain::getCurrentSample).toArray()), ranks(this));
                super.swapLoggers(a, b);
                exchanges++;
                release.countDown();
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        @Override void storeStateToFile(long sample) throws IOException {
            for (int k = 0; k < chains.length; k++) {
                check(threads == null || threads[k] == null || !threads[k].isAlive(), "live chain at checkpoint");
                check(chains[k].getCurrentSample() == sample + 1, "all-chain checkpoint coordinate");
            }
            barrierCount++;
            row("SCHEDULER_EVENTS.tsv", "fixture\tevent\texecutor\tlive_threads\tchain_samples\tranks", tag, "checkpoint",
                    "storeStateToFile", 0, Long.toString(sample + 1), ranks(this));
            super.storeStateToFile(sample);
        }
        @Override void checkpointFailurePoint(String stage) throws IOException {
            stages.add(stage);
            String next = "A".equals(getUnchecked(this, "committedSlot")) ? "B" : "A";
            check(!Files.exists(Path.of(stateFileName + ".mc3." + next + ".commit")), "descriptor appeared before publication");
            row("CHECKPOINT_STAGES.tsv", "fixture\tstage\tnext_slot\tdescriptor_present", tag, stage, next, false);
            if (stage.equals(failureStage)) throw new IOException("deliberate test seam failure at " + stage);
        }
    }

    static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException e) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static Object get(Object object, String name) throws ReflectiveOperationException { return field(object.getClass(), name).get(object); }
    static Object getUnchecked(Object object, String name) {
        try { return get(object, name); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    static void set(Object object, String name, Object value) throws ReflectiveOperationException { field(object.getClass(), name).set(object, value); }
    static Object call(Object object, Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(object, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception x) throw x;
            if (e.getCause() instanceof Error x) throw x;
            throw e;
        }
    }
    static void control(CoupledMCMC c, String method) throws Exception { call(c, CoupledMCMC.class, method, new Class<?>[0]); }
    static void register() {
        BEASTClassLoader.classLoader.addServices("IntegrationTestFixtures",
                Map.of("beast.base.core.BEASTInterface", Set.of(Score.class.getName(), Step.class.getName(), OtherStep.class.getName())));
    }
    static synchronized void row(String report, String header, Object... values) throws IOException {
        Path p = ROOT.resolve("reports").resolve(report);
        if (!Files.exists(p)) Files.writeString(p, header + "\n", StandardOpenOption.CREATE_NEW);
        String text = String.join("\t", Arrays.stream(values).map(v -> String.valueOf(v).replace('\t', ' ').replace('\n', ' ')).toList());
        Files.writeString(p, text + "\n", StandardOpenOption.APPEND);
    }
    static Path directory(String tag) throws IOException {
        Path p = ARTIFACTS.resolve(tag); Files.createDirectories(p); return p;
    }
    static String hash(Path p) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
    static State state(MCMC c) { return (State) getUnchecked(c, "state"); }
    static RealParameter x(MCMC c) { return (RealParameter) state(c).stateNodeInput.get().get(0); }
    static Step step(MCMC c) { return (Step) c.operatorsInput.get().get(0); }
    static List<Object> payload(Operator o) { return List.of(o.get_m_nNrAccepted(), o.get_m_nNrRejected(), o.get_m_nNrAcceptedForCorrection(), o.get_m_nNrRejectedForCorrection(), o.getCoercableParameterValue()); }

    static <T extends MCMC> T model(T c, String tag, String shape, double stepSize, double h, long length) throws Exception {
        register();
        RealParameter x = new RealParameter("0.0"); x.setID("x");
        State state = new State(); state.setID("state"); state.initByName("stateNode", x, "storeEvery", -1);
        Score prior = new Score(); prior.setID("prior"); prior.initByName("x", x, "shape", shape, "likelihoodPart", false);
        Score like = new Score(); like.setID("likelihood"); like.initByName("x", x, "shape", shape, "likelihoodPart", true);
        CompoundDistribution posterior = new CompoundDistribution(); posterior.setID("posterior");
        posterior.initByName("distribution", List.of(prior, like));
        Step op = new Step(); op.setID("step"); op.initByName("x", x, "weight", 1.0, "step", stepSize, "h", h, "scalar", 0.5);
        c.startStateInput.setValue(state, c); c.posteriorInput.setValue(posterior, c);
        c.operatorsInput.setValue(op, c); c.chainLengthInput.setValue(length, c); c.storeEveryInput.setValue(-1, c);
        Path output = directory(tag).resolve("cold.log");
        if (c instanceof HeatedChain hc) {
            CoupledLogger logger = new CoupledLogger(); logger.setID("trace");
            logger.initByName("log", x, "fileName", output.toString(), "logEvery", 1);
            hc.coupledLoggersInput.setValue(logger, hc);
        } else {
            Logger logger = new Logger(); logger.setID("trace");
            logger.initByName("log", x, "fileName", output.toString(), "logEvery", 1);
            c.loggersInput.setValue(logger, c);
        }
        c.setID(tag.replace('-', '_'));
        return c;
    }
    static Observer controller(String tag, int n, boolean pre, boolean neighbour, boolean likelihoodOnly,
                               double delta, long length, int store, Path statePath, boolean restore) throws Exception {
        Observer c = model(new Observer(tag), tag, "linear", 0.0, 0.0, length);
        c.nrOfChainsInput.setValue(n, c); c.resampleEveryInput.setValue(2, c);
        c.preScheduleInput.setValue(pre, c); c.neighbourSwappingInput.setValue(neighbour, c);
        c.heatLikelihoodOnlyInput.setValue(likelihoodOnly, c); c.deltaTemperatureInput.setValue(delta, c);
        c.optimiseInput.setValue(false, c); c.storeEveryInput.setValue(store, c);
        c.setStateFile(statePath.toString(), restore); c.initAndValidate(); return c;
    }
    static Observer initialized(String tag, int n, boolean pre, boolean neighbour, double delta, long length, int store) throws Exception {
        Observer c = controller(tag, n, pre, neighbour, false, delta, length, store, directory(tag).resolve(tag + ".state"), false);
        control(c, "preflightRestart"); control(c, "initRun"); return c;
    }
    static void closeChains(CoupledMCMC c) {
        if (c.chains != null) for (HeatedChain chain : c.chains) if (chain != null)
            for (CoupledLogger logger : chain.coupledLoggersInput.get()) { if (logger.getM_out() != null) logger.getM_out().flush(); logger.close(); }
    }
    static String ranks(CoupledMCMC c) { return Arrays.toString(Arrays.stream(c.chains).mapToInt(HeatedChain::getChainNr).toArray()); }
    static void ladder(CoupledMCMC c) {
        Set<Integer> ranks = new java.util.HashSet<>();
        for (HeatedChain chain : c.chains) {
            check(ranks.add(chain.getChainNr()), "duplicate rank");
            check(Math.abs(chain.getBeta() - 1.0 / (1.0 + chain.getChainNr() * c.deltaTemperature)) < 1e-12, "ladder mismatch");
            if (chain.getChainNr() == 0) check(chain.getBeta() == 1.0, "rank0 is not beta1");
        }
        check(ranks.size() == c.chains.length && ranks.contains(0), "rank permutation");
    }
    static void scoreAndStop(HeatedChain c, int physical, double value, int rank, double delta, long stop) throws Exception {
        x(c).setValue(value); c.setChainNr(rank); c.setBeta(1.0 / (1.0 + rank * delta));
        c.calcCurrentLogLikelihoodRobustly(); c.runTillResample(stop);
        Step op = step(c); op.setAcceptedRejected(100 * physical + 1, 100 * physical + 2, 100 * physical + 3, 100 * physical + 4);
        op.scalar = physical + 0.25; op.diagnostics(physical + 7, physical + 17);
    }
    static Map<String, Object> snapshot(HeatedChain c) {
        CoupledLogger logger = c.coupledLoggersInput.get().get(0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("physical", System.identityHashCode(c)); r.put("stateIdentity", System.identityHashCode(state(c)));
        r.put("modelIdentity", System.identityHashCode(c.posteriorInput.get())); r.put("x", x(c).getValue());
        r.put("rank", c.getChainNr()); r.put("beta", c.getBeta());
        r.put("logger", System.identityHashCode(logger)); r.put("stream", System.identityHashCode(logger.getM_out()));
        r.put("suppressed", logger.getSuppressLogging()); r.put("stateFile", c.getStateFileName());
        r.put("payload", payload(step(c))); r.put("rejectIv", step(c).invalid()); r.put("rejectOp", step(c).operatorReject());
        return r;
    }
    static long choosePair(CoupledMCMC c, int rankA, int rankB) throws Exception {
        for (long seed = 1; seed <= 1000; seed++) {
            Randomizer.setSeed(seed);
            int[] pair = (int[]) call(c, CoupledMCMC.class, "selectRuntimePair", new Class<?>[0]);
            if (c.chains[pair[0]].getChainNr() == rankA && c.chains[pair[1]].getChainNr() == rankB) { Randomizer.setSeed(seed); return seed; }
        }
        throw new AssertionError("bounded deterministic pair seed search exhausted");
    }
    static Map<String, String> descriptor(Path p) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (String line : Files.readAllLines(p)) { int i = line.indexOf('='); m.put(line.substring(0, i), line.substring(i + 1)); }
        return m;
    }
    static Path descriptor(Observer c, String slot) { return Path.of(getUnchecked(c, "stateFileName") + ".mc3." + slot + ".commit"); }
    static List<String> semanticLog(Path p) throws IOException {
        return Files.readAllLines(p).stream().filter(s -> s.matches("[0-9]+\\s+.*")).map(String::trim).toList();
    }
}
