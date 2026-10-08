package app.nottheomi.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android SQLite/Keystore, AudioTrack, focus and receiver-registration lifecycle tests.
 * Only synthetic PCM silence enters the real audio sink; no microphone or production store.
 * A synthetic noisy Intent is delivered to the actually registered receiver on main because
 * ordinary test apps cannot send Android's protected system broadcast. This checks the
 * broadcast handler, NOT wired/Bluetooth routing or hardware audibility.
 */
@SuppressWarnings("deprecation")
public final class PlaybackTest extends AndroidTestCase {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private final List<ReaderGate> gates = new ArrayList<>();
    private Recordings store;
    private TrackingContext context;
    private LocalPlayback playback;
    private String databaseName;
    private String alias;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        databaseName = "playback-test-" + token + ".db";
        alias = "app.nottheomi.ai.playback-test." + token;
        store = new Recordings(getContext(), databaseName, alias);
        context = new TrackingContext(getContext().getApplicationContext());
    }

    @Override protected void tearDown() throws Exception {
        try {
            // Even a failed assertion must release a deliberately held decrypted reader.
            for (ReaderGate gate : gates) gate.release.countDown();
            if (playback != null) {
                CountDownLatch cleaned = new CountDownLatch(1);
                playback.stop(cleaned::countDown);
                await("teardown playback cleanup", cleaned);
            }
            if (store != null) store.closeForTest();
            if (databaseName != null) getContext().deleteDatabase(databaseName);
            if (alias != null) {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
                keys.load(null);
                keys.deleteEntry(alias);
            }
        } finally { super.tearDown(); }
    }

    public void testStopCompletionWaitsForReaderBeforeDelete() throws Exception {
        String id = silence(Recordings.MAX_CHUNK_BYTES);
        ReaderGate gate = gate();
        playback = new LocalPlayback(context,
                (recording, sink) -> store.forEachPcm(recording, pcm -> {
                    gate.hold();
                    sink.accept(pcm);
                }));
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        playback.play(id, checked(() -> {
            assertMainThread();
            assertFalse(playback.playing);
            completions.incrementAndGet();
            finished.countDown();
        }), this::unexpectedFailure);
        await("active decrypted reader", gate.entered);
        assertDeleteBlocked(id);
        playback.stop(checked(() -> {
            assertMainThread();
            assertEquals(0, context.active.get());
            assertEquals(1, context.unregistrations.get());
            // This is intentionally inside the barrier: the old implementation raced it.
            try { store.delete(id); }
            catch (Exception error) { throw new AssertionError("Reader still blocks deletion", error); }
            cleaned.countDown();
        }));
        await("cancellation reached reader", gate.interrupted);
        onMain(() -> assertEquals("Stop cannot complete while reader is held", 1L,
                cleaned.getCount()));
        assertDeleteBlocked(id);
        gate.release.countDown();
        await("stop cleanup and deletion", cleaned);
        await("cancelled playback UI completion", finished);
        checkCallbacks();
        assertEquals(1, completions.get());
        assertNull(store.find(id));
        assertFalse(playback.playing);
        assertEquals(1, context.registrations.get());
    }

    public void testNoisyBroadcastCancelsAndResetsUiAfterCleanup() throws Exception {
        String id = silence(Recordings.MAX_CHUNK_BYTES);
        ReaderGate gate = gate();
        playback = new LocalPlayback(context,
                (recording, sink) -> store.forEachPcm(recording, pcm -> {
                    gate.hold();
                    sink.accept(pcm);
                }));
        CountDownLatch finished = new CountDownLatch(1);
        playback.play(id, checked(() -> {
            assertMainThread();
            assertFalse(playback.playing);
            assertEquals(0, context.active.get());
            finished.countDown();
        }), this::unexpectedFailure);
        await("reader before noisy event", gate.entered);
        BroadcastReceiver receiver = context.receiver.get();
        assertNotNull(receiver);
        onMain(() -> receiver.onReceive(context,
                new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)));
        await("noisy cancellation reached reader", gate.interrupted);
        assertFalse(playback.playing);
        assertEquals("UI completion must await resource cleanup", 1L, finished.getCount());
        gate.release.countDown();
        await("noisy UI completion", finished);
        checkCallbacks();
        assertEquals(1, context.unregistrations.get());
        assertNull(context.receiver.get());
        store.delete(id); // No retained reader after the finished callback either.
        assertNull(store.find(id));
    }

    public void testReplacementIsSerializedAndStaleNoisyCannotStopIt() throws Exception {
        String firstId = silence(Recordings.MAX_CHUNK_BYTES);
        String secondId = silence(Recordings.MAX_CHUNK_BYTES);
        ReaderGate first = gate();
        ReaderGate second = gate();
        AtomicInteger obsoleteFinished = new AtomicInteger();
        CountDownLatch currentFinished = new CountDownLatch(1);
        playback = new LocalPlayback(context,
                (recording, sink) -> store.forEachPcm(recording, pcm -> {
                    (firstId.equals(recording) ? first : second).hold();
                    sink.accept(pcm);
                }));
        playback.play(firstId, obsoleteFinished::incrementAndGet, this::unexpectedFailure);
        await("first reader", first.entered);
        BroadcastReceiver oldReceiver = context.receiver.get();
        playback.play(secondId, checked(() -> {
            assertMainThread();
            currentFinished.countDown();
        }), this::unexpectedFailure);
        await("replacement cancels first reader", first.interrupted);
        assertEquals("Replacement cannot open a concurrent reader", 1L, second.entered.getCount());
        assertEquals("Replacement cannot register/open audio before old cleanup", 1,
                context.registrations.get());
        first.release.countDown();
        await("replacement reader", second.entered);
        onMain(() -> {
            // Model a route event already queued when the old receiver was unregistered.
            oldReceiver.onReceive(context, new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            context.receiver.get().onReceive(context, new Intent("app.nottheomi.ai.UNRELATED"));
            assertTrue("Stale or unrelated route callback stopped the new play", playback.playing);
            assertEquals(0, obsoleteFinished.get());
        });
        assertEquals(1, context.peakActive.get());
        assertEquals(2, context.registrations.get());
        assertEquals(1, context.unregistrations.get());
        playback.stop(); // Preserve the no-argument API and its UI completion behavior.
        await("replacement stop reaches reader", second.interrupted);
        second.release.countDown();
        await("replacement UI reset", currentFinished);
        checkCallbacks();
        assertEquals(0, obsoleteFinished.get());
        assertEquals(2, context.unregistrations.get());
        assertEquals(0, context.active.get());
        store.delete(firstId);
        store.delete(secondId);
    }

    public void testQueuedPlayCancelledBeforeStartStillCompletesStopBarrier() throws Exception {
        String firstId = silence(Recordings.MAX_CHUNK_BYTES);
        String queuedId = silence(Recordings.MAX_CHUNK_BYTES);
        ReaderGate first = gate();
        AtomicInteger sourceEntries = new AtomicInteger();
        AtomicInteger obsoleteFinished = new AtomicInteger();
        CountDownLatch cancelledFinished = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        playback = new LocalPlayback(context, (recording, sink) -> {
            sourceEntries.incrementAndGet();
            store.forEachPcm(recording, pcm -> { first.hold(); sink.accept(pcm); });
        });
        playback.play(firstId, obsoleteFinished::incrementAndGet, this::unexpectedFailure);
        await("first reader", first.entered);
        playback.play(queuedId, checked(() -> {
            assertMainThread();
            cancelledFinished.countDown();
        }), this::unexpectedFailure);
        playback.stop(checked(() -> {
            assertMainThread();
            assertEquals(0, context.active.get());
            assertEquals(1, sourceEntries.get());
            cleaned.countDown();
        }));
        await("prior reader cancellation", first.interrupted);
        onMain(() -> assertEquals(1L, cleaned.getCount()));
        first.release.countDown();
        await("all prior jobs cleanup", cleaned);
        await("queued cancellation UI reset", cancelledFinished);
        checkCallbacks();
        assertFalse(playback.playing);
        assertEquals(0, obsoleteFinished.get());
        assertEquals(1, context.registrations.get());
        assertEquals(1, context.unregistrations.get());
        store.delete(firstId);
        store.delete(queuedId);
    }

    public void testStopBarrierIsNotSuppressedByNewerPlay() throws Exception {
        String firstId = silence(Recordings.MAX_CHUNK_BYTES);
        String secondId = silence(Recordings.MAX_CHUNK_BYTES);
        ReaderGate first = gate();
        ReaderGate second = gate();
        AtomicInteger obsoleteFinished = new AtomicInteger();
        CountDownLatch cleaned = new CountDownLatch(1);
        CountDownLatch currentFinished = new CountDownLatch(1);
        playback = new LocalPlayback(context,
                (recording, sink) -> store.forEachPcm(recording, pcm -> {
                    (firstId.equals(recording) ? first : second).hold();
                    sink.accept(pcm);
                }));
        playback.play(firstId, obsoleteFinished::incrementAndGet, this::unexpectedFailure);
        await("first reader", first.entered);
        playback.stop(checked(() -> {
            assertMainThread();
            try { store.delete(firstId); }
            catch (Exception error) { throw new AssertionError("Prior reader survived barrier", error); }
            cleaned.countDown();
        }));
        playback.play(secondId, currentFinished::countDown, this::unexpectedFailure);
        await("prior cancellation", first.interrupted);
        first.release.countDown();
        await("barrier despite newer generation", cleaned);
        await("newer reader", second.entered);
        onMain(() -> {
            assertTrue("Prior barrier must not cancel a subsequent play", playback.playing);
            assertEquals(0, obsoleteFinished.get());
        });
        assertNull(store.find(firstId));
        playback.stop();
        await("newer cancellation", second.interrupted);
        second.release.countDown();
        await("newer UI completion", currentFinished);
        checkCallbacks();
        store.delete(secondId);
    }

    public void testNaturalCompletionUnregistersReceiver() throws Exception {
        String id = silence(320); // Real sink writes a short silent PCM buffer and drains it.
        playback = new LocalPlayback(context, store::forEachPcm);
        CountDownLatch finished = new CountDownLatch(1);
        playback.play(id, checked(() -> {
            assertMainThread();
            assertFalse(playback.playing);
            assertEquals(0, context.active.get());
            finished.countDown();
        }), this::unexpectedFailure);
        await("natural completion", finished);
        checkCallbacks();
        assertEquals(1, context.registrations.get());
        assertEquals(1, context.unregistrations.get());
        store.delete(id);
    }

    public void testFailureUnregistersBeforeMainCallbacks() throws Exception {
        playback = new LocalPlayback(context, store::forEachPcm);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        playback.play("missing-synthetic-recording", checked(() -> {
            assertMainThread();
            assertFalse(playback.playing);
            assertEquals(0, context.active.get());
            finished.countDown();
        }), message -> checked(() -> {
            assertMainThread();
            assertEquals("Playback unavailable. Your recording was not changed.", message);
            assertEquals(0, context.active.get());
            errors.incrementAndGet();
        }).run());
        await("failure UI completion", finished);
        checkCallbacks();
        assertEquals(1, errors.get());
        assertEquals(1, context.registrations.get());
        assertEquals(1, context.unregistrations.get());
    }

    public void testIdleStopCompletionIsAsynchronousAndOnMain() throws Exception {
        playback = new LocalPlayback(context, store::forEachPcm);
        CountDownLatch cleaned = new CountDownLatch(1);
        onMain(() -> {
            playback.stop(checked(() -> {
                assertMainThread();
                cleaned.countDown();
            }));
            assertEquals("Callback must not run inline", 1L, cleaned.getCount());
        });
        await("idle stop completion", cleaned);
        checkCallbacks();
        assertEquals(0, context.registrations.get());
    }

    private String silence(int bytes) throws Exception {
        String id = store.create().id;
        byte[] pcm = new byte[bytes];
        store.appendAudio(id, pcm, pcm.length);
        store.finish(id, "completed");
        return id;
    }

    private ReaderGate gate() {
        ReaderGate gate = new ReaderGate();
        gates.add(gate);
        return gate;
    }

    private void assertDeleteBlocked(String id) throws Exception {
        try { store.delete(id); fail("Deleting an active playback reader must fail"); }
        catch (IllegalStateException expected) {
            assertEquals("Stop playback or wait for export before deleting.", expected.getMessage());
        }
    }

    private void unexpectedFailure(String message) {
        callbackFailure.compareAndSet(null, new AssertionError(message));
    }

    private Runnable checked(Runnable assertion) {
        return () -> {
            try { assertion.run(); }
            catch (Throwable error) { callbackFailure.compareAndSet(null, error); }
        };
    }

    private void checkCallbacks() {
        Throwable error = callbackFailure.get();
        if (error != null) throw new AssertionError("Playback callback assertion failed", error);
    }

    private void onMain(Runnable action) throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        main.post(() -> { try { checked(action).run(); } finally { delivered.countDown(); } });
        await("main callback", delivered);
        checkCallbacks();
    }

    private static void assertMainThread() {
        assertSame(Looper.getMainLooper(), Looper.myLooper());
    }

    private static void await(String label, CountDownLatch latch) throws InterruptedException {
        assertTrue("Timed out: " + label, latch.await(10, TimeUnit.SECONDS));
    }

    /** Hold a real Recordings reader through cancellation to make the deletion race deterministic. */
    private static final class ReaderGate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void hold() throws Exception {
            entered.countDown();
            boolean wasInterrupted = false;
            try {
                while (true) {
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Synthetic reader gate timed out");
                        }
                        return;
                    } catch (InterruptedException cancellation) {
                        wasInterrupted = true;
                        interrupted.countDown();
                    }
                }
            } finally {
                if (wasInterrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Observe actual framework registration without replacing the media or storage APIs. */
    private static final class TrackingContext extends ContextWrapper {
        final AtomicReference<BroadcastReceiver> receiver = new AtomicReference<>();
        final AtomicInteger registrations = new AtomicInteger();
        final AtomicInteger unregistrations = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger peakActive = new AtomicInteger();

        TrackingContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }

        @Override public Intent registerReceiver(BroadcastReceiver target, IntentFilter filter,
                String permission, Handler scheduler) {
            Intent sticky = super.registerReceiver(target, filter, permission, scheduler);
            registered(target, filter, scheduler);
            return sticky;
        }

        @Override public Intent registerReceiver(BroadcastReceiver target, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            Intent sticky = super.registerReceiver(target, filter, permission, scheduler, flags);
            registered(target, filter, scheduler);
            return sticky;
        }

        private void registered(BroadcastReceiver target, IntentFilter filter, Handler scheduler) {
            assertTrue(filter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            assertSame(Looper.getMainLooper(), scheduler.getLooper());
            receiver.set(target);
            registrations.incrementAndGet();
            int count = active.incrementAndGet();
            peakActive.updateAndGet(peak -> Math.max(peak, count));
        }

        @Override public void unregisterReceiver(BroadcastReceiver target) {
            super.unregisterReceiver(target);
            receiver.compareAndSet(target, null);
            unregistrations.incrementAndGet();
            active.decrementAndGet();
        }
    }
}
