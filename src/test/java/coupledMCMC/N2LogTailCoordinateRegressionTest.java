package coupledMCMC;

import beast.base.evolution.tree.TreeParser;
import beast.base.inference.Logger;
import beast.base.inference.parameter.RealParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static coupledMCMC.IntegrationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** N2: genuine public-run committed writers and isolated fresh-JVM appended readers. */
@Timeout(55)
public class N2LogTailCoordinateRegressionTest {
    private static final long C = 6;
    private static final Pattern TREE = Pattern.compile("^tree\\s+STATE_(\\d+)\\s*=.*;\\s*$");

    @Test void alignedTraceAppendsFromCheckpoint() throws Exception { aligned("alignedTrace", "trace.log", 2, false); }
    @Test void aheadTraceRejectsBeforeInitRun() throws Exception { mismatch("aheadTrace", "trace.log", 2, false, 8, "N2_AHEAD_ACCEPTED"); }
    @Test void behindTraceRejectsBeforeInitRun() throws Exception { mismatch("behindTrace", "trace.log", 2, false, 4, "N2_BEHIND_ACCEPTED"); }
    @Test void offCadenceExistingTraceRejects() throws Exception {
        Path dir = writer("offCadence");
        assertEquals(List.of(0L, 4L), samples(dir.resolve("trace.log"), false));
        reject(dir, "offCadence", "trace.log", 4, "4", snapshot(dir, "trace.log"));
    }
    @Test void alignedMultipleCadencesContinueIndependently() throws Exception {
        Path dir = writer("alignedMultiple");
        Map<String, byte[]> before = snapshot(dir, "trace.log", "second.log");
        ok(child(dir, "reader", "alignedMultiple"));
        appended(dir.resolve("trace.log"), before.get("trace.log"), 2, false);
        appended(dir.resolve("second.log"), before.get("second.log"), 3, false);
    }
    @Test void incompatibleSecondCadencePreservesAllFiles() throws Exception {
        Path dir = writer("incompatibleSecond");
        tail(dir.resolve("second.log"), false, 9);
        reject(dir, "incompatibleSecond", "second.log", 3, "9", snapshot(dir, "trace.log", "second.log"));
    }
    @Test void alignedTreeAppendsFromCheckpoint() throws Exception { aligned("alignedTree", "tree.trees", 2, true); }
    @Test void aheadTreeRejectsWithoutRemovingEnd() throws Exception { mismatch("aheadTree", "tree.trees", 2, true, 8, null); }
    @Test void behindTreeRejectsWithoutRemovingEnd() throws Exception { mismatch("behindTree", "tree.trees", 2, true, 4, null); }
    @Test void partialTraceRejectsWithoutRepair() throws Exception {
        Path dir = writer("partialTrace");
        Files.writeString(dir.resolve("trace.log"), Files.readString(dir.resolve("trace.log")) + "8\t\n", StandardCharsets.UTF_8);
        reject(dir, "partialTrace", "trace.log", 2, null, snapshot(dir, "trace.log"));
    }
    @Test void partialTreeRejectsWithoutRepair() throws Exception {
        Path dir = writer("partialTree");
        String text = withoutEnd(Files.readString(dir.resolve("tree.trees")));
        Files.writeString(dir.resolve("tree.trees"), text + "tree STATE_8 = (1:1.0,\n", StandardCharsets.UTF_8);
        reject(dir, "partialTree", "tree.trees", 2, null, snapshot(dir, "tree.trees"));
    }
    @Test void emptyExistingTraceRejects() throws Exception {
        Path dir = writer("emptyExisting");
        Files.write(dir.resolve("trace.log"), new byte[0]);
        reject(dir, "emptyExisting", "trace.log", 2, null, snapshot(dir, "trace.log"));
    }
    @Test void nonpositiveCadenceRejects() throws Exception {
        Path dir = writer("nonpositive");
        reject(dir, "nonpositive", "trace.log", 0, "6", snapshot(dir, "trace.log"));
    }
    @Test void allNewTraceUsesCheckpointOffset() throws Exception { newOutput("allNewTrace", "continuation.log", false); }
    @Test void allNewTreeUsesCheckpointOffset() throws Exception { newOutput("allNewTree", "continuation.trees", true); }
    @Test void freshAfterResumeResetsStaticOffset() throws Exception {
        Path dir = writer("freshAfterResume");
        ok(child(dir, "resumeAndFresh", "freshAfterResume"));
        assertEquals(List.of(0L, 2L, 4L, 6L), samples(dir.resolve("fresh.log"), false), "N2_STALE_STATIC_OFFSET");
        assertEquals(List.of(0L, 2L, 4L, 6L, 8L, 10L, 12L), samples(dir.resolve("trace.log"), false));
    }
    @Test void activeHeatedOutputRejectsMismatch() throws Exception {
        Path dir = writer("activeHeated");
        assertEquals(C, last(dir.resolve("chain1trace.log"), false));
        tail(dir.resolve("chain1trace.log"), false, 8);
        reject(dir, "activeHeated", "chain1trace.log", 2, "8", snapshot(dir, "trace.log", "chain1trace.log"));
    }
    @Test void suppressedHeatedOutputIgnored() throws Exception {
        Path dir = writer("suppressedHeated");
        Path ignored = dir.resolve("chain1trace.log");
        Files.writeString(ignored, "INCOMPATIBLE_SUPPRESSED_FILE\n");
        byte[] before = Files.readAllBytes(ignored);
        ok(child(dir, "reader", "suppressedHeated"));
        assertArrayEquals(before, Files.readAllBytes(ignored));
        assertEquals(List.of(0L, 2L, 4L, 6L, 8L, 10L, 12L), samples(dir.resolve("trace.log"), false));
    }
    @Test void stdoutOnlyLoggerIgnored() throws Exception {
        Path dir = writer("stdoutOnly");
        ok(child(dir, "reader", "stdoutOnly"));
        assertEquals(List.of(0L, 2L, 4L, 6L, 8L, 10L, 12L), samples(dir.resolve("trace.log"), false));
        assertFalse(Files.exists(dir.resolve("chain1")), "suppressed empty-name hot output must not open");
    }
    @Test void filenamePrefixAndTreeTokenResolveNormally() throws Exception {
        aligned("filenamePrefixTreeToken", "prefix-oak.trees", 2, true);
    }
    @Test void emptyFilenameHeatedTargetIsPreflighted() throws Exception {
        Path dir = writer("emptyFilenameHeated");
        assertEquals(C, last(dir.resolve("chain1"), false));
        tail(dir.resolve("chain1"), false, 8);
        reject(dir, "emptyFilenameHeated", "chain1", 2, "8", snapshot(dir, "chain1"));
    }
    @Test void sortedCompoundTreeTokenUsesInheritedOrder() throws Exception {
        for (String scenario : List.of("sortedCompoundToken", "smartCompoundToken", "smartUnnamedCompoundToken")) {
            Path dir = writer(scenario);
            assertTrue(Files.isRegularFile(dir.resolve("prefix-zeta.log")), "sorted payload ordering determines final token match");
            assertFalse(Files.exists(dir.resolve("prefix-alpha.log")));
            byte[] before = Files.readAllBytes(dir.resolve("prefix-zeta.log"));
            Properties result = child(dir, "reader", scenario);
            ok(result);
            assertTrue(result.getProperty("resolvedOutputs").contains("prefix-zeta.log:"));
            appended(dir.resolve("prefix-zeta.log"), before, 2, false);
        }
    }

