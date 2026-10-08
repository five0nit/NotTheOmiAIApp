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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs only the bundled, pinned English model. Never downloads anything. */
public final class PreviewModelInstaller {
    public static final String ARCHIVE_SHA256 =
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    private static final String ROOT = "vosk-model-small-en-us-0.15";
    private static final long INSTALL_SPACE = 100L * 1024L * 1024L;
    private static final Map<String, Expected> FILES = new LinkedHashMap<>();

    static {
        expected("am/final.mdl", 15962575, "75370a0137f9daf8f469dedd7daa4513ae7a621f03240c6e512e2b50b656a7b6");
        expected("graph/disambig_tid.int", 102, "9ad87cc166d0998f08f758f47a6223a120dfffcbee805c4849c3aa5e6bb3c0fc");
        expected("graph/HCLr.fst", 22416994, "5caafba3081e1646545ac6bff0dd7a318e53dcbdc86f237909ce1d2ac1293d34");
        expected("graph/Gr.fst", 24013795, "023c8b7e30704a9e37765c635c252e608a02f361235bf94abdcf2a5225d85b20");
        expected("graph/phones/word_boundary.int", 1761, "da199d9c991e0e84681ddbb34627b915b26302d50a8fdaa23c51e2bc3a50b5c3");
        expected("conf/model.conf", 290, "8f14cb1eeb07c762c371db648c6be688d347236155ca0f64fb13b6567a8ce81f");
        expected("conf/mfcc.conf", 131, "1e2228006d01d805ad1c267fee9f79709ca87ac51bd82b0e3f5c69ba543f0fc4");
        expected("ivector/splice.conf", 35, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756");
        expected("ivector/final.dubm", 168048, "8c5d7dd69d2122313baaf19f61f35dd3fa18b70c62ac0687e311e1c46e6daca7");
        expected("ivector/global_cmvn.stats", 1080, "33be09afcc80059847a275c3d043b51f1ab954c7c2438ddbbf4745e8ba144ff9");
        expected("ivector/final.ie", 8288887, "3f37faf90c375b9e4740b569398b5829ed9cc07d19be6d441f72c3b71d7efcc6");
        expected("ivector/online_cmvn.conf", 95, "a2f3571754b64297cb7efb2e7ca3df61995c5a45fcbb97188f90613552bb2dfe");
        expected("ivector/final.mat", 44975, "ddd83586dc5f928cda8738b922c85ffe38fc789cb5f9151a712ca12f37265382");
        expected("README", 199, "c0cf286e4f7783306c5f6469b37db69228fb16803b03cae661edb2d7bba64ebb");
    }

    private PreviewModelInstaller() { }

    /** Call on a worker. Thread interruption cancels before a model is returned. */
    public static File prepare(Context context) throws Exception {
        return prepare(context, () -> false);
    }

    /** Cooperative cancellation also covers an explicit Stop during preparation. */
    public static File prepare(Context context, BooleanSupplier cancelled) throws Exception {
        return prepare(new File(context.getNoBackupFilesDir(), "speech-model"),
                () -> context.getAssets().open("model.zip"), cancelled);
    }

    interface AssetSource { InputStream open() throws IOException; }

    // Package-private entry point permits host testing with the actual bundled asset.
    static synchronized File prepare(File base, AssetSource archive, BooleanSupplier cancelled)
            throws Exception {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(base.toPath())) throw new IOException("Unsafe model directory");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Model directory unavailable");
        File installed = new File(base, ROOT);
        File staging = new File(base, ROOT + ".installing");
        if (verify(installed, cancelled)) return installed;
        removeTree(staging);
        if (base.getUsableSpace() < INSTALL_SPACE) throw new IOException("Insufficient space for offline model");
        // Check the ENTIRE compressed asset, not only the ZIP member payloads.
        try (InputStream input = archive.open()) {
            if (!ARCHIVE_SHA256.equals(hash(input, cancelled))) {
                throw new IOException("Bundled model checksum mismatch");
            }
        }
        checkCancelled(cancelled);
        if (!staging.mkdir()) throw new IOException("Cannot stage offline model");
        boolean published = false;
        try {
            try (InputStream input = archive.open()) {
                extractChecked(input, staging, cancelled);
            }
            if (!verify(staging, cancelled)) throw new IOException("Incomplete offline model");
            checkCancelled(cancelled);
            // Only invalid installs are removed. A partially extracted tree is never published.
            removeTree(installed);
            Files.move(staging.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE);
            published = true;
            checkCancelled(cancelled);
            return installed;
        } finally {
            if (!published) removeTree(staging);
        }
    }

