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
        Controller controller = new Controller();
        controller.nrOfChainsInput.setValue(2, controller);
        controller.resampleEveryInput.setValue(10, controller);
        controller.deltaTemperatureInput.setValue(configuredDelta, controller);
        controller.storeEveryInput.setValue(10, controller);
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
