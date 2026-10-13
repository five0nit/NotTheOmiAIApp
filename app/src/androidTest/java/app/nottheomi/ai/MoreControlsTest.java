package app.nottheomi.ai;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Synthetic emulator only; no wearable, capture, torch activation or permission grant. */
@SuppressWarnings("deprecation")
public final class MoreControlsTest extends ActivityInstrumentationTestCase2<OmiSettingsActivity> {
    private Context context;
    private SharedPreferences prefs;
    private Map<String,?> saved;
    private OmiSettingsActivity activity;
    public MoreControlsTest(){super(OmiSettingsActivity.class);}
    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated emulator only", "ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE));
        assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);assertFalse(CaptureService.active);
        context=getInstrumentation().getTargetContext();prefs=OmiSettingsActivity.preferences(context);
        saved=new HashMap<>(prefs.getAll());
        assertTrue(prefs.edit().remove("control_profile").remove("single_action").remove("double_action").remove("triple_action").remove("quadruple_action").remove("quintuple_action").commit());
        SharedPreferences.Editor e=prefs.edit();for(String k:prefs.getAll().keySet())if(k.startsWith("profile_"))e.remove(k);assertTrue(e.commit());
        setActivityInitialTouchMode(true);
    }
    @Override protected void tearDown() throws Exception {
        try {
            if(activity!=null)getInstrumentation().runOnMainSync(()->activity.finish());
            getInstrumentation().waitForIdleSync();
            if(prefs!=null&&saved!=null){
                SharedPreferences.Editor e=prefs.edit().clear();
                for(Map.Entry<String,?> item:saved.entrySet()){
                    String k=item.getKey();Object v=item.getValue();
                    if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
                    else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
                    else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,(Set<String>)v);
                }
                assertTrue(e.commit());
            }
            assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);assertFalse(CaptureService.active);
        } finally {super.tearDown();}
    }
    private void main(Runnable r){getInstrumentation().runOnMainSync(r);getInstrumentation().waitForIdleSync();}
    private View view(String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private void open(){activity=getActivity();getInstrumentation().waitForIdleSync();}
    private void select(String tag,int index){main(()->((Spinner)view(tag)).setSelection(index));}

    public void testLegacyUpgradePreservesTripleAndAddsUnassignedHigherCounts(){
        prefs.edit().putString("single_action","media_pause").putString("double_action","toggle").putString("triple_action","flashlight").commit();
        assertEquals("everyday",ButtonActions.profile(context));
        assertEquals("media_pause",ButtonActions.action(context,ButtonEvent.SINGLE));
        assertEquals("toggle",ButtonActions.action(context,ButtonEvent.DOUBLE));
        assertEquals("flashlight",ButtonActions.action(context,ButtonEvent.TRIPLE));
        assertEquals("ignore",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        assertEquals("ignore",ButtonActions.action(context,ButtonEvent.QUINTUPLE));
        assertEquals(3,ButtonActions.maxButtonTaps(context));
    }
    public void testEveryGestureHasIndependentPersistentMapping(){
        String[] ids={"smart","pause_start","flashlight","media_next","shortcut"};
        for(int i=0;i<ButtonActions.GESTURES.length;i++)assertTrue(ButtonActions.setAction(context,ButtonActions.GESTURES[i],ids[i]));
        for(int i=0;i<ButtonActions.GESTURES.length;i++)assertEquals(ids[i],ButtonActions.action(context,ButtonActions.GESTURES[i]));
        assertEquals(5,ButtonActions.maxButtonTaps(context));
        ButtonActions.setAction(context,ButtonEvent.QUINTUPLE,"ignore");assertEquals(4,ButtonActions.maxButtonTaps(context));
        ButtonActions.setAction(context,ButtonEvent.QUADRUPLE,"ignore");assertEquals(3,ButtonActions.maxButtonTaps(context));
    }
    public void testProfilesIndependentDefaultsAndPendingFence(){
        ButtonActions.setAction(context,ButtonEvent.TRIPLE,"flashlight");long generation=ButtonActions.configurationGeneration();
        assertTrue(ButtonActions.setProfile(context,"meeting"));assertTrue(ButtonActions.configurationGeneration()>generation);
        assertEquals("bookmark",ButtonActions.action(context,ButtonEvent.SINGLE));assertEquals("ignore",ButtonActions.action(context,ButtonEvent.TRIPLE));
        ButtonActions.setAction(context,ButtonEvent.TRIPLE,"pause_start");ButtonActions.setProfile(context,"music");
        assertEquals("media_play_pause",ButtonActions.action(context,ButtonEvent.SINGLE));assertEquals("media_next",ButtonActions.action(context,ButtonEvent.DOUBLE));
        ButtonActions.setProfile(context,"everyday");assertEquals("flashlight",ButtonActions.action(context,ButtonEvent.TRIPLE));
        ButtonActions.setProfile(context,"meeting");assertEquals("pause_start",ButtonActions.action(context,ButtonEvent.TRIPLE));
        assertFalse(ButtonActions.setProfile(context,"not-a-profile"));assertEquals("meeting",ButtonActions.profile(context));
    }
    public void testContextActionResolvesWithoutMutatingSavedAction(){
        ButtonActions.setAction(context,ButtonEvent.QUADRUPLE,"smart");
        assertEquals("bookmark",ButtonActions.resolvedAction(context,ButtonEvent.QUADRUPLE,true));
        assertEquals("media_play_pause",ButtonActions.resolvedAction(context,ButtonEvent.QUADRUPLE,false));
        assertEquals("smart",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        ButtonActions.setAction(context,ButtonEvent.QUINTUPLE,"pause_start");
        assertEquals("pause_start",ButtonActions.resolvedAction(context,ButtonEvent.QUINTUPLE,true));
        assertEquals("pause_start",ButtonActions.resolvedAction(context,ButtonEvent.QUINTUPLE,false));
    }
    public void testShortcutTargetsArePerProfileAndHigherGesture(){
        String fixturePackage=getInstrumentation().getContext().getPackageName();
        Intent target=new Intent(Intent.ACTION_MAIN).setComponent(new android.content.ComponentName(
            fixturePackage,ButtonShortcutFixtureActivity.Launch.class.getName()));
        assertTrue(ButtonActions.saveTarget(context,ButtonEvent.QUINTUPLE,target,"Everyday home",fixturePackage));
        assertEquals("Everyday home",ButtonActions.targetLabel(context,ButtonEvent.QUINTUPLE));
        ButtonActions.setProfile(context,"meeting");assertNull(ButtonActions.target(context,ButtonEvent.QUINTUPLE));
        assertTrue(ButtonActions.saveTarget(context,ButtonEvent.QUINTUPLE,target,"Meeting home",fixturePackage));
        ButtonActions.setProfile(context,"everyday");assertEquals("Everyday home",ButtonActions.targetLabel(context,ButtonEvent.QUINTUPLE));
        assertNotNull(ButtonActions.target(context,ButtonEvent.QUINTUPLE));
    }
    public void testCorruptValuesFailClosedAndReservedGestureUnchanged(){
        prefs.edit().putInt("control_profile",44).putString("quadruple_action","not-an-action").putInt("quintuple_action",7).commit();
        assertEquals("everyday",ButtonActions.profile(context));assertEquals("ignore",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        assertEquals("ignore",ButtonActions.action(context,ButtonEvent.QUINTUPLE));assertEquals(2,ButtonActions.maxButtonTaps(context));
        assertFalse(ButtonActions.setAction(context,ButtonEvent.SHAKE,"flashlight"));assertEquals("ignore",ButtonActions.action(context,ButtonEvent.SHAKE));
    }
    public void testSettingsAllFiveControlsAndNotificationHelperPresent(){
        open();
        for(int event:ButtonActions.GESTURES)assertNotNull(view("omi.action."+ButtonEvent.key(event)));
        assertNotNull(view("omi.controls.profile"));assertNotNull(view("omi.torch.permission"));
        assertNotNull(view("omi.notifications.mode"));assertNotNull(view("omi.notifications.pattern.default"));
        assertFalse(TorchControl.permissionGranted(context));assertTrue(((TextView)view("omi.torch.status")).getText().toString().contains("permission"));
        assertEquals("Double pulse",HapticPatterns.label(NotificationVibrations.defaultPattern(context)));
    }
    public void testRealProfileSpinnerDoesNotOverwriteOtherProfiles() throws Exception {
        open();
        select("omi.action.quadruple",ButtonActions.index("flashlight"));
        select("omi.action.quintuple",ButtonActions.index("smart"));
        assertEquals("flashlight",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        select("omi.controls.profile",2);assertEquals("music",ButtonActions.profile(context));
        assertEquals("ignore",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        assertEquals("media_play_pause",ButtonActions.action(context,ButtonEvent.SINGLE));
        select("omi.action.quadruple",ButtonActions.index("pause_start"));
        select("omi.controls.profile",0);assertEquals("flashlight",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        assertEquals("smart",ButtonActions.action(context,ButtonEvent.QUINTUPLE));
        assertEquals(ButtonActions.index("flashlight"),((Spinner)view("omi.action.quadruple")).getSelectedItemPosition());
        Thread.sleep(850);getInstrumentation().waitForIdleSync();
        assertEquals("flashlight",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
        select("omi.controls.profile",2);assertEquals("pause_start",ButtonActions.action(context,ButtonEvent.QUADRUPLE));
    }
    public void testNewSettingsOpeningCannotGrantOrActivateTorch(){
        boolean granted=TorchControl.permissionGranted(context);assertFalse(granted);open();
        assertEquals(granted,TorchControl.permissionGranted(context));
        assertTrue(TorchControl.toggle(context).contains("permission"));
        assertFalse(TorchControl.permissionGranted(context));
    }
    public void testVisualControlsCapture() throws Exception {
        open();
        main(()->{
            android.view.ViewGroup root=(android.view.ViewGroup)activity.findViewById(android.R.id.content);
            android.widget.ScrollView scroll=(android.widget.ScrollView)root.getChildAt(0);
            View profile=view("omi.controls.profile");scroll.scrollTo(0,Math.max(0,profile.getTop()-70));
        });
        main(()->{
            View content=activity.findViewById(android.R.id.content);
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(content.getWidth(),content.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            content.draw(new android.graphics.Canvas(image));
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"controls-0412.png"))){
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
            }catch(java.io.IOException failure){throw new AssertionError(failure);}finally{image.recycle();}
        });
    }
}
