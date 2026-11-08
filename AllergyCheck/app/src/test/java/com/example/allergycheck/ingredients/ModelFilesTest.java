package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ModelFilesTest {
    private static final byte[] MODEL_BYTES = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String MODEL_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void publishesOnlyAfterSizeAndHashMatch() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        stage(files, MODEL_BYTES);
        assertNull(files.findInstalled());

        files.install(MODEL_BYTES.length, MODEL_SHA256.toUpperCase(java.util.Locale.ROOT));

        File installed = new File(directory, ModelFiles.MODEL_FILE);
        assertEquals(installed, files.findInstalled());
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(installed.toPath()));
        assertFalse(files.stagingFile().exists());
    }

    @Test
    public void validDownloadAtomicallyReplacesExistingModel() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File installed = write(directory, ModelFiles.MODEL_FILE, "old model");
        stage(files, MODEL_BYTES);

        files.install(MODEL_BYTES.length, MODEL_SHA256);

        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(installed.toPath()));
        assertFalse(files.stagingFile().exists());
    }

    @Test
    public void repeatedInstallRecoversAlreadyPublishedModel() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        stage(files, MODEL_BYTES);
        files.install(MODEL_BYTES.length, MODEL_SHA256);
        assertFalse(files.stagingFile().exists());

        files.install(MODEL_BYTES.length, MODEL_SHA256);

        File installed = new File(directory, ModelFiles.MODEL_FILE);
        assertEquals(installed, files.findInstalled());
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(installed.toPath()));
    }

    @Test
    public void missingStagingDoesNotAcceptUnverifiedTarget() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File installed = write(directory, ModelFiles.MODEL_FILE, "abd");

        assertInstallFails(files, MODEL_BYTES.length, MODEL_SHA256);

        assertArrayEquals("abd".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(installed.toPath()));
        assertFalse(files.stagingFile().exists());
    }

    @Test
    public void presentButCorruptStagingCannotFallBackToMatchingTarget() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File installed = write(directory, ModelFiles.MODEL_FILE, "abc");
        stage(files, "abd".getBytes(StandardCharsets.UTF_8));

        assertInstallFails(files, MODEL_BYTES.length, MODEL_SHA256);

        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(installed.toPath()));
        assertTrue(files.stagingFile().exists());
    }

    @Test
    public void truncatedDownloadDoesNotReplaceExistingModel() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File installed = write(directory, ModelFiles.MODEL_FILE, "old model");
        byte[] original = Files.readAllBytes(installed.toPath());
        stage(files, "ab".getBytes(StandardCharsets.UTF_8));

        assertInstallFails(files, MODEL_BYTES.length, MODEL_SHA256);

        assertArrayEquals(original, Files.readAllBytes(installed.toPath()));
        assertEquals(installed, files.findInstalled());
        assertTrue(files.stagingFile().exists());
    }

    @Test
    public void sameSizeCorruptionDoesNotExposeAModel() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        stage(files, "abd".getBytes(StandardCharsets.UTF_8));

        assertInstallFails(files, MODEL_BYTES.length, MODEL_SHA256);

        assertNull(files.findInstalled());
        assertFalse(new File(directory, ModelFiles.MODEL_FILE).exists());
    }

    @Test
    public void hashMismatchPreservesExistingModel() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File installed = write(directory, ModelFiles.MODEL_FILE, "old model");
        byte[] original = Files.readAllBytes(installed.toPath());
        stage(files, "abd".getBytes(StandardCharsets.UTF_8));

        assertInstallFails(files, MODEL_BYTES.length, MODEL_SHA256);

        assertArrayEquals(original, Files.readAllBytes(installed.toPath()));
        assertEquals(installed, files.findInstalled());
    }

    @Test
    public void rejectsMissingDownloadAndInvalidVerificationMetadata() throws Exception {
        ModelFiles files = new ModelFiles(temporaryFolder.newFolder("models"));
        assertInstallFails(files, MODEL_BYTES.length, MODEL_SHA256);
        stage(files, MODEL_BYTES);

        assertInstallFails(files, 0, MODEL_SHA256);
        assertInstallFails(files, -1, MODEL_SHA256);
        assertInstallFails(files, MODEL_BYTES.length, null);
        assertInstallFails(files, MODEL_BYTES.length, "invalid hash");
        assertNull(files.findInstalled());
    }

    @Test
    public void preferredModelWinsAndManualModelsHaveStableOrder() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        write(directory, "z-manual.task", "z");
        File first = write(directory, "a-manual.task", "a");
        assertEquals(first, files.findInstalled());

        File preferred = write(directory, ModelFiles.MODEL_FILE, "preferred");
        assertEquals(preferred, files.findInstalled());
    }

    @Test
    public void emptyFilesDirectoriesAndStagingAreNotInstalledModels() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        write(directory, ModelFiles.MODEL_FILE, "");
        assertTrue(new File(directory, "folder.task").mkdir());
        stage(files, MODEL_BYTES);
        write(directory, "notes.txt", "not a model");
        assertNull(files.findInstalled());

        File manual = write(directory, "manual.task", "manual");
        assertEquals(manual, files.findInstalled());
    }

    @Test
    public void deletesAllModelFilesAndStagingWithoutTraversingOtherFiles() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File preferred = write(directory, ModelFiles.MODEL_FILE, "preferred");
        File manual = write(directory, "manual.task", "manual");
        File empty = write(directory, "empty.task", "");
        File other = write(directory, "notes.txt", "keep");
        File nestedDirectory = new File(directory, "folder.task");
        assertTrue(nestedDirectory.mkdir());
        File nestedModel = write(nestedDirectory, "nested.task", "keep");
        stage(files, MODEL_BYTES);

        files.delete();

        assertFalse(preferred.exists());
        assertFalse(manual.exists());
        assertFalse(empty.exists());
        assertFalse(files.stagingFile().exists());
        assertTrue(other.exists());
        assertTrue(nestedModel.exists());
        assertNull(files.findInstalled());
        files.delete(); // Deletion is safe when already absent.
    }

    private static File write(File directory, String name, String text) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void stage(ModelFiles files, byte[] bytes) throws IOException {
        File staged = files.stagingFile();
        assertTrue(staged.getParentFile().isDirectory() || staged.getParentFile().mkdirs());
        Files.write(staged.toPath(), bytes);
    }

    private static void assertInstallFails(ModelFiles files, long expectedBytes, String hash)
            throws IOException {
        try {
            files.install(expectedBytes, hash);
            fail("Expected model verification to fail");
        } catch (IOException expected) {
            // Invalid or partial downloads must not be published as installed models.
        }
    }
}