    static void extractChecked(InputStream input, File staging, BooleanSupplier cancelled)
            throws Exception {
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[64 * 1024];
        String prefix = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancelled);
                String name = entry.getName();
                if (!name.startsWith(ROOT + "/") || name.contains("\\")
                        || name.contains("\u0000") || name.startsWith("/")
                        || name.contains("//")) throw new IOException("Unsafe model archive path");
                String relative = name.substring(ROOT.length() + 1);
                for (String part : relative.split("/", -1)) {
                    if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe model archive path");
                }
                File output = new File(staging, relative);
                if (!output.getCanonicalPath().startsWith(prefix)
                        && !(entry.isDirectory() && relative.isEmpty())) {
                    throw new IOException("Model archive path escapes staging directory");
                }
                if (entry.isDirectory()) {
                    String directory = relative.endsWith("/")
                            ? relative.substring(0, relative.length() - 1) : relative;
                    if (!directory.isEmpty() && FILES.keySet().stream()
                            .noneMatch(path -> path.startsWith(directory + "/"))) {
                        throw new IOException("Unexpected model directory");
                    }
                    if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create model directory");
                    continue;
                }
                Expected expected = FILES.get(relative);
                if (expected == null || !seen.add(relative)) throw new IOException("Unexpected or duplicate model file");
                if (entry.getSize() >= 0 && entry.getSize() != expected.length) throw new IOException("Model file length mismatch");
                File parent = output.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create model directory");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (FileOutputStream out = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        length += count;
                        if (length > expected.length) throw new IOException("Oversized model file");
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                    }
                    if (length != expected.length || !expected.sha256.equals(hex(digest.digest()))) {
                        throw new IOException("Extracted model checksum mismatch");
                    }
                    out.getFD().sync();
                }
                zip.closeEntry();
            }
        }
        if (!seen.equals(FILES.keySet())) throw new IOException("Incomplete model archive");
    }

    static boolean verify(File model, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (!Files.isDirectory(model.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        if (!auditTree(model, model, cancelled)) return false;
        for (Map.Entry<String, Expected> entry : FILES.entrySet()) {
            checkCancelled(cancelled);
            File file = new File(model, entry.getKey());
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || file.length() != entry.getValue().length) return false;
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                if (!entry.getValue().sha256.equals(hash(in, cancelled))) return false;
            }
        }
        return true;
    }

    private static boolean auditTree(File root, File current, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        File[] children = current.listFiles();
        if (children == null) return false;
        for (File child : children) {
            checkCancelled(cancelled);
            if (Files.isSymbolicLink(child.toPath())) return false;
            String relative = root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
            if (child.isDirectory()) {
                if (FILES.keySet().stream().noneMatch(path -> path.startsWith(relative + "/"))
                        || !auditTree(root, child, cancelled)) return false;
            } else if (!FILES.containsKey(relative)) return false;
        }
        return true;
    }

    private static String hash(InputStream in, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            checkCancelled(cancelled);
            digest.update(buffer, 0, count);
        }
        checkCancelled(cancelled);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return out.toString();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model preparation cancelled");
        }
    }

    private static void removeTree(File file) throws IOException {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect model staging directory");
            for (File child : children) removeTree(child);
        }
        Files.delete(file.toPath());
    }

    private static void expected(String path, long length, String sha256) {
        FILES.put(path, new Expected(length, sha256));
    }

    private static final class Expected {
        final long length;
        final String sha256;
        Expected(long length, String sha256) { this.length = length; this.sha256 = sha256; }
    }
}