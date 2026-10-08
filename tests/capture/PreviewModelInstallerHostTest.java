package app.nottheomi.ai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Actual production installer + real bundled archive, executed on a host JVM. */
public final class PreviewModelInstallerHostTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        File archive = new File(args[0]);
        File temporary = new File(args[1]);
        File base = new File(temporary, "install");
        PreviewModelInstaller.AssetSource source = () -> new FileInputStream(archive);
        File installed = PreviewModelInstaller.prepare(base, source, () -> false);
        check(PreviewModelInstaller.verify(installed, () -> false), "Full pinned install verifies");
        check(!new File(base, installed.getName() + ".installing").exists(), "No staging after publish");
        AtomicInteger opens = new AtomicInteger();
        File reused = PreviewModelInstaller.prepare(base, () -> {
            opens.incrementAndGet(); throw new IOException("Should not reopen asset");
        }, () -> false);
        check(reused.equals(installed) && opens.get() == 0, "Verified install reused offline");

        File mdl = new File(installed, "am/final.mdl");
        try (RandomAccessFile file = new RandomAccessFile(mdl, "rw")) {
            int first = file.read(); file.seek(0); file.write(first ^ 1);
        }
        check(!PreviewModelInstaller.verify(installed, () -> false), "Same-size corruption detected");
        check(new File(installed, "graph/Gr.fst").delete(), "Required graph removed for recovery fixture");
        check(!PreviewModelInstaller.verify(installed, () -> false), "Missing required graph rejected");
        PreviewModelInstaller.prepare(base, source, () -> false);
        check(PreviewModelInstaller.verify(installed, () -> false), "Corrupt/missing install repaired from asset");
        File extra = new File(installed, "unexpected");
        Files.write(extra.toPath(), new byte[] { 1 });
        check(!PreviewModelInstaller.verify(installed, () -> false), "Unexpected installed file rejected");
        Files.delete(extra.toPath());

        File original = new File(installed, "conf/mfcc.conf");
        File outside = new File(temporary, "external.conf");
        Files.copy(original.toPath(), outside.toPath());
        Files.delete(original.toPath());
        Files.createSymbolicLink(original.toPath(), outside.toPath());
        check(!PreviewModelInstaller.verify(installed, () -> false), "Symlink even to valid model content rejected");
        Files.delete(original.toPath());
        Files.copy(outside.toPath(), original.toPath());

        reject(() -> PreviewModelInstaller.prepare(new File(temporary, "cancel"), source, () -> true),
                InterruptedIOException.class, "Cancellation before preparation");
        File symlinkBase = new File(temporary, "symlink-base");
        Files.createSymbolicLink(symlinkBase.toPath(), base.toPath());
        reject(() -> PreviewModelInstaller.prepare(symlinkBase, source, () -> false),
                IOException.class, "Symlinked model installation base rejected");
        check(PreviewModelInstaller.verify(installed, () -> false), "Rejected symlink leaves existing install untouched");
        AtomicInteger polls = new AtomicInteger();
        reject(() -> PreviewModelInstaller.prepare(base, source, () -> polls.incrementAndGet() > 5),
                InterruptedIOException.class, "Cancellation during installed checksum verification");
        reject(() -> PreviewModelInstaller.prepare(new File(temporary, "bad-zip"),
                        () -> new ByteArrayInputStream(new byte[] {1,2,3}), () -> false),
                IOException.class, "Wrong archive hash rejected before installation");

        for (String name : new String[] {"vosk-model-small-en-us-0.15/../escape",
                "vosk-model-small-en-us-0.15/am/../../escape", "/absolute",
                "vosk-model-small-en-us-0.15/am\\escape", "vosk-model-small-en-us-0.15//escape",
                "vosk-model-small-en-us-0.15/unexpected"}) {
            File stage = Files.createTempDirectory(temporary.toPath(), "bad-entry-").toFile();
            reject(() -> PreviewModelInstaller.extractChecked(new ByteArrayInputStream(zip(name)), stage, () -> false),
                    IOException.class, "Unsafe/unexpected ZIP member rejected: " + name);
        }
        check(!new File(temporary, "escape").exists(), "Traversal wrote nothing outside staging");
        File truncatedStage = Files.createTempDirectory(temporary.toPath(), "incomplete-").toFile();
        reject(() -> PreviewModelInstaller.extractChecked(new ByteArrayInputStream(zip(
                        "vosk-model-small-en-us-0.15/am/final.mdl")), truncatedStage, () -> false),
                IOException.class, "Truncated required member rejected");
        File emptyStage = Files.createTempDirectory(temporary.toPath(), "empty-").toFile();
        reject(() -> PreviewModelInstaller.extractChecked(new ByteArrayInputStream(emptyZip()), emptyStage, () -> false),
                IOException.class, "Missing all required members rejected");

        // First open hashes the whole asset; cancellation on the second open occurs
        // after staging was created, exercising guaranteed interrupted-tree removal.
        AtomicInteger extractionOpens = new AtomicInteger();
        File cancelledBase = new File(temporary, "cancel-extract");
        reject(() -> PreviewModelInstaller.prepare(cancelledBase, () -> {
            extractionOpens.incrementAndGet(); return new FileInputStream(archive);
        }, () -> extractionOpens.get() >= 2), InterruptedIOException.class,
                "Cancellation during extraction");
        check(!new File(cancelledBase, "vosk-model-small-en-us-0.15").exists(), "Cancelled install not published");
        check(!new File(cancelledBase, "vosk-model-small-en-us-0.15.installing").exists(), "Cancelled staging removed");
        System.out.println("PreviewModelInstallerHostTest PASS: " + assertions + " assertions, real pinned model archive");
    }

    private static byte[] zip(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] emptyZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { }
        return bytes.toByteArray();
    }

    private interface Checked { void run() throws Exception; }
    private static void reject(Checked checked, Class<? extends Exception> expected, String label) throws Exception {
        try { checked.run(); }
        catch (Exception failure) {
            if (!expected.isInstance(failure)) throw failure;
            check(true, label); return;
        }
        throw new AssertionError("Expected rejection: " + label);
    }
    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        assertions++;
    }
}