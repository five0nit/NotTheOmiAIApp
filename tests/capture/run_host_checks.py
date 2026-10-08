#!/usr/bin/env python3
"""Targeted host checks: real installer + mocked Android/native capture lifecycle.
Not a replacement for APK/emulator/physical microphone acceptance.
Run: python3 tests/capture/run_host_checks.py [--sdk /path/to/android-sdk] [--model /path/to/ggml-small.en-q5_1.bin]
"""
import argparse
import pathlib
import subprocess
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--sdk', type=pathlib.Path)
parser.add_argument('--service-only', action='store_true', help='run service fixtures without the installer or Android SDK')
parser.add_argument('--model', type=pathlib.Path,
                    default=ROOT / 'app/src/main/assets/ggml-small.en-q5_1.bin',
                    help='real Whisper model file (defaults to the packaged asset)')
parser.add_argument('--preview-model', type=pathlib.Path,
                    default=ROOT / 'app/src/main/assets/model.zip', help='pinned Vosk model ZIP')
args = parser.parse_args()
source = ROOT / 'app/src/main/java/app/nottheomi/ai'
if not args.service_only:
    if args.sdk:
        sdk = args.sdk
    else:
        properties = (ROOT / 'local.properties').read_text()
        sdk = pathlib.Path(next(line.split('=', 1)[1] for line in properties.splitlines()
                                if line.startswith('sdk.dir=')))
    android = sdk / 'platforms/android-34/android.jar'

    # Compile the real installer against Android's real API; no Context is invoked
    # when using the installer's package-private host-test entry point.
    with tempfile.TemporaryDirectory(prefix='hermes-verify-phone-model-') as tmp:
        directory = pathlib.Path(tmp)
        classes = directory / 'classes'
        classes.mkdir()
        subprocess.run(['javac', '--release', '17', '-cp', str(android), '-d', str(classes),
                        str(source / 'ModelInstaller.java'),
                        str(ROOT / 'tests/capture/ModelInstallerHostTest.java'),
                        str(source / 'PreviewModelInstaller.java'),
                        str(ROOT / 'tests/capture/PreviewModelInstallerHostTest.java')], check=True)
        fixture = directory / 'fixture'
        fixture.mkdir()
        subprocess.run(['java', '-cp', f'{classes}:{android}', 'app.nottheomi.ai.ModelInstallerHostTest',
                        str(args.model), str(fixture)], check=True, timeout=120)
        preview_fixture = directory / 'preview-fixture'
        preview_fixture.mkdir()
        subprocess.run(['java', '-cp', f'{classes}:{android}', 'app.nottheomi.ai.PreviewModelInstallerHostTest',
                        str(args.preview_model), str(preview_fixture)], check=True, timeout=120)

