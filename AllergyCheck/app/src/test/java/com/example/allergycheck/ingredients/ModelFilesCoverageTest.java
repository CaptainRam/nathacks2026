package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Model discovery and verification cases supplementing test-plan section 11. */
public class ModelFilesCoverageTest {
    private static final byte[] MODEL_BYTES = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String MODEL_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void m01LinkedStagingCannotPublishOrModifyItsTarget() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File target = write(temporaryFolder.getRoot(), "outside-model", MODEL_BYTES);
        assertTrue(files.stagingFile().getParentFile().mkdirs());
        link(files.stagingFile(), target);

        assertInstallFails(files);

        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(target.toPath()));
        assertFalse(new File(directory, ModelFiles.MODEL_FILE).exists());
    }

    @Test
    public void m09RecoveryRejectsALinkedPublishedModel() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File target = write(temporaryFolder.getRoot(), "outside-model", MODEL_BYTES);
        link(new File(directory, ModelFiles.MODEL_FILE), target);

        assertInstallFails(files);

        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void m02DeletingALinkedModelLeavesItsTargetUntouched() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File target = write(temporaryFolder.getRoot(), "outside-model", MODEL_BYTES);
        File link = new File(directory, "manual.task");
        link(link, target);

        files.delete();

        assertFalse(link.exists());
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void m03ManuallyCopiedUppercaseTaskExtensionIsFound() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File installed = write(directory, "MODEL.TASK", MODEL_BYTES);

        assertEquals(installed, files.findInstalled());
    }

    @Test
    public void deletingModelsAlsoRemovesMixedCaseExtensionsButPreservesOtherFiles() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        File upper = write(directory, "MODEL.TASK", MODEL_BYTES);
        File mixed = write(directory, "manual.Task", MODEL_BYTES);
        File other = write(directory, "model.task.backup", MODEL_BYTES);

        files.delete();

        assertFalse(upper.exists());
        assertFalse(mixed.exists());
        assertTrue(other.exists());
    }

    @Test
    public void uppercaseEmptyFilesAndDirectoriesAreNotInstalledModels() throws Exception {
        File directory = temporaryFolder.newFolder("models");
        ModelFiles files = new ModelFiles(directory);
        write(directory, "EMPTY.TASK", new byte[0]);
        File folder = new File(directory, "FOLDER.TASK");
        assertTrue(folder.mkdir());
        File nested = write(folder, "nested.TASK", MODEL_BYTES);

        org.junit.Assert.assertNull(files.findInstalled());
        files.delete();
        assertTrue(nested.exists());
    }

    private static File write(File directory, String name, byte[] content) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), content);
        return file;
    }

    private static void link(File link, File target) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            Assume.assumeNoException("Test filesystem must allow symbolic links", error);
        }
    }

    private static void assertInstallFails(ModelFiles files) {
        org.junit.Assert.assertThrows(IOException.class,
                () -> files.install(MODEL_BYTES.length, MODEL_SHA256));
    }
}
