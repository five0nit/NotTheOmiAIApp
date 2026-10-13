package app.nottheomi.ai;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Exercises real OmiCaptureService/queue with controlled boundary/fault doubles. */
public final class OmiCaptureServiceHostTest {
    static OmiCaptureService service;
    static int assertions;
    static final AtomicReference<Throwable> failure = new AtomicReference<>();
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,e) -> {failure.set(e);e.printStackTrace();});
        SharedPreferences.values.put("address", "AA:BB:CC:DD:EE:FF");
        service = new OmiCaptureService(); service.onCreate();
        try {
            switch(args[0]) {
                case "null_intent":
                    service.onStartCommand(null,0,1); service.onStartCommand(new Intent().setAction("other"),0,2);
                    check(!OmiCaptureService.active && Service.foregroundStarts==0,"no implicit startup"); break;
                case "permission": Context.denied="scan"; rejected(); break;
                case "legacy_permission": Build.VERSION.SDK_INT=26; Context.denied="location"; rejected(); break;
                case "notifications": NotificationManager.enabled=false; rejected(); break;
                case "notification_dedupe": notificationDedupe(); break;
                case "wake_late_prepare": wakeLatePrepare(); break;
                case "wake_prepare_cancel": wakePrepareCancel(); break;
                case "channel_blocked": NotificationManager.importance=0; rejected(); break;
                case "phone_busy": CaptureService.active=true; rejected(); break;
                case "address": SharedPreferences.values.put("address","bad"); rejected(); break;
                case "no_microphone_permission":
                    Context.denied="record"; ready(); OmiBle.pcm(pcm(100,1)); stop(); finished();
                    check(!Context.checked.contains("record"),"no microphone permission queried"); break;
                case "prepare_cancel":
                    PreviewModelInstaller.hold=true; start(); await(PreviewModelInstaller.entered); stop(); finished();
                    check(OmiBle.connects==0 && PreviewModel.created==0 && Recordings.created==0,"cancelled preparation never connects"); break;
                case "native_cancel":
                    PreviewModel.gate=new CountDownLatch(1);start();await(PreviewModel.entered);stop();
                    check(OmiCaptureService.active,"warm model still owns lifecycle");release(PreviewModel.gate);finished();
                    check(OmiBle.connects==0 && PreviewRecognizer.created==0 && PreviewModel.closed==1,"cancelled native warmup never connects");break;
                case "archive_bookmark": archiveBookmark(); break;
                case "button_mapping": buttonMapping(); break;
                case "gap": gap(); break;
                case "initial_retry": initialRetry(); break;
                case "stop_recovery": stopRecovery(); break;
                case "terminal_after_audio": terminalAfterAudio(); break;
                case "recovery_long_wait": recoveryLongWait(false, false); break;
                case "recovery_long_stop": recoveryLongWait(true, false); break;
                case "recovery_permission_loss": recoveryLongWait(false, true); break;
                case "startup_timeout":
                    ready();Thread.sleep(30);android.os.SystemClock.offset=120001;finished();
                    check(OmiCaptureService.state.contains("startup timed out") && Recordings.total()==0,"startup retries remain bounded without PCM");break;
                case "repeated_gap": repeatedGap(); break;
                case "gap_speech_failure": gapSpeechFailure(); break;
                case "segment_drain": segmentDrain(); break;
                case "segment_final_stall": segmentSpeechStall("final"); break;
                case "segment_accept_stall": segmentSpeechStall("accept"); break;
                case "segment_overload_stall": segmentSpeechStall("overload"); break;
                case "speech_control_saturation": speechControlSaturation(); break;
                case "segment_warning_failure": segmentWarningFailure(); break;
                case "segment_no_empty": segmentNoEmpty(); break;
                case "segment_bookmark_recovery": segmentBookmarkRecovery(); break;
                case "segment_finish_failure": segmentFailure("finish"); break;
                case "segment_create_failure": segmentFailure("create"); break;
                case "segment_marker_failure": segmentFailure("marker"); break;
                case "overflow": capacityRecovery("bytes"); break;
                case "capacity_events": capacityRecovery("events"); break;
                case "capacity_stop": capacityRecovery("stop"); break;
                case "capacity_controls": capacityRecovery("controls"); break;
                case "capacity_full_gap": capacityRecovery("gap"); break;
                case "capacity_near_gap": capacityGapOverlap("near"); break;
                case "capacity_gap_controls": capacityGapOverlap("controls"); break;
                case "capacity_inflight_gap": capacityGapOverlap("inflight"); break;
                case "capacity_repeat": capacityRecovery("repeat"); break;
                case "capacity_invalid": capacityRecovery("invalid"); break;
                case "capacity_storage": capacityRecovery("storage"); break;
                case "queue_rejections": queueRejections(); break;
                case "queue_prefix": queuePrefix(); break;
                case "queue_recovery": queueRecovery(); break;
                case "rejection_bytes": rejectionPostmortem("bytes"); break;
                case "rejection_events": rejectionPostmortem("events"); break;
                case "rejection_null": rejectionPostmortem("null"); break;
                case "rejection_empty": rejectionPostmortem("empty"); break;
                case "rejection_oversize": rejectionPostmortem("oversize"); break;
                case "consumer_timing": consumerTiming(); break;
                case "slow_asr": slowAsr(); break;
                case "streaming_partial": streamingPartial(); break;
                case "partial_cancel": partialCancel(); break;
                case "gap_partial": gapPartial(); break;
                case "retired_partial": retiredPartial(); break;
                case "start_failure": Service.failForeground=true;start();
                    check(!OmiCaptureService.active && PreviewModel.created==0,"failed foreground allocates no native owner");
                    check(RefinementJobService.pauses==1 && RefinementJobService.schedules==1,"failed start resumes refinement");break;
                case "stop_accept_timeout": stopTimeout(false); break;
                case "stop_final_timeout": stopTimeout(true); break;
                case "small_packets_10ms": smallPackets(160, 10); break;
                case "small_packets_20ms": smallPackets(320, 20); break;
                case "native_failure": nativeFailure(); break;
                case "storage_failure": storageFailure(); break;
                case "text_failure": textFailure(false); break;
                case "late_text_failure": textFailure(true); break;
                case "active_until_saved": activeUntilSaved(); break;
                case "initial_failure":
                    OmiBle.initialFailure=true;start();finished();
                    check(Recordings.audio.isEmpty() && "error".equals(Recordings.status),"startup failure is not a recording");
                    check(OmiBle.stops==1 && OmiBle.exited==1,"no connection retry after failure");break;
                default: throw new AssertionError("unknown scenario");
            }
        } finally {
            Recordings.audioDelayMs=0; PreviewModelInstaller.hold=false; release(PreviewModel.gate); release(PreviewRecognizer.acceptGate); release(PreviewRecognizer.finalGate);
            release(Recordings.textGate);release(PreviewRecognizer.partialGate); release(Recordings.audioGate); release(Recordings.finishGate); release(OmiBle.stopGate);
            OmiCaptureService.requestStopCapture(); joinWorker(); Handler.drain();
            if (failure.get()!=null) throw new AssertionError("background failure",failure.get());
            check(PowerManager.held==0,"wake lock released");
            check(PreviewModel.created==PreviewModel.closed && PreviewRecognizer.created==PreviewRecognizer.closed,"native owners closed exactly once");
            check(RefinementJobService.pauses==RefinementJobService.schedules,"every capture owner resumes refinement after teardown");
            check(RefinementJobService.pauses==(Service.foregroundStarts>0||Service.failForeground?1:0),"exactly one hook pair per owner, none for rejected starts");
            check(OmiBle.connects==OmiBle.exited,"BLE thread exited before idle");
            check(!OmiCaptureService.active,"inactive after cleanup");
            for (Thread t:Thread.getAllStackTraces().keySet())
                check(!t.isAlive() || (!t.getName().equals("omi-offline-speech") && !t.getName().equals("omi-capture") && !t.getName().equals("fake-OmiBle")),"no capture worker leak");
        }
        System.out.println("OmiCaptureServiceHostTest PASS "+args[0]+": "+assertions+" assertions");
    }
    static void rejected() {
        start();check(!OmiCaptureService.active && Service.foregroundStarts==0,"rejection before foreground");
        check(OmiBle.connects==0 && Recordings.created==0 && PreviewModel.created==0,"rejection allocates nothing");
    }
    static void ready() throws Exception {
        start();until(() -> OmiBle.connects==1 && OmiCaptureService.transport.startsWith("Connecting"));
        check(OmiCaptureService.active && OmiCaptureService.startedAt==0,"preparation active but no recording clock");
        check(!OmiCaptureService.state.startsWith("Recording"),"no recording claim before actual PCM");
        OmiBle.button(1); OmiBle.button(2); check(Recordings.text.isEmpty(),"buttons unarmed before first PCM");
        check(OmiCaptureService.active,"pre-PCM button cannot stop");
    }
    static void wakeLatePrepare() throws Exception {
        android.os.SystemClock.fixed=1000;
        PreviewModel.gate=new CountDownLatch(1);start();await(PreviewModel.entered);
        check(PowerManager.acquires==1 && PowerManager.acquiredAt==1000 && PowerManager.expiresAt==601000,
                "initial bounded wake lease starts before model preparation");
        android.os.SystemClock.fixed=361000; // Six-minute preparation, still inside initial lease.
        check(PowerManager.instance.isHeld(),"initial lease still held when late preparation returns");
        release(PreviewModel.gate);
        until(()->OmiBle.connects==1 && OmiCaptureService.transport.startsWith("Connecting"));
        Thread.sleep(250); // Allow at least one service polling cycle before exact assertions.
        check(PowerManager.acquires==2 && PowerManager.acquiredAt==361000,
                "late preparation must renew from actual acquisition, not reset the renewal clock");
        OmiBle.pcm(pcm(100,1));until(()->Recordings.total()==200);
        // Healthy PCM at each minute preserves the real 120-second no-PCM contract.
        for(int minute=1;minute<=4;minute++) {
            android.os.SystemClock.fixed=361000+minute*60000L;
            OmiBle.pcm(pcm(100,minute+1));final int bytes=(minute+1)*200;
            until(()->Recordings.total()==bytes);
            check(PowerManager.instance.isHeld() && PowerManager.acquires==2,"no early periodic renewal or lease gap");
        }
        android.os.SystemClock.fixed=660000;OmiBle.pcm(pcm(100,6));until(()->Recordings.total()==1200);
        Thread.sleep(250);
        check(PowerManager.acquires==2,"renewal waits until exact five-minute cadence");
        android.os.SystemClock.fixed=661000;OmiBle.pcm(pcm(100,7));
        until(()->PowerManager.acquires==3);
        check(PowerManager.acquiredAt==661000 && PowerManager.expiresAt==1261000 && PowerManager.expirations==0,
                "healthy capture renews at five minutes without initial lease expiry");
        check(OmiCaptureService.active && OmiBle.stops==0,"wake renewal preserves active capture");
        stop();finished();check(Recordings.total()==1400,"renewal preserves every PCM byte");
    }
    static void wakePrepareCancel() throws Exception {
        android.os.SystemClock.fixed=1000;
        PreviewModel.gate=new CountDownLatch(1);start();await(PreviewModel.entered);
        android.os.SystemClock.fixed=361000;stop();release(PreviewModel.gate);finished();
        check(PowerManager.acquires==1 && PowerManager.held==0,"cancelled late preparation does not renew its lease");
        check(OmiBle.connects==0 && Recordings.created==0,"cancelled late preparation never connects or records");
    }
    static void notificationDedupe() throws Exception {
        ready();
        OmiBle.send(() -> OmiBle.instance.listener.onStatus("Omi invalid frame; incomplete task discarded"));
        int connectingPosts=NotificationManager.posts;
        for(int i=0;i<100;i++) OmiBle.send(() -> OmiBle.instance.listener.onStatus("Omi invalid frame; incomplete task discarded"));
        check(NotificationManager.posts==connectingPosts,"identical startup diagnostics do not repost connecting notification");
        OmiBle.pcm(pcm(100,3));
        check(NotificationManager.posts==connectingPosts+1,"first actual PCM publishes Recording: before="+connectingPosts+" after="+NotificationManager.posts+" attempts="+NotificationManager.attempts+" state="+OmiCaptureService.state);
        OmiBle.gap();
        check(NotificationManager.posts==connectingPosts+2,"audio loss publishes Recovering");
        for(int i=0;i<20;i++) OmiBle.gap();
        check(NotificationManager.posts==connectingPosts+2,"repeated gaps do not repeat recovery notification");
        OmiBle.pcm(pcm(100,4));
        check(NotificationManager.posts==connectingPosts+3,"actual resumed PCM republishes Recording");
        // Exercise the private presentation boundary without changing transport or PCM.
        java.lang.reflect.Method progress=OmiCaptureService.class.getDeclaredMethod("setProgress",String.class);
        progress.setAccessible(true);
        NotificationManager.failNext=true;
        int beforeFailure=NotificationManager.posts;
        progress.invoke(service,"Synthetic notification retry state");
        check(NotificationManager.posts==beforeFailure,"failed notification is not successful publication");
        progress.invoke(service,"Synthetic notification retry state");
        check(NotificationManager.posts==beforeFailure+1,"same text retries after failed publication");
        progress.invoke(service,"Synthetic notification retry state");
        check(NotificationManager.posts==beforeFailure+1,"successful retry then deduplicates");
        stop();finished();
        check(NotificationManager.posts>beforeFailure+1 && Service.foregroundStops==1,"Stop still updates and removes foreground notification");
        check(Recordings.total()==400 && Recordings.created==2,"notification filtering preserves exact segmented PCM");
    }
    static void archiveBookmark() throws Exception {
        ready(); short[] first=pcm(1600,11), second=pcm(1600,17);
        OmiBle.pcm(first); Arrays.fill(first,(short)0); // callback must own an independent copy
        OmiBle.button(1); OmiBle.pcm(second); OmiBle.button(2); finished();
        check(Arrays.equals(Recordings.audio.get(0),little(pcm(1600,11))),"PCM defensively copied and little-endian");
        check(Arrays.equals(Recordings.audio.get(1),little(second)),"second ordered PCM retained");
        check(Recordings.text.equals(List.of("endpoint","stop final","[Omi button bookmark]","endpoint","stop final")),"bookmark ordered between speech segments, not ASR utterance");
        check("saved".equals(Recordings.status) && PreviewRecognizer.accepted==2,"saved encrypted-before-ASR path");
        int before=Recordings.total(); OmiBle.instance.listener.onPcm(pcm(100,4));
        check(Recordings.total()==before,"late PCM ignored after finish");
    }
    static void buttonMapping() throws Exception {
        SharedPreferences.values.put("single_action","stop");SharedPreferences.values.put("double_action","bookmark");
        ready();OmiBle.pcm(pcm(100,2));
        OmiBle.button(3);OmiBle.button(4);OmiBle.button(5);
        check(OmiCaptureService.active && OmiCaptureService.lastButton.equals("No press received"),"long/power events firmware reserved");
        OmiCaptureService.readLedBrightness(); OmiCaptureService.setLedBrightness(30);
        check(OmiBle.ledReads==1 && OmiBle.ledWrites==1,"service-owned LED commands");
        OmiBle.button(2);OmiBle.button(1);finished();
        check(Recordings.text.contains("[Omi button bookmark]"),"configured double bookmark saved");
        check(OmiCaptureService.lastButton.equals("Single press — stop"),"configured single stop");
    }
    static void gap() throws Exception {
        Recordings.audioGate=new CountDownLatch(1);ready();OmiBle.pcm(pcm(100,3));await(Recordings.audioEntered);
        OmiBle.button(1);OmiBle.pcm(pcm(100,7));OmiBle.gap();OmiBle.gap();
        check(OmiCaptureService.active && OmiBle.stops==0,"recoverable gap must not cancel explicit session");
        check(OmiCaptureService.state.contains("Waiting for Omi"),"gap is visible, not falsely recording");
        OmiBle.send(() -> OmiBle.instance.listener.onStatus("Omi connection lost (Bluetooth status 8); retry in 1 seconds"));
        check(OmiBle.stops==0,"transport backoff remains armed");
        OmiBle.pcm(pcm(100,9));
        check(OmiCaptureService.state.startsWith("Recording"),"actual PCM resumes recording state");
        release(Recordings.audioGate);stop();finished();
        check(Recordings.total()==600,"pre-gap and recovered PCM retained without fabricated silence");
        check(Recordings.created==2 && Recordings.finished==2,"audio gap rotates archive; playback/export cannot silently splice PCM");
        byte[] prefix=new byte[400];System.arraycopy(little(pcm(100,3)),0,prefix,0,200);System.arraycopy(little(pcm(100,7)),0,prefix,200,200);
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),prefix),"prefix segment byte-exact");
        check(Arrays.equals(Recordings.segmentAudio("fixture-2"),little(pcm(100,9))),"resumed segment byte-exact, no prefix or fabricated silence");
        check(Recordings.text.get(2).equals("[Omi button bookmark]"),"bookmark stays in FIFO before gap");
        int gap=-1,count=0;
        for(int i=0;i<Recordings.text.size();i++)if(Recordings.text.get(i).startsWith("[Omi audio gap")){gap=i;count++;}
        check(count==1 && gap>2 && gap<Recordings.text.size()-2,"one ordered explicit gap before resumed speech");
        check("saved".equals(Recordings.status) && OmiBle.connects==1,"reuse same service/transport, no second capture owner");
        check(OmiCaptureService.state.contains("separate segments"),"saved status discloses separate recording segments");
    }
    static void initialRetry() throws Exception {
        ready();OmiBle.gap();
        OmiBle.send(() -> OmiBle.instance.listener.onStatus("Omi connection lost (Bluetooth status 133); retry in 1 seconds"));
        check(OmiCaptureService.active && OmiBle.stops==0 && OmiCaptureService.startedAt==0,"pre-PCM failure can retry without claiming recording");
        OmiBle.gap();OmiBle.pcm(pcm(100,5));stop();finished();
        check(Recordings.total()==200 && Recordings.text.stream().noneMatch(t -> t.startsWith("[Omi audio gap")),"startup reset gaps do not invent missing audio");
        check("saved".equals(Recordings.status),"initial retry can finish saved");
    }
    static void stopRecovery() throws Exception {
        ready();OmiBle.pcm(pcm(100,2));OmiBle.gap();
        check(OmiCaptureService.active && OmiBle.stops==0,"active recovery before explicit Stop");
        stop();finished();int bytes=Recordings.total();
        OmiBle.instance.listener.onPcm(pcm(100,8));
        OmiBle.instance.listener.onStatus("Omi connection lost; retry in 1 seconds");
        check(!OmiCaptureService.active && OmiBle.stops==1 && Recordings.total()==bytes,"Stop fences recovery and late callbacks");
    }
    static void terminalAfterAudio() throws Exception {
        ready();OmiBle.pcm(pcm(100,2));OmiBle.gap();
        OmiBle.send(() -> OmiBle.instance.listener.onStatus("BLE connection permission denied; capture disconnected, restart to retry"));
        finished();check("error".equals(Recordings.status) && OmiBle.stops==1,"nonrecoverable transport failures remain terminal");
    }
    static void recoveryLongWait(boolean stopWhileWaiting, boolean revokePermission) throws Exception {
        ready();OmiBle.pcm(pcm(100,2));OmiBle.gap();
        OmiBle.send(() -> OmiBle.instance.listener.onStatus("Bluetooth linked · discovering Omi audio service"));
        until(()->Recordings.total()==200);
        android.os.SystemClock.offset=120001;Thread.sleep(450);
        check(OmiCaptureService.active && OmiBle.stops==0,"established session survives the former 120-second terminal boundary");
        check(OmiCaptureService.state.startsWith("Waiting for Omi") && OmiCaptureService.level==0 && OmiCaptureService.partial.isEmpty(),"waiting never claims live audio or stale preview");
        int posts=NotificationManager.posts;
        for(int i=0;i<10;i++)OmiBle.gap();
        android.os.SystemClock.offset=720001;Thread.sleep(450);
        check(OmiCaptureService.active && PowerManager.held==1 && PowerManager.acquires>=2,"long wait renews bounded CPU lease without abandoning session");
        check(Recordings.created==1 && Recordings.total()==200 && NotificationManager.posts==posts,"retry traffic creates no empty archives, fake audio or notification churn");
        if(revokePermission){
            android.content.Context.denied="connect";
            android.os.SystemClock.offset+=1001;finished();
            check("error".equals(Recordings.status) && OmiCaptureService.state.contains("access was removed"),"revoked permission remains terminal while waiting");
        } else if(stopWhileWaiting){
            stop();finished();
            OmiBle.instance.listener.onPcm(pcm(100,8));
            check(Recordings.total()==200 && Recordings.created==1,"Stop while waiting fences late PCM and preserves prefix only");
        } else {
            OmiBle.pcm(pcm(100,8));until(()->Recordings.total()==400);
            check(OmiCaptureService.state.startsWith("Recording"),"real PCM resumes after twelve minutes unavailable");
            stop();finished();
            check(Recordings.created==2 && Recordings.finished==2,"long recovery creates exactly one separate continuation");
            check(Arrays.equals(Recordings.segmentAudio("fixture-2"),little(pcm(100,8))),"continuation contains exact resumed PCM only");
        }
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),little(pcm(100,2))),"long wait retains byte-exact prefix");
        check(OmiBle.stops==1,"one transport Stop after explicit/fatal terminal event");
    }
    static void repeatedGap() throws Exception {
        ready();OmiBle.pcm(pcm(100,1));OmiBle.gap();OmiBle.pcm(pcm(100,2));
        OmiBle.gap();OmiBle.gap();OmiBle.pcm(pcm(100,3));stop();finished();
        check(Recordings.text.stream().filter(t -> t.startsWith("[Omi audio gap")).count()==2,"separate interruptions each marked, repeated callbacks coalesced");
        check(PreviewRecognizer.resets==2,"speech recognizer reset at each distinct gap, not spliced across missing audio");
        check(Recordings.total()==600,"all three audio prefixes retained");
        check(Recordings.created==3 && Recordings.finished==3,"one segment per contiguous PCM run, not per reset callback");
        for(int i=1;i<=3;i++)check(Arrays.equals(Recordings.segmentAudio("fixture-"+i),little(pcm(100,i))),"each successive interruption keeps an independent exact PCM range");
        check(PreviewRecognizer.created==1,"safe segment rollover reuses the native recognizer");
    }
    static void segmentDrain() throws Exception {
        PreviewRecognizer.finalGate=new CountDownLatch(1);ready();OmiBle.pcm(pcm(100,2));OmiBle.gap();
        await(PreviewRecognizer.finalEntered);OmiBle.pcm(pcm(100,4));stop();Thread.sleep(60);
        check(Recordings.created==1 && Recordings.finished==0 && OmiCaptureService.active,"rollover cannot finish archive while old ASR is writing");
        release(PreviewRecognizer.finalGate);finished();
        check(Recordings.created==2 && Recordings.total()==400,"Stop drains previously authorized resumed PCM into distinct segment");
        check(OmiBle.connects==1 && OmiBle.stops==1,"rollover never starts new radio/capture authorization");
        int before=Recordings.total();OmiBle.instance.listener.onPcm(pcm(100,7));
        check(Recordings.total()==before,"late PCM cannot create another segment after Stop");
    }
    static void incompleteForEachSegment() {
        for(int segment=1;segment<=Recordings.created;segment++) {
            String id="fixture-"+segment;int warnings=0;
            for(int i=0;i<Recordings.text.size();i++)if(id.equals(Recordings.textIds.get(i)) && Recordings.text.get(i).equals("[Offline transcript incomplete — encrypted audio retained]"))warnings++;
            check(warnings==1,"each audio-only segment exports its own single incomplete warning: "+id);
            check("audio_only".equals(Recordings.statuses.get(id)),"each incomplete segment finalized audio_only: "+id);
        }
    }
    static void segmentSpeechStall(String mode) throws Exception {
        if(mode.equals("final"))PreviewRecognizer.finalGate=new CountDownLatch(1);
        else PreviewRecognizer.acceptGate=new CountDownLatch(1);
        ready();OmiBle.pcm(pcm(1920,2));await(PreviewRecognizer.acceptEntered);
        if(mode.equals("overload")) {
            archiveBacklog(254);
            until(()->OmiCaptureService.state.contains("speech processing fell behind"));
            check(OmiCaptureService.state.contains("speech processing fell behind"),"speech overloaded before rollover");
        }
        int prefix=Recordings.total();OmiBle.button(1);
        if(mode.equals("final"))await(PreviewRecognizer.finalEntered);
        OmiBle.gap();OmiBle.button(1);
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        for(int i=0;i<24;i++) {
            short[] frame=pcm(1920,40+i);expected.write(little(frame));OmiBle.pcm(frame);Thread.sleep(30);
        }
        check(OmiCaptureService.active && OmiBle.stops==0,"stalled native ASR cannot cancel continued archive capture");
        until(()->Recordings.total()==prefix+expected.size());
        check(expected.size()>OmiPcmQueue.MAX_BYTES,"resumed stream exceeds incoming capacity while native call remains blocked");
        check(Recordings.created==2 && Recordings.finished==1,"archive rolls over without waiting for native return");
        check(Arrays.equals(Recordings.segmentAudio("fixture-2"),expected.toByteArray()),"all resumed bytes durably retained independently of ASR");
        check(PreviewRecognizer.closed==0,"native owner not closed while its call is blocked");
        check(PreviewModel.cancelled==1,"retirement fences blocked preview output without closing its owner");
        if(mode.equals("accept")) {
            String state=OmiCaptureService.state;PreviewRecognizer.fail=true;release(PreviewRecognizer.acceptGate);
            until(()->PreviewModel.closed==1);
            check(OmiCaptureService.active && OmiCaptureService.state.equals(state),"late retired native failure cannot rewrite live continuation state");
        }
        stop();check(OmiBle.stops==1,"Stop immediately invalidates transport despite native stall");
        OmiBle.instance.listener.onPcm(pcm(100,77));
        release(PreviewRecognizer.acceptGate);release(PreviewRecognizer.finalGate);finished();
        check(Recordings.total()==prefix+expected.size(),"late callbacks add no audio after Stop");
        int bookmarks=0,gaps=0;java.util.ArrayList<String> controls=new java.util.ArrayList<>();
        for(int i=0;i<Recordings.text.size();i++) {
            String text=Recordings.text.get(i),id=Recordings.textIds.get(i);
            if(text.equals("[Omi button bookmark]") || text.startsWith("[Omi audio gap"))controls.add(text.startsWith("[Omi audio gap") ? "gap" : "bookmark");
            if(text.equals("[Omi button bookmark]")){bookmarks++;check(id.equals("fixture-1"),"pending bookmark transferred to old segment");}
            if(text.startsWith("[Omi audio gap")){gaps++;check(id.equals("fixture-1"),"pending gap transferred to old segment");}
            if(id.equals("fixture-2"))check(!text.equals("endpoint")&&!text.equals("stop final"),"late retired speech cannot write into continuation");
        }
        check(bookmarks==2 && gaps==1,"retirement keeps all pending controls exactly once");
        check(controls.equals(Arrays.asList("bookmark","gap","bookmark")),"retired controls preserve exact FIFO order across boundary");
        check(PreviewRecognizer.created==1,"retirement does not create unbounded native replacement workers");
        incompleteForEachSegment();
    }
    static void segmentWarningFailure() throws Exception {
        PreviewRecognizer.fail=true;ready();OmiBle.pcm(pcm(100,2));
        until(()->OmiCaptureService.state.contains("offline speech failed"));
        Recordings.failTextContaining="Offline transcript incomplete";
        OmiBle.gap();OmiBle.pcm(pcm(100,4));finished();
        check(Recordings.textFailures==1,"ambiguous incomplete-warning commit never retried in final cleanup");
        check(Recordings.created==1 && Recordings.total()==200 && "error".equals(Recordings.statuses.get("fixture-1")),"warning failure is terminal without splicing resumed audio");
        check(OmiCaptureService.state.equals("Stopped — Encrypted storage unavailable or full — earlier audio retained"),"warning failure reports exact storage error");
    }
    static void speechControlSaturation() throws Exception {
        PreviewRecognizer.acceptGate=new CountDownLatch(1);ready();OmiBle.pcm(pcm(1920,2));await(PreviewRecognizer.acceptEntered);
        int count=512+4; // Speech's 512-event limit, not the independent 128-event ingress limit.
        Thread speech=Thread.getAllStackTraces().keySet().stream()
                .filter(t->t.isAlive() && t.getName().equals("omi-offline-speech")).findFirst().orElseThrow();
        Field field=speech.getClass().getDeclaredField("queue");field.setAccessible(true);
        java.util.Collection<?> queue=(java.util.Collection<?>)field.get(speech);
        for(int i=0;i<count;i++) {
            OmiBle.button(1);Thread.sleep(5); // Allow archive ingress to drain independently.
            if(i+1==512) {
                until(()->{synchronized(queue){return queue.size()==512;}});
                check(PreviewModel.cancelled==0,"all 512 speech controls fit before retirement");
                check(OmiCaptureService.active && OmiBle.stops==0,"full speech queue does not overflow ingress");
            }
        }
        OmiBle.pcm(pcm(1920,4));until(()->Recordings.total()==7680);
        until(()->PreviewModel.cancelled==1);
        check(OmiCaptureService.active && OmiBle.stops==0,"speech-control saturation retires speech without blocking archive");
        stop();release(PreviewRecognizer.acceptGate);finished();
        check(Recordings.text.stream().filter(t->t.equals("[Omi button bookmark]")).count()==count,"saturated speech controls retained exactly once");
        incompleteForEachSegment();
    }
    static void segmentNoEmpty() throws Exception {
        ready();for(int i=0;i<5;i++)OmiBle.gap();
        OmiBle.pcm(pcm(100,2));for(int i=0;i<12;i++)OmiBle.gap();
        stop();finished();check(Recordings.created==1,"no new empty segments from startup/backoff/reset or Stop");
    }
    static void segmentBookmarkRecovery() throws Exception {
        ready();OmiBle.pcm(pcm(100,2));OmiBle.gap();OmiBle.button(1);OmiBle.pcm(pcm(100,4));stop();finished();
        int i=Recordings.text.indexOf("[Omi button bookmark]");
        check(i>=0 && "fixture-1".equals(Recordings.textIds.get(i)),"bookmark during recovery belongs to preceding segment before rollover");
        check(Recordings.text.stream().anyMatch(t->t.contains("resumed")&&t.contains("segment")),"new segment discloses its continuation");
        check("fixture-2".equals(OmiCaptureService.display.snapshot().sessionId),"live display associates with resumed archive");
    }
    static void segmentFailure(String mode) throws Exception {
        ready();OmiBle.pcm(pcm(100,2));until(()->Recordings.total()==200);
        if(mode.equals("finish"))Recordings.failFinishAt=0;
        if(mode.equals("create"))Recordings.failCreateAt=1;
        if(mode.equals("marker"))Recordings.failTextContaining="resumed";
        OmiBle.gap();OmiBle.pcm(pcm(100,4));joinWorker();Handler.drain();
        check(!OmiCaptureService.active && OmiBle.stops==1,"rollover storage failure is terminal: "+mode);
        check(Recordings.total()==200 && Arrays.equals(Recordings.segmentAudio("fixture-1"),little(pcm(100,2))),"failed rollover never appends resumed PCM to preceding segment");
        String expected=mode.equals("finish") ? "Stopped — Save finalization failed — committed audio needs recovery on next launch" : "Stopped — Encrypted storage unavailable or full — earlier audio retained";
        check(OmiCaptureService.state.equals(expected),"exact storage failure, never either successful completion message: "+OmiCaptureService.state);
        if(mode.equals("finish"))check(Recordings.created==1 && Recordings.finished==0 && Recordings.statuses.isEmpty(),"failed finish not mislabeled committed");
        if(mode.equals("create"))check(Recordings.created==1 && Recordings.finished==1 && "saved".equals(Recordings.statuses.get("fixture-1")),"create failure preserves already-finished prefix without rewriting status");
        if(mode.equals("marker"))check(Recordings.created==2 && Recordings.finished==2 && "saved".equals(Recordings.statuses.get("fixture-1")) && "error".equals(Recordings.statuses.get("fixture-2")),"continuation-marker failure preserves saved prefix and marks new segment error");
    }
    static void gapSpeechFailure() throws Exception {
        PreviewRecognizer.acceptGate=new CountDownLatch(1);PreviewRecognizer.fail=true;
        ready();OmiBle.pcm(pcm(100,3));await(PreviewRecognizer.acceptEntered);OmiBle.gap();
        release(PreviewRecognizer.acceptGate);until(() -> PreviewRecognizer.failures==1);Thread.sleep(30);
        check(OmiCaptureService.state.startsWith("Waiting for Omi") && OmiCaptureService.partial.isEmpty(),"late speech failure cannot falsely revive recording during gap");
        OmiBle.pcm(pcm(100,4));
        check(OmiCaptureService.state.contains("audio only"),"recovered PCM discloses failed speech");
        stop();finished();check(Recordings.total()==400 && "audio_only".equals(Recordings.status),"audio retained across gap with failed speech");
        check(Recordings.created==2 && Recordings.finished==2,"audio-only recovery also isolates archive segments");
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),little(pcm(100,3))) && Arrays.equals(Recordings.segmentAudio("fixture-2"),little(pcm(100,4))),"speech failure cannot splice playback audio across interruption");
        incompleteForEachSegment();
    }
    static void capacityGapOverlap(String mode) throws Exception {
        boolean inflight=mode.equals("inflight"), controls=mode.equals("controls");
        Recordings.audioGate=new CountDownLatch(1);ready();
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        OmiBle.pcm(pcm(100,3));expected.write(little(pcm(100,3)));await(Recordings.audioEntered);
        int bookmarks=0;
        if(inflight) {
            release(Recordings.audioGate);until(()->Recordings.total()==200);
            Recordings.textGate=new CountDownLatch(1);
            OmiBle.gap();await(Recordings.textEntered);
            // The original gap is already consumed and its speech output is in flight.
            // Hold the archive consumer on the next accepted control's output lock.
            OmiBle.button(1);
            Field workerField=OmiCaptureService.class.getDeclaredField("worker");workerField.setAccessible(true);
            Thread archive=(Thread)workerField.get(service);
            until(()->archive.getState()==Thread.State.BLOCKED);
            for(int i=0;i<128;i++)OmiBle.button(1);
            bookmarks=129;
        } else {
            for(int i=0;i<(controls?126:127);i++) {
                short[] frame=pcm(1,10+i);OmiBle.pcm(frame);expected.write(little(frame));
            }
            OmiBle.gap();
            if(controls){OmiBle.button(1);bookmarks=1;}
        }
        OmiBle.pcm(pcm(1,999));
        check(OmiBle.stops==0 && OmiCaptureService.active && OmiCaptureService.state.contains("archive backlog"),
                "capacity after an existing gap pauses without terminal Stop");
        check(value(dumpActivity(),"queued_events")==128,"overlap fixture reaches exact event capacity");
        String rejection=dumpActivity();
        for(int i=0;i<10;i++){OmiBle.gap();OmiBle.pcm(pcm(1,999));}
        check(dumpActivity().equals(rejection),"same episode preserves original diagnostic");
        release(Recordings.audioGate);release(Recordings.textGate);
        until(()->OmiCaptureService.state.contains("archive backlog drained"));
        check(Recordings.created==1 && OmiCaptureService.waitingForOmi(),"drain creates no empty continuation");
        OmiBle.pcm(pcm(100,77));stop();finished();
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),expected.toByteArray()),"overlap keeps exact accepted prefix");
        check(Arrays.equals(Recordings.segmentAudio("fixture-2"),little(pcm(100,77))),"only newly accepted PCM resumes into separate segment");
        int gaps=0, savedBookmarks=0;
        for(String text:Recordings.text) {
            if(text.contains("recovery attempted"))gaps++;
            if(text.equals("[Omi button bookmark]")){savedBookmarks++;check(gaps==1,"accepted controls stay after original gap");}
        }
        check(gaps==1,"one missing-audio episode must not produce duplicate recovery markers: "+gaps);
        check(savedBookmarks==bookmarks && PreviewRecognizer.resets==bookmarks+1,"every accepted control retained, one gap reset");
        check(Recordings.created==2 && "saved".equals(Recordings.status),"overlap is nonterminal with exactly two PCM segments");
    }
    static void capacityRecovery(String mode) throws Exception {
        check(OmiPcmQueue.MAX_BYTES==64000 && OmiPcmQueue.MAX_EVENTS==128 && OmiPcmQueue.MAX_BATCH_BYTES==6400,
                "capacity recovery does not enlarge any queue or commit bound");
        Recordings.audioGate=new CountDownLatch(1);ready();
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        OmiBle.pcm(pcm(100,3));expected.write(little(pcm(100,3)));await(Recordings.audioEntered);
        boolean events=mode.equals("events") || mode.equals("gap") || mode.equals("controls");
        // Include accepted controls both before and at the end of a full FIFO.
        int frames=events?126:100, samples=events?1:320;
        OmiBle.button(1);
        for(int i=0;i<frames;i++) { short[] frame=pcm(samples,10+i);OmiBle.pcm(frame);expected.write(little(frame)); }
        OmiBle.button(1);
        if(mode.equals("gap")) OmiBle.gap(); else OmiBle.pcm(pcm(samples,999));
        check(OmiCaptureService.active && OmiBle.stops==0,"capacity recovery must keep capture and its BLE transport alive");
        check(OmiCaptureService.state.contains("archive backlog") && !OmiCaptureService.state.startsWith("Recording"),
                "archive backlog is visible, distinct from radio reconnecting");
        check(OmiCaptureService.waitingForOmi() && OmiCaptureService.level==0 && OmiCaptureService.partial.isEmpty(),
                "paused ingress cannot publish live level or preview");
        String snapshot=dumpActivity();int posts=NotificationManager.posts;
        for(int i=0;i<40;i++) { OmiBle.pcm(pcm(1,900+i));OmiBle.gap(); }
        OmiBle.send(()->OmiBle.instance.listener.onStatus("Omi connected: receiving audio · local transcription active"));
        check(!OmiCaptureService.transport.contains("receiving audio") && !OmiCaptureService.transport.contains("PCM arriving"),
                "radio status cannot claim accepted live audio while archive ingress is paused");
        check(dumpActivity().equals(snapshot) && NotificationManager.posts==posts,
                "blocked PCM and repeated gaps coalesce without overwriting original cause or notification churn");
        if(!mode.equals("gap")) {
            numericOnly(snapshot);
            check(value(snapshot,"rejection_reason")== (events?5:6),"original capacity cause retained");
        }
        if(mode.equals("controls")) {
            OmiBle.button(1);
            check(OmiCaptureService.lastButton.contains("not saved") && OmiBle.stops==0,
                    "control behind reserved fence visibly rejected, not reordered or made terminal");
        }
        if(mode.equals("invalid")) {
            OmiBle.pcm(new short[0]);check(OmiBle.stops==1,"invalid input remains terminal even during backlog");
        }
        if(mode.equals("stop")) {
            // Use the actual Stop entry point, not the fixture's Disconnect shorthand.
            OmiCaptureService.requestStopCapture();check(OmiBle.stops==1,"explicit Stop fences radio while backlog is blocked");
            OmiBle.instance.listener.onPcm(pcm(100,999));
        }
        if(mode.equals("storage")) Recordings.failAudioAt=1;
        release(Recordings.audioGate);
        if(mode.equals("stop") || mode.equals("invalid") || mode.equals("storage")) {
            finished();
            if(mode.equals("storage")) check(Recordings.total()==200 && Recordings.audioAttempts==2 && "error".equals(Recordings.status),
                    "storage failure remains terminal, failed append never retried");
            else {
                check(Arrays.equals(Recordings.segmentAudio("fixture-1"),expected.toByteArray()),"Stop/error drains exact accepted prefix");
                check(Recordings.created==1,"Stop behind recovery fence creates no empty continuation");
                check("error".equals(Recordings.status)==mode.equals("invalid"),"only malformed input is terminal error");
                check(Recordings.text.stream().anyMatch(t->t.contains("recovery attempted")),"reserved gap survives terminal Stop");
            }
            return;
        }
        until(()->Recordings.total()==expected.size() && OmiCaptureService.state.startsWith("Waiting for Omi"));
        check(OmiCaptureService.active && OmiBle.stops==0 && Recordings.created==1,"drained backlog waits for real PCM, no empty segment or reconnect");
        OmiBle.pcm(pcm(100,77));until(()->Recordings.created==2 && Recordings.total()==expected.size()+200);
        check(OmiCaptureService.state.startsWith("Recording"),"accepted resumed PCM automatically restores recording state");
        if(mode.equals("repeat")) {
            Recordings.audioGate=new CountDownLatch(1);Recordings.audioEntered=new CountDownLatch(1);
            OmiBle.pcm(pcm(100,78));await(Recordings.audioEntered);
            java.io.ByteArrayOutputStream second=new java.io.ByteArrayOutputStream();
            second.write(little(pcm(100,77)));second.write(little(pcm(100,78)));
            for(int i=0;i<100;i++){short[] frame=pcm(320,200+i);OmiBle.pcm(frame);second.write(little(frame));}
            OmiBle.pcm(pcm(320,999));OmiBle.gap();
            check(OmiBle.stops==0 && OmiCaptureService.state.contains("archive backlog"),"second overload receives a new bounded fence");
            release(Recordings.audioGate);
            until(()->Recordings.total()==expected.size()+second.size() && OmiCaptureService.state.startsWith("Waiting for Omi"));
            OmiBle.pcm(pcm(100,88));until(()->Recordings.created==3);
            stop();finished();
            check(Arrays.equals(Recordings.segmentAudio("fixture-2"),second.toByteArray()),"second prefix exact without unmarked gap");
            check(Arrays.equals(Recordings.segmentAudio("fixture-3"),little(pcm(100,88))),"second continuation isolated");
        } else {
            stop();finished();
            check(Arrays.equals(Recordings.segmentAudio("fixture-2"),little(pcm(100,77))),"continuation contains only real post-fence audio");
        }
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),expected.toByteArray()),"every accepted prefix byte preserved exactly once in order");
        int bookmarks=0,gaps=0;
        for(int i=0;i<Recordings.text.size();i++) {
            String text=Recordings.text.get(i);
            if(text.equals("[Omi button bookmark]")) {bookmarks++;check(gaps==0,"all accepted controls precede reserved boundary");}
            if(text.contains("recovery attempted"))gaps++;
        }
        check(bookmarks==2 && gaps==(mode.equals("repeat")?2:1),"controls retained and one gap per distinct overload episode");
        check(PreviewRecognizer.resets==bookmarks+gaps,"speech reset follows each accepted boundary");
        check("saved".equals(Recordings.status),"capacity loss disclosed without fatal storage/capture error");
    }
    static void queueRecovery() throws Exception {
        for(boolean bytes:new boolean[]{false,true}) for(boolean stop:new boolean[]{false,true}) {
            OmiPcmQueue q=new OmiPcmQueue();
            java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
            int frames=bytes?100:128, samples=bytes?320:1;
            for(int i=0;i<frames;i++) {
                short[] frame=pcm(samples,i);
                check(q.offerRecovering(frame,"gap")==0,"exact original queue limit accepted");
                expected.write(little(frame));
            }
            check(q.offerRecovering(pcm(1,999),"gap")== (bytes?6:5),"capacity failure atomically reserves recovery");
            OmiPcmQueue.Rejection cause=q.lastRejection();
            check(q.paused() && !q.bookmark("late"),"reserved fence blocks controls at full capacity");
            java.io.ByteArrayOutputStream actual=new java.io.ByteArrayOutputStream();
            OmiPcmQueue.Event fence;
            while(true) {
                OmiPcmQueue.Event event=q.pollBatch(0);
                check(event!=null,"accepted prefix then reserved fence available without timer");
                if(event.pcm==null) {fence=event;break;}
                check(event.pcm.length<=OmiPcmQueue.MAX_BATCH_BYTES,"batch size not enlarged by recovery");
                actual.write(event.pcm);
                check(q.offerRecovering(pcm(1,999),"gap")==7 && !q.bookmark("late"),
                        "freeing some capacity never admits PCM/controls ahead of pending fence");
                check(q.recover("duplicate") && q.lastRejection()==cause,"repeated gaps coalesce without replacing cause");
            }
            check(Arrays.equals(expected.toByteArray(),actual.toByteArray()) && "gap".equals(fence.marker)
                    && !fence.terminal,"exact accepted prefix followed by one nonterminal fence");
            check(q.paused() && q.offerRecovering(pcm(1,999),"gap")==7 && q.pollBatch(0)==null,
                    "dequeued fence still blocks ingress until consumer acknowledges it");
            check(!q.completeRecovery(new OmiPcmQueue.Event(null,"gap",false)),"unrelated event cannot reopen ingress");
            if(stop) q.close("stop");
            check(q.completeRecovery(fence) && !q.completeRecovery(fence),"recovery acknowledgement is single-shot");
            if(stop) {
                check(q.offerRecovering(pcm(1,7),"gap")==1 && !q.bookmark("late"),"Stop remains closed after recovery acknowledgement");
                OmiPcmQueue.Event terminal=q.pollBatch(0);
                check(terminal.terminal && "stop".equals(terminal.marker),"Stop follows the prefix and reserved recovery fence");
            } else {
                check(q.offerRecovering(pcm(1,7),"gap")==0,"acknowledgement automatically admits the next real PCM");
                check(Arrays.equals(q.pollBatch(0).pcm,little(pcm(1,7))),"no blocked PCM silently retained for later replay");
                check(q.lastRejection()==cause,"successful resume retains original diagnostic");
            }
            check(q.pollBatch(0)==null,"no duplicate fence or unbounded recovery/control queue");
            q.clear();
        }
    }
    static void rejection(OmiPcmQueue queue, short[] input, int reason, int bytes, int events, boolean closed) {
        check(!queue.offer(input), "offer rejected");
        OmiPcmQueue.Rejection r=queue.lastRejection();
        check(r!=null && r.reason==reason && r.queuedBytes==bytes && r.queuedEvents==events
                && r.inputSamples==(input==null?-1:input.length) && r.closed==closed,
                "atomic rejection captures exact branch and pre-clear numeric occupancy");
        numericOnly(r.diagnostics());
        check(r.stopReason().contains("queue overflow")==(reason==5 || reason==6),
                "only capacity saturation is called overflow");
    }
    static void queueRejections() throws Exception {
        check(OmiPcmQueue.MAX_BYTES==64000 && OmiPcmQueue.MAX_EVENTS==128 && OmiPcmQueue.MAX_BATCH_BYTES==6400,
                "diagnostics do not alter any queue bound");
        OmiPcmQueue q=new OmiPcmQueue();
        check(q.lastRejection()==null,"new queue has no failed offer");
        check(q.offer(pcm(1,7)) && q.bookmark("private marker"),"nonempty queue for invalid-input snapshots");
        rejection(q,null,2,2,2,false);
        rejection(q,new short[0],3,2,2,false);
        rejection(q,new short[1921],4,2,2,false);
        OmiPcmQueue.Rejection invalid=q.lastRejection();
        check(q.offer(pcm(1,11)) && q.lastRejection()==invalid,"accepted offers do not erase last rejection");
        q.close("private terminal");
        rejection(q,null,1,4,3,true); // Closed takes precedence even over malformed PCM.
        OmiPcmQueue.Rejection saved=q.lastRejection();
        q.clear();
        check(q.lastRejection()==saved,"clear preserves exact immutable rejection, not zeroed occupancy");
        rejection(q,pcm(1,9),1,0,0,true);
        check(saved.queuedBytes==4 && saved.queuedEvents==3,"later rejection cannot mutate prior snapshot");

        q=new OmiPcmQueue();
        for(int i=0;i<16;i++)check(q.offer(pcm(1920,i)),"valid decoder maximum fits");
        check(q.offer(pcm(1280,33)),"exact byte limit accepted");
        rejection(q,pcm(1,4),6,OmiPcmQueue.MAX_BYTES,17,false);
        saved=q.lastRejection();q.clear();
        check(q.lastRejection()==saved && saved.queuedBytes==64000,"byte-limit evidence survives scrub");

        q=new OmiPcmQueue();
        for(int i=0;i<OmiPcmQueue.MAX_EVENTS;i++)check(q.offer(pcm(1,i)),"exact event limit accepted");
        rejection(q,pcm(1,4),5,OmiPcmQueue.MAX_EVENTS*2,OmiPcmQueue.MAX_EVENTS,false);
        // Input validation still precedes saturation; no label guesses from length later.
        rejection(q,new short[1921],4,OmiPcmQueue.MAX_EVENTS*2,OmiPcmQueue.MAX_EVENTS,false);
        q.clear();
        q=new OmiPcmQueue();
        for(int i=0;i<OmiPcmQueue.MAX_EVENTS;i++)check(q.offer(pcm(250,i)),"both exact bounds accepted");
        rejection(q,pcm(1,4),5,OmiPcmQueue.MAX_BYTES,OmiPcmQueue.MAX_EVENTS,false);
        q.clear();
    }
    static void queuePrefix() throws Exception {
        OmiPcmQueue q=new OmiPcmQueue();
        java.util.ArrayList<byte[]> prefixes=new java.util.ArrayList<>();
        java.util.ArrayList<String> markers=new java.util.ArrayList<>();
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        int bytes=0;
        for(int i=0;i<OmiPcmQueue.MAX_EVENTS;i++) {
            if(i%7==6) {
                String marker="private-control-"+i;
                check(q.bookmark(marker),"control shares bounded FIFO");
                markers.add(marker);prefixes.add(expected.toByteArray());expected.reset();
            } else {
                short[] frame=pcm(17,i);byte[] owned=little(frame);
                check(q.offer(frame),"accepted prefix PCM");expected.write(owned);bytes+=owned.length;
                Arrays.fill(frame,(short)0);
            }
        }
        prefixes.add(expected.toByteArray());
        rejection(q,pcm(17,999),5,bytes,OmiPcmQueue.MAX_EVENTS,false);
        OmiPcmQueue.Rejection saved=q.lastRejection();
        check(!q.bookmark("rejected-control"),"full event queue still rejects extra control");
        q.close("terminal");
        java.io.ByteArrayOutputStream actual=new java.io.ByteArrayOutputStream();int boundary=0;
        while(true) {
            OmiPcmQueue.Event event=q.pollBatch(0);
            check(event!=null,"terminal cannot be lost by rejection diagnostics");
            if(event.pcm!=null) {
                check(event.pcm.length<=OmiPcmQueue.MAX_BATCH_BYTES,"original batch bound");
                actual.write(event.pcm);
            } else {
                check(Arrays.equals(actual.toByteArray(),prefixes.get(boundary)),"byte-exact accepted prefix before every boundary");
                actual.reset();
                if(event.terminal) {check(boundary==markers.size() && event.marker.equals("terminal"),"terminal follows every accepted event");break;}
                check(event.marker.equals(markers.get(boundary++)),"exact FIFO control order");
            }
        }
        check(q.pollBatch(0)==null,"rejected input never splices into drained prefix");q.clear();
        check(q.lastRejection()==saved,"drain and clear retain rejection");
    }
    static String dumpService() {
        java.io.StringWriter text=new java.io.StringWriter();
        service.dump(null,new java.io.PrintWriter(text),new String[]{"private argument"});return text.toString();
    }
    static String dumpActivity() {
        java.io.StringWriter text=new java.io.StringWriter();
        new MainActivity().dump("private prefix",null,new java.io.PrintWriter(text),new String[]{"private argument"});
        return text.toString().trim();
    }
    static String liveConsumer() {
        return dumpService().lines().filter(s->s.startsWith("consumer_approximate=")).findFirst().orElseThrow();
    }
    static long value(String line,String name) {
        for(String token:line.split(" "))if(token.startsWith(name+"="))return Long.parseLong(token.substring(name.length()+1));
        throw new AssertionError("missing numeric diagnostic: "+name);
    }
    static void numericOnly(String line) {
        check(line.matches("[a-z_]+=-?[0-9]+(?: [a-z_]+=-?[0-9]+)*"),"fixed labels and numeric values only");
        check(line.length()<512,"single bounded diagnostic, no payload or history");
    }
    static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static Object speechOutput() throws Exception {
        Thread speech=Thread.getAllStackTraces().keySet().stream()
                .filter(t->t.isAlive() && t.getName().equals("omi-offline-speech")).findFirst().orElseThrow();
        return field(speech,"output");
    }
    static void rejectionPostmortem(String kind) throws Exception {
        android.os.SystemClock.fixed=1000;
        check(dumpActivity().equals("omi_queue_rejection=0"),"fresh process has explicit absent postmortem");
        Recordings.audioGate=new CountDownLatch(1);ready();
        short[] first=pcm(1920,3);OmiBle.pcm(first);await(Recordings.audioEntered);
        OmiPcmQueue incoming=(OmiPcmQueue)field(service,"incoming");
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();expected.write(little(first));
        int frames=kind.equals("bytes")?16:kind.equals("events")?128:2;
        int samples=kind.equals("bytes")?1920:kind.equals("events")?1:100;
        int reason=kind.equals("bytes")?6:kind.equals("events")?5:kind.equals("null")?2:kind.equals("empty")?3:4;
        short[] rejected=reason==2?null:reason==3?new short[0]:reason==4?new short[1921]:pcm(samples,999);
        String snapshot;
        // BLE's different thread must return while both archive append and the speech
        // output lock are unavailable. Diagnostics cannot acquire either one.
        synchronized(speechOutput()) {
            for(int i=0;i<frames;i++) {
                short[] frame=pcm(samples,10+i);OmiBle.pcm(frame);expected.write(little(frame));
            }
            android.os.SystemClock.fixed=1250;
            OmiBle.pcm(rejected);
            check(Recordings.audioGate.getCount()==1 && Recordings.audio.isEmpty(),"rejection captured while writer is still blocked");
            check(OmiCaptureService.active && OmiBle.stops== (reason>=5?0:1),
                    "only malformed PCM fences BLE; capacity keeps the archive and transport owner alive");
            snapshot=dumpActivity();numericOnly(snapshot);
            check(value(snapshot,"omi_queue_rejection")==1 && value(snapshot,"rejection_reason")==reason
                    && value(snapshot,"queued_bytes")==frames*samples*2 && value(snapshot,"queued_events")==frames
                    && value(snapshot,"input_samples")== (rejected==null?-1:rejected.length) && value(snapshot,"queue_closed")==0,
                    "exact rejection, not post-Stop closed/cleared queue state");
            check(value(snapshot,"consumer_phase")==3 && value(snapshot,"consumer_phase_age_ms")==250
                    && value(snapshot,"append_recent_ms")==-1 && value(snapshot,"append_max_ms")==-1,
                    "blocked first append captured without inventing completed duration");
            check(dumpService().contains(snapshot),"service and Activity expose identical retained snapshot");
            OmiBle.instance.listener.onPcm(pcm(1,88));
            check(dumpActivity().equals(snapshot),"paused or fenced callback cannot overwrite failed-offer evidence");
        }
        OmiPcmQueue.Rejection saved=incoming.lastRejection();
        if(reason>=5) stop(); // Capacity itself is no longer a terminal event.
        android.os.SystemClock.fixed=1400;release(Recordings.audioGate);finished();
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),expected.toByteArray()),"all and only accepted-prefix bytes saved once, in exact order");
        check("error".equals(Recordings.status)== (reason<5)
                && Recordings.text.stream().anyMatch(t->t.startsWith("[Omi audio gap")),
                "malformed PCM stays terminal; capacity prefix is saved with ordered gap");
        check(!OmiCaptureService.state.contains("queue overflow"),"capacity is recoverable and invalid PCM is not mislabeled overflow");
        check(incoming.lastRejection()==saved && dumpActivity().equals(snapshot),"queue scrub and teardown cannot erase/recompute postmortem");
        check(value(liveConsumer(),"consumer_phase")==13 && value(liveConsumer(),"append_max_ms")==400,
                "completed metrics update independently from frozen rejection snapshot");
        check(field(service,"owner")==null,"retained diagnostics do not retain lifecycle ownership");
        service.onDestroy();service=new OmiCaptureService();service.onCreate();
        check(dumpActivity().equals(snapshot) && dumpService().contains(snapshot),"new service and activity can read prior process-local postmortem");
    }
    static void consumerTiming() throws Exception {
        android.os.SystemClock.fixed=1000;ready();
        until(()->value(liveConsumer(),"consumer_phase")==2);
        numericOnly(liveConsumer());
        for(int i=0;i<3;i++) {
            Recordings.audioGate=new CountDownLatch(1);Recordings.audioEntered=new CountDownLatch(1);
            if(i==2)Recordings.failAudioAt=2;
            OmiBle.pcm(pcm(100,40+i));await(Recordings.audioEntered);
            long duration=i==0?41:i==1?17:9;
            android.os.SystemClock.fixed+=duration;
            String blocked=liveConsumer();
            check(value(blocked,"consumer_phase")==3 && value(blocked,"consumer_phase_age_ms")==duration,"phase age describes in-flight append");
            check(value(blocked,"append_recent_ms")== (i==0?-1:i==1?41:17),"recent measures only completed append attempts");
            release(Recordings.audioGate);
            final long expected=duration;
            until(()->value(liveConsumer(),"append_recent_ms")==expected);
            check(value(liveConsumer(),"append_max_ms")==41,"maximum retains earlier slower append");
        }
        finished();
        check(Recordings.total()==400 && "error".equals(Recordings.status),"timing failed append neither retries nor changes storage policy");
        check(dumpActivity().equals("omi_queue_rejection=0"),"non-queue errors do not manufacture a rejection");
    }
    static void slowAsr() throws Exception {
        PreviewRecognizer.acceptGate=new CountDownLatch(1);ready();OmiBle.pcm(pcm(1920,3));await(PreviewRecognizer.acceptEntered);
        byte[] backlog=archiveBacklog(254);
        until(()->OmiCaptureService.state.contains("speech processing fell behind"));
        check(OmiCaptureService.active && OmiCaptureService.state.contains("speech processing fell behind"),"speech overload degrades without capture stop");
        stop();check(OmiCaptureService.active,"stop waits for native call");release(PreviewRecognizer.acceptGate);finished();
        check(Recordings.audio.size()==255 && PreviewRecognizer.accepted==251,"ASR retains in-flight PCM plus exactly 960000 queued bytes while all archive PCM is retained");
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        expected.write(little(pcm(1920,3)));expected.write(backlog);
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),expected.toByteArray()),"Preview overload loses no archive bytes or ordering");
        check("audio_only".equals(Recordings.status),"incomplete ASR not mislabeled saved transcript");
    }
    static void stopTimeout(boolean finalCall) throws Exception {
        if(finalCall)PreviewRecognizer.finalGate=new CountDownLatch(1);
        else PreviewRecognizer.acceptGate=new CountDownLatch(1);
        ready();short[] bytes=pcm(1920,83);OmiBle.pcm(bytes);await(PreviewRecognizer.acceptEntered);
        if(finalCall)until(()->Recordings.text.size()==1);
        // A pending control must survive retirement of a blocked speech owner.
        OmiBle.button(1);stop();
        check(OmiBle.stops==1,"Stop immediately invalidates BLE before native drain");
        if(finalCall)await(PreviewRecognizer.finalEntered);
        Field limit=OmiCaptureService.class.getDeclaredField("ASR_STOP_DRAIN_MS");limit.setAccessible(true);
        check(limit.getLong(null)==30000L,"production native drain budget is thirty seconds");
        Field deadline=OmiCaptureService.class.getDeclaredField("stopDeadlineNanos");deadline.setAccessible(true);
        long remaining=deadline.getLong(service)-System.nanoTime();
        check(remaining>TimeUnit.SECONDS.toNanos(25) && remaining<=TimeUnit.SECONDS.toNanos(30),
                "normal Stop grants final drain before cancellation");
        check(PreviewModel.cancelled==0,"normal drain does not cancel native inference early");
        deadline.setLong(service,System.nanoTime()-1);
        until(()->PreviewModel.cancelled==1);
        check(OmiCaptureService.active && PreviewModel.closed==0 && PreviewRecognizer.closed==0,
                "cancel does not close native state or publish idle before inference exits");
        check(Recordings.finished==0 && Service.foregroundStops==0,"archive and foreground ownership retained");
        OmiBle.instance.listener.onPcm(pcm(100,77));
        release(PreviewRecognizer.acceptGate);release(PreviewRecognizer.finalGate);finished();
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),little(bytes)),"timeout and late callback lose no archived PCM");
        check(Recordings.text.stream().filter(t->t.equals("[Omi button bookmark]")).count()==1,
                "pending bookmark transfers exactly once on cancellation");
        check(Recordings.text.stream().noneMatch(t->t.equals("stop final")),"late cancelled final cannot write");
        check(Recordings.text.stream().filter(t->t.equals("endpoint")).count()==(finalCall?1:0),
                "late cancelled accept cannot write");
        check(OmiCaptureService.state.contains("drain timed out") && PreviewModel.cancelled==1,
                "timeout remains truthful and cancellation is single-shot");
        incompleteForEachSegment();
    }
    static byte[] archiveBacklog(int chunks) throws Exception {
        int initial=Recordings.audio.size();
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        for(int i=0;i<chunks;i++) {
            short[] frame=pcm(1920,i);expected.write(little(frame));OmiBle.pcm(frame);
            // Eight 3840-byte frames fit ingress; drain each batch before sending more.
            // Adjacent frames cannot coalesce past MAX_BATCH_BYTES, so ASR chunks stay exact.
            if((i+1)%8==0 || i+1==chunks) {
                final int target=initial+i+1;until(()->Recordings.audio.size()==target);
                check(OmiCaptureService.active && !OmiCaptureService.state.contains("queue overflow"),
                        "only the speech backlog may overload, never archive ingress");
            }
        }
        return expected.toByteArray();
    }
    static void streamingPartial() throws Exception {
        PreviewRecognizer.nonEndpointAccepts=2;
        ready();OmiBle.pcm(pcm(100,73));until(()->OmiCaptureService.partial.equals("preview 1"));
        check(OmiCaptureService.display.snapshot().partial.equals("preview 1") && Recordings.text.isEmpty(),
                "real non-endpoint service branch publishes before endpoint without persisting partial");
        OmiBle.pcm(pcm(100,79));until(()->OmiCaptureService.partial.equals("preview 2"));
        OmiBle.pcm(pcm(100,83));until(()->Recordings.text.size()==1 && OmiCaptureService.partial.isEmpty());
        stop();finished();
        check(PreviewRecognizer.partials==2 && PreviewRecognizer.accepted==3 && PreviewRecognizer.finals==1,
                "changing partials polled; endpoints and Stop-final still drain");
        check(Recordings.text.equals(List.of("endpoint","stop final")),"only endpoint and final JSON persisted");
        java.io.ByteArrayOutputStream expected=new java.io.ByteArrayOutputStream();
        for(int seed:new int[]{73,79,83})expected.write(little(pcm(100,seed)));
        check(Arrays.equals(Recordings.segmentAudio("fixture-1"),expected.toByteArray()),"all preview PCM archived exactly");
        check("saved".equals(Recordings.status),"streaming preview saves normally");
    }
    static void partialCancel() throws Exception {
        PreviewRecognizer.nonEndpointAccepts=2;
        ready();OmiBle.pcm(pcm(100,73));until(()->OmiCaptureService.partial.equals("preview 1"));
        PreviewRecognizer.partialGate=new CountDownLatch(1);PreviewRecognizer.partialEntered=new CountDownLatch(1);
        OmiBle.pcm(pcm(100,79));await(PreviewRecognizer.partialEntered);stop();
        check(OmiCaptureService.partial.isEmpty() && OmiCaptureService.display.snapshot().partial.isEmpty(),"Stop clears visible partial immediately");
        check(OmiCaptureService.active && PreviewRecognizer.closed==0 && RefinementJobService.schedules==0,"partial call retains owner and refinement pause");
        PreviewRecognizer.finalGate=new CountDownLatch(1);release(PreviewRecognizer.partialGate);await(PreviewRecognizer.finalEntered);
        check(PreviewRecognizer.partials==2 && OmiCaptureService.partial.isEmpty()
                && OmiCaptureService.display.snapshot().partial.isEmpty(),"late partial cannot revive cancelled display");
        release(PreviewRecognizer.finalGate);finished();
        check(Recordings.text.equals(List.of("stop final")),"normal Stop retains final JSON");
    }
    static void gapPartial() throws Exception {
        PreviewRecognizer.nonEndpointAccepts=2;
        ready();OmiBle.pcm(pcm(100,73));until(()->OmiCaptureService.partial.equals("preview 1"));
        PreviewRecognizer.partialGate=new CountDownLatch(1);PreviewRecognizer.partialEntered=new CountDownLatch(1);
        OmiBle.pcm(pcm(100,79));await(PreviewRecognizer.partialEntered);OmiBle.gap();
        check(OmiCaptureService.partial.isEmpty() && OmiCaptureService.display.snapshot().partial.isEmpty(),"gap clears live partial immediately");
        PreviewRecognizer.finalGate=new CountDownLatch(1);release(PreviewRecognizer.partialGate);await(PreviewRecognizer.finalEntered);
        check(PreviewRecognizer.partials==2 && OmiCaptureService.partial.isEmpty() && OmiCaptureService.state.startsWith("Waiting for Omi"),
                "pre-gap partial returns during recovery without reviving preview or recording state");
        release(PreviewRecognizer.finalGate);stop();finished();
    }
    static void retiredPartial() throws Exception {
        PreviewRecognizer.nonEndpointAccepts=1;PreviewRecognizer.partialGate=new CountDownLatch(1);
        ready();OmiBle.pcm(pcm(100,73));await(PreviewRecognizer.partialEntered);
        OmiBle.gap();OmiBle.pcm(pcm(100,79));until(()->Recordings.created==2 && Recordings.total()==400);
        check(PreviewModel.cancelled==1 && PreviewRecognizer.closed==0 && OmiCaptureService.active,
                "rollover Java-only cancellation never closes a blocked partial call");
        check(OmiCaptureService.sessionId.equals("fixture-2") && OmiCaptureService.partial.isEmpty(),"successor has independent empty preview");
        // A retired worker must not even clear a successor display on late return.
        OmiCaptureService.display.partial("successor display sentinel");OmiCaptureService.partial="successor display sentinel";
        String state=OmiCaptureService.state;release(PreviewRecognizer.partialGate);until(()->PreviewModel.closed==1);
        check(PreviewRecognizer.partials==1 && OmiCaptureService.partial.equals("successor display sentinel")
                && OmiCaptureService.display.snapshot().partial.equals("successor display sentinel"),"retired partial publication and cleanup are both fenced");
        check(OmiCaptureService.state.equals(state) && OmiCaptureService.sessionId.equals("fixture-2"),"late retired partial cannot rewrite successor state");
        check(RefinementJobService.schedules==0,"retired native exit alone does not resume refinement while capturing");
        stop();finished();incompleteForEachSegment();
    }

    static void smallPackets(int samples, int intervalMs) throws Exception {
        Recordings.audioDelayMs = 40;
        ready();
        java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        long next = System.nanoTime();
        for (int i = 0; i < 6000 / intervalMs; i++) {
            short[] frame = pcm(samples, i);
            expected.write(little(frame)); OmiBle.pcm(frame);
            check(OmiCaptureService.active && !OmiCaptureService.state.contains("Saving"),
                    "paced PCM must not stop capture: " + OmiCaptureService.state);
            next += TimeUnit.MILLISECONDS.toNanos(intervalMs);
            long wait = next - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
        }
        stop(); finished();
        java.io.ByteArrayOutputStream actual = new java.io.ByteArrayOutputStream();
        for (byte[] chunk : Recordings.audio) actual.write(chunk);
        check(Arrays.equals(expected.toByteArray(), actual.toByteArray()),
                "all small-packet PCM archived once, in order, including stop tail");
        check(Recordings.audio.size() <= 40, "six seconds uses bounded batched commits");
        check(PreviewRecognizer.accepted == Recordings.audio.size(), "each durable batch reaches ASR");
        check("saved".equals(Recordings.status), "paced stream ends saved, not overflow/audio-only");
    }
    static void nativeFailure() throws Exception {
        PreviewRecognizer.fail=true;ready();OmiBle.pcm(pcm(100,6));
        until(() -> OmiCaptureService.state.contains("offline speech failed"));
        check(PreviewRecognizer.failures==1 && OmiCaptureService.active,"native fault exercised, archive continues");
        OmiBle.button(1);OmiBle.pcm(pcm(100,8));stop();finished();
        check(Recordings.total()==400 && Recordings.text.contains("[Omi button bookmark]"),"audio and typed control survive native failure");
        check("audio_only".equals(Recordings.status),"native failure disclosed");
    }
    static void storageFailure() throws Exception {
        Recordings.failAudioAt=1;ready();OmiBle.pcm(pcm(100,6));until(() -> Recordings.audio.size()==1);
        OmiBle.pcm(pcm(100,8));finished();
        check(Recordings.total()==200 && PreviewRecognizer.accepted==1,"failed commit never enters ASR or retries");
        check("error".equals(Recordings.status),"quota/storage error retained");
    }
    static void textFailure(boolean late) throws Exception {
        if(late)PreviewRecognizer.finalGate=new CountDownLatch(1);else Recordings.failText=true;
        ready();OmiBle.pcm(pcm(100,6));
        if(late){until(() -> Recordings.text.size()==1);stop();await(PreviewRecognizer.finalEntered);Recordings.failNextText=true;release(PreviewRecognizer.finalGate);}
        finished();
        check(Recordings.textFailures>=1 && Recordings.total()==200,"text fault real, archive retained");
        check("error".equals(Recordings.status),"text failure explicit");
        if(late)check(Recordings.text.get(Recordings.text.size()-1).startsWith("[Omi audio gap"),"late stop-final failure still persists terminal marker");
    }
    static void activeUntilSaved() throws Exception {
        OmiBle.stopGate=new CountDownLatch(1);PreviewRecognizer.finalGate=new CountDownLatch(1);Recordings.finishGate=new CountDownLatch(1);
        ready();OmiBle.pcm(pcm(100,4));until(() -> Recordings.text.size()==1);stop();await(OmiBle.stopEntered);
        check(OmiCaptureService.active && Service.foregroundStops==0 && OmiBle.exited==0,"foreground stays until actual BLE shutdown");
        start();check(Service.foregroundStarts==1 && Recordings.created==1,"duplicate owner rejected during teardown");
        release(OmiBle.stopGate);await(PreviewRecognizer.finalEntered);
        check(OmiCaptureService.active && OmiBle.exited==1 && Recordings.finished==0,"native drain before archive finish");
        release(PreviewRecognizer.finalGate);await(Recordings.finishEntered);
        check(OmiCaptureService.active && PreviewRecognizer.closed==1 && PreviewModel.closed==1 && PowerManager.held==1,"active until storage finish");
        release(Recordings.finishGate);joinWorker();
        check(OmiCaptureService.active && PowerManager.held==0,"idle waits for main foreground teardown");
        Handler.drain();check(!OmiCaptureService.active && Service.foregroundStops==1,"foreground ends last");
    }
    static void start(){check(service.onStartCommand(new Intent().setAction(OmiCaptureService.ACTION_START),0,1)==Service.START_NOT_STICKY,"nonsticky explicit start");}
    // These archive-only tests finish with Disconnect; standby has real-BLE composition tests.
    static void stop(){OmiCaptureService.requestDisconnect();}
    static void finished() throws Exception {
        joinWorker();Handler.drain();
        if(OmiCaptureService.controlsActive){
            OmiCaptureService.requestDisconnect();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(Service.foregroundStops==0 && System.nanoTime()<end){Handler.drain();Thread.sleep(2);}
            check(Service.foregroundStops==1,"standby disconnect completes");
        }
        check(!OmiCaptureService.active,"finished inactive");check(Recordings.created==Recordings.finished,"one finish per session");
    }
    static void joinWorker() throws Exception {
        Field f=OmiCaptureService.class.getDeclaredField("worker");f.setAccessible(true);Thread t=(Thread)f.get(service);
        if(t!=null){t.join(5000);check(!t.isAlive(),"capture worker terminates");}
    }
    static void await(CountDownLatch l) throws Exception {check(l.await(3,TimeUnit.SECONDS),"boundary reached");}
    static void release(CountDownLatch l){if(l!=null)l.countDown();}
    static void until(BooleanSupplier predicate) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!predicate.getAsBoolean() && System.nanoTime()<end){if(failure.get()!=null)throw new AssertionError(failure.get());Thread.sleep(2);}
        check(predicate.getAsBoolean(),"expected condition reached");
    }
    static short[] pcm(int size,int seed){short[] out=new short[size];for(int i=0;i<size;i++)out[i]=(short)(i*31+seed);return out;}
    static byte[] little(short[] in){byte[] out=new byte[in.length*2];for(int i=0;i<in.length;i++){out[i*2]=(byte)in[i];out[i*2+1]=(byte)(in[i]>>8);}return out;}
    static void check(boolean yes,String message){assertions++;if(!yes)throw new AssertionError(message);}
}
