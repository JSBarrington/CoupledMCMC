package coupledMCMC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static coupledMCMC.PortTestSupport.invoke;

/** Controller protocol tests use synthetic composite bytes, not a BEAST model. */
class CheckpointGenerationTest {
    @TempDir Path directory;

    static final class Controller extends CoupledMCMC {
        String failureStage;
        @Override void checkpointFailurePoint(String stage) throws IOException {
            if (stage.equals(failureStage)) throw new IOException("deliberate failure: " + stage);
        }
    }

    static final class StoppedChain extends HeatedChain {
        StoppedChain(int rank, double delta, long sample) {
            setChainNr(rank);
            setTemperature(0, rank * delta);
            currentSample = sample + 1;
        }
        @Override void storeCommittedState(Path file, long absoluteSample) throws IOException {
            Files.writeString(file, composite(absoluteSample, "{\"operators\":[{\"id\":\"step\"}]}"));
        }
    }

    private static String composite(long sample, String appendix) {
        return "<itsabeastystatewerein version='2.0' sample='" + sample
                + "'></itsabeastystatewerein>\n<!--\n" + appendix + "\n-->\n";
    }

    private Controller controller(boolean restore, double configuredDelta) {
        // Existing T=12,H=2 protocol fixtures require the reachable fixed delay D=2.
        return controller(restore, configuredDelta, true, 2);
    }

    private Controller controller(boolean restore, double configuredDelta,
                                  boolean optimise, int delay) {
        Controller controller = new Controller();
        controller.nrOfChainsInput.setValue(2, controller);
        controller.resampleEveryInput.setValue(10, controller);
        controller.deltaTemperatureInput.setValue(configuredDelta, controller);
        controller.storeEveryInput.setValue(10, controller);
        controller.optimiseInput.setValue(optimise, controller);
        controller.optimiseDelayInput.setValue(delay, controller);
        controller.setStateFile(directory.resolve("run.state").toString(), restore);
        controller.initAndValidate();
        return controller;
    }

    private void commit(Controller controller, long sample) throws IOException {
        // Put rank zero in physical slot 1 to exercise rank-addressed publication.
        controller.chains = new HeatedChain[] {
                new StoppedChain(1, controller.deltaTemperature, sample),
                new StoppedChain(0, controller.deltaTemperature, sample)};
        controller.totalSwaps = 12;
        controller.successfullSwaps = 5;
        controller.successfullSwaps0 = 3;
        controller.acceptedSwaps = new ArrayList<>(List.of(true, false));
        controller.storeStateToFile(sample);
    }

    private Path descriptor(String slot) { return directory.resolve("run.state.mc3." + slot + ".commit"); }
    private Path rankFile(String slot, int rank) { return directory.resolve("run.state.mc3." + slot + ".commit.rank-" + rank + ".state"); }
    private void preflight(Controller controller) throws Exception {
        invoke(controller, "preflightRestart", new Class<?>[0]);
    }

    private static List<Boolean> h6History(int length) {
        return new ArrayList<>(java.util.Collections.nCopies(length, false));
    }

    private static String h6HistoryText(int length) {
        return h6History(length).toString().replace("[", "").replace("]", "").replace(" ", "");
    }

    private void h6PublishReachableHistory(boolean optimise, int delay,
                                           int total, int expectedLength) throws Exception {
        H1RegressionTest.assertLocalClassOrigin();
        Controller writer = controller(false, 0.1, optimise, delay);
        long sample = 10L * total;
        writer.chains = new HeatedChain[] {
                new StoppedChain(1, writer.deltaTemperature, sample),
                new StoppedChain(0, writer.deltaTemperature, sample)};
        writer.totalSwaps = total;
        writer.successfullSwaps = 0;
        writer.successfullSwaps0 = 0;
        writer.acceptedSwaps = h6History(expectedLength);
        writer.storeStateToFile(sample);

        // Establish validity of every other fixture field before changing history alone.
        Controller control = controller(true, 0.1, optimise, delay);
        assertDoesNotThrow(() -> preflight(control));
        assertEquals(total, control.totalSwaps);
        assertEquals(sample, control.sampleOffset);
        assertEquals(expectedLength, control.acceptedSwaps.size());
        assertEquals(h6History(expectedLength), control.acceptedSwaps);
        assertTrue(Files.readString(descriptor("A")).contains(
                "acceptedSwaps=" + h6HistoryText(expectedLength) + "\n"));
    }

    private void h6AcceptsHistory(boolean optimise, int delay,
                                  int total, int expectedLength) throws Exception {
        h6PublishReachableHistory(optimise, delay, total, expectedLength);
        System.out.println("H6_SYNTHETIC optimise=" + optimise + " delay=" + delay
                + " total=" + total + " history=" + expectedLength
                + " expected=" + expectedLength + " outcome=accepted");
    }

