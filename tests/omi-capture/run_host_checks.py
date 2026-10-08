#!/usr/bin/env python3
"""Focused unchanged-source Omi service fixtures; no Gradle, ADB, or real radio.
Uses only the STUBS literal from the existing phone harness, not its execution.
Android/BLE/preview/Recordings doubles exercise ordering, cancellation and faults;
this does not prove Android encryption, actual speech or wearable acceptance.
"""
import ast
import pathlib
import subprocess
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/app/nottheomi/ai'
tree = ast.parse((ROOT / 'tests/capture/run_host_checks.py').read_text())
STUBS = ast.literal_eval(next(node.value for node in tree.body
    if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in node.targets)))
STUBS = {k: v for k, v in STUBS.items() if not k.startswith('android/media/')}
STUBS.pop('app/nottheomi/ai/OmiCaptureService.java')  # Compile the real Omi service below.
STUBS.update({
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static volatile long offset, fixed=-1; public static long elapsedRealtime(){return fixed>=0?fixed:System.nanoTime()/1000000+offset;}}',
'android/os/PowerManager.java': '''package android.os; public class PowerManager {
 public static final int PARTIAL_WAKE_LOCK=1; public static volatile int held,acquires,expirations;
 public static volatile long acquiredAt,expiresAt; public static volatile WakeLock instance;
 public WakeLock newWakeLock(int type,String tag){instance=new WakeLock();return instance;}
 public static class WakeLock {
  private boolean acquired;
  public void setReferenceCounted(boolean value){if(value)throw new AssertionError("reference-counted wake lease");}
  public synchronized boolean isHeld(){if(acquired&&SystemClock.elapsedRealtime()>=expiresAt){acquired=false;held--;expirations++;}return acquired;}
  public synchronized void acquire(long timeout){
   if(timeout!=600000L)throw new AssertionError("wake lease must stay bounded to ten minutes");
   if(!isHeld())held++;acquired=true;acquiredAt=SystemClock.elapsedRealtime();expiresAt=acquiredAt+timeout;acquires++;
  }
  public synchronized void release(){if(isHeld())held--;acquired=false;}
 }
}''',
'android/Manifest.java': '''package android; public class Manifest {public static class permission {
 public static final String RECORD_AUDIO="record",BLUETOOTH_CONNECT="connect",BLUETOOTH_SCAN="scan",
 BLUETOOTH="bluetooth",BLUETOOTH_ADMIN="admin",ACCESS_FINE_LOCATION="location";
}}''',
'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth; public class BluetoothAdapter {
 public static boolean checkBluetoothAddress(String s){return s!=null&&s.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}");}
}''',
'android/content/SharedPreferences.java': '''package android.content; public class SharedPreferences {
 public static final java.util.Map<String,String> values=new java.util.concurrent.ConcurrentHashMap<>();
 public String getString(String key,String def){return values.getOrDefault(key,def);}
}''',
'android/content/Context.java': '''package android.content; public class Context {
 public static final int MODE_PRIVATE=0; public static String denied;
 public static final java.util.Set<String> checked=java.util.concurrent.ConcurrentHashMap.newKeySet();
 public int checkSelfPermission(String p){checked.add(p);return p.equals(denied)?-1:0;}
 public SharedPreferences getSharedPreferences(String n,int m){if(!n.equals("omi"))throw new AssertionError("prefs");return new SharedPreferences();}
 public <T>T getSystemService(Class<T> t){try{return t.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public String getPackageName(){return "app.nottheomi.ai";}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 String action; java.util.Map<String,String> extras=new java.util.HashMap<>();public Intent(){}public Intent(Context c,Class<?> t){}
 public Intent setAction(String a){action=a;return this;} public String getAction(){return action;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}public String getStringExtra(String k){return extras.get(k);}
}''',
'android/content/pm/ServiceInfo.java': '''package android.content.pm; public class ServiceInfo {
 public static final int FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE=16;
}''',
'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public static final int IMPORTANCE_LOW=2,IMPORTANCE_NONE=0; public static boolean enabled=true; public static int importance=2;
 public static volatile int posts,attempts; public static volatile boolean failNext;
 public void createNotificationChannel(NotificationChannel c){} public void notify(int id,Notification n){attempts++;if(failNext){failNext=false;throw new IllegalStateException("injected notification failure");}posts++;}
 public boolean areNotificationsEnabled(){return enabled;}public NotificationChannel getNotificationChannel(String id){return new NotificationChannel(id,id,importance);}
}''',
'android/app/NotificationChannel.java': '''package android.app;public class NotificationChannel {
 int importance;public NotificationChannel(String a,String b,int c){importance=c;}public int getImportance(){return importance;}
 public void setDescription(String d){}public void setSound(Object a,Object b){}public void enableVibration(boolean v){}
 public void setLockscreenVisibility(int v){}
}''',
'app/nottheomi/ai/CaptureService.java': 'package app.nottheomi.ai;public class CaptureService {public static volatile boolean active;}',
'app/nottheomi/ai/Recordings.java': '''package app.nottheomi.ai;
import java.util.*;import java.util.concurrent.*;
public class Recordings {
 public static volatile int created,finished,textFailures;public static volatile String status;
 public static volatile int failAudioAt=-1,failCreateAt=-1,failFinishAt=-1;public static volatile String failTextContaining;
 public static volatile long audioDelayMs;public static volatile boolean failText,failNextText;
 public static CountDownLatch audioGate,audioEntered=new CountDownLatch(1),finishGate,finishEntered=new CountDownLatch(1);
 static final Recordings INSTANCE=new Recordings();
 public static final List<byte[]> audio=Collections.synchronizedList(new ArrayList<>());
 public static final List<String> text=Collections.synchronizedList(new ArrayList<>());
 public static Recordings get(android.content.Context c){return INSTANCE;}
 public static final List<String> audioIds=Collections.synchronizedList(new ArrayList<>()),textIds=Collections.synchronizedList(new ArrayList<>());
 public static final Map<String,String> statuses=new ConcurrentHashMap<>();
 private String activeId;
 public static class Session{public String id;Session(String id){this.id=id;}}
 public Session create()throws Exception{if(created!=finished)throw new IllegalStateException("active session");if(created==failCreateAt)throw new java.io.IOException("create full");created++;activeId="fixture-"+created;return new Session(activeId);}
 private void active(String id){if(created==finished||!id.equals(activeId))throw new AssertionError("write after finish or wrong segment: "+id);}
 public void appendAudio(String id,byte[] bytes,int length)throws Exception{
  active(id);audioEntered.countDown();if(audioGate!=null)audioGate.await();
  if(audioDelayMs>0)Thread.sleep(audioDelayMs);
  if(audio.size()==failAudioAt)throw new java.io.IOException("full");
  if(Thread.currentThread().getName().equals("fake-OmiBle"))throw new AssertionError("storage in BLE callback");
  if(length>6400)throw new AssertionError("unbounded PCM commit");audioIds.add(id);audio.add(Arrays.copyOf(bytes,length));
 }
 public void appendText(String id,String t)throws Exception{
  active(id);if(failText||failNextText||(failTextContaining!=null&&t.contains(failTextContaining))){textFailures++;failNextText=false;throw new java.io.IOException("full");}textIds.add(id);text.add(t);
 }
 public void finish(String id,String s)throws Exception{active(id);finishEntered.countDown();if(finishGate!=null)finishGate.await();if(finished==failFinishAt)throw new java.io.IOException("finish full");status=s;statuses.put(id,s);finished++;activeId=null;}
 public static byte[] segmentAudio(String id){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();synchronized(audio){for(int i=0;i<audio.size();i++)if(id.equals(audioIds.get(i)))out.write(audio.get(i),0,audio.get(i).length);}return out.toByteArray();}
 public static void checkArchived(int index,byte[] bytes,int count){
  synchronized(audio){if(index>=audio.size()||!Arrays.equals(audio.get(index),Arrays.copyOf(bytes,count)))
   throw new AssertionError("ASR preceded archive or reordered PCM");}
  if(Thread.currentThread().getName().equals("fake-OmiBle"))throw new AssertionError("ASR on BLE callback");
 }
 public static int total(){synchronized(audio){return audio.stream().mapToInt(a->a.length).sum();}}
}'''
})

