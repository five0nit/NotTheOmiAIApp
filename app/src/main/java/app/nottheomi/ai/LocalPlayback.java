package app.nottheomi.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Streams decrypted PCM directly into AudioTrack; never makes a plaintext cache. */
public final class LocalPlayback {
    private final Context context;
    private final PcmSource source;
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    // A replacement never opens audio/focus/readers until the previous job has closed them.
    // Let the idle thread expire so stopped/destroyed Activities do not retain an executor thread.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 1, 10,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            task -> new Thread(task, "local-playback"));
    private long generation;
    private Playback latest;
    private Thread running;
    public volatile boolean playing;

    public LocalPlayback(Context context) {
        this.context = context.getApplicationContext();
        this.source = (id, consumer) -> Recordings.get(this.context).forEachPcm(id, consumer);
    }

    /** Package-private source seam: tests still stream from isolated real encrypted stores. */
    LocalPlayback(Context context, PcmSource source) {
        this.context = context.getApplicationContext();
        this.source = source;
    }

    interface PcmSource {
        void forEachPcm(String id, Recordings.PcmConsumer consumer) throws Exception;
    }

    public void stop() { stop(null); }

    /**
     * Cancels without blocking the caller. afterCleanup, when non-null, runs on the main
     * thread after ALL previously queued playback jobs have closed their readers, released
     * audio, abandoned focus and unregistered their receiver. It runs even if a newer play
     * supersedes the UI completion callback. A later play is not part of this barrier.
     */
    public void stop(Runnable afterCleanup) {
        synchronized (lock) {
            cancelLocked();
            if (afterCleanup != null) workers.execute(() -> main.post(afterCleanup));
        }
    }

    public void play(String id, Runnable finished, Consumer<String> failed) {
        synchronized (lock) {
            cancelLocked();
            Playback playback = new Playback(++generation, id, finished, failed);
            latest = playback;
            playing = true;
            workers.execute(() -> run(playback));
        }
    }

    private void cancelLocked() {
        if (latest != null) latest.cancelled = true;
        playing = false;
        if (running != null) running.interrupt();
    }

    // The check and cancellation are atomic: an old queued route/focus event cannot stop
    // playback selected after the event's originating generation.
    private void stopGeneration(Playback playback) {
        synchronized (lock) {
            if (latest == playback && !playback.cancelled) cancelLocked();
        }
    }

    private void checkCancelled(Playback playback) throws InterruptedException {
        if (playback.cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private void run(Playback playback) {
        AudioTrack track = null;
        AudioFocusRequest focus = null;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean registered = false;
        boolean failed = false;
        BroadcastReceiver noisy = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    stopGeneration(playback);
                }
            }
        };
        synchronized (lock) { running = Thread.currentThread(); }
        try {
            checkCancelled(playback);
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(noisy, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(noisy, filter, null, main);
            }
            registered = true;
            checkCancelled(playback);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0) stopGeneration(playback);
                    }, main).build();
            if (manager == null || manager.requestAudioFocus(focus)
                    != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw new IllegalStateException("Audio focus unavailable");
            }
            checkCancelled(playback);
            int min = AudioTrack.getMinBufferSize(Recordings.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("Audio unavailable");
            track = new AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Recordings.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(Recordings.MAX_CHUNK_BYTES, min * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Audio unavailable");
            }
            checkCancelled(playback);
            track.play();
            final AudioTrack sink = track;
            final long[] written = {0};
            source.forEachPcm(playback.id, pcm -> {
                writePcm(playback, sink, pcm);
                written[0] += pcm.length / 2;
            });
            // Streaming tracks wait for a full initial buffer, even after play().
            // Pad short recordings in memory; drain only their actual audio frames.
            if (written[0] > 0 && written[0] < sink.getBufferSizeInFrames()) {
                writePcm(playback, sink,
                        new byte[(int) (sink.getBufferSizeInFrames() - written[0]) * 2]);
            }
            // Do not truncate the last queued audio buffer on natural completion.
            while (Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < written[0]) {
                checkCancelled(playback);
                Thread.sleep(20);
            }
        } catch (InterruptedException cancelled) {
            // Cancellation is completion, not an error. Do not leak interruption into the
            // next task on this single worker; the generation flag remains cancelled.
        } catch (Exception error) {
            failed = true;
        } finally {
            // Recordings.forEachPcm's finally has already released its reader by this point.
            if (track != null) {
                try { track.pause(); } catch (RuntimeException ignored) { }
                try { track.flush(); } catch (RuntimeException ignored) { }
                try { track.release(); } catch (RuntimeException ignored) { }
            }
            if (focus != null && manager != null) {
                try { manager.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) { }
            }
            if (registered) {
                try { context.unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) { }
            }
            synchronized (lock) {
                running = null;
                Thread.interrupted();
                if (latest == playback) playing = false;
            }
            final boolean reportFailure = failed;
            main.post(() -> {
                synchronized (lock) {
                    // Recheck at delivery, not merely when posting: a newer play may now own UI.
                    if (playback.generation != generation) return;
                    if (reportFailure && !playback.cancelled && playback.failed != null) {
                        playback.failed.accept("Playback unavailable. Your recording was not changed.");
                    }
                    if (playback.generation == generation && playback.finished != null) {
                        playback.finished.run();
                    }
                }
            });
        }
    }

    private void writePcm(Playback playback, AudioTrack sink, byte[] pcm)
            throws InterruptedException {
        int at = 0;
        while (at < pcm.length) {
            checkCancelled(playback);
            int count = sink.write(pcm, at, pcm.length - at, AudioTrack.WRITE_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("Audio playback failed");
            if (count == 0) Thread.sleep(10);
            else at += count;
        }
    }

    private static final class Playback {
        final long generation;
        final String id;
        final Runnable finished;
        final Consumer<String> failed;
        volatile boolean cancelled;

        Playback(long generation, String id, Runnable finished, Consumer<String> failed) {
            this.generation = generation;
            this.id = id;
            this.finished = finished;
            this.failed = failed;
        }
    }
}
