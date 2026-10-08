package app.nottheomi.ai;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Real production installer and pinned bundled Whisper file; no native inference. */
public final class ModelInstallerHostTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        File asset = new File(args[0]), temporary = new File(args[1]);
        File base = new File(temporary, "install");
        ModelInstaller.AssetSource source = () -> new FileInputStream(asset);
        File installed = ModelInstaller.prepare(base, source, () -> false);
        check(installed.getName().equals(ModelInstaller.MODEL_FILE), "Exact model filename");
        check(ModelInstaller.verify(installed, () -> false), "Full pinned install verifies");
        check(!new File(base, installed.getName() + ".installing").exists(), "No staging after publish");
        AtomicInteger opens = new AtomicInteger();
        File reused = ModelInstaller.prepare(base, () -> {
            opens.incrementAndGet(); throw new IOException("Should not reopen asset");
        }, () -> false);
        check(reused.equals(installed) && opens.get() == 0, "Verified install reused offline");
        try (RandomAccessFile file = new RandomAccessFile(installed, "rw")) {
            int first = file.read(); file.seek(0); file.write(first ^ 1);
        }
        check(!ModelInstaller.verify(installed, () -> false), "Same-size corruption detected");
        reject(() -> ModelInstaller.prepare(base, () -> new ByteArrayInputStream(new byte[]{1}), () -> false),
                IOException.class, "Invalid asset rejected");
        check(installed.length() == ModelInstaller.MODEL_BYTES, "Failed repair preserves prior file");
        ModelInstaller.prepare(base, source, () -> false);
        check(ModelInstaller.verify(installed, () -> false), "Corrupt install repaired");
        try (RandomAccessFile file = new RandomAccessFile(installed, "rw")) { file.setLength(100); }
        check(!ModelInstaller.verify(installed, () -> false), "Truncation detected");
        Files.delete(installed.toPath());
        check(!ModelInstaller.verify(installed, () -> false), "Missing model detected");
        Files.createSymbolicLink(installed.toPath(), asset.toPath());
        check(!ModelInstaller.verify(installed, () -> false), "Symlink to valid model rejected");
        ModelInstaller.prepare(base, source, () -> false);
        check(!Files.isSymbolicLink(installed.toPath()), "Repair replaces symlink, not target");
        check(ModelInstaller.verify(asset, () -> false), "Original asset untouched");
        reject(() -> ModelInstaller.prepare(new File(temporary, "cancel"), source, () -> true),
                InterruptedIOException.class, "Cancellation before preparation");
        AtomicInteger polls = new AtomicInteger();
        reject(() -> ModelInstaller.prepare(base, source, () -> polls.incrementAndGet() > 5),
                InterruptedIOException.class, "Cancellation during existing-file verification");
        File cancelledBase = new File(temporary, "cancel-copy");
        polls.set(0);
        reject(() -> ModelInstaller.prepare(cancelledBase, source, () -> polls.incrementAndGet() > 10),
                InterruptedIOException.class, "Cancellation during asset copy");
        check(!new File(cancelledBase, ModelInstaller.MODEL_FILE).exists(), "Cancelled copy not published");
        check(!new File(cancelledBase, ModelInstaller.MODEL_FILE + ".installing").exists(), "Cancelled staging removed");
        File baseLink = new File(temporary, "base-link");
        Files.createSymbolicLink(baseLink.toPath(), base.toPath());
        reject(() -> ModelInstaller.prepare(baseLink, source, () -> false), IOException.class, "Symlink base rejected");
        File legacy = new File(base, "vosk-model-small-en-us-0.15");
        check(legacy.mkdir(), "Legacy directory fixture");
        Files.write(new File(legacy, "keep").toPath(), new byte[]{4,5,6});
        ModelInstaller.prepare(base, source, () -> false);
        check(new File(legacy, "keep").length() == 3, "Migration does not delete old data");
        System.out.println("ModelInstallerHostTest PASS: " + assertions + " assertions, real pinned Whisper model");
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked code, Class<? extends Exception> expected, String label) throws Exception {
        try { code.run(); } catch (Exception failure) {
            if (!expected.isInstance(failure)) throw failure;
            check(true, label); return;
        }
        throw new AssertionError("Expected rejection: " + label);
    }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        assertions++;
    }
}
