package com.example.allergycheck.ingredients;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/** Keeps incomplete downloads separate from the model files the inference engine can see. */
public final class ModelFiles {
    public static final String MODEL_FILE = "gemma3-1b-it-int4.task";

    private final File directory;

    public ModelFiles(File directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    /** Also recognises models copied into the folder manually, as previous app versions did. */
    public File findInstalled() {
        File preferred = new File(directory, MODEL_FILE);
        if (isModel(preferred)) {
            return preferred;
        }
        File[] candidates = directory.listFiles(file -> hasModelExtension(file)
                && isModel(file));
        if (candidates == null || candidates.length == 0) {
            return null;
        }
        Arrays.sort(candidates, Comparator.comparing(File::getName));
        return candidates[0];
    }

    /** This name deliberately has no .task extension, even after DownloadManager finishes. */
    public File stagingFile() {
        return new File(new File(directory, "download"), "model.download");
    }

    /**
     * Checks the complete download before publishing it with a rename on the same filesystem.
     * Call off the UI thread; hashing a model reads hundreds of megabytes.
     * If validation or the atomic move fails, an existing installed model is left untouched.
     */
    public void install(long expectedBytes, String expectedSha256) throws IOException {
        if (expectedBytes <= 0 || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("The model download has invalid verification metadata");
        }
        File staged = stagingFile();
        File installed = new File(directory, MODEL_FILE);
        if (!Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            // Recover if the process stopped after publishing but before clearing the download ID.
            verify(installed, expectedBytes, expectedSha256);
            return;
        }
        verify(staged, expectedBytes, expectedSha256);
        Files.move(staged.toPath(), installed.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void verify(File file, long expectedBytes, String expectedSha256)
            throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                || file.length() != expectedBytes) {
            throw new IOException("The model download is incomplete");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        long bytesRead = 0;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytesRead += count;
            }
        }
        if (bytesRead != expectedBytes || !hex(digest.digest()).equalsIgnoreCase(expectedSha256)) {
            throw new IOException("The model download failed its integrity check");
        }
    }

    /** Removes model files and the download only; never traverses or deletes other app files. */
    public void delete() throws IOException {
        IOException failure = null;
        File[] models = directory.listFiles(file -> hasModelExtension(file)
                && !file.isDirectory());
        if (models == null && directory.exists()) {
            failure = new IOException("The model directory could not be read");
        }
        if (models != null) {
            for (File model : models) {
                try {
                    Files.deleteIfExists(model.toPath());
                } catch (IOException e) {
                    failure = collect(failure, e);
                }
            }
        }
        File staged = stagingFile();
        if (staged.isDirectory()) {
            failure = collect(failure, new IOException("The model download path is a directory"));
        } else {
            try {
                Files.deleteIfExists(staged.toPath());
            } catch (IOException e) {
                failure = collect(failure, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean isModel(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() > 0;
    }

    private static boolean hasModelExtension(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".task");
    }

    private static IOException collect(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static String hex(byte[] bytes) {
        char[] characters = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = characters[(bytes[i] & 0xff) >>> 4];
            result[i * 2 + 1] = characters[bytes[i] & 0xf];
        }
        return new String(result);
    }
}
