package app.nottheomi.ai;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Synthetic UI state only. Run on the dedicated emulator, never a private phone. */
@SuppressWarnings("deprecation")
public final class HomeActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private MainActivity activity;
    private Recordings recordings;
    private String savedId;

    public HomeActivityTest(){super(MainActivity.class);}

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Synthetic emulator only", "ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);
        OmiSettingsActivity.preferences(getInstrumentation().getTargetContext()).edit().clear().putString("source","omi").commit();
        OmiCaptureService.display.reset(null);CaptureService.display.reset(null);
        recordings=Recordings.get(getInstrumentation().getTargetContext());
        activity=getActivity();
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        while(!Boolean.TRUE.equals(field("ready"))&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
        assertEquals(true,field("ready"));
    }

    @Override protected void tearDown() throws Exception {
        OmiCaptureService.active=false;OmiCaptureService.startedAt=0;OmiCaptureService.sessionId=null;OmiCaptureService.state="Stopped";
        OmiCaptureService.display.reset(null);CaptureService.display.reset(null);
        if(savedId!=null){recordings.finish(savedId,"saved");recordings.delete(savedId);}
        super.tearDown();
    }

    private Object field(String name) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void call(String name) throws Exception {
        Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);
        Throwable[] failure={null};getInstrumentation().runOnMainSync(()->{try{method.invoke(activity);}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(name,failure[0]);
    }
    private String text(String name) throws Exception {return ((TextView)field(name)).getText().toString();}

    public void testHomeHasConnectAndTranscriptSectionsWithoutStartingCapture() throws Exception {
        call("refreshCapture");assertEquals("Connect Omi",text("recordButton"));
        assertTrue(text("preview").contains("Connect your Omi"));assertTrue(text("finalText").contains("Finished phrases"));
        assertNotNull(field("historyRows"));assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);
        assertTrue((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);
    }

    public void testLivePartialAndPersistedFinalRemainSeparate() throws Exception {
        savedId=recordings.create().id;OmiCaptureService.display.reset(savedId);
        recordings.appendText(savedId,"Synthetic finished sentence.");OmiCaptureService.display.finalized("Synthetic finished sentence.");
        OmiCaptureService.display.partial("synthetic changing preview");
        OmiCaptureService.active=true;OmiCaptureService.sessionId=savedId;OmiCaptureService.startedAt=android.os.SystemClock.elapsedRealtime();
        call("refreshCapture");assertEquals("Disconnect & save",text("recordButton"));
        assertEquals("synthetic changing preview",text("preview"));assertTrue(text("finalText").contains("Synthetic finished sentence."));
        assertFalse(text("finalText").contains("changing preview"));assertFalse(((Button)field("sourceButton")).isEnabled());
        OmiCaptureService.display.partial("");call("refreshCapture");assertEquals("Listening…",text("preview"));
        assertTrue(text("finalText").contains("Synthetic finished sentence."));
    }

    public void testStoppingKeepsFinalLogAndRestoresConnect() throws Exception {
        OmiCaptureService.display.reset("synthetic-stop");OmiCaptureService.display.finalized("Synthetic retained final.");
        OmiCaptureService.active=true;call("refreshCapture");
        OmiCaptureService.active=false;OmiCaptureService.display.partial("");call("refreshCapture");
        assertEquals("Connect Omi",text("recordButton"));assertTrue(text("finalText").contains("Synthetic retained final."));
        assertTrue(text("finalHint").contains("Last session"));
    }

    public void testConnectingCanBeCancelledAndDoesNotClaimRecording() throws Exception {
        OmiCaptureService.active=true;OmiCaptureService.startedAt=0;OmiCaptureService.state="Connecting to selected Omi…";
        call("refreshCapture");assertEquals("Cancel connection",text("recordButton"));assertEquals("00:00",text("timer"));
        assertEquals("Waiting for Omi audio…",text("preview"));
    }

    public void testPickerCancellationDoesNotStartEitherService() throws Exception {
        Method result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{try{result.invoke(activity,30,Activity.RESULT_CANCELED,null);}catch(Exception e){throw new AssertionError(e);}});
        assertEquals(false,field("resumeStart"));assertFalse(CaptureService.active);assertFalse(OmiCaptureService.active);
    }

    public void testFreshHomeReloadsSavedHistoryAfterRamReset() throws Exception {
        savedId=recordings.create().id;recordings.rename(savedId,"Synthetic home history");recordings.appendText(savedId,"Synthetic persisted words.");recordings.finish(savedId,"saved");
        OmiCaptureService.display.reset(null);call("loadHomeHistory");
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            final boolean[] found={false};
            getInstrumentation().runOnMainSync(()->{try{found[0]=contains((android.view.View)field("historyRows"),"Synthetic persisted words.");}catch(Exception e){throw new AssertionError(e);}});
            if(found[0])return;Thread.sleep(50);
        }
        fail("Saved history did not appear on Home");
    }
    public void testOpeningSavedHistoryResetsScrollPosition() throws Exception {
        savedId=recordings.create().id;recordings.appendText(savedId,"Synthetic scroll regression.");recordings.finish(savedId,"saved");
        Method detail=MainActivity.class.getDeclaredMethod("detail",String.class);detail.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{try{
            android.widget.ScrollView scroll=(android.widget.ScrollView)((android.view.View)field("content")).getParent();
            scroll.scrollTo(0,500);assertTrue(scroll.getScrollY()>0);detail.invoke(activity,savedId);
        }catch(Exception e){throw new AssertionError(e);}});
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            final boolean[] shown={false};
            getInstrumentation().runOnMainSync(()->{try{
                android.view.View content=(android.view.View)field("content");
                shown[0]=contains(content,"Synthetic scroll regression.")&&((android.widget.ScrollView)content.getParent()).getScrollY()==0;
            }catch(Exception e){throw new AssertionError(e);}});
            if(shown[0])return;Thread.sleep(50);
        }
        fail("Saved detail did not return to the top");
    }

    private static boolean contains(android.view.View view,String expected){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(expected))return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),expected))return true;}
        return false;
    }
}