    private void h6RejectsHistory(boolean optimise, int delay, int total,
                                  int expectedLength, int invalidLength) throws Exception {
        h6PublishReachableHistory(optimise, delay, total, expectedLength);
        byte[] rankZero = Files.readAllBytes(rankFile("A", 0));
        byte[] rankOne = Files.readAllBytes(rankFile("A", 1));
        String original = Files.readString(descriptor("A"));
        String validLine = "acceptedSwaps=" + h6HistoryText(expectedLength) + "\n";
        String invalidLine = "acceptedSwaps=" + h6HistoryText(invalidLength) + "\n";
        assertTrue(original.contains(validLine));
        assertNotEquals(validLine, invalidLine);
        String changed = original.replace(validLine, invalidLine);
        Files.writeString(descriptor("A"), changed);
        assertEquals(original, changed.replace(invalidLine, validLine));
        assertArrayEquals(rankZero, Files.readAllBytes(rankFile("A", 0)));
        assertArrayEquals(rankOne, Files.readAllBytes(rankFile("A", 1)));

        Controller reader = controller(true, 0.1, optimise, delay);
        String caseDescription = "optimise=" + optimise + " delay=" + delay
                + " total=" + total + " history=" + invalidLength + " expected=" + expectedLength;
        System.out.println("H6_SYNTHETIC " + caseDescription + " outcome=expect-rejection");
        IOException rejection = assertThrows(IOException.class, () -> preflight(reader),
                "H6_EXPECTED_RESTART_REJECTION_BUT_VALIDATION_SUCCEEDED " + caseDescription);
        assertTrue(rejection.getMessage().startsWith(
                "Invalid checkpoint acceptedSwaps history length"), rejection.getMessage());
        System.out.println("H6_SYNTHETIC " + caseDescription
                + " outcome=rejected reason=" + rejection.getMessage());
    }

    @Test void h6OptimiseDisabledAcceptsEmptyInitialHistory() throws Exception {
        h6AcceptsHistory(false, 2, 0, 0);
    }

    @Test void h6OptimiseDisabledAcceptsEmptyHistoryAfterExchanges() throws Exception {
        h6AcceptsHistory(false, 2, 3, 0);
    }

    @Test void h6OptimiseDisabledRejectsNonemptyHistory() throws Exception {
        h6RejectsHistory(false, 2, 3, 0, 1);
    }

    @Test void h6NegativeDelayAcceptsEmptyHistory() throws Exception {
        h6AcceptsHistory(true, -1, 3, 0);
    }

    @Test void h6NegativeDelayRejectsNonemptyHistory() throws Exception {
        h6RejectsHistory(true, -1, 3, 0, 1);
    }

    @Test void h6ZeroDelayAcceptsEmptyHistory() throws Exception {
        h6AcceptsHistory(true, 0, 3, 0);
    }

    @Test void h6ZeroDelayRejectsNonemptyHistory() throws Exception {
        h6RejectsHistory(true, 0, 3, 0, 1);
    }

    @Test void h6PositiveDelayAcceptsBeforeDelayHistory() throws Exception {
        h6AcceptsHistory(true, 2, 1, 1);
    }

    @Test void h6PositiveDelayRejectsShortBeforeDelayHistory() throws Exception {
        h6RejectsHistory(true, 2, 1, 1, 0);
    }

    @Test void h6PositiveDelayAcceptsAtDelayHistory() throws Exception {
        h6AcceptsHistory(true, 2, 2, 2);
    }

    @Test void h6PositiveDelayRejectsShortAtDelayHistory() throws Exception {
        h6RejectsHistory(true, 2, 2, 2, 1);
    }

    @Test void h6PositiveDelayAcceptsAfterDelayHistory() throws Exception {
        h6AcceptsHistory(true, 2, 3, 2);
    }

    @Test void h6PositiveDelayRejectsShortAfterDelayHistory() throws Exception {
        h6RejectsHistory(true, 2, 3, 2, 1);
    }

    @Test void h6PositiveDelayRejectsLongAfterDelayHistory() throws Exception {
        h6RejectsHistory(true, 2, 3, 2, 3);
    }