STUBS['android/app/Service.java'] = STUBS['android/app/Service.java'].replace(
    'public void startForeground(int id,Notification n,int type){foregroundStarts++;}',
    'public void startForeground(int id,Notification n,int type){if(type!=16)throw new AssertionError("not connectedDevice FGS");foregroundStarts++;}')
SCENARIOS = ['null_intent', 'permission', 'legacy_permission', 'no_microphone_permission',
 'notifications', 'notification_dedupe', 'channel_blocked', 'phone_busy', 'address', 'prepare_cancel', 'native_cancel', 'wake_late_prepare', 'wake_prepare_cancel',
 'archive_bookmark', 'button_mapping', 'gap', 'overflow', 'slow_asr', 'native_failure',
 'storage_failure', 'text_failure', 'late_text_failure', 'active_until_saved', 'initial_failure',
 'small_packets_10ms', 'small_packets_20ms', 'initial_retry', 'stop_recovery', 'terminal_after_audio',
 'recovery_timeout', 'startup_timeout', 'repeated_gap', 'gap_speech_failure',
 'segment_drain', 'segment_no_empty', 'segment_bookmark_recovery', 'segment_finish_failure',
 'segment_create_failure', 'segment_marker_failure', 'segment_final_stall', 'segment_accept_stall', 'segment_overload_stall',
 'speech_control_saturation', 'segment_warning_failure', 'streaming_partial', 'partial_cancel', 'gap_partial', 'retired_partial', 'start_failure', 'stop_accept_timeout', 'stop_final_timeout']
import sys
if sys.argv[1:]:
    unknown = set(sys.argv[1:]) - set(SCENARIOS)
    if unknown:
        raise SystemExit(f'Unknown scenarios: {sorted(unknown)}')
    SCENARIOS = sys.argv[1:]
with tempfile.TemporaryDirectory(prefix='hermes-verify-omi-capture-') as tmp:
    directory = pathlib.Path(tmp)
    java = directory / 'src'
    classes = directory / 'classes'
    classes.mkdir()
    for path, content in STUBS.items():
        target = java / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(textwrap.dedent(content))
    subprocess.run(['javac', '--release', '17', '-d', str(classes),
        *map(str, java.rglob('*.java')), str(SOURCE / 'OmiCaptureService.java'), str(SOURCE / 'OmiPcmQueue.java'),
        str(SOURCE / 'LiveTranscript.java'),
        str(ROOT / 'tests/omi-capture/OmiBle.java'), str(ROOT / 'tests/omi-capture/OmiCaptureServiceHostTest.java')], check=True)
    for scenario in SCENARIOS:
        subprocess.run(['java', '-cp', str(classes), 'app.nottheomi.ai.OmiCaptureServiceHostTest', scenario],
            check=True, timeout=25)
    print(f'OmiCaptureService host PASS: {len(SCENARIOS)} scenarios (controlled doubles, not device acceptance).')
