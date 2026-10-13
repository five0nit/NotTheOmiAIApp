package app.nottheomi.ai;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Real Android media routing to a synthetic session. Never runs on physical hardware. */
@SuppressWarnings("deprecation")
public final class ButtonMediaTest extends AndroidTestCase {
    private static final String[] TRANSPORT_IDS={"media_play_pause","media_pause","media_play","media_next",
        "media_previous","media_stop","media_fast_forward","media_rewind"};
    private static final int[] TRANSPORT_KEYS={KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND};
    private static final String[] VOLUME_IDS={"media_volume_up","media_volume_down","media_mute"};
    private SharedPreferences prefs;
    private MediaSession session;
    private AudioTrack track;
    private AudioManager audio;
    private Boolean originalMusicMuted;
    private int originalMusicVolume=-1;
    private final BlockingQueue<KeyEvent> received=new LinkedBlockingQueue<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Isolated emulator required", "ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);
        prefs=OmiSettingsActivity.preferences(getContext());prefs.edit().clear().commit();
        audio=(AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
        assertNotNull("Emulator audio service",audio);
    }
    @Override protected void tearDown() throws Exception {
        try{restoreMusicVolume();}
        finally{
            try{if(track!=null){try{track.stop();}finally{track.release();}}}
            finally{
                try{if(session!=null){try{session.setActive(false);}finally{session.release();}}}
                finally{try{if(prefs!=null)prefs.edit().clear().commit();}finally{super.tearDown();}}
            }
        }
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
            .setActions(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PLAY_PAUSE
                |PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_STOP
                |PlaybackState.ACTION_FAST_FORWARD|PlaybackState.ACTION_REWIND)
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
        assertEquals(0,up.getRepeatCount());assertTrue(up.getEventTime()>=down.getEventTime());
    }
    private void assertNoCaptureChanges(){
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);
    }
    private void assertNoKeys() throws Exception {assertNull("No extra or wrong media keys",received.poll(300,TimeUnit.MILLISECONDS));}
    private interface Condition {boolean get();}
    private void await(String message,Condition condition) throws Exception {
        long end=SystemClock.elapsedRealtime()+5000;
        while(!condition.get()&&SystemClock.elapsedRealtime()<end)Thread.sleep(30);
        assertTrue(message,condition.get());
    }
    private void snapshotMusicVolume() throws Exception {
        originalMusicMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
        if(originalMusicMuted){
            // getStreamVolume returns zero when muted. Read the retained level only after
            // unmuting, then restore both level and original mute state even on test failure.
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
            await("Read retained music level",()->!audio.isStreamMute(AudioManager.STREAM_MUSIC));
        }
        originalMusicVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    }
    private void restoreMusicVolume() throws Exception {
        if(originalMusicMuted==null)return;
        final boolean muted=originalMusicMuted;
        try{
            if(originalMusicVolume>=0){
                final int volume=originalMusicVolume;
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);
                await("Restore original music level",()->audio.getStreamVolume(AudioManager.STREAM_MUSIC)==volume);
            }
        }finally{
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,muted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
            await("Restore original music mute state",()->audio.isStreamMute(AudioManager.STREAM_MUSIC)==muted);
        }
        originalMusicMuted=null;originalMusicVolume=-1;
    }
    public void testMediaCataloguePreservesLegacyIdsLabelsAndDefaults(){
        String[] legacyIds={"bookmark","start","stop","toggle","media_play_pause","media_pause","shortcut","ignore"};
        String[] legacyLabels={"Bookmark in transcript","Start recording","Stop & save","Start / stop recording",
            "Play / pause music","Pause music","Phone shortcut","No action"};
        assertEquals(ButtonActions.IDS.length,ButtonActions.LABELS.length);
        Set<String> expected=new HashSet<>(Arrays.asList(legacyIds));
        expected.addAll(Arrays.asList(TRANSPORT_IDS));expected.addAll(Arrays.asList(VOLUME_IDS));
        expected.addAll(Arrays.asList("flashlight","smart","pause_start"));
        assertEquals(expected,new HashSet<>(Arrays.asList(ButtonActions.IDS)));
        assertEquals("No duplicate IDs",expected.size(),ButtonActions.IDS.length);
        for(int i=0;i<legacyIds.length;i++)assertEquals(legacyLabels[i],ButtonActions.LABELS[ButtonActions.index(legacyIds[i])]);
        assertEquals("bookmark",ButtonActions.action(getContext(),ButtonEvent.SINGLE));
        assertEquals("stop",ButtonActions.action(getContext(),ButtonEvent.DOUBLE));
        assertEquals("ignore",ButtonActions.action(getContext(),ButtonEvent.TRIPLE));
        assertEquals("ignore",ButtonActions.action(getContext(),ButtonEvent.SHAKE));
        Set<String> media=new HashSet<>(Arrays.asList(TRANSPORT_IDS));media.addAll(Arrays.asList(VOLUME_IDS));
        for(String id:ButtonActions.IDS)assertEquals(id,media.contains(id),ButtonActions.isMediaAction(id));
        for(String id:new String[]{null,"","media","media_unknown","MEDIA_PLAY","stop","start","toggle"})
            assertFalse(String.valueOf(id),ButtonActions.isMediaAction(id));
    }
    public void testEveryTransportPresetRoutesOnlyItsExactDownUpPair() throws Exception {
        currentPlayer();assertEquals(TRANSPORT_IDS.length,TRANSPORT_KEYS.length);
        for(int i=0;i<TRANSPORT_IDS.length;i++){
            assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.SINGLE,TRANSPORT_IDS[i]));
            String status=ButtonActions.dispatchMedia(getContext(),ButtonEvent.SINGLE);
            assertTrue(status,status.startsWith(ButtonActions.LABELS[ButtonActions.index(TRANSPORT_IDS[i])]+" requested"));
            assertKeyPair(TRANSPORT_KEYS[i]);assertNoKeys();
        }
        assertNoCaptureChanges();
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
    public void testTripleUsesIndependentLiveMediaMappingAndShakeRejected() throws Exception {
        currentPlayer();
        int[] events={ButtonEvent.SINGLE,ButtonEvent.DOUBLE,ButtonEvent.TRIPLE};
        String[] ids={"media_play","media_pause","media_next"};
        int[] keys={KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT};
        for(int i=0;i<events.length;i++)assertTrue(ButtonActions.setAction(getContext(),events[i],ids[i]));
        for(int i=0;i<events.length;i++){
            assertEquals(ids[i],ButtonActions.action(getContext(),events[i]));
            ButtonActions.dispatchMedia(getContext(),events[i]);assertKeyPair(keys[i]);assertNoKeys();
        }
        assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.TRIPLE,"media_rewind"));
        ButtonActions.dispatchMedia(getContext(),ButtonEvent.TRIPLE);assertKeyPair(KeyEvent.KEYCODE_MEDIA_REWIND);
        assertFalse(ButtonActions.setAction(getContext(),ButtonEvent.SHAKE,"media_previous"));
        assertEquals("ignore",ButtonActions.action(getContext(),ButtonEvent.SHAKE));
        assertEquals("No music action selected",ButtonActions.dispatchMedia(getContext(),ButtonEvent.SHAKE));
        assertNoKeys();assertNoCaptureChanges();
    }
    public void testNonMediaCorruptAndReservedEventsSendNoKeys() throws Exception {
        currentPlayer();
        Context noAudioLookup=new ContextWrapper(getContext()){
            @Override public Object getSystemService(String name){
                if(Context.AUDIO_SERVICE.equals(name))throw new AssertionError("Non-media action accessed audio service");
                return super.getSystemService(name);
            }
        };
        for(String id:new String[]{"bookmark","start","stop","toggle","shortcut","ignore"}){
            assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.SINGLE,id));
            assertEquals("No music action selected",ButtonActions.dispatchMedia(noAudioLookup,ButtonEvent.SINGLE));
        }
        assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.DOUBLE,"media_play_pause"));
        for(int event:new int[]{-1,0,3,4,5,99,102,Integer.MAX_VALUE})
            assertEquals("No music action selected",ButtonActions.dispatchMedia(noAudioLookup,event));
        for(int event:new int[]{ButtonEvent.TRIPLE,ButtonEvent.SHAKE})
            assertEquals("No music action selected",ButtonActions.dispatchMedia(noAudioLookup,event));
        prefs.edit().putString("single_action","media_unknown").commit();
        assertEquals("No music action selected",ButtonActions.dispatchMedia(noAudioLookup,ButtonEvent.SINGLE));
        prefs.edit().putInt("single_action",123).commit();
        assertEquals("No music action selected",ButtonActions.dispatchMedia(noAudioLookup,ButtonEvent.SINGLE));
        assertNoKeys();assertNoCaptureChanges();
    }
    public void testUnavailableAudioManagerDoesNotClaimControlOrSendFallbackKeys() throws Exception {
        currentPlayer();
        Context missing=new ContextWrapper(getContext()){
            @Override public Object getSystemService(String name){return Context.AUDIO_SERVICE.equals(name)?null:super.getSystemService(name);}
        };
        for(String id:ButtonActions.IDS)if(ButtonActions.isMediaAction(id)){
            assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.SINGLE,id));
            assertEquals(id,"Music control unavailable",ButtonActions.dispatchMedia(missing,ButtonEvent.SINGLE));
        }
        assertNoKeys();assertNoCaptureChanges();
    }
    public void testAudioServiceExceptionDoesNotClaimControlOrSendFallbackKeys() throws Exception {
        currentPlayer();
        Context unavailable=new ContextWrapper(getContext()){
            @Override public Object getSystemService(String name){
                if(Context.AUDIO_SERVICE.equals(name))throw new SecurityException("Synthetic unavailable audio service");
                return super.getSystemService(name);
            }
        };
        for(String id:ButtonActions.IDS)if(ButtonActions.isMediaAction(id)){
            assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.SINGLE,id));
            assertEquals(id,"Music control could not be sent",ButtonActions.dispatchMedia(unavailable,ButtonEvent.SINGLE));
        }
        assertNoKeys();assertNoCaptureChanges();
    }
    public void testVolumePermissionDenialChangesNothingAndSendsNoKeys() throws Exception {
        currentPlayer();
        try{
            // Restore even if a regression accidentally adjusts volume before rejecting access.
            snapshotMusicVolume();
            final int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            final boolean muted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
            Context denied=new ContextWrapper(getContext()){
                @Override public int checkSelfPermission(String permission){
                    return Manifest.permission.MODIFY_AUDIO_SETTINGS.equals(permission)?PackageManager.PERMISSION_DENIED:super.checkSelfPermission(permission);
                }
            };
            for(String id:VOLUME_IDS){
                assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.SINGLE,id));
                assertEquals(id,"Music control could not be sent",ButtonActions.dispatchMedia(denied,ButtonEvent.SINGLE));
            }
            assertNoKeys();assertEquals(volume,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            assertEquals(muted,audio.isStreamMute(AudioManager.STREAM_MUSIC));assertNoCaptureChanges();
        }finally{restoreMusicVolume();}
    }
    public void testVolumePresetsAdjustOnlyMusicAndMuteTogglesWithRestoration() throws Exception {
        currentPlayer();
        assertEquals("Manifest must declare MODIFY_AUDIO_SETTINGS",PackageManager.PERMISSION_GRANTED,
            getContext().checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS));
        assertFalse("Adjustable emulator volume required",audio.isVolumeFixed());
        final int min=Build.VERSION.SDK_INT>=28?audio.getStreamMinVolume(AudioManager.STREAM_MUSIC):0;
        final int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        assertTrue("Room to raise and lower music volume",max-min>=2);
        final int baseline=min+(max-min)/2;
        final int ring=audio.getStreamVolume(AudioManager.STREAM_RING),call=audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        final boolean ringMuted=audio.isStreamMute(AudioManager.STREAM_RING),callMuted=audio.isStreamMute(AudioManager.STREAM_VOICE_CALL);
        final int ringerMode=audio.getRingerMode();
        try{
            snapshotMusicVolume();
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,baseline,0);
            await("Known unmuted music baseline",()->audio.getStreamVolume(AudioManager.STREAM_MUSIC)==baseline&&!audio.isStreamMute(AudioManager.STREAM_MUSIC));
            String[] actions={"media_volume_up","media_volume_down","media_mute","media_mute"};
            int[] levels={baseline+1,baseline,0,baseline};
            for(int i=0;i<actions.length;i++){
                assertTrue(ButtonActions.setAction(getContext(),ButtonEvent.SINGLE,actions[i]));
                String status=ButtonActions.dispatchMedia(getContext(),ButtonEvent.SINGLE);
                assertTrue(status,status.startsWith(ButtonActions.LABELS[ButtonActions.index(actions[i])]+" requested"));
                final int expected=levels[i];final boolean mute=i==2;
                await(actions[i]+" changes only the music stream",()->audio.getStreamVolume(AudioManager.STREAM_MUSIC)==expected&&audio.isStreamMute(AudioManager.STREAM_MUSIC)==mute);
                assertEquals(ring,audio.getStreamVolume(AudioManager.STREAM_RING));assertEquals(call,audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
                assertEquals(ringMuted,audio.isStreamMute(AudioManager.STREAM_RING));assertEquals(callMuted,audio.isStreamMute(AudioManager.STREAM_VOICE_CALL));
                assertEquals(ringerMode,audio.getRingerMode());assertNoKeys();
            }
            assertNoCaptureChanges();
        }finally{restoreMusicVolume();}
    }
}