    @Test void restoreUsesSavedSpacingCountersAndAbsoluteCoordinate() throws Exception {
        Controller writer = controller(false, 0.1);
        writer.sampleOffset = 100;
        commit(writer, 10);
        Controller reader = controller(true, 0.9);
        preflight(reader);
        assertEquals(0.1, reader.deltaTemperature, 0.0);
        assertEquals(110, reader.sampleOffset);
        assertEquals(12, reader.totalSwaps);
        assertEquals(5, reader.successfullSwaps);
        assertEquals(3, reader.successfullSwaps0);
        assertEquals(List.of(true, false), reader.acceptedSwaps);
        String record = Files.readString(descriptor("A"));
        assertTrue(record.contains("rank.0.physical_at_commit=1\n"));
        assertTrue(record.contains("epoch=POST_EXCHANGE_POST_ADAPTATION\n"));
        assertTrue(record.contains("segment_controller_sample=10\n"));
        assertTrue(record.contains("prior_sample_offset=100\n"));
    }

    @Test void selectsNewestPublishedGeneration() throws Exception {
        Controller writer = controller(false, 0.1);
        commit(writer, 10);
        commit(writer, 20);
        Controller reader = controller(true, 0.9);
        preflight(reader);
        assertEquals(20, reader.sampleOffset);
    }

    @Test void corruptNewestGenerationFailsWithoutFallingBack() throws Exception {
        Controller writer = controller(false, 0.1);
        commit(writer, 10);
        commit(writer, 20);
        // Still well-formed composite bytes, but no longer the committed checksum.
        Files.writeString(rankFile("B", 0), composite(20, "{\"operators\":[{\"id\":\"changed\"}]}"));
        assertThrows(IOException.class, () -> preflight(controller(true, 0.1)));
    }

    @Test void equalSequenceNumbersFailClosed() throws Exception {
        Controller writer = controller(false, 0.1);
        commit(writer, 10);
        commit(writer, 20);
        Path b = descriptor("B");
        Files.writeString(b, Files.readString(b).replace("sequence=2\n", "sequence=1\n"));
        assertThrows(IOException.class, () -> preflight(controller(true, 0.1)));
    }

    @Test void prepublicationFailuresPreservePreviousGenerationByteForByte() throws Exception {
        Controller writer = controller(false, 0.1);
        commit(writer, 10);
        Map<Path, byte[]> prior = new LinkedHashMap<>();
        for (Path file : List.of(descriptor("A"), rankFile("A", 0), rankFile("A", 1))) {
            prior.put(file, Files.readAllBytes(file));
        }
        for (String stage : List.of("AFTER_INVALIDATION", "AFTER_RANK_1", "AFTER_RANK_2",
                "AFTER_ALL_RANKS", "AFTER_VALIDATION", "BEFORE_PUBLICATION")) {
            writer.failureStage = stage;
            assertThrows(IOException.class, () -> commit(writer, 20));
            assertFalse(Files.exists(descriptor("B")));
            for (Map.Entry<Path, byte[]> entry : prior.entrySet()) {
                assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
            }
            Controller reader = controller(true, 0.1);
            preflight(reader);
            assertEquals(10, reader.sampleOffset);
        }
    }

    @Test void legacyOuterFileAndOrphanRanksCannotAuthorizeRestore() throws Exception {
        Files.writeString(directory.resolve("run.state"), "sample=100\ntotalSwaps=2\n");
        Files.writeString(rankFile("A", 0), composite(100, "{\"operators\":[]}"));
        assertThrows(IOException.class, () -> preflight(controller(true, 0.1)));
    }

    @Test void freshRunRefusesPublishedDescriptors() throws Exception {
        Controller writer = controller(false, 0.1);
        commit(writer, 10);
        assertThrows(IOException.class, () -> preflight(controller(false, 0.1)));
    }

    @Test void changedRestartCriticalConfigurationFails() throws Exception {
        Controller writer = controller(false, 0.1);
        commit(writer, 10);
        Controller reader = controller(true, 0.1);
        reader.neighbourSwappingInput.setValue(true, reader);
        assertThrows(IOException.class, () -> preflight(reader));
    }

    @Test void compositeValidationRequiresStructuredObjectArrayAndMatchingSample() throws Exception {
        Path file = directory.resolve("composite.state");
        Controller controller = controller(false, 0.1);
        Files.writeString(file, composite(10, "{\"operators\":[{\"id\":\"step\"}]}"));
        assertDoesNotThrow(() -> invoke(controller, "validateRankFile",
                new Class<?>[] {Path.class, long.class}, file, 10L));
        for (String invalid : List.of("not JSON", "{}", "{\"operators\":17}", "{\"operators\":[5]}")) {
            Files.writeString(file, composite(10, invalid));
            assertThrows(IOException.class, () -> invoke(controller, "validateRankFile",
                    new Class<?>[] {Path.class, long.class}, file, 10L));
        }
        Files.writeString(file, composite(11, "{\"operators\":[]}"));
        assertThrows(IOException.class, () -> invoke(controller, "validateRankFile",
                new Class<?>[] {Path.class, long.class}, file, 10L));
    }
}
