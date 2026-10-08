#!/usr/bin/env python3
"""Production Java wrappers with deterministic JNI double; no model-quality claim."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NATIVE = r'''package app.nottheomi.ai;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
public final class WhisperNative {
 static int calls, closes, cancels; static short[] held; static short first,last;
 static boolean fail, block; static volatile boolean cancelled;
 static CountDownLatch entered=new CountDownLatch(1), released=new CountDownLatch(1);
 public static long openFile(String path) {cancelled=false; return 1;}
 public static String transcribe(long h,short[] pcm,int threads) throws IOException {
  calls++; held=pcm; first=pcm.length==0?0:pcm[0]; last=pcm.length==0?0:pcm[pcm.length-1];
  if(fail)throw new IOException("forced");
  if(block){entered.countDown();try{released.await();}catch(InterruptedException e){throw new IOException(e);}}
  return cancelled?"":" ask \"not\"\\\n\t café 🐈 ";
 }
 public static void cancel(long h){cancels++;cancelled=true;released.countDown();}
 public static void close(long h){closes++;}
}'''
TEST = r'''package app.nottheomi.ai;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
public final class WrapperTest {
 static int assertions;
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);assertions++;}
 interface Checked{void run()throws Exception;}
 static void reject(Checked c)throws Exception{try{c.run();}catch(IOException e){assertions++;return;}throw new AssertionError("expected IOException");}
 static short[] buffer(WhisperRecognizer r)throws Exception{Field f=WhisperRecognizer.class.getDeclaredField("buffered");f.setAccessible(true);return(short[])f.get(r);}
 static boolean zero(short[] x){for(short v:x)if(v!=0)return false;return true;}
 public static void main(String[]args)throws Exception{
  WhisperModel m=new WhisperModel("public-model");
  reject(()->new WhisperRecognizer(m,8000)); reject(()->new WhisperRecognizer(null,16000));
  WhisperRecognizer r=new WhisperRecognizer(m,16000);
  reject(()->r.acceptWaveForm(null,0)); reject(()->r.acceptWaveForm(new byte[2],-1));
  reject(()->r.acceptWaveForm(new byte[2],3)); reject(()->r.acceptWaveForm(new byte[2],1));
  reject(()->r.acceptWaveForm(new byte[960002],960002));
  check(!r.acceptWaveForm(new byte[0],0)&&WhisperNative.calls==0,"empty input no inference");
  byte[] part=new byte[(WhisperRecognizer.BATCH_SAMPLES-1)*2];part[0]=(byte)0xff;part[1]=(byte)0xff;
  check(!r.acceptWaveForm(part,part.length)&&WhisperNative.calls==0,"strict prebatch boundary");
  check(r.getPartialResult().equals("{\"partial\":\"\"}"),"no invented live partial");
  check(r.acceptWaveForm(new byte[]{0,(byte)0x80},2)&&WhisperNative.calls==1,"exact batch flush");
  check(WhisperNative.first==-1&&WhisperNative.last==Short.MIN_VALUE,"signed little-endian conversion");
  check(zero(WhisperNative.held)&&zero(buffer(r)),"copies wiped after success");
  String expected="{\"text\":\"ask \\\"not\\\"\\\\\\u000a\\u0009 café 🐈\"}";
  check(r.getResult().equals(expected),"JSON escaping and unicode");
  check(r.getResult().equals("{\"text\":\"\"}"),"results consumed once");
  r.acceptWaveForm(new byte[]{1,0},2);
  check(r.getFinalResult().equals(expected)&&WhisperNative.calls==2,"short final tail flushed");
  check(r.getFinalResult().equals("{\"text\":\"\"}")&&WhisperNative.calls==2,"final idempotent");
  r.acceptWaveForm(new byte[]{2,0},2);r.reset();
  check(zero(buffer(r))&&r.getFinalResult().equals("{\"text\":\"\"}"),"gap reset wipes pending audio");
  WhisperNative.fail=true;r.acceptWaveForm(new byte[]{3,0},2);reject(r::getFinalResult);
  check(zero(buffer(r))&&zero(WhisperNative.held),"failure wipes all PCM copies");WhisperNative.fail=false;
  r.acceptWaveForm(new byte[]{4,0},2);r.close();r.close();
  check(zero(buffer(r))&&WhisperNative.closes==0,"recognizer close wipes without destroying model");
  reject(()->r.acceptWaveForm(new byte[0],0));reject(r::reset);reject(r::getResult);reject(r::getFinalResult);
  WhisperRecognizer next=new WhisperRecognizer(m,16000); next.acceptWaveForm(new byte[]{5,0},2);
  check(next.getFinalResult().equals(expected),"one model reusable across archive segments");next.close();
  WhisperNative.block=true;AtomicReference<Throwable> failure=new AtomicReference<>();
  Thread t=new Thread(()->{try{m.transcribe(new short[]{7});failure.set(new AssertionError("cancelled inference published"));}catch(IOException expectedFailure){}});
  t.start();check(WhisperNative.entered.await(2,TimeUnit.SECONDS),"entered native inference");
  long started=System.nanoTime();m.cancel();check(System.nanoTime()-started<500000000L,"cancel never waits for Java inference monitor");
  t.join(2000);check(!t.isAlive()&&failure.get()==null,"in-flight cancellation rejected");
  reject(()->m.transcribe(new short[]{8}));m.close();m.close();
  check(WhisperNative.closes==1,"native context closed exactly once");
  reject(()->m.transcribe(new short[]{9}));
  System.out.println("WhisperJavaWrapperTest PASS: "+assertions+" assertions; mocked JNI, production wrappers");
 }
}'''
with tempfile.TemporaryDirectory(prefix='hermes-verify-whisper-java-') as temporary:
    work = Path(temporary)
    (work/'WhisperNative.java').write_text(NATIVE)
    (work/'WrapperTest.java').write_text(TEST)
    source = ROOT/'app/src/main/java/app/nottheomi/ai'
    subprocess.run(['javac','--release','17','-d',str(work),str(work/'WhisperNative.java'),str(work/'WrapperTest.java'),str(source/'WhisperModel.java'),str(source/'WhisperRecognizer.java')],check=True)
    subprocess.run(['java','-cp',str(work),'app.nottheomi.ai.WrapperTest'],check=True,timeout=30)
