package app.nottheomi.ai;

import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Runs the bundled native recognizer with public fixture audio inside Android. */
public final class SpeechIntegrationTest extends InstrumentationTestCase {
    public void testBundledModelRecognisesAndPersistsOffline() throws Exception {
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
        Recordings db=Recordings.get(getInstrumentation().getTargetContext());
        Recordings.Session session=db.create();
        try(WhisperModel model=new WhisperModel(ModelInstaller.prepare(getInstrumentation().getTargetContext()).getAbsolutePath());WhisperRecognizer recognizer=new WhisperRecognizer(model,16000)){
            for(int at=data;at<data+length;at+=8000){
                byte[] pcm=Arrays.copyOfRange(wav,at,Math.min(at+8000,data+length));
                db.appendAudio(session.id,pcm,pcm.length);
                if(recognizer.acceptWaveForm(pcm,pcm.length))db.appendText(session.id,new JSONObject(recognizer.getResult()).optString("text"));
            }
            db.appendText(session.id,new JSONObject(recognizer.getFinalResult()).optString("text"));
            db.finish(session.id,"saved");
            Recordings.Session saved=db.find(session.id);
            assertTrue("Native recognizer should decode the public JFK fixture",saved.text.toLowerCase(java.util.Locale.ROOT).contains("ask not what your country"));
            assertTrue(saved.durationMs>8000);
            assertTrue(db.list("ask not").stream().anyMatch(s->s.id.equals(session.id)));
            ByteArrayOutputStream exported=new ByteArrayOutputStream();db.exportWav(session.id,exported);
            byte[] result=exported.toByteArray();
            assertEquals(length+44,result.length);
            assertTrue(Arrays.equals(Arrays.copyOfRange(wav,data,data+length),Arrays.copyOfRange(result,44,result.length)));
        }finally{
            db.finish(session.id,"saved");db.delete(session.id);
        }
    }
    private static int littleInt(byte[] b,int at){return(b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|((b[at+3]&255)<<24);}
}
