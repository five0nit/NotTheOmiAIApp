package app.nottheomi.ai;

import android.app.job.JobScheduler;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Public-audio engine + real encrypted-store proof. Run only on the isolated emulator. */
@SuppressWarnings("deprecation")
public final class HybridSpeechIntegrationTest extends InstrumentationTestCase {
    private byte[] fixture() throws Exception {
        byte[] wav;
        try (InputStream in=getInstrumentation().getContext().getAssets().open("jfk.wav");
             ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192];int count;while((count=in.read(block))!=-1)out.write(block,0,count);wav=out.toByteArray();
        }
        ByteBuffer buffer=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        for(int at=12;at+8<=wav.length;){int length=buffer.getInt(at+4);if(length<0||length>wav.length-at-8)break;
            if(new String(wav,at,4,StandardCharsets.US_ASCII).equals("data"))return Arrays.copyOfRange(wav,at+8,at+8+length);
            at+=8+length+(length&1);}
        throw new AssertionError("Missing fixture PCM");
    }
    private void append(Recordings store,String id,byte[] pcm)throws Exception{
        for(int at=0;at<pcm.length;at+=3200){byte[] block=Arrays.copyOfRange(pcm,at,Math.min(at+3200,pcm.length));store.appendAudio(id,block,block.length);Arrays.fill(block,(byte)0);}
    }
    public void testStreamingDraftThenRealWhisperRefinement() throws Exception {
        Recordings store=Recordings.get(getInstrumentation().getTargetContext());byte[] pcm=fixture();Recordings.Session session=store.create();boolean finished=false;
        int firstPartialBytes=-1,partialUpdates=0;String previous="";
        try {
            try(PreviewModel model=new PreviewModel(PreviewModelInstaller.prepare(getInstrumentation().getTargetContext()).getAbsolutePath());
                PreviewRecognizer recognizer=new PreviewRecognizer(model,16000)){
                for(int at=0;at<pcm.length;at+=3200){byte[] block=Arrays.copyOfRange(pcm,at,Math.min(at+3200,pcm.length));
                    store.appendAudio(session.id,block,block.length);
                    if(recognizer.acceptWaveForm(block,block.length)){String text=new JSONObject(recognizer.getResult()).optString("text");if(!text.isEmpty())store.appendText(session.id,text);}
                    else{String partial=new JSONObject(recognizer.getPartialResult()).optString("partial");if(!partial.isEmpty()&&!partial.equals(previous)){if(firstPartialBytes<0)firstPartialBytes=at+block.length;partialUpdates++;previous=partial;}}
                    Arrays.fill(block,(byte)0);
                }
                String text=new JSONObject(recognizer.getFinalResult()).optString("text");if(!text.isEmpty())store.appendText(session.id,text);
            }
            assertTrue("Streaming draft before old 8-second batch",firstPartialBytes>0&&firstPartialBytes<256000);assertTrue(partialUpdates>1);
            String draft=store.find(session.id).text;assertTrue(draft.toLowerCase(Locale.ROOT).contains("country"));
            store.finish(session.id,"saved");finished=true;assertEquals("pending",store.find(session.id).transcriptState);
            try(WhisperModel model=new WhisperModel(ModelInstaller.prepare(getInstrumentation().getTargetContext()).getAbsolutePath())){
                RefinementJobService.refine(store,store.refinement(session.id),model,()->false);
            }
            Recordings.Session finalSession=store.find(session.id);assertEquals("complete",finalSession.transcriptState);assertEquals(draft,finalSession.liveText);assertEquals(pcm.length,finalSession.bytes);
            assertTrue(finalSession.text.toLowerCase(Locale.ROOT).contains("country"));ByteArrayOutputStream text=new ByteArrayOutputStream();store.exportText(session.id,text);assertEquals(finalSession.text,text.toString("UTF-8"));
            ByteArrayOutputStream audio=new ByteArrayOutputStream();store.exportWav(session.id,audio);assertTrue(Arrays.equals(pcm,Arrays.copyOfRange(audio.toByteArray(),44,audio.size())));
            System.out.println("HYBRID_INTEGRATION first_partial_input_ms="+(firstPartialBytes*1000L/32000)+" changed_partials="+partialUpdates+" final=complete encrypted_audio_unchanged=true");
        }finally{Arrays.fill(pcm,(byte)0);if(!finished)store.finish(session.id,"test cleanup");store.delete(session.id);}
    }
    public void testAndroidSchedulerDefersDuringCaptureAndRefinesSavedAudio() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();Recordings store=Recordings.get(context);byte[] pcm=fixture();Recordings.Session session=store.create();boolean finished=false;
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        try{
            append(store,session.id,pcm);store.appendText(session.id,"Original scheduler live draft");store.finish(session.id,"saved");finished=true;
            scheduler.cancel(RefinementJobService.JOB_ID);CaptureService.active=true;
            RefinementJobService.schedule(context);assertNull("No new work scheduled during capture",scheduler.getPendingJob(RefinementJobService.JOB_ID));assertEquals("pending",store.refinement(session.id).state);
            CaptureService.active=false;RefinementJobService.schedule(context);
            long end=android.os.SystemClock.elapsedRealtime()+120000;
            while(android.os.SystemClock.elapsedRealtime()<end&&"pending".equals(store.refinement(session.id).state))Thread.sleep(100);
            assertEquals("complete",store.refinement(session.id).state);assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);
            assertEquals("Original scheduler live draft",store.find(session.id).liveText);assertTrue(store.find(session.id).text.toLowerCase(Locale.ROOT).contains("country"));
        }finally{CaptureService.active=false;Arrays.fill(pcm,(byte)0);scheduler.cancel(RefinementJobService.JOB_ID);RefinementJobService.pauseForCapture();if(!finished)store.finish(session.id,"test cleanup");
            for(int i=0;i<100;i++)try{store.delete(session.id);break;}catch(IllegalStateException leased){Thread.sleep(100);}
        }
    }
}
