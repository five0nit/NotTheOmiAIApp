#!/usr/bin/env python3
"""Production JobService lifecycle tests with controlled Android/store/native doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/content/Context.java': '''package android.content;
public class Context { public <T> T getSystemService(Class<T> type) { return type.cast(android.app.job.JobScheduler.INSTANCE); } }''',
    'android/content/ComponentName.java': '''package android.content;
public class ComponentName { public ComponentName(Context c, Class<?> type) {} }''',
    'android/os/Looper.java': '''package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
    'android/os/Handler.java': '''package android.os;
public class Handler {
 public static final java.util.Queue<Runnable> QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Handler(Looper l){} public boolean post(Runnable r){QUEUE.add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){return true;}
 public void removeCallbacks(Runnable r){QUEUE.remove(r);}
 public static void drain(){Runnable r; while((r=QUEUE.poll())!=null)r.run();}
}''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }''',
    'android/app/job/JobParameters.java': '''package android.app.job; public class JobParameters {
 private final int id; public JobParameters(int n){id=n;} public int getJobId(){return id;} }''',
    'android/app/job/JobInfo.java': '''package android.app.job; public class JobInfo {
 public static final int NETWORK_TYPE_NONE=0,BACKOFF_POLICY_EXPONENTIAL=1;
 public static class Builder { public Builder(int id,android.content.ComponentName c){}
 public Builder setRequiredNetworkType(int n){return this;} public Builder setRequiresStorageNotLow(boolean b){return this;}
 public Builder setMinimumLatency(long n){return this;} public Builder setBackoffCriteria(long n,int p){return this;}
 public JobInfo build(){return new JobInfo();} }
}''',
    'android/app/job/JobScheduler.java': '''package android.app.job; public class JobScheduler {
 public static final JobScheduler INSTANCE=new JobScheduler(); public static final int RESULT_SUCCESS=1;
 public int schedules; public int schedule(JobInfo i){schedules++;return RESULT_SUCCESS;} }''',
    'android/app/job/JobService.java': '''package android.app.job; public class JobService extends android.content.Context {
 public final java.util.List<JobParameters> finished = new java.util.ArrayList<>();
 public final java.util.List<Boolean> retry = new java.util.ArrayList<>();
 public boolean onStartJob(JobParameters p){return false;} public boolean onStopJob(JobParameters p){return false;}
 public void onDestroy(){} public void jobFinished(JobParameters p,boolean r){finished.add(p);retry.add(r);}
}''',
    'app/nottheomi/ai/CaptureService.java': '''package app.nottheomi.ai; public class CaptureService { public static volatile boolean active; }''',
    'app/nottheomi/ai/OmiCaptureService.java': '''package app.nottheomi.ai; public class OmiCaptureService { public static volatile boolean active; }''',
    'app/nottheomi/ai/ModelInstaller.java': '''package app.nottheomi.ai; public class ModelInstaller {
 public static java.io.File prepare(android.content.Context c,java.util.function.BooleanSupplier stop){return new java.io.File("fake-model");} }''',
    'app/nottheomi/ai/Recordings.java': '''package app.nottheomi.ai;
import java.util.*;
public class Recordings {
 static final Recordings INSTANCE=new Recordings(); static volatile int commits,completes,failures;
 static final Refinement ENTRY=new Refinement();
 public static class Refinement { public String id="synthetic",state="pending"; public long totalBytes=2,offsetBytes=0; }
 interface Consumer{void accept(byte[] b)throws Exception;}
 public static Recordings get(android.content.Context c){return INSTANCE;}
 public List<Refinement> pendingRefinements(){return ENTRY.state.equals("pending")?List.of(ENTRY):List.of();}
 public Refinement refinement(String id){return ENTRY;}
 public void forEachPcm(String id,Consumer c)throws Exception{c.accept(new byte[]{1,0});}
 public void commitRefinementBatch(String id,long before,long after,String text){commits++;ENTRY.offsetBytes=after;}
 public void completeRefinement(String id){completes++;ENTRY.state="complete";}
 public void failRefinement(String id){failures++;ENTRY.state="failed";}
}''',
    'app/nottheomi/ai/WhisperModel.java': '''package app.nottheomi.ai;
import java.util.concurrent.*;
public class WhisperModel {
 static final CountDownLatch ENTERED=new CountDownLatch(1), RELEASE=new CountDownLatch(1), CLOSED=new CountDownLatch(1);
 static volatile int owners,maxOwners,opens,cancels; static volatile Thread inferenceThread,closeThread;
 public WhisperModel(String p){opens++;owners++;maxOwners=Math.max(owners,maxOwners);}
 public String transcribe(short[] s)throws Exception{inferenceThread=Thread.currentThread();ENTERED.countDown();if(!RELEASE.await(5,TimeUnit.SECONDS))throw new AssertionError("test release timeout");return "synthetic decoded";}
 public void cancel(){cancels++;}
 public void close(){closeThread=Thread.currentThread();owners--;CLOSED.countDown();}
}'''
}
TEST = '''package app.nottheomi.ai;
import android.app.job.*; import android.os.Handler; import java.util.concurrent.TimeUnit;
public class RefinementJobHostTest {
 static int assertions; static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static void settled()throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){Handler.drain();java.lang.reflect.Field f=RefinementJobService.class.getDeclaredField("owner");f.setAccessible(true);if(f.get(null)==null)return;Thread.sleep(5);}throw new AssertionError("worker did not exit");}
 public static void main(String[] args)throws Exception{
  String mode=args[0];RefinementJobService service=new RefinementJobService();JobParameters start=new JobParameters(812);
  if(mode.equals("capture-active")){CaptureService.active=true;check(service.onStartJob(start),"async start");settled();check(WhisperModel.opens==0,"capture blocks model loading");check(Recordings.commits==0,"no capture-time commit");check(service.finished.size()==1&&service.retry.get(0),"idle retry");}
  else {
   check(service.onStartJob(start),"async start");check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"real worker entered double");
   if(mode.equals("stop")){
    JobParameters stop=new JobParameters(812);check(start!=stop,"distinct Binder objects");check(service.onStopJob(stop),"reschedule requested");check(WhisperModel.cancels>0,"same job ID cancelled immediately");
    service.onStartJob(new JobParameters(812));Handler.drain();check(WhisperModel.opens==1&&WhisperModel.owners==1,"blocked older worker retains sole native ownership");check(!service.finished.contains(start),"stopped job not finished early");
   } else if(mode.equals("different-job")) {service.onStopJob(new JobParameters(999));check(WhisperModel.cancels==0,"different job not cancelled");}
   else if(mode.equals("destroy")){service.onDestroy();check(WhisperModel.cancels>0,"destroy cancels without freeing");check(WhisperModel.owners==1,"destroy retains native owner");}
   else if(mode.equals("capture-preempt")){CaptureService.active=true;RefinementJobService.pauseForCapture();check(WhisperModel.cancels>0,"capture preempts immediately");check(WhisperModel.owners==1,"capture cancellation never frees worker");}
   else if(!mode.equals("success"))throw new IllegalArgumentException(mode);
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"worker closes native model");settled();
   check(WhisperModel.maxOwners==1&&WhisperModel.owners==0,"serialized native ownership");check(WhisperModel.inferenceThread==WhisperModel.closeThread,"inference thread owns close");
   boolean cancelled=mode.equals("stop")||mode.equals("destroy")||mode.equals("capture-preempt");
   check(Recordings.commits==(cancelled?0:1),"cancelled inference never publishes checkpoint");check(Recordings.completes==(cancelled?0:1),"cancelled inference never completes");check(Recordings.failures==0,"cancellation is not durable failure");
   if(mode.equals("stop")||mode.equals("destroy"))check(!service.finished.contains(start),"no jobFinished after platform stop");
   else {check(service.finished.contains(start),"live job finishes");check(service.retry.get(service.finished.indexOf(start))==mode.equals("capture-preempt"),"correct retry disposition");}
  }
  System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");
 }
}'''
with tempfile.TemporaryDirectory(prefix='nottheomi-hybrid-job-') as directory:
    work = Path(directory)
    sources = []
    for name, text in {**STUBS, 'app/nottheomi/ai/RefinementJobHostTest.java': TEST}.items():
        path = work/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(path)
    sources += [ROOT/'app/src/main/java/app/nottheomi/ai'/name for name in ('RefinementJobService.java', 'RefinementEngine.java')]
    subprocess.run(['javac', '--release', '17', '-d', str(work), *map(str, sources)], check=True)
    scenarios = ['stop', 'different-job', 'destroy', 'capture-preempt', 'capture-active', 'success']
    for case in scenarios:
        subprocess.run(['java', '-cp', str(work), 'app.nottheomi.ai.RefinementJobHostTest', case], check=True, timeout=15)
    print(f'JobService lifecycle PASS: {len(scenarios)} scenarios; Android/native/store doubles, not device lifecycle acceptance.')