    private static void aligned(String scenario, String name, int every, boolean tree) throws Exception {
        Path dir = writer(scenario);
        byte[] before = Files.readAllBytes(dir.resolve(name));
        assertEquals(C, last(dir.resolve(name), tree));
        if (tree) assertTrue(new String(before, StandardCharsets.UTF_8).stripTrailing().endsWith("End;"));
        ok(child(dir, "reader", scenario));
        appended(dir.resolve(name), before, every, tree);
    }

    private static void mismatch(String scenario, String name, int every, boolean tree, long logged, String red) throws Exception {
        Path dir = writer(scenario);
        tail(dir.resolve(name), tree, logged);
        Map<String, byte[]> before = snapshot(dir, name);
        Properties result = child(dir, "reader", scenario);
        if (red != null && "OK".equals(result.getProperty("status"))) {
            // Only emit the meaningful red after proving checkpoint C and independent inherited L offset.
            assertEquals("6", result.getProperty("checkpointOffset"), "SETUP_FAILED: wrong genuine restored C");
            List<Long> prior = samples(before.get(name), tree);
            List<Long> observed = samples(dir.resolve(name), tree);
            assertEquals(prior, observed.subList(0, prior.size()), "SETUP_FAILED: mismatch row was not retained");
            assertEquals(logged + every, observed.get(prior.size()), "SETUP_FAILED: did not observe inherited L-based continuation");
            assertTrue(result.getProperty("proposals").matches("[1-9][0-9]*"), "SETUP_FAILED: reader never sampled");
            System.out.println(red + " C=" + C + " L=" + logged + " E=" + every + " observed=" + observed + " child=" + dir);
            fail(red + ": restored checkpoint C=" + C + " but accepted L=" + logged + " and appended at " + observed.get(prior.size()));
        }
        assertRejected(result, name, every, Long.toString(logged));
        preserved(dir, before);
    }

