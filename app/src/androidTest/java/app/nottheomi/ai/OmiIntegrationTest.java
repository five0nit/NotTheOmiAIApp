package app.nottheomi.ai;

import android.test.InstrumentationTestCase;
import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Real Android codec/ASR/encrypted-store pipeline; NOT a physical BLE test. */
public final class OmiIntegrationTest extends InstrumentationTestCase {
    public void testOpusSpeechSurvivesOfflinePipeline() throws Exception {
        byte[] wav;
        try(InputStream in=getInstrumentation().getContext().getAssets().open("jfk.wav")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);wav=out.toByteArray();
        }
        int data=-1,length=0;
        for(int at=12;at+8<=wav.length;){
            int size=littleInt(wav,at+4);
            if(new String(wav,at,4,java.nio.charset.StandardCharsets.US_ASCII).equals("data")){data=at+8;length=size;break;}
            at+=8+size+(size&1);
        }
        assertTrue(data>0&&length>0&&data+length<=wav.length);
        OpusEncoder encoder=new OpusEncoder(16000,1,OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(16000);OpusDecoder decoder=new OpusDecoder(16000,1);
        Recordings db=Recordings.get(getInstrumentation().getTargetContext());
        Recordings.Session session=db.create();ByteArrayOutputStream decoded=new ByteArrayOutputStream();
        int packets=0;
        try(WhisperModel model=new WhisperModel(ModelInstaller.prepare(getInstrumentation().getTargetContext()).getAbsolutePath());WhisperRecognizer recognizer=new WhisperRecognizer(model,16000)){
            for(int at=data;at<data+length;at+=1280){
                short[] input=new short[640];
                for(int i=0;i<input.length&&at+i*2+1<data+length;i++)input[i]=(short)((wav[at+i*2]&255)|(wav[at+i*2+1]<<8));
                byte[] packet=new byte[512];int bytes=encoder.encode(input,0,input.length,packet,0,packet.length);
                assertTrue(bytes>0);short[] output=new short[1920];int count=decoder.decode(packet,0,bytes,output,0,output.length,false);
                assertEquals(640,count);byte[] pcm=new byte[count*2];
                for(int i=0;i<count;i++){pcm[i*2]=(byte)output[i];pcm[i*2+1]=(byte)(output[i]>>8);}
                db.appendAudio(session.id,pcm,pcm.length);decoded.write(pcm);packets++;
                if(recognizer.acceptWaveForm(pcm,pcm.length))db.appendText(session.id,new JSONObject(recognizer.getResult()).optString("text"));
            }
            db.appendText(session.id,new JSONObject(recognizer.getFinalResult()).optString("text"));db.finish(session.id,"saved");
            Recordings.Session saved=db.find(session.id);
            assertTrue("Real lossy Opus audio should retain spoken fixture words",saved.text.toLowerCase(java.util.Locale.ROOT).contains("ask not what your country"));
            assertTrue(packets>200);assertTrue(saved.durationMs>8000);
            ByteArrayOutputStream exported=new ByteArrayOutputStream();db.exportWav(session.id,exported);
            assertTrue(Arrays.equals(decoded.toByteArray(),Arrays.copyOfRange(exported.toByteArray(),44,exported.size())));
        }finally{db.finish(session.id,"saved");db.delete(session.id);}
    }
    public void testPcmControlsAndGapKeepOneFifoBoundary() throws Exception {
        OmiPcmQueue queue=new OmiPcmQueue();
        assertTrue(queue.offer(new short[]{1,-2,32767}));assertTrue(queue.bookmark("mark"));
        assertTrue(queue.offer(new short[]{3}));queue.close("gap");
        assertFalse(queue.offer(new short[]{4}));assertFalse(queue.bookmark("late"));
        OmiPcmQueue.Event first=queue.poll(1);assertTrue(Arrays.equals(new byte[]{1,0,-2,-1,-1,127},first.pcm));
        assertEquals("mark",queue.poll(1).marker);assertTrue(Arrays.equals(new byte[]{3,0},queue.poll(1).pcm));
        OmiPcmQueue.Event last=queue.poll(1);assertTrue(last.terminal);assertEquals("gap",last.marker);queue.clear();
    }
    public void testBatchBoundariesAndEncryptedRoundTrip() throws Exception {
        OmiPcmQueue queue = new OmiPcmQueue();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < 30; i++) {
            short[] frame = new short[160];
            Arrays.fill(frame, (short) (i + 1));
            assertTrue(queue.offer(frame));
            for (short sample : frame) { expected.write(sample & 255); expected.write(sample >> 8); }
            if (i == 9) assertTrue(queue.bookmark("synthetic marker"));
        }
        queue.close("synthetic gap");
        assertFalse(queue.offer(new short[]{9}));
        Recordings db = Recordings.get(getInstrumentation().getTargetContext());
        String id = db.create().id;
        try {
            OmiPcmQueue.Event first = queue.pollBatch(0);
            assertEquals(3200, first.pcm.length);
            db.appendAudio(id, first.pcm, first.pcm.length);
            assertEquals("synthetic marker", queue.pollBatch(0).marker);
            OmiPcmQueue.Event second = queue.pollBatch(0);
            assertEquals(6400, second.pcm.length);
            db.appendAudio(id, second.pcm, second.pcm.length);
            OmiPcmQueue.Event end = queue.pollBatch(0);
            assertTrue(end.terminal); assertEquals("synthetic gap", end.marker);
            assertNull(queue.pollBatch(0));
            db.finish(id, "saved");
            ByteArrayOutputStream wav = new ByteArrayOutputStream(); db.exportWav(id, wav);
            assertTrue(Arrays.equals(expected.toByteArray(), Arrays.copyOfRange(wav.toByteArray(), 44, wav.size())));
        } finally { queue.clear(); db.finish(id, "saved"); db.delete(id); }
    }
    public void testBatchedStorageSustainsTenSecondsOfSmallPackets() throws Exception {
        OmiPcmQueue queue = new OmiPcmQueue();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Recordings db = Recordings.get(getInstrumentation().getTargetContext());
        String id = db.create().id;
        Thread producer = new Thread(() -> {
            try {
                long next = System.nanoTime();
                for (int i = 0; i < 1000; i++) {
                    short[] frame = new short[160]; Arrays.fill(frame, (short) i);
                    if (!queue.offer(frame)) throw new AssertionError("paced capture queue overflow");
                    next += 10_000_000L;
                    long wait = next - System.nanoTime();
                    if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
                }
            } catch (Throwable failure) { error.set(failure); }
            finally { queue.close(null); }
        }, "synthetic-omi-10ms");
        int bytes = 0, commits = 0;
        try {
            producer.start();
            while (true) {
                OmiPcmQueue.Event event = queue.pollBatch(200);
                if (event == null) continue;
                if (event.terminal) break;
                assertTrue(event.pcm.length <= OmiPcmQueue.MAX_BATCH_BYTES);
                db.appendAudio(id, event.pcm, event.pcm.length);
                bytes += event.pcm.length; commits++; Arrays.fill(event.pcm, (byte) 0);
            }
            producer.join(2000); assertFalse(producer.isAlive());
            if (error.get() != null) throw new AssertionError(error.get());
            assertEquals(320000, bytes); assertTrue(commits <= 65);
            db.finish(id, "saved"); assertEquals(10000L, db.find(id).durationMs);
        } finally {
            queue.close(null); producer.interrupt(); producer.join(2000); queue.clear();
            db.finish(id, "saved"); db.delete(id);
        }
    }
    public void testButtonWireLayoutRejectsUnknownAndReservedEvents(){
        assertEquals(ButtonEvent.SINGLE,ButtonEvent.decode(new byte[]{1,0,0,0,0,0,0,0}));
        assertEquals(ButtonEvent.DOUBLE,ButtonEvent.decode(new byte[]{2,0,0,0,0,0,0,0}));
        assertEquals(ButtonEvent.NONE,ButtonEvent.decode(new byte[]{3,0,0,0,0,0,0,0}));
        assertEquals(ButtonEvent.NONE,ButtonEvent.decode(new byte[]{1}));
        assertEquals(ButtonEvent.NONE,ButtonEvent.decode(new byte[]{1,0,0,0,1,0,0,0}));
        assertEquals(ButtonEvent.NONE,ButtonEvent.decode(null));
    }
    private static int littleInt(byte[] b,int at){return(b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|((b[at+3]&255)<<24);}
}
