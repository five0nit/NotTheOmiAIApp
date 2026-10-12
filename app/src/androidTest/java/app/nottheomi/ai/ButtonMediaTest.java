package app.nottheomi.ai;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.view.KeyEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Real Android media routing to a synthetic session. Never runs on physical hardware. */
@SuppressWarnings("deprecation")
public final class ButtonMediaTest extends AndroidTestCase {
    private SharedPreferences prefs;
    private MediaSession session;
    private AudioTrack track;
    private final BlockingQueue<KeyEvent> received=new LinkedBlockingQueue<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Isolated emulator required", "ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);
        prefs=OmiSettingsActivity.preferences(getContext());prefs.edit().clear().commit();
    }
    @Override protected void tearDown() throws Exception {
        try{
            if(track!=null){track.stop();track.release();}
            if(session!=null){session.setActive(false);session.release();}
            prefs.edit().clear().commit();
        }finally{super.tearDown();}
    }
    private void currentPlayer() throws Exception {
        session=new MediaSession(getContext(),"Synthetic Omi media routing test");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback(){
            @Override public boolean onMediaButtonEvent(Intent intent){
                KeyEvent key=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if(key!=null)received.add(key);
                return true;
            }
        },new Handler(Looper.getMainLooper()));
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_PLAYING,0,1f).build());
        session.setActive(true);
        // Android selects media-key receivers using actual playback, not just a claimed state.
        // Loop silent PCM: output-only synthetic fixture, never microphone capture or saved audio.
        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(32000).build();
        assertEquals(32000,track.write(new byte[32000],0,32000));
        assertEquals(AudioTrack.SUCCESS,track.setLoopPoints(0,16000,-1));track.play();
        AudioManager audio=(AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
        long end=SystemClock.elapsedRealtime()+5000;
        while(!audio.isMusicActive()&&SystemClock.elapsedRealtime()<end)Thread.sleep(30);
        assertTrue("Synthetic playback is active",audio.isMusicActive());
        Thread.sleep(300);
    }
    private void assertKeyPair(int key) throws Exception {
        KeyEvent down=received.poll(8,TimeUnit.SECONDS),up=received.poll(8,TimeUnit.SECONDS);
        assertNotNull("Android routed DOWN",down);assertNotNull("Android routed UP",up);
        assertEquals(KeyEvent.ACTION_DOWN,down.getAction());assertEquals(key,down.getKeyCode());
        assertEquals(KeyEvent.ACTION_UP,up.getAction());assertEquals(key,up.getKeyCode());
        assertEquals(down.getDownTime(),up.getDownTime());assertEquals(0,down.getRepeatCount());
    }
    public void testPauseRoutesRealDownUpAndNeverTogglesToPlay() throws Exception {
        currentPlayer();ButtonActions.setAction(getContext(),1,"media_pause");
        for(int i=0;i<2;i++){
            String status=ButtonActions.dispatchMedia(getContext(),1);assertTrue(status,status.startsWith("Pause music requested"));
            assertKeyPair(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
        assertNull(received.poll(200,TimeUnit.MILLISECONDS));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);
    }
    public void testSingleAndDoubleMediaKeysUseLiveIndependentMappings() throws Exception {
        currentPlayer();ButtonActions.setAction(getContext(),1,"media_pause");ButtonActions.setAction(getContext(),2,"media_play_pause");
        assertTrue(ButtonActions.dispatchMedia(getContext(),2).startsWith("Play / pause music requested"));
        assertKeyPair(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        ButtonActions.setAction(getContext(),2,"media_pause");ButtonActions.dispatchMedia(getContext(),2);
        assertKeyPair(KeyEvent.KEYCODE_MEDIA_PAUSE);
        assertEquals("media_pause",ButtonActions.action(getContext(),1));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);
    }
    public void testNonMediaAndReservedEventsSendNoKeys() throws Exception {
        currentPlayer();ButtonActions.setAction(getContext(),1,"start");ButtonActions.setAction(getContext(),2,"media_pause");
        for(int event:new int[]{-1,0,1,3,4,5})assertEquals("No music action selected",ButtonActions.dispatchMedia(getContext(),event));
        assertNull(received.poll(300,TimeUnit.MILLISECONDS));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);
    }
    public void testUnavailableAudioManagerDoesNotClaimControl(){
        ButtonActions.setAction(getContext(),1,"media_pause");
        Context missing=new ContextWrapper(getContext()){
            @Override public Object getSystemService(String name){return Context.AUDIO_SERVICE.equals(name)?null:super.getSystemService(name);}
        };
        assertEquals("Music control unavailable",ButtonActions.dispatchMedia(missing,1));
    }
}