# These intentionally small doubles control blocking boundaries and faults. The
# CaptureService source under test is copied/compiled UNCHANGED. Android API type
# correctness is separately checked by the actual app Java compilation/build.
STUBS = {
'android/Manifest.java': '''package android; public class Manifest { public static class permission { public static final String RECORD_AUDIO="record"; }}''',
'android/content/Context.java': '''package android.content;
public class Context {
 public static volatile int permission=0;
 public int checkSelfPermission(String p){return permission;}
 public <T> T getSystemService(Class<T> type){ try { return type.getDeclaredConstructor().newInstance(); } catch(Exception e){throw new RuntimeException(e);} }
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public String getPackageName(){return "app.nottheomi.ai";}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 private String action; public Intent(){} public Intent(Context c,Class<?> t){}
 public Intent setAction(String a){action=a;return this;} public String getAction(){return action;}
}''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager {
 public static final int PERMISSION_GRANTED=0; public android.content.Intent getLaunchIntentForPackage(String p){return new android.content.Intent();}
}''',
'android/content/pm/ServiceInfo.java': '''package android.content.pm; public class ServiceInfo { public static final int FOREGROUND_SERVICE_TYPE_MICROPHONE=128; }''',
'android/app/Service.java': '''package android.app; public class Service extends android.content.Context {
 public static final int START_NOT_STICKY=2, STOP_FOREGROUND_REMOVE=1;
 public static int foregroundStarts, foregroundStops, selfStops; public static boolean failForeground;
 public void onCreate(){} public void onDestroy(){} public android.os.IBinder onBind(android.content.Intent i){return null;}
 public int onStartCommand(android.content.Intent i,int f,int id){return 0;}
 public void startForeground(int id,Notification n){foregroundStarts++;}
 public void startForeground(int id,Notification n,int type){foregroundStarts++;}
 public void stopForeground(int flags){foregroundStops++;} public void stopSelf(){selfStops++;} public void stopSelf(int id){selfStops++;}
}''',
'android/app/Notification.java': '''package android.app; public class Notification {
 public static final int VISIBILITY_SECRET=-1,FOREGROUND_SERVICE_IMMEDIATE=1; public static final String CATEGORY_SERVICE="service";
 public static class Builder { public Builder(android.content.Context c,String s){} public Builder setSmallIcon(int x){return this;}
 public Builder setContentTitle(String x){return this;} public Builder setContentText(String x){return this;}
 public Builder setCategory(String x){return this;} public Builder setVisibility(int x){return this;}
 public Builder setOnlyAlertOnce(boolean x){return this;} public Builder setOngoing(boolean x){return this;}
 public Builder addAction(Action x){return this;} public Builder setContentIntent(PendingIntent x){return this;}
 public Builder setForegroundServiceBehavior(int x){return this;} public Notification build(){if(android.app.Service.failForeground)throw new IllegalStateException("injected foreground rejection");return new Notification();}}
 public static class Action { public static class Builder { public Builder(Object icon,String title,PendingIntent p){} public Action build(){return new Action();}}}
}''',
'android/app/NotificationChannel.java': '''package android.app; public class NotificationChannel {
 public NotificationChannel(String a,String b,int c){} public void setDescription(String d){} public void setSound(Object a,Object b){}
 public void enableVibration(boolean v){} public void setLockscreenVisibility(int v){}
}''',
'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public static final int IMPORTANCE_LOW=2; public void createNotificationChannel(NotificationChannel c){} public void notify(int id,Notification n){}
}''',
'android/app/PendingIntent.java': '''package android.app; public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
 public static PendingIntent getService(android.content.Context c,int id,android.content.Intent i,int f){return new PendingIntent();}
 public static PendingIntent getActivity(android.content.Context c,int id,android.content.Intent i,int f){return new PendingIntent();}
}''',
'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();}}',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=34;}}',
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
'android/os/Handler.java': '''package android.os; public class Handler {
 static java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Handler(Looper l){} public boolean post(Runnable r){queue.add(r);return true;}
 public static void drain(){Runnable r;while((r=queue.poll())!=null)r.run();}
}''',
'android/os/PowerManager.java': '''package android.os; public class PowerManager {
 public static final int PARTIAL_WAKE_LOCK=1; public static volatile int held;
 public WakeLock newWakeLock(int type,String tag){return new WakeLock();}
 public static class WakeLock { boolean acquired; public void setReferenceCounted(boolean b){}
 public void acquire(long timeout){if(!acquired)held++;acquired=true;} public boolean isHeld(){return acquired;}
 public void release(){if(acquired)held--;acquired=false;}}
}''',
'android/media/AudioFormat.java': 'package android.media; public class AudioFormat {public static final int CHANNEL_IN_MONO=1,ENCODING_PCM_16BIT=2;}',
'android/media/MediaRecorder.java': 'package android.media; public class MediaRecorder {public static class AudioSource{public static final int VOICE_RECOGNITION=6;}}',
'android/media/AudioDeviceInfo.java': 'package android.media; public class AudioDeviceInfo {public static final int TYPE_BUILTIN_MIC=15; public int getType(){return TYPE_BUILTIN_MIC;}}',
'android/media/AudioManager.java': 'package android.media; public class AudioManager {public static final int GET_DEVICES_INPUTS=1; public AudioDeviceInfo[] getDevices(int f){return new AudioDeviceInfo[]{new AudioDeviceInfo()};}}',
'android/media/AudioRecordingConfiguration.java': 'package android.media; public class AudioRecordingConfiguration { public boolean isClientSilenced(){return false;}}',
'android/media/AudioRecord.java': '''package android.media;
import java.util.concurrent.*;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1,RECORDSTATE_RECORDING=3,READ_NON_BLOCKING=1;
 public static volatile int instances,starts,stops,releases,delivered; public static volatile boolean readFailure;
 public static CountDownLatch constructorGate, constructed=new CountDownLatch(1);
 public static volatile CountDownLatch readGate;
 public static CountDownLatch readEntered=new CountDownLatch(1);
 static final ConcurrentLinkedQueue<byte[]> incoming=new ConcurrentLinkedQueue<>();
 byte[] current; int offset; volatile boolean started;
 public AudioRecord(int a,int b,int c,int d,int e){instances++;constructed.countDown(); await(constructorGate);}
 static void await(CountDownLatch l){if(l!=null)try{l.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
 public static void push(byte[] bytes){incoming.add(bytes.clone());}
 public static int getMinBufferSize(int a,int b,int c){return 3200;}
 public int getState(){return STATE_INITIALIZED;} public boolean setPreferredDevice(AudioDeviceInfo d){return true;}
 public void startRecording(){starts++;started=true;} public int getRecordingState(){return started?RECORDSTATE_RECORDING:1;}
 public AudioDeviceInfo getRoutedDevice(){return new AudioDeviceInfo();}
 public AudioRecordingConfiguration getActiveRecordingConfiguration(){return new AudioRecordingConfiguration();}
 public int read(byte[] out,int at,int size,int mode){
  if(current==null){current=incoming.poll();offset=0;}
  if(current==null)return readFailure?-6:0;
  int n=Math.min(size,current.length-offset);System.arraycopy(current,offset,out,at,n);offset+=n;delivered+=n;
  if(offset==current.length)current=null;
  if(readGate!=null){readEntered.countDown();await(readGate);}return n;
 }
 public void stop(){stops++;started=false;} public void release(){releases++;}
}''',
'org/json/JSONObject.java': '''package org.json; public class JSONObject {
 String json; public JSONObject(String x){json=x;}
 public String optString(String key,String fallback){
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+key+"\\\"\\\\s*:\\\"([^\\\"]*)\\\"").matcher(json);
  return m.find()?m.group(1):fallback;
 }
}''',
'app/nottheomi/ai/PreviewModel.java': '''package app.nottheomi.ai; public class PreviewModel {
 public static volatile int created,closed,cancelled; public static java.util.concurrent.CountDownLatch gate,entered=new java.util.concurrent.CountDownLatch(1);
 public PreviewModel(String path){created++;entered.countDown();if(gate!=null)try{gate.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
 private boolean stopped; public synchronized void cancel(){if(!stopped){stopped=true;cancelled++;}}
 public void close(){if(PreviewRecognizer.created!=PreviewRecognizer.closed)throw new AssertionError("model closed before recognizer");closed++;}
}''',
'app/nottheomi/ai/PreviewRecognizer.java': '''package app.nottheomi.ai; public class PreviewRecognizer {
 public static volatile int created,accepted,closed,finals,failures,partials,resets,nonEndpointAccepts; public static volatile boolean fail;
 public static java.util.concurrent.CountDownLatch partialGate,partialEntered=new java.util.concurrent.CountDownLatch(1);
 public static java.util.concurrent.CountDownLatch acceptGate,finalGate,finalEntered=new java.util.concurrent.CountDownLatch(1);
 public static java.util.concurrent.CountDownLatch acceptEntered=new java.util.concurrent.CountDownLatch(1);
 public PreviewRecognizer(PreviewModel m,float rate){if(rate!=16000)throw new AssertionError("unexpected sample rate");created++;}
 public boolean acceptWaveForm(byte[] bytes,int length){
  acceptEntered.countDown();await(acceptGate); if(fail){failures++;throw new UnsatisfiedLinkError("injected native failure");}
  app.nottheomi.ai.Recordings.checkArchived(accepted,bytes,length);accepted++;return accepted>nonEndpointAccepts;
 }
 public String getResult(){return "{\\"text\\":\\"endpoint\\"}";}
 public String getPartialResult(){partialEntered.countDown();await(partialGate);partials++;return "{\\"partial\\":\\"preview "+partials+"\\"}";}
 public String getFinalResult(){finalEntered.countDown();await(finalGate);finals++;return "{\\"text\\":\\"stop final\\"}";}
 static void await(java.util.concurrent.CountDownLatch l){if(l!=null)try{l.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
 public void reset(){resets++;}
 public void close(){closed++;}
}''',
'app/nottheomi/ai/OmiCaptureService.java': 'package app.nottheomi.ai; public class OmiCaptureService {public static volatile boolean active;}',
'app/nottheomi/ai/R.java': 'package app.nottheomi.ai; public class R {public static class drawable {public static final int ic_wave=1;}}',
'app/nottheomi/ai/PreviewModelInstaller.java': '''package app.nottheomi.ai; public class PreviewModelInstaller {
 public static volatile boolean hold; public static java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
 public static java.io.File prepare(android.content.Context c,java.util.function.BooleanSupplier stop)throws Exception{
  entered.countDown();while(hold){if(stop.getAsBoolean())throw new java.io.InterruptedIOException("cancelled");Thread.sleep(2);}
  if(stop.getAsBoolean())throw new java.io.InterruptedIOException("cancelled");return new java.io.File("speech-model/vosk-model-small-en-us-0.15");
 }
}''',
'app/nottheomi/ai/RefinementJobService.java': '''package app.nottheomi.ai; public class RefinementJobService {
 public static volatile int pauses,schedules;
 public static void pauseForCapture(){if(!CaptureService.active&&!OmiCaptureService.active)throw new AssertionError("pause before capture active");pauses++;}
 public static void schedule(android.content.Context c){
  if(CaptureService.active||OmiCaptureService.active)throw new AssertionError("schedule while capture still active");
  if(PreviewModel.created!=PreviewModel.closed||PreviewRecognizer.created!=PreviewRecognizer.closed||android.os.PowerManager.held!=0)
   throw new AssertionError("schedule before owner teardown");
  schedules++;
 }
}''',
'app/nottheomi/ai/Recordings.java': '''package app.nottheomi.ai;
import java.util.*;import java.util.concurrent.*;
public class Recordings {
 public static volatile int created,finished,textFailures; public static volatile String status;
 public static volatile int failAudioAt=-1;public static volatile boolean failText;
 public static CountDownLatch audioGate,audioEntered=new CountDownLatch(1),finishGate,finishEntered=new CountDownLatch(1);
 static final Recordings INSTANCE=new Recordings();
 public static final List<byte[]> audio=Collections.synchronizedList(new ArrayList<>());
 public static final List<String> text=Collections.synchronizedList(new ArrayList<>());
 public static Recordings get(android.content.Context c){return INSTANCE;}
 public static class Session {public String id="fixture";}
 public Session create(){created++;return new Session();}
 public void appendAudio(String id,byte[] bytes,int length)throws Exception{
  audioEntered.countDown();if(audioGate!=null)audioGate.await();
  if(audio.size()==failAudioAt)throw new java.io.IOException("storage full");
  if(length>32000)throw new AssertionError("unbounded PCM commit");
  audio.add(Arrays.copyOf(bytes,length));
 }
 public void appendText(String id,String t)throws Exception{if(failText){textFailures++;throw new java.io.IOException("full");}text.add(t);}
 public void finish(String id,String s)throws Exception{finishEntered.countDown();if(finishGate!=null)finishGate.await();status=s;finished++;}
 public static void checkArchived(int index,byte[] bytes,int count){
  synchronized(audio){if(index>=audio.size()||!Arrays.equals(audio.get(index),Arrays.copyOf(bytes,count)))
   throw new AssertionError("ASR saw PCM before encrypted archive commit or out of order");}
 }
 public static int total(){synchronized(audio){return audio.stream().mapToInt(a->a.length).sum();}}
}'''
}
with tempfile.TemporaryDirectory(prefix='hermes-verify-phone-capture-') as tmp:
    directory = pathlib.Path(tmp)
    java = directory / 'src'
    classes = directory / 'classes'
    classes.mkdir()
    for path, content in STUBS.items():
        target = java / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(textwrap.dedent(content))
    subprocess.run(['javac', '--release', '17', '-d', str(classes),
                    *map(str, java.rglob('*.java')), str(source / 'CaptureService.java'),
                    str(source / 'LiveTranscript.java'),
                    str(ROOT / 'tests/capture/CaptureServiceHostTest.java')], check=True)
    scenarios = ['permission', 'omi_busy', 'null_intent', 'prepare_cancel', 'native_warm_cancel',
                 'mic_setup_cancel', 'archive_and_final', 'active_until_saved',
                 'native_failure', 'slow_asr', 'storage_failure', 'read_failure', 'text_failure', 'streaming_partial', 'partial_cancel', 'start_failure', 'stop_accept_timeout', 'stop_final_timeout', 'stop_storage_blocked']
    for scenario in scenarios:
        subprocess.run(['java', '-cp', str(classes), 'app.nottheomi.ai.CaptureServiceHostTest', scenario],
                       check=True, timeout=15)
    print(f'CaptureService host PASS: {len(scenarios)} scenarios (Android/native/storage doubles).')
