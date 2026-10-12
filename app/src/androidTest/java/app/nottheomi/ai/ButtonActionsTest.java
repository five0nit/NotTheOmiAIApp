package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Synthetic isolated emulator only: no Omi connection, audio, broadcasts, services or physical phone. */
@SuppressWarnings("deprecation")
public final class ButtonActionsTest extends ActivityInstrumentationTestCase2<OmiSettingsActivity> {
    private Context context;
    private SharedPreferences prefs;
    private OmiSettingsActivity activity;
    private String fixturePackage;
    private ComponentName provider, launch;
    public ButtonActionsTest(){super(OmiSettingsActivity.class);}

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated emulator required", "ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);
        context=getInstrumentation().getTargetContext();prefs=OmiSettingsActivity.preferences(context);
        prefs.edit().clear().commit();
        fixturePackage=getInstrumentation().getContext().getPackageName();
        provider=new ComponentName(fixturePackage,ButtonShortcutFixtureActivity.class.getName());
        launch=new ComponentName(fixturePackage,ButtonShortcutFixtureActivity.Launch.class.getName());
        setActivityInitialTouchMode(true);activity=getActivity();getInstrumentation().waitForIdleSync();
    }
    @Override protected void tearDown() throws Exception {
        try{
            main(()->{OmiCaptureService.active=false;OmiCaptureService.controlsActive=false;ButtonActions.onPause(activity);activity.finish();});
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            manager.cancel(ButtonActions.notificationId(1));manager.cancel(ButtonActions.notificationId(2));
            prefs.edit().clear().commit();getInstrumentation().waitForIdleSync();
        }finally{super.tearDown();}
    }
    public void testMissingDefaultsAndLegacyMappingsMigrateWithoutTargetRewrite(){
        assertEquals("bookmark",ButtonActions.action(context,1));assertEquals("stop",ButtonActions.action(context,2));
        for(String legacy:new String[]{"bookmark","stop","ignore"}){
            prefs.edit().putString("single_action",legacy).putString("double_action",legacy).commit();
            assertEquals(legacy,ButtonActions.action(context,1));assertEquals(legacy,ButtonActions.action(context,2));
        }
        assertFalse(prefs.contains(ButtonActions.targetKey(1)));assertFalse(prefs.contains(ButtonActions.targetKey(2)));
    }
    public void testAllStableActionIdsAreIndependentAndReadLive(){
        for(String id:ButtonActions.IDS){
            assertTrue(ButtonActions.setAction(context,1,id));assertEquals(id,ButtonActions.action(context,1));assertEquals("stop",ButtonActions.action(context,2));
        }
        assertTrue(ButtonActions.setAction(context,2,"toggle"));assertEquals("toggle",ButtonActions.action(context,2));assertEquals("ignore",ButtonActions.action(context,1));
    }
    public void testUnknownWrongTypeAndReservedGesturesFailClosed(){
        prefs.edit().putString("single_action","unexpected").putInt("double_action",123).commit();
        assertEquals("ignore",ButtonActions.action(context,1));assertEquals("ignore",ButtonActions.action(context,2));
        assertFalse(ButtonActions.setAction(context,1,"START"));
        for(int event:new int[]{-1,0,3,4,5,Integer.MAX_VALUE}){
            assertEquals("ignore",ButtonActions.action(context,event));assertFalse(ButtonActions.setAction(context,event,"stop"));
            assertNull(ButtonActions.target(context,event));assertTrue(ButtonActions.launchShortcut(context,event).contains("Reserved"));
        }
    }
    public void testRealSeparateDropdownsHaveEightLabelsAndPersistAcrossRecreation() throws Exception {
        Spinner single=spinner(1),two=spinner(2);
        main(()->{assertNotSame(single,two);assertNotNull(single.getPopupBackground());assertNotNull(two.getPopupBackground());
            assertEquals(8,single.getCount());assertEquals(8,two.getCount());
            for(int i=0;i<ButtonActions.LABELS.length;i++)assertEquals(ButtonActions.LABELS[i],single.getItemAtPosition(i));
            single.setSelection(ButtonActions.index("shortcut"));two.setSelection(ButtonActions.index("toggle"));});
        await(()->"shortcut".equals(ButtonActions.action(context,1))&&"toggle".equals(ButtonActions.action(context,2)));
        recreate();
        main(()->{assertEquals("Phone shortcut",spinner(1).getSelectedItem());assertEquals("Start / stop recording",spinner(2).getSelectedItem());});
    }
    public void testStatusRefreshKeepsSpinnerInstancesAndCaptureAllowsEdits() throws Exception {
        Spinner single=spinner(1),two=spinner(2);
        main(()->{OmiCaptureService.active=true;refresh();assertTrue(single.isEnabled());assertTrue(two.isEnabled());single.setSelection(ButtonActions.index("start"));});
        await(()->"start".equals(ButtonActions.action(context,1)));
        main(()->{for(int i=0;i<20;i++)refresh();assertSame(single,spinner(1));assertSame(two,spinner(2));assertEquals("Start recording",single.getSelectedItem());});
        assertEquals("start",ButtonActions.action(context,1));assertEquals("stop",ButtonActions.action(context,2));
    }
    public void testOpeningSettingsAndPermissionCallbacksDoNotStartConnection() throws Exception {
        prefs.edit().putString("address","AA:BB:CC:DD:EE:FF").putString("name","Synthetic only").commit();
        recreate();
        main(()->{
            activity.onRequestPermissionsResult(42,new String[0],new int[0]);
            activity.onRequestPermissionsResult(43,new String[0],new int[0]);
            assertTrue(text("omi.connection.status").contains("Disconnected — no audio saved"));
        });
        assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);assertFalse(CaptureService.active);
        assertFalse(serviceRunning(OmiCaptureService.class.getName()));assertFalse(serviceRunning(CaptureService.class.getName()));
    }
    public void testStandbyStatusAndDeviceEditsAreLockedWithoutLockingMappings() throws Exception {
        prefs.edit().putString("address","AA:BB:CC:DD:EE:FF").commit();
        main(()->{
            OmiCaptureService.controlsActive=true;refresh();
            assertTrue(text("omi.connection.status").contains("Button-only connection — no audio saved"));
            assertEquals("Disconnect buttons",text("omi.controls.connect"));
            assertFalse(((Button)field("scanButton")).isEnabled());assertFalse(((Button)field("forgetButton")).isEnabled());
            assertTrue(spinner(1).isEnabled());assertTrue(spinner(2).isEnabled());
            OmiCaptureService.active=true;refresh();assertTrue(text("omi.connection.status").contains(OmiCaptureService.state));
            assertFalse(tag("omi.controls.connect").isEnabled());
        });
    }
    public void testPreparingConnectingRecoveringStoppingAreNotMislabelledRecording() throws Exception {
        String original=OmiCaptureService.state;
        try{main(()->{
            OmiCaptureService.active=true;
            for(String state:new String[]{"Preparing offline speech model…","Connecting to Omi — waiting for audio…","Waiting for Omi — reconnecting","Stopping — saving audio"}){
                OmiCaptureService.state=state;refresh();
                assertTrue(text("omi.connection.status").contains(state));
                assertFalse(text("omi.connection.status").contains("Recording from Omi"));
            }
            OmiCaptureService.state="Recording from Omi";refresh();assertTrue(text("omi.connection.status").contains("Recording from Omi"));
        });}finally{OmiCaptureService.state=original;}
    }
    public void testPerGestureExplicitTargetsRoundTripAndPersistScalarExtras(){
        Intent one=target("single").putExtra("number",7).putExtra("enabled",true);
        Intent two=target("double").putExtra("number",9L);
        assertTrue(ButtonActions.saveTarget(context,1,one,"Single fixture",fixturePackage));
        assertTrue(ButtonActions.saveTarget(context,2,two,"Double fixture",fixturePackage));
        assertEquals(launch,ButtonActions.target(context,1).getComponent());assertEquals(fixturePackage,ButtonActions.target(context,1).getPackage());
        assertEquals("single",ButtonActions.target(context,1).getStringExtra("which"));assertEquals(7,ButtonActions.target(context,1).getIntExtra("number",0));
        assertTrue(ButtonActions.target(context,1).getBooleanExtra("enabled",false));assertEquals(9L,ButtonActions.target(context,2).getLongExtra("number",0));
        assertEquals("Single fixture",ButtonActions.targetLabel(context,1));assertEquals("Double fixture",ButtonActions.targetLabel(context,2));
        assertTrue(prefs.getString(ButtonActions.targetKey(1),"").startsWith("intent:"));
        ButtonActions.setAction(context,1,"ignore");assertEquals("single",ButtonActions.target(context,1).getStringExtra("which"));
    }
    public void testValidationRejectsOwnPrivateMissingRestrictedAndForeignProviderTargets(){
        assertNull(ButtonActions.validated(context,new Intent(context,OmiSettingsActivity.class),context.getPackageName()));
        assertNull(ButtonActions.validated(context,new Intent().setComponent(new ComponentName(fixturePackage,ButtonShortcutFixtureActivity.Private.class.getName())),fixturePackage));
        assertNull(ButtonActions.validated(context,new Intent().setComponent(new ComponentName(fixturePackage,ButtonShortcutFixtureActivity.Restricted.class.getName())),fixturePackage));
        assertNull(ButtonActions.validated(context,new Intent().setComponent(new ComponentName(fixturePackage,"missing.activity")),fixturePackage));
        assertNull(ButtonActions.validated(context,target("bad"),"unselected.provider"));
        assertNull(ButtonActions.validated(context,target("bad").setPackage("unselected.package"),fixturePackage));
    }
    public void testValidationRejectsGrantsClipDataSelectorsNestedExtrasAndFiles(){
        int[] grants={Intent.FLAG_GRANT_READ_URI_PERMISSION,Intent.FLAG_GRANT_WRITE_URI_PERMISSION,Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,Intent.FLAG_GRANT_PREFIX_URI_PERMISSION};
        for(int grant:grants)assertNull(ButtonActions.validated(context,target("bad").addFlags(grant),fixturePackage));
        Intent clip=target("bad");clip.setClipData(ClipData.newPlainText("private","payload"));assertNull(ButtonActions.validated(context,clip,fixturePackage));
        Intent selector=target("bad");selector.setSelector(new Intent(Intent.ACTION_VIEW));assertNull(ButtonActions.validated(context,selector,fixturePackage));
        assertNull(ButtonActions.validated(context,target("bad").putExtra("nested",new Intent(Intent.ACTION_VIEW)),fixturePackage));
        assertNull(ButtonActions.validated(context,target("bad").putExtra("bundle",new Bundle()),fixturePackage));
        assertNull(ButtonActions.validated(context,target("bad").setData(Uri.parse("file:///private/file")),fixturePackage));
    }
    public void testOversizeMalformedLabelsAndUnsupportedArraysPreserveOldTarget(){
        assertTrue(ButtonActions.saveTarget(context,1,target("old"),"Old",fixturePackage));
        String prior=prefs.getString(ButtonActions.targetKey(1),"");
        String huge=new String(new char[ButtonActions.MAX_URI+1]).replace('\0','x');
        assertFalse(ButtonActions.saveTarget(context,1,target(huge),"Large",fixturePackage));
        assertFalse(ButtonActions.saveTarget(context,1,target("new"),huge,fixturePackage));
        for(String label:new String[]{"","   ","line\nbreak","hidden\u202etext"})assertFalse(ButtonActions.saveTarget(context,1,target("new"),label,fixturePackage));
        assertFalse(ButtonActions.saveTarget(context,1,target("new").putExtra("array",new int[]{1,2}),"Array",fixturePackage));
        assertEquals(prior,prefs.getString(ButtonActions.targetKey(1),""));assertEquals("Old",ButtonActions.targetLabel(context,1));
    }
    public void testStoredTargetsAreRevalidatedAndCannotUseImplicitOrGrantUris(){
        assertTrue(ButtonActions.saveTarget(context,1,target("old"),"Old",fixturePackage));
        prefs.edit().putString(ButtonActions.targetKey(1),new Intent(Intent.ACTION_MAIN).toUri(Intent.URI_INTENT_SCHEME)).commit();assertNull(ButtonActions.target(context,1));
        prefs.edit().putString(ButtonActions.targetKey(1),target("bad").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).toUri(Intent.URI_INTENT_SCHEME)).commit();assertNull(ButtonActions.target(context,1));
        prefs.edit().putString(ButtonActions.targetKey(1),"intent:#Intent;component=not-a-component;end").commit();assertNull(ButtonActions.target(context,1));
        prefs.edit().putInt(ButtonActions.targetKey(1),12).commit();assertNull(ButtonActions.target(context,1));
        assertTrue(ButtonActions.launchShortcut(context,1).contains("unavailable"));
    }
    public void testProviderResultCancellationMalformedAndUnselectedProviderPreserveTargets(){
        assertTrue(ButtonActions.providerAvailable(context,provider));
        assertTrue(ButtonActions.saveTarget(context,1,target("old"),"Old",fixturePackage));
        assertTrue(ButtonActions.saveTarget(context,2,target("two"),"Two",fixturePackage));
        String old=prefs.getString(ButtonActions.targetKey(1),"");
        Intent result=result("new","New");
        assertFalse(ButtonActions.acceptProviderResult(context,1,provider,Activity.RESULT_CANCELED,result));
        assertFalse(ButtonActions.acceptProviderResult(context,1,provider,Activity.RESULT_OK,new Intent()));
        assertFalse(ButtonActions.acceptProviderResult(context,1,provider,Activity.RESULT_OK,null));
        assertFalse(ButtonActions.acceptProviderResult(context,1,null,Activity.RESULT_OK,result));
        assertFalse(ButtonActions.acceptProviderResult(context,1,launch,Activity.RESULT_OK,result));
        assertEquals(old,prefs.getString(ButtonActions.targetKey(1),""));
        assertTrue(ButtonActions.acceptProviderResult(context,1,provider,Activity.RESULT_OK,result));
        assertEquals("new",ButtonActions.target(context,1).getStringExtra("which"));assertEquals("two",ButtonActions.target(context,2).getStringExtra("which"));
    }
    public void testPendingProviderAndGestureSurviveActivityRecreationAndRouteOnlyOnce() throws Exception {
        assertTrue(ButtonActions.saveTarget(context,1,target("one"),"One",fixturePackage));
        main(()->activity.restorePending(pending(2,provider)));
        recreate();
        main(()->{
            assertEquals(2,field("pendingGesture"));assertEquals(provider,field("pendingProvider"));
            activity.onActivityResult(999,Activity.RESULT_OK,result("wrong","Wrong"));assertEquals(2,field("pendingGesture"));
            activity.onActivityResult(41,Activity.RESULT_OK,result("two","Two"));
            assertEquals(0,field("pendingGesture"));assertNull(field("pendingProvider"));
            activity.onActivityResult(41,Activity.RESULT_OK,result("replay","Replay"));
        });
        assertEquals("one",ButtonActions.target(context,1).getStringExtra("which"));assertEquals("two",ButtonActions.target(context,2).getStringExtra("which"));
    }
    public void testCancellationAfterPendingRestoreKeepsBothMappings() throws Exception {
        assertTrue(ButtonActions.saveTarget(context,1,target("one"),"One",fixturePackage));assertTrue(ButtonActions.saveTarget(context,2,target("two"),"Two",fixturePackage));
        main(()->{activity.restorePending(pending(1,provider));activity.onActivityResult(41,Activity.RESULT_CANCELED,result("cancelled","Cancelled"));
            assertTrue(text("omi.shortcut.status").contains("unchanged"));assertEquals(0,field("pendingGesture"));
            activity.restorePending(pending(5,provider));assertEquals(0,field("pendingGesture"));
            activity.restorePending(pending(1,null));assertNull(field("pendingProvider"));});
        assertEquals("one",ButtonActions.target(context,1).getStringExtra("which"));assertEquals("two",ButtonActions.target(context,2).getStringExtra("which"));
    }
    public void testActualProviderActivityResultIsSelectedWithoutLaunchingItsTarget() throws Exception {
        main(()->activity.beginTargetSelection(2,new ButtonActions.Choice(provider,"Synthetic provider",true)));
        await(()->ButtonActions.target(context,2)!=null);
        assertEquals("Synthetic phone shortcut",ButtonActions.targetLabel(context,2));assertEquals(launch,ButtonActions.target(context,2).getComponent());
        assertNull(ButtonActions.target(context,1));assertFalse(OmiCaptureService.active);assertFalse(OmiCaptureService.controlsActive);
    }
    public void testChooserContainsLaunchableAppAndLegacyProviderButNoInternalActivities(){
        List<ButtonActions.Choice> choices=ButtonActions.choices(context);boolean foundProvider=false,foundApp=false;
        for(ButtonActions.Choice choice:choices){
            assertFalse(context.getPackageName().equals(choice.component.getPackageName()));
            if(provider.equals(choice.component)){foundProvider=true;assertTrue(choice.provider);}
            if(launch.equals(choice.component)){foundApp=true;assertFalse(choice.provider);}
        }
        assertTrue(foundProvider);assertTrue(foundApp);
    }
    public void testForegroundLaunchRequiresResumedFocusAndUsesOnlyTheSelectedActivity() throws Exception {
        assertTrue(ButtonActions.saveTarget(context,1,target("foreground"),"Foreground",fixturePackage));
        await(()->activity.hasWindowFocus());
        Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(launch.getClassName(),new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
        try{main(()->{ButtonActions.onResume(activity);assertTrue(ButtonActions.visiblyResumed(activity));assertEquals("Phone shortcut launch requested",ButtonActions.launchShortcut(context,1));});assertEquals(1,monitor.getHits());}
        finally{getInstrumentation().removeMonitor(monitor);}
    }
    public void testBackgroundPostsDirectImmutableTapNotificationWithoutActivityLaunch() throws Exception {
        grantNotifications();
        assertTrue(ButtonActions.saveTarget(context,1,target("background"),"Background fixture",fixturePackage));
        Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(launch.getClassName(),new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
        try{
            main(()->ButtonActions.onPause(activity));
            String outcome=ButtonActions.launchShortcut(context,1);assertTrue(outcome,outcome.contains("tap the notification"));assertTrue(outcome.contains("not auto-launched"));
            assertEquals(0,monitor.getHits());
            Notification notification=null;
            for(StatusBarNotification entry:context.getSystemService(NotificationManager.class).getActiveNotifications())if(entry.getId()==ButtonActions.notificationId(1))notification=entry.getNotification();
            assertNotNull(notification);assertNotNull(notification.contentIntent);
            if(Build.VERSION.SDK_INT>=31){assertTrue(notification.contentIntent.isActivity());assertTrue(notification.contentIntent.isImmutable());}
            assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility);
            assertTrue(notification.extras.getString(Notification.EXTRA_TITLE).contains("Tap to run"));
            assertTrue(ButtonActions.setAction(context,1,"ignore"));
            // NotificationManager.cancel queues system-server work; IPC return is not removal.
            await(()->{
                for(StatusBarNotification entry:context.getSystemService(NotificationManager.class).getActiveNotifications())
                    if(entry.getId()==ButtonActions.notificationId(1))return false;
                return true;
            });
        }finally{getInstrumentation().removeMonitor(monitor);main(()->ButtonActions.onResume(activity));}
    }
    public void testNotificationDenialDoesNotClaimLaunch() throws Exception {
        assertTrue(ButtonActions.saveTarget(context,1,target("denied"),"Denied",fixturePackage));
        main(()->ButtonActions.onPause(activity));
        Context denied=new ContextWrapper(context){@Override public int checkSelfPermission(String permission){return Manifest.permission.POST_NOTIFICATIONS.equals(permission)?PackageManager.PERMISSION_DENIED:super.checkSelfPermission(permission);}};
        if(Build.VERSION.SDK_INT>=33){String outcome=ButtonActions.launchShortcut(denied,1);assertTrue(outcome.contains("not launched"));assertTrue(outcome.contains("allow notifications"));}
    }
    public void testSyntheticSettingsScreenshotPreservesSecureWindow() throws Exception {
        ButtonActions.setAction(context,1,"toggle");ButtonActions.setAction(context,2,"media_pause");
        assertTrue(ButtonActions.saveTarget(context,1,target("preview"),"Synthetic test app",fixturePackage));
        recreate();
        main(()->{
            OmiCaptureService.controlsActive=true;refresh();
            assertTrue((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            ViewGroup holder=activity.findViewById(android.R.id.content);ScrollView scroll=(ScrollView)holder.getChildAt(0);View content=scroll.getChildAt(0);
            assertTrue(content.getWidth()>0);assertTrue(content.getHeight()>0);
            Bitmap image=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);
            try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"buttons-settings-preview.png"))){content.draw(new Canvas(image));assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
            finally{image.recycle();}
        });
        assertTrue(new File(activity.getFilesDir(),"buttons-settings-preview.png").length()>0);
    }

    private Intent target(String which){return new Intent(Intent.ACTION_VIEW).setComponent(launch).putExtra("which",which);}
    private Intent result(String which,String label){return new Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT,target(which)).putExtra(Intent.EXTRA_SHORTCUT_NAME,label);}
    private Bundle pending(int event,ComponentName component){Bundle state=new Bundle();state.putInt("button.pending_gesture",event);if(component!=null)state.putString("button.pending_provider",component.flattenToString());return state;}
    private boolean serviceRunning(String name){for(ActivityManager.RunningServiceInfo service:context.getSystemService(ActivityManager.class).getRunningServices(100))if(name.equals(service.service.getClassName()))return true;return false;}
    private View tag(String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private String text(String tag){return ((TextView)tag(tag)).getText().toString();}
    private Spinner spinner(int event){return (Spinner)tag(event==1?"omi.action.single":"omi.action.double");}
    private Object field(String name) throws Exception {Field field=OmiSettingsActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    private void refresh() throws Exception {Method method=OmiSettingsActivity.class.getDeclaredMethod("refresh");method.setAccessible(true);method.invoke(activity);}
    private void recreate() throws Exception {
        OmiSettingsActivity old=activity;Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(OmiSettingsActivity.class.getName(),null,false);
        try{main(old::recreate);Activity replacement=getInstrumentation().waitForMonitorWithTimeout(monitor,10000);assertNotNull("Settings recreation",replacement);activity=(OmiSettingsActivity)replacement;getInstrumentation().waitForIdleSync();assertNotSame(old,activity);}
        finally{getInstrumentation().removeMonitor(monitor);}
    }
    private void grantNotifications() throws Exception {
        if(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)return;
        try(ParcelFileDescriptor result=getInstrumentation().getUiAutomation().executeShellCommand("pm grant "+context.getPackageName()+" android.permission.POST_NOTIFICATIONS");
            ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(result)){
            byte[] buffer=new byte[1024];while(input.read(buffer)>=0){}
        }
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
    }
    private interface Checked{void run() throws Exception;}
    private interface Condition{boolean get() throws Exception;}
    private void main(Checked work) throws Exception {
        Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{work.run();}catch(Throwable problem){error[0]=problem;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private void await(Condition condition) throws Exception {
        long end=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<end){boolean[] met={false};main(()->met[0]=condition.get());if(met[0])return;Thread.sleep(40);}
        fail("Synthetic button-settings condition not reached");
    }
}
