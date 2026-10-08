package app.nottheomi.ai;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Storage boundary spy, NOT encryption/durability implementation. */
public final class Recordings {
    private static final Recordings INSTANCE = new Recordings();
    public static final Map<String, ByteArrayOutputStream> pcm = new LinkedHashMap<>();
    public static final Map<String, List<String>> texts = new LinkedHashMap<>();
    public static final Map<String, String> statuses = new LinkedHashMap<>();
    public static final List<String> operations = new ArrayList<>();
    public static final List<byte[]> audio = new ArrayList<>();
    public static final List<String> audioIds = new ArrayList<>();
    public static String current;
    public static Recordings get(android.content.Context context) { return INSTANCE; }
    public static final class Session { public String id; Session(String id) { this.id = id; } }
    public synchronized Session create() {
        if (current != null) throw new AssertionError("new segment before old finish");
        current = "segment-" + (pcm.size() + 1);
        pcm.put(current, new ByteArrayOutputStream()); texts.put(current, new ArrayList<>());
        operations.add("create:" + current); return new Session(current);
    }
    private void writable(String id) {
        if (!Objects.equals(id, current) || statuses.containsKey(id)) throw new AssertionError("write to non-current/finished segment " + id);
        if (Thread.currentThread().getName().equals("OmiBle")) throw new AssertionError("storage on BLE worker");
    }
    public synchronized void appendAudio(String id, byte[] bytes, int length) {
        writable(id);
        if (length <= 0 || length > 6400) throw new AssertionError("unbounded/empty PCM append");
        byte[] copy = Arrays.copyOf(bytes, length);
        pcm.get(id).write(copy, 0, length); audio.add(copy); audioIds.add(id);
        operations.add("audio:" + id + ":" + length);
    }
    public synchronized void appendText(String id, String text) {
        writable(id); texts.get(id).add(text); operations.add("text:" + id + ":" + text);
    }
    public synchronized void finish(String id, String status) {
        writable(id); statuses.put(id, status); operations.add("finish:" + id); current = null;
    }
    public static void checkArchived(int index, byte[] bytes, int count) {
        synchronized (INSTANCE) {
            if (index >= audio.size() || !Arrays.equals(audio.get(index), Arrays.copyOf(bytes, count)))
                throw new AssertionError("ASR before archive or PCM reordered");
            if (Thread.currentThread().getName().equals("OmiBle")) throw new AssertionError("ASR on BLE worker");
        }
    }
    public static int total() { synchronized (INSTANCE) { return pcm.values().stream().mapToInt(ByteArrayOutputStream::size).sum(); } }
    public static List<String> ids() { synchronized (INSTANCE) { return new ArrayList<>(pcm.keySet()); } }
    public static List<String> finishedIds() { synchronized (INSTANCE) { return new ArrayList<>(statuses.keySet()); } }
    public static byte[] bytes(String id) { synchronized (INSTANCE) { return pcm.get(id).toByteArray(); } }
    public static List<String> text(String id) { synchronized (INSTANCE) { return List.copyOf(texts.get(id)); } }
    public static List<String> log() { synchronized (INSTANCE) { return List.copyOf(operations); } }
    public static String status(String id) { synchronized (INSTANCE) { return statuses.get(id); } }
}