    private static void newOutput(String scenario, String name, boolean tree) throws Exception {
        Path dir = writer(scenario);
        assertFalse(Files.exists(dir.resolve(name)));
        ok(child(dir, "reader", scenario));
        assertEquals(List.of(8L, 10L, 12L), samples(dir.resolve(name), tree), "N2_NEW_OUTPUT_OFFSET must use C+E, never fabricate C");
    }

    private static Path writer(String scenario) throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        Path dir = Files.createTempDirectory(directory("N2-" + scenario), "case-").toAbsolutePath();
        ok(child(dir, "writer", scenario));
        List<Long> commits = new ArrayList<>();
        for (String slot : List.of("A", "B")) {
            Path file = dir.resolve("run.state.mc3." + slot + ".commit");
            if (Files.exists(file)) {
                Map<String, String> fields = descriptor(file);
                assertEquals("CoupledMCMC-checkpoint-v1", fields.get("format"));
                commits.add(Long.parseLong(fields.get("absolute_checkpoint_sample")));
                for (int rank = 0; rank < 2; rank++)
                    assertEquals(fields.get("rank." + rank + ".sha256"), hash(dir.resolve(fields.get("rank." + rank + ".file"))));
            }
        }
        assertEquals(C, commits.stream().mapToLong(Long::longValue).max().orElseThrow(), "genuine writer selected committed C");
        return dir;
    }

    private static Properties child(Path dir, String phase, String scenario) throws Exception {
        Path stdout = dir.resolve(phase + ".stdout.txt"), stderr = dir.resolve(phase + ".stderr.txt");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String production = Path.of(CoupledMCMC.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> argv = List.of(java, "-Duser.home=" + System.getProperty("user.home"),
                "-DBEAST_PACKAGE_PATH=" + production, "-Dintegration.attempt=" + dir.resolve("child-evidence"),
                "-cp", cp, N2LogTailCoordinateRegressionTest.class.getName(), phase, scenario);
        Files.writeString(dir.resolve(phase + ".argv.txt"), String.join("\n", argv) + "\n");
        Process process = new ProcessBuilder(argv).directory(dir.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        boolean exited;
        try { exited = process.waitFor(23, TimeUnit.SECONDS); }
        catch (InterruptedException e) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); throw e; }
        if (!exited) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
        assertTrue(exited, "SETUP_FAILED: subprocess timed out: " + dir);
        assertFalse(process.isAlive(), "owned child JVM survived cleanup");
        assertEquals(0, process.exitValue(), "SETUP_FAILED: child exit; stderr=" + Files.readString(stderr));
        Properties result = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve(phase + ".result.properties"))) { result.load(in); }
        System.out.println("N2_CHILD scenario=" + scenario + " phase=" + phase + " pid=" + result.getProperty("pid")
                + " result=" + result + " evidence=" + dir + " ownedProcessStopped=true");
        return result;
    }

    private static void ok(Properties result) {
        assertEquals("OK", result.getProperty("status"), "SETUP_FAILED_OR_REGRESSION: " + result);
        assertEquals("true", result.getProperty("workersStopped"));
    }

    private static void reject(Path dir, String scenario, String name, int every, String logged, Map<String, byte[]> before) throws Exception {
        Properties result = child(dir, "reader", scenario);
        assertRejected(result, name, every, logged);
        preserved(dir, before);
    }

    private static void assertRejected(Properties result, String name, int every, String logged) {
        assertEquals("REJECTED", result.getProperty("status"), "N2_INCOMPATIBLE_OUTPUT_ACCEPTED: " + result);
        assertEquals("6", result.getProperty("checkpointOffset"), "generation selection must precede N2 validation");
        assertEquals("true", result.getProperty("chainsUninitialized"), "N2 rejection must precede initRun");
        assertEquals("true", result.getProperty("threadsUninitialized"));
        assertEquals("true", result.getProperty("rngUninitialized"));
        assertEquals("0", result.getProperty("proposals"));
        assertEquals("true", result.getProperty("workersStopped"));
        String diagnostic = result.getProperty("error");
        assertTrue(diagnostic.contains(name), "diagnostic path missing: " + diagnostic);
        assertTrue(diagnostic.contains("C=6"), "diagnostic C missing: " + diagnostic);
        assertTrue(diagnostic.contains("cadence=" + every), "diagnostic cadence missing: " + diagnostic);
        assertTrue(diagnostic.contains("L="), "diagnostic L field missing: " + diagnostic);
        if (logged != null && every > 0) assertTrue(diagnostic.contains("L=" + logged), "diagnostic L missing: " + diagnostic);
    }

    private static Map<String, byte[]> snapshot(Path dir, String... names) throws Exception {
        Map<String, byte[]> before = new LinkedHashMap<>();
        for (String name : names) before.put(name, Files.readAllBytes(dir.resolve(name)));
        return before;
    }

    private static void preserved(Path dir, Map<String, byte[]> before) throws Exception {
        for (Map.Entry<String, byte[]> entry : before.entrySet())
            assertArrayEquals(entry.getValue(), Files.readAllBytes(dir.resolve(entry.getKey())), "N2 rejection mutated " + entry.getKey());
        try (var paths = Files.walk(dir)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".bu")), "N2 preflight created inherited backup artifact");
        }
    }

    private static void appended(Path file, byte[] before, int every, boolean tree) throws Exception {
        String prefix = new String(before, StandardCharsets.UTF_8);
        if (tree) prefix = withoutEnd(prefix);
        assertTrue(Files.readString(file).startsWith(prefix), "historical rows/header are not an exact byte prefix");
        List<Long> prior = samples(before, tree), after = samples(file, tree);
        assertEquals(prior, after.subList(0, prior.size()));
        assertEquals(C + every, after.get(prior.size()), "first appended sample must be C+E");
        assertEquals(1, after.stream().filter(value -> value == C).count(), "duplicate checkpoint row");
        for (int i = prior.size(); i < after.size(); i++) assertEquals(C + every * (i - prior.size() + 1L), after.get(i));
    }

    private static String withoutEnd(String text) { return text.replaceFirst("(?s)End;\\s*$", ""); }
    private static long last(Path file, boolean tree) throws Exception {
        List<Long> values = samples(file, tree); return values.get(values.size() - 1);
    }
    private static List<Long> samples(Path file, boolean tree) throws Exception { return samples(Files.readAllBytes(file), tree); }
    private static List<Long> samples(byte[] bytes, boolean tree) {
        List<Long> values = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (tree) { Matcher m = TREE.matcher(line); if (m.matches()) values.add(Long.parseLong(m.group(1))); }
            else if (line.matches("[0-9]+\\s+.*")) values.add(Long.parseLong(line.split("\\s+")[0]));
        }
        return values;
    }

    private static void tail(Path file, boolean tree, long target) throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (tree ? TREE.matcher(lines.get(i)).matches() : lines.get(i).matches("[0-9]+\\s+.*")) index = i;
        }
        assertTrue(index >= 0, "genuine writer has no data row");
        long old = last(file, tree);
        if (target > old) {
            String row = lines.get(index);
            lines.add(index + 1, tree ? row.replaceFirst("STATE_\\d+", "STATE_" + target) : row.replaceFirst("^\\d+", Long.toString(target)));
        } else {
            for (int i = lines.size() - 1; i >= 0; i--) {
                List<Long> value = samples((lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8), tree);
                if (!value.isEmpty() && value.get(0) > target) lines.remove(i);
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        assertEquals(target, last(file, tree));
    }

    /** Child entry point; never invokes or manufactures checkpoint internals. */
    public static void main(String[] args) throws Exception {
        String phase = args[0], scenario = args[1];
        Properties result = new Properties();
        result.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        result.setProperty("java", System.getProperty("java.version"));
        result.setProperty("production", CoupledMCMC.class.getProtectionDomain().getCodeSource().getLocation().toString());
        boolean restore = !phase.equals("writer");
        Logger.FILE_MODE = Logger.LogFileMode.valueOf(restore ? "resume" : "overwrite");
        if (scenario.equals("filenamePrefixTreeToken") || scenario.endsWith("CompoundToken")) {
            System.setProperty("file.name.prefix", "prefix-");
            // Runnable.setStateFile gives this supported property precedence over the general prefix.
            // Keep genuine checkpoint paths independent while Logger uses its ordinary prefix path.
            System.setProperty("state.file.name", Path.of("run.state").toAbsolutePath().toString());
        }
        CoupledMCMC c = null;
        try {
            c = configured(scenario, restore, false);
            c.run();
            result.setProperty("status", "OK");
        } catch (Throwable error) {
            result.setProperty("status", "REJECTED");
            StringBuilder message = new StringBuilder();
            for (Throwable t = error; t != null; t = t.getCause()) message.append(t).append(" | ");
            result.setProperty("error", message.toString());
            error.printStackTrace();
        } finally {
            observe(c, result);
            if (c != null) closeChains(c);
        }
        if (phase.equals("resumeAndFresh") && "OK".equals(result.getProperty("status"))) {
            // Retain FILE_MODE=resume and static Logger state, precisely the stale-offset scenario.
            CoupledMCMC fresh = configured(scenario, false, true);
            try { fresh.run(); }
            finally { closeChains(fresh); }
            Properties observation = new Properties(); observe(fresh, observation);
            assertEquals("true", observation.getProperty("workersStopped"));
        }
        try (OutputStream out = Files.newOutputStream(Path.of(phase + ".result.properties"))) { result.store(out, "N2 child observations"); }
    }

    private static void observe(CoupledMCMC c, Properties out) throws Exception {
        assertNotNull(c, "fixture configuration failed before public run");
        out.setProperty("checkpointOffset", Long.toString(c.sampleOffset));
        out.setProperty("chainsUninitialized", Boolean.toString(Arrays.stream(c.chains).allMatch(chain -> chain == null)));
        out.setProperty("threadsUninitialized", Boolean.toString(c.threads == null));
        out.setProperty("rngUninitialized", Boolean.toString(get(c, "workerRngNames") == null));
        int proposals = 0;
        List<String> paths = new ArrayList<>();
        for (HeatedChain chain : c.chains) if (chain != null) {
            proposals += step(chain).proposals;
            for (CoupledLogger logger : chain.coupledLoggersInput.get()) paths.add(logger.getFileName() + ":suppressed=" + logger.getSuppressLogging());
        }
        out.setProperty("proposals", Integer.toString(proposals));
        out.setProperty("resolvedOutputs", paths.toString());
        boolean stopped = c.threads == null || Arrays.stream(c.threads).allMatch(thread -> thread == null || !thread.isAlive());
        out.setProperty("workersStopped", Boolean.toString(stopped));
        assertTrue(stopped, "owned child sampling worker survived public run");
    }

    private static CoupledMCMC configured(String scenario, boolean restore, boolean fresh) throws Exception {
        CoupledMCMC c = model(new CoupledMCMC(), "N2Child", "linear", 0.0, 0.0, 6);
        c.loggersInput.get().clear();
        int every = scenario.equals("offCadence") ? 4 : scenario.equals("nonpositive") && restore ? 0 : 2;
        boolean treeMode = scenario.contains("Tree") || scenario.equals("filenamePrefixTreeToken");
        boolean heated = scenario.equals("activeHeated") || scenario.equals("emptyFilenameHeated");
        String filename = fresh ? "fresh.log" : treeMode ? "tree.trees" : "trace.log";
        if (scenario.startsWith("allNew") && restore) filename = treeMode ? "continuation.trees" : "continuation.log";
        if (scenario.equals("filenamePrefixTreeToken")) filename = "$(tree).trees";
        if (scenario.endsWith("CompoundToken")) filename = "$(tree).log";
        if (scenario.equals("emptyFilenameHeated")) filename = "";
        Logger logger = new Logger(); logger.setID("n2trace");
        Object payload;
        if (treeMode) {
            TreeParser tree = new TreeParser("(A:1.0,B:1.0);"); tree.setID("tree.t:oak");
            c.startStateInput.get().stateNodeInput.setValue(tree, c.startStateInput.get());
            payload = tree;
        } else if (scenario.endsWith("CompoundToken")) {
            RealParameter x = (RealParameter) c.startStateInput.get().stateNodeInput.get().get(0);
            x.setID(scenario.startsWith("smart") ? "v.t:zeta" : "z.t:zeta");
            RealParameter y = new RealParameter("1.0"); y.setID(scenario.startsWith("smart") ? "v.t:alpha" : "a.t:alpha");
            c.startStateInput.get().stateNodeInput.setValue(y, c.startStateInput.get());
            if (scenario.equals("smartUnnamedCompoundToken")) {
                RealParameter unnamed = new RealParameter("2.0");
                assertNull(unnamed.getID(), "fixture requires a payload ID assigned by normal XML serialization");
                c.startStateInput.get().stateNodeInput.setValue(unnamed, c.startStateInput.get());
                payload = List.of(c.posteriorInput.get(), x, unnamed, y);
            } else payload = scenario.startsWith("smart") ? List.of(c.posteriorInput.get(), x, y) : List.of(x, y);
            logger.sortModeInput.setValue(scenario.startsWith("smart") ? Logger.SORTMODE.smart : Logger.SORTMODE.alphabetic, logger);
        } else payload = c.startStateInput.get().stateNodeInput.get().get(0);
        logger.initByName("log", payload, "fileName", filename, "logEvery", every, "mode", treeMode ? "tree" : "compound");
        c.loggersInput.setValue(logger, c);
        if (scenario.equals("alignedMultiple") || scenario.equals("incompatibleSecond")) {
            Logger second = new Logger(); second.setID("n2second");
            second.initByName("log", c.startStateInput.get().stateNodeInput.get().get(0), "fileName", "second.log", "logEvery", 3);
            c.loggersInput.setValue(second, c);
        }
        if (scenario.equals("stdoutOnly")) {
            Logger stdout = new Logger(); stdout.setID("n2stdout");
            stdout.initByName("log", c.startStateInput.get().stateNodeInput.get().get(0), "fileName", "", "logEvery", 2);
            c.loggersInput.setValue(stdout, c);
            Logger excluded = new Logger(); excluded.setID("n2nullName");
            excluded.initByName("log", c.startStateInput.get().stateNodeInput.get().get(0), "logEvery", 0);
            c.loggersInput.setValue(excluded, c);
        }
        c.nrOfChainsInput.setValue(2, c); c.resampleEveryInput.setValue(2, c);
        c.storeEveryInput.setValue(2, c); c.preScheduleInput.setValue(true, c);
        c.neighbourSwappingInput.setValue(true, c); c.logHeatedChainsInput.setValue(heated, c);
        c.deltaTemperatureInput.setValue(0.0, c); c.optimiseInput.setValue(false, c);
        c.setStateFile(Path.of(fresh ? "fresh.state" : "run.state").toAbsolutePath().toString(), restore);
        c.initAndValidate();
        return c;
    }
}
