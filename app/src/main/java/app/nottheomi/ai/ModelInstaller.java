package app.nottheomi.ai;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** Copies only the pinned bundled Whisper model; never downloads or touches recordings. */
public final class ModelInstaller {
    public static final String MODEL_FILE = "ggml-small.en-q5_1.bin";
    public static final String MODEL_SHA256 =
            "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30";
    public static final long MODEL_BYTES = 190098681L;
    private ModelInstaller() { }

    public static File prepare(Context context) throws Exception {
        return prepare(context, () -> false);
    }

    public static File prepare(Context context, BooleanSupplier cancelled) throws Exception {
        return prepare(new File(context.getNoBackupFilesDir(), "speech-model"),
                () -> context.getAssets().open(MODEL_FILE), cancelled);
    }

    interface AssetSource { InputStream open() throws IOException; }

    static synchronized File prepare(File base, AssetSource source, BooleanSupplier cancelled)
            throws Exception {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(base.toPath())) throw new IOException("Unsafe model directory");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Model directory unavailable");
        File installed = new File(base, MODEL_FILE);
        File staging = new File(base, MODEL_FILE + ".installing");
        // Fully hash existing content on every preparation, rather than trusting a marker.
        if (verify(installed, cancelled)) return installed;
        Files.deleteIfExists(staging.toPath()); // Unlinks symlinks, never follows them.
        if (base.getUsableSpace() < MODEL_BYTES + 8L * 1024 * 1024)
            throw new IOException("Insufficient space for offline model");
        boolean published = false;
        try {
            if (!staging.createNewFile()) throw new IOException("Cannot stage offline model");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(source.open());
                 FileOutputStream out = new FileOutputStream(staging)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    length += count;
                    if (length > MODEL_BYTES) throw new IOException("Oversized bundled model");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
                if (length != MODEL_BYTES || !MODEL_SHA256.equals(hex(digest.digest())))
                    throw new IOException("Bundled model checksum mismatch");
                out.getFD().sync();
            }
            checkCancelled(cancelled);
            Files.move(staging.toPath(), installed.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            checkCancelled(cancelled);
            return installed;
        } finally {
            if (!published) Files.deleteIfExists(staging.toPath());
        }
    }

    static boolean verify(File file, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                || file.length() != MODEL_BYTES) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, count);
            }
        }
        checkCancelled(cancelled);
        return MODEL_SHA256.equals(hex(digest.digest()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Model preparation cancelled");
    }
}
