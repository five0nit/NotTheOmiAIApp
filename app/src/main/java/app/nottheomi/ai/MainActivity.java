package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One local-first UI, no accounts, servers, trackers or hidden destinations. */
public final class MainActivity extends Activity {
    private static final int PAPER=0xfff5f2eb, INK=0xff191c18, MUTED=0xff646a61, LINE=0xffdadcd2, ACCENT=0xffbd432b;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout page, content, libraryRows, historyRows;
    private TextView stateLabel,timer,preview,storageLabel,connectionLabel,liveHint,finalText,finalHint,historyHint;
    private Button recordButton, sourceButton, deviceButton;
    private boolean resumeStart, wasActive;
    private int historyGeneration;
    private long displayRevision=-1;
    private boolean displayWasOmi;
    private Meter meter;
    private LocalPlayback playback;
    private boolean library, resumed, destroyed, ready, startPending;
    private int viewGeneration, searchGeneration;
    private String query="", selectedId, exportId, exportKind;
    private long refinementRevision = -1;
    private TextView detailTranscript, detailRefinement;
    private Button detailRetry;
    private final Runnable ticker=new Runnable(){public void run(){if(resumed){refreshCapture();refreshRefinement();main.postDelayed(this,250);}}};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); playback=new LocalPlayback(this);
        if(saved!=null){library=saved.getBoolean("library");query=saved.getString("query","");exportId=saved.getString("exportId");exportKind=saved.getString("exportKind");}
        draw();
        io.execute(() -> {
            try { if(!captureActive())Recordings.get(this).recoverInterrupted();
                RefinementJobService.schedule(this);
                main.post(() -> {if(destroyed)return;ready=true;if(library)loadLibrary();else{refreshCapture();loadHomeHistory();}resumeStart();});
            } catch(Exception error){main.post(() -> error("Private library could not be opened. No files were deleted. Close and reopen the app."));}
        });
    }
    @Override protected void onResume(){super.onResume();resumed=true;main.removeCallbacks(ticker);main.post(ticker);if(ready){if(library)loadLibrary();else loadHomeHistory();}main.post(this::resumeStart);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(ticker);playback.stop();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;viewGeneration++;playback.stop();io.shutdown();main.removeCallbacks(ticker);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("library",library);out.putString("query",query);out.putString("exportId",exportId);out.putString("exportKind",exportKind);super.onSaveInstanceState(out);}

    private int dp(float value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private GradientDrawable box(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));v.setPadding(0,dp(4),0,dp(4));return v;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void gap(LinearLayout target,int height){View v=new View(this);target.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(String label, boolean primary, View.OnClickListener click){Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:INK);b.setBackground(box(primary?INK:0xffe5e8df,14));b.setMinHeight(dp(52));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setOnClickListener(click);return b;}
    private void addButton(LinearLayout target,Button b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.topMargin=dp(10);target.addView(b,p);}
    private LinearLayout card(int color){LinearLayout c=column();c.setBackground(box(color,20));c.setPadding(dp(20),dp(20),dp(20),dp(20));return c;}

    private void draw(){
        viewGeneration++;selectedId=null;playback.stop();
        page=column();page.setBackgroundColor(PAPER);page.setPadding(dp(22),dp(14),dp(22),dp(12));setContentView(page);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("NotTheOmiAIApp",23,INK,true);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button info=button("?",false,v -> about());info.setContentDescription("Privacy and help");top.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));page.addView(top);
        page.addView(text("OMI + PHONE  /  PRIVATE BY DESIGN",10,MUTED,true));gap(page,12);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(library)drawLibrary();else drawCapture();
        gap(page,10);LinearLayout tabs=new LinearLayout(this);
        Button record=button("●  Home",!library,v -> {library=false;draw();});Button saved=button("≡  Library",library,v -> {library=true;draw();});
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(52),1);left.rightMargin=dp(6);tabs.addView(record,left);tabs.addView(saved,new LinearLayout.LayoutParams(0,dp(52),1));page.addView(tabs);
    }
    private static boolean captureActive(){return CaptureService.active||OmiCaptureService.active;}
    private boolean omiSource(){return OmiCaptureService.active||(!CaptureService.active&&!"phone".equals(OmiSettingsActivity.preferences(this).getString("source","omi")));}
    private String readyText(){
        return omiSource()?"Connect your Omi. Live words appear here as you speak.":"Start the phone microphone to see live words here.";
    }
    private void chooseSource(){
        if(captureActive()||startPending)return;
        new AlertDialog.Builder(this).setTitle("Recording source")
            .setSingleChoiceItems(new String[]{"Omi wearable","Phone microphone"},omiSource()?0:1,(d,w)->{
                OmiSettingsActivity.preferences(this).edit().putString("source",w==0?"omi":"phone").apply();d.dismiss();draw();
            }).setNegativeButton("Cancel",null).show();
    }
    private void drawCapture(){
        displayRevision=-1;wasActive=captureActive();
        content.addView(text(omiSource()?"Your Omi. On your phone.":"Your phone. Your words.",24,INK,true));
        content.addView(text("Live words, saved audio. No PC or cloud.",14,MUTED,false));gap(content,10);
        LinearLayout capture=card(INK);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        connectionLabel=text("OMI WEARABLE",12,0xffd2dcc7,true);
        heading.addView(connectionLabel,new LinearLayout.LayoutParams(0,-2,1));
        timer=text("00:00",26,PAPER,true);heading.addView(timer);capture.addView(heading);
        stateLabel=text("Opening private library…",14,0xffd2dcc7,false);capture.addView(stateLabel);
        meter=new Meter();capture.addView(meter,new LinearLayout.LayoutParams(-1,dp(20)));
        recordButton=button("Connect Omi",true,v->toggleRecording());recordButton.setBackground(box(ACCENT,14));addButton(capture,recordButton);
        capture.addView(text(omiSource()?"Connecting starts recording automatically. Stop saves locally.":"Phone microphone fallback. Stop saves locally.",12,0xffc3cbb8,false));content.addView(capture);
        deviceButton=button("Omi device & controls",false,v->startActivity(new Intent(this,OmiSettingsActivity.class)));
        addButton(content,deviceButton);gap(content,14);
        content.addView(text("LIVE TRANSCRIPTION",11,MUTED,true));
        LinearLayout live=card(Color.WHITE);
        preview=text(readyText(),21,INK,false);preview.setMinHeight(dp(62));preview.setTextIsSelectable(true);live.addView(preview);
        liveHint=text("Fast offline draft · Whisper refines after saving",11,MUTED,false);live.addView(liveHint);content.addView(live);gap(content,14);
        content.addView(text("TRANSCRIPTION LOG",11,MUTED,true));
        finalHint=text("This session · finished phrases are saved as you speak",12,MUTED,false);content.addView(finalHint);
        finalText=text("Finished phrases will appear here. Your complete transcript stays in the library.",16,INK,false);
        finalText.setTextIsSelectable(true);finalText.setLineSpacing(dp(3),1.06f);content.addView(finalText);gap(content,14);
        content.addView(text("SAVED ON THIS PHONE",11,MUTED,true));
        historyHint=text("Opening local history…",12,MUTED,false);content.addView(historyHint);
        historyRows=column();content.addView(historyRows);
        addButton(content,button("View all recordings & transcripts",false,v->{library=true;draw();}));gap(content,14);
        sourceButton=button(omiSource()?"Source: Omi wearable ▾":"Source: Phone microphone ▾",false,v->chooseSource());addButton(content,sourceButton);
        gap(content,10);content.addView(text("Record with everyone’s permission. Audio and transcripts stay encrypted here until you delete them.",12,MUTED,false));
        refreshCapture();if(ready)loadHomeHistory();
    }
    private static void setText(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void refreshCapture(){
        if(library||selectedId!=null||timer==null)return;
        boolean active=captureActive(), omi=omiSource();
        String state=omi?OmiCaptureService.state:CaptureService.state;
        if(active)startPending=false;
        long started=omi?OmiCaptureService.startedAt:CaptureService.startedAt;
        setText(timer,duration(active&&started>0?SystemClock.elapsedRealtime()-started:0));
        sourceButton.setEnabled(!active&&!startPending);
        deviceButton.setVisibility(omi?View.VISIBLE:View.GONE);deviceButton.setEnabled(!startPending);
        String name=OmiSettingsActivity.preferences(this).getString("name","Omi wearable");
        setText(connectionLabel,omi?(name.isEmpty()?"OMI WEARABLE":name)+(active&&OmiCaptureService.battery>=0?" · "+OmiCaptureService.battery+"%":""):"PHONE MICROPHONE");
        setText(stateLabel,!ready?"Opening private library…":startPending?(omi?"Connecting…":"Starting…"):state==null||state.isEmpty()||"Stopped".equals(state)?(omi?"Ready to connect · auto-record on connection":"Phone microphone is off"):state);
        recordButton.setEnabled(ready&&!startPending);
        setText(recordButton,active?(omi?(started>0?"Disconnect & save":"Cancel connection"):"■  Stop & save"):(omi?"Connect Omi":"●  Start recording"));
        LiveTranscript.Snapshot display=(omi?OmiCaptureService.display:CaptureService.display).snapshot();
        if(display.revision!=displayRevision||omi!=displayWasOmi){
            displayRevision=display.revision;displayWasOmi=omi;
            setText(finalText,display.finalized.isEmpty()?"Finished phrases will appear here. Your complete transcript stays in the library.":display.finalized);
        }
        setText(finalHint,display.finalized.isEmpty()?"This session · finished phrases are saved as you speak":(active?"This session":"Last session")+" · recent phrases · full transcript in Library");
        setText(preview,active?(display.partial.isEmpty()?(started>0?"Listening…":omi?"Waiting for Omi audio…":"Preparing offline speech…"):display.partial):readyText());
        setText(liveHint,active?"LIVE DRAFT · Vosk · English · offline":"Fast offline draft · Whisper refines after saving");
        meter.level=active?(omi?OmiCaptureService.level:CaptureService.level):0;meter.invalidate();
        if(wasActive&&!active&&ready)loadHomeHistory();
        wasActive=active;
    }
    private void loadHomeHistory(){
        if(destroyed||library||selectedId!=null||!ready)return;
        final int generation=viewGeneration, request=++historyGeneration;
        io.execute(()->{
            try{
                List<Recordings.Session> rows=Recordings.get(this).recent(6);
                main.post(()->{
                    if(destroyed||library||selectedId!=null||generation!=viewGeneration||request!=historyGeneration)return;
                    historyRows.removeAllViews();
                    setText(historyHint,rows.isEmpty()?"No saved sessions yet. Connect Omi to begin.":"Recent sessions · tap to read or play · all history in Library");
                    for(Recordings.Session s:rows){
                        LinearLayout c=card(Color.WHITE);c.addView(text(s.title,17,INK,true));
                        c.addView(text(date(s.createdAt)+"  ·  "+duration(s.durationMs)+"  ·  "+s.status,11,MUTED,false));
                        TextView excerpt=text(s.text.isEmpty()?"No speech transcribed — open for saved audio":s.text,14,INK,false);excerpt.setMaxLines(4);excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);c.addView(excerpt);
                        c.setContentDescription("Open saved transcript "+s.title);c.setOnClickListener(v->detail(s.id));historyRows.addView(c);gap(historyRows,8);
                    }
                });
            }catch(Exception e){main.post(()->{if(!destroyed&&!library&&selectedId==null&&generation==viewGeneration&&request==historyGeneration)setText(historyHint,"Local history could not be read. Your files were not changed.");});}
        });
    }
    private void continueStart(){resumeStart=true;if(resumed)main.post(this::resumeStart);}
    private void resumeStart(){if(!resumeStart||!resumed||!ready||destroyed)return;resumeStart=false;if(!captureActive())toggleRecording();}
    private void toggleRecording(){
        if(captureActive()){startService(new Intent(this,OmiCaptureService.active?OmiCaptureService.class:CaptureService.class).setAction(OmiCaptureService.active?OmiCaptureService.ACTION_STOP:CaptureService.ACTION_STOP));return;}
        if(!ready||startPending)return;
        if(omiSource()){
            if(OmiSettingsActivity.preferences(this).getString("address","").isEmpty()){
                startActivityForResult(new Intent(this,OmiSettingsActivity.class).putExtra(OmiSettingsActivity.CONNECT_AFTER_SELECTION,true),30);return;
            }
            if(!OmiSettingsActivity.permitted(this)){requestPermissions(OmiSettingsActivity.permissions(),12);return;}
        }
        if(!omiSource()&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            new AlertDialog.Builder(this).setTitle("Use your phone microphone?").setMessage("Audio and English transcripts stay encrypted on this phone. Allowing permission starts this recording. Stop from the app or its notification.")
                .setNegativeButton("Not now",null).setPositiveButton("Allow microphone",(d,w)->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10)).show();return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        confirmNotificationAccess();
    }
    private boolean notificationsAvailable(){
        android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel(omiSource()?"omi_capture":"phone_capture");
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=android.app.NotificationManager.IMPORTANCE_NONE);
    }
    private void confirmNotificationAccess(){
        if(destroyed||!resumed||captureActive()||startPending)return;
        if(notificationsAvailable()){startCapture();return;}
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Notification Stop is unavailable")
            .setMessage(omiSource()?"Enable notifications before recording from Omi. The phone must show recording status and a Stop control.":"Notifications are disabled. Recording can continue outside the app, but you must return here to Stop & save. Enable notifications for a visible Stop control.")
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Settings",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        if(!omiSource())dialog.setPositiveButton("Record anyway",(d,w)->startCapture());
        dialog.show();
    }
    private void startCapture(){
        if(destroyed||!resumed||captureActive()||startPending)return;
        playback.stop();startPending=true;refreshCapture();
        boolean omi=omiSource();
        try {startForegroundService(new Intent(this,omi?OmiCaptureService.class:CaptureService.class).setAction(omi?OmiCaptureService.ACTION_START:CaptureService.ACTION_START));}
        catch(RuntimeException e){startPending=false;error("Android could not start recording. Keep the app open and check permissions for the selected source.");}
        main.postDelayed(()->{if(!destroyed){startPending=false;refreshCapture();}},2500);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==10){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)continueStart();else error("Microphone permission is required to record. You can still browse your library.");}else if(code==11)continueStart();else if(code==12){if(OmiSettingsActivity.permitted(this))continueStart();else error("Nearby devices permission is required for Omi. Your phone microphone will not be used instead.");}}

    private void drawLibrary(){
        content.addView(text("Your library",32,INK,true));
        storageLabel=text("Kept until you delete. No sync queue.",13,MUTED,false);content.addView(storageLabel);gap(content,10);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Search titles or transcripts");search.setTextSize(15);search.setTextColor(INK);search.setPadding(dp(14),dp(10),dp(14),dp(10));search.setBackground(box(0xffe5e8df,12));search.setText(query);search.setContentDescription("Search recordings");content.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){} public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();final int g=++searchGeneration;main.postDelayed(()->{if(g==searchGeneration&&library)loadLibrary();},250);}public void afterTextChanged(Editable s){}});
        gap(content,12);libraryRows=column();content.addView(libraryRows);if(ready)loadLibrary();else libraryRows.addView(text("Opening private library…",15,MUTED,false));
    }
    private void loadLibrary(){
        if(!library||selectedId!=null||destroyed)return;
        final int generation=viewGeneration, search=++searchGeneration;final String term=query;
        io.execute(()->{
            try {Recordings db=Recordings.get(this);List<Recordings.Session> rows=db.list(term);long bytes=db.totalBytes();
                main.post(()->{if(destroyed||!library||generation!=viewGeneration||search!=searchGeneration||selectedId!=null)return;
                    storageLabel.setText(size(bytes)+" stored · 2 GB audio limit\nKept until you delete. Export important recordings.");
                    libraryRows.removeAllViews();
                    if(rows.isEmpty()){LinearLayout empty=card(0xffe9eadd);empty.addView(text(term.isEmpty()?"A little space for your thoughts.":"No matches",21,INK,true));empty.addView(text(term.isEmpty()?"Your recordings and transcripts will appear here. Start with a short voice note.":"Try a different word from the title or transcript.",15,MUTED,false));libraryRows.addView(empty);}
                    for(Recordings.Session s:rows){LinearLayout c=card(Color.WHITE);c.addView(text(s.title,19,INK,true));c.addView(text(date(s.createdAt)+"  ·  "+duration(s.durationMs),12,MUTED,false));TextView excerpt=text(s.text.isEmpty()?"No speech transcribed":s.text,14,MUTED,false);excerpt.setMaxLines(2);c.addView(excerpt);c.addView(text(s.status.toUpperCase(Locale.ROOT),10,ACCENT,true));c.setContentDescription("Open recording "+s.title);c.setOnClickListener(v->detail(s.id));libraryRows.addView(c);gap(libraryRows,10);}
                });
            }catch(Exception e){main.post(()->error("Could not read the encrypted library. Your files have not been changed."));}
        });
    }
    private void detail(String id){
        playback.stop();selectedId=id;final int generation=++viewGeneration;
        io.execute(()->{try{Recordings.Session s=Recordings.get(this).find(id);main.post(()->{if(!destroyed&&generation==viewGeneration)showDetail(s);});}catch(Exception e){main.post(()->error("This recording could not be opened. No data was changed."));}});
    }
    private void showDetail(Recordings.Session s){
        if(s==null){selectedId=null;draw();return;}
        content.removeAllViews();addButton(content,button(library?"‹  Back to library":"‹  Back to home",false,v->{selectedId=null;draw();}));gap(content,12);
        content.addView(text(s.title,28,INK,true));content.addView(text(date(s.createdAt)+"  ·  "+duration(s.durationMs)+"  ·  "+s.status,12,MUTED,false));
        if((s.id.equals(CaptureService.sessionId)&&CaptureService.active)||(s.id.equals(OmiCaptureService.sessionId)&&OmiCaptureService.active)){content.addView(text("Recording in progress. Stop & save before playback, rename, export or delete.",15,ACCENT,true));addButton(content,button("Go to recorder",true,v->{library=false;draw();}));}
        else {
            Button play=button("▶  Play recording",true,null);play.setOnClickListener(v->{if(playback.playing){playback.stop();play.setText("▶  Play recording");}else {if(captureActive()){error("Stop recording before playback to prevent feedback.");return;}play.setText("■  Stop playback");playback.play(s.id,()->{if(!destroyed)play.setText("▶  Play recording");},this::error);}});addButton(content,play);
            LinearLayout actions=new LinearLayout(this);Button txt=button("Export text",false,v->confirmExport(s.id,"text"));Button wav=button("Export audio",false,v->confirmExport(s.id,"wav"));LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(52),1);half.rightMargin=dp(6);actions.addView(txt,half);actions.addView(wav,new LinearLayout.LayoutParams(0,dp(52),1));gap(content,10);content.addView(actions);
            addButton(content,button("Rename",false,v->rename(s)));addButton(content,button("Delete recording",false,v->delete(s)));
        }
        gap(content,20);content.addView(text("TRANSCRIPT",11,MUTED,true));
        detailRefinement=text(refinementLabel(s),12,ACCENT,true);content.addView(detailRefinement);
        detailRetry=button("Refine / retry with Whisper",false,v->retryRefinement(s.id));
        detailRetry.setVisibility(canRetry(s)?View.VISIBLE:View.GONE);addButton(content,detailRetry);
        addButton(content,button("View original live draft",false,v->new AlertDialog.Builder(this).setTitle("Original live draft").setMessage(s.liveText.isEmpty()?"No live draft was saved.":s.liveText).setPositiveButton("Close",null).show()));
        detailTranscript=text(transcriptText(s),18,INK,false);detailTranscript.setTextIsSelectable(true);detailTranscript.setLineSpacing(dp(3),1.08f);content.addView(detailTranscript);gap(content,20);
        final int generation=viewGeneration;ScrollView scroll=(ScrollView)content.getParent();
        scroll.post(()->{if(!destroyed&&generation==viewGeneration&&s.id.equals(selectedId))scroll.scrollTo(0,0);});
    }
    private void rename(Recordings.Session s){EditText name=new EditText(this);name.setText(s.title);name.setSingleLine();name.setSelectAllOnFocus(true);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});new AlertDialog.Builder(this).setTitle("Rename recording").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{String title=name.getText().toString().trim();if(title.isEmpty()){error("Enter a title.");return;}io.execute(()->{try{Recordings.get(this).rename(s.id,title);main.post(()->{if(!destroyed)detail(s.id);});}catch(Exception e){main.post(()->error("Rename was not saved."));}});}).show();}
    private static String transcriptText(Recordings.Session s){return s.text.isEmpty()?"No speech was recognised. Your saved audio is still available.":s.text;}
    private static boolean canRetry(Recordings.Session s){return !"recording".equals(s.status)&&("failed".equals(s.transcriptState)||"none".equals(s.transcriptState));}
    private static String refinementLabel(Recordings.Session s){
        if("complete".equals(s.transcriptState))return "WHISPER · refined offline · original draft retained";
        if("pending".equals(s.transcriptState))return "LIVE DRAFT · Whisper queued / refining\n"+RefinementJobService.state;
        if("failed".equals(s.transcriptState))return "LIVE DRAFT · Whisper failed · audio retained; retry available";
        return "SAVED TRANSCRIPT · not refined with this workflow";
    }
    private void retryRefinement(String id){
        io.execute(()->{try{Recordings.get(this).retryRefinement(id);RefinementJobService.schedule(this);main.post(()->{if(!destroyed&&id.equals(selectedId))detail(id);});}
            catch(Exception failure){main.post(()->error("Whisper could not be queued. Original audio and draft were not removed."));}});
    }
    private void refreshRefinement(){
        long revision=RefinementJobService.revision;if(!ready||destroyed||refinementRevision==revision)return;refinementRevision=revision;
        if(selectedId==null){if(library)loadLibrary();else loadHomeHistory();return;}
        final String id=selectedId;final int generation=viewGeneration;
        io.execute(()->{try{Recordings.Session s=Recordings.get(this).find(id);main.post(()->{
            if(destroyed||s==null||generation!=viewGeneration||!id.equals(selectedId)||detailTranscript==null)return;
            setText(detailTranscript,transcriptText(s));setText(detailRefinement,refinementLabel(s));detailRetry.setVisibility(canRetry(s)?View.VISIBLE:View.GONE);
        });}catch(Exception ignored){/* Do not replace retained UI with a failed read. */}});
    }
    private void delete(Recordings.Session s){new AlertDialog.Builder(this).setTitle("Delete this recording?").setMessage("Permanently removes its audio and transcript from this app. Files you previously exported are not removed.").setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->playback.stop(()->{if(destroyed)return;io.execute(()->{try{Recordings.get(this).delete(s.id);if(s.id.equals(OmiCaptureService.display.snapshot().sessionId))OmiCaptureService.display.reset(null);if(s.id.equals(CaptureService.display.snapshot().sessionId))CaptureService.display.reset(null);main.post(()->{if(!destroyed){selectedId=null;draw();}});}catch(Exception e){main.post(()->error("Deletion could not be completed. Reopen the library to check its state."));}});})).show();}
    private void confirmExport(String id,String kind){new AlertDialog.Builder(this).setTitle("Export an unencrypted copy?").setMessage("Only your chosen file will leave the private library. The destination you select may sync to a cloud service. Keep this copy somewhere you trust.").setNegativeButton("Cancel",null).setPositiveButton("Choose destination",(d,w)->{exportId=id;exportKind=kind;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind.equals("wav")?"audio/wav":"text/plain").putExtra(Intent.EXTRA_TITLE,"NotTheOmiAIApp-"+id+(kind.equals("wav")?".wav":".txt"));try{startActivityForResult(intent,20);}catch(RuntimeException e){exportId=null;exportKind=null;error("No document picker is available on this phone.");}}).show();}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==30){if(result==RESULT_OK)continueStart();return;}if(code!=20)return;final String id=exportId,kind=exportKind;exportId=null;exportKind=null;if(result!=RESULT_OK||data==null||data.getData()==null||id==null||kind==null)return;Uri uri=data.getData();io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IllegalStateException();if(kind.equals("wav"))Recordings.get(this).exportWav(id,out);else Recordings.get(this).exportText(id,out);out.flush();main.post(()->toast("Export saved to your chosen destination."));}catch(Exception e){main.post(()->error("Export failed. The destination may contain a partial file; remove it before retrying."));}});}
    private void about(){new AlertDialog.Builder(this).setTitle("NotTheOmiAIApp 0.4.1").setMessage("An independent offline Omi companion, with optional phone-microphone recording. Not affiliated with Omi or Based Hardware.\n\n• No internet permission, accounts or analytics.\n• Omi BLE Opus audio and English speech recognition run on this phone (Concentus + Vosk live preview; Whisper small.en Q5_1 after saving). Live drafts update as you speak. Saved audio is refined locally while capture is idle; Android may defer background work. Reopen the app after a reboot or force-stop to resume queued work. No AI summaries yet.\n• Device settings: brightness read/write where supported, local single/double press actions while recording. No firmware update or device-storage download.\n• Audio and transcripts encrypted using this phone’s Android Keystore. Device backups and transfer are disabled.\n• Uninstalling or clearing app data destroys access. Export anything important first.\n• Storage: 2 GB audio limit, no automatic deletion. The app stops safely when storage is low.\n• Recording can continue with the screen off. Stop in the app, or in its notification when enabled. Android or Samsung battery controls can interrupt it.\n• Exports are unencrypted copies.\n\nWhisper.cpp / English model: MIT. Concentus: BSD-style. Omi protocol: MIT. Licenses included.").setNegativeButton("Close",null).setPositiveButton("Licenses",(d,w)->licenses()).show();}
    private void licenses(){try{String value;try(java.io.InputStream in=getAssets().open("licenses/whisper.cpp-MIT.txt")){value=readUtf8(in);}try(java.io.InputStream in=getAssets().open("licenses/whisper-model-MIT.txt")){value+="\n\nWhisper model\n"+readUtf8(in);}for(String name:new String[]{"vosk","jna","concentus","omi"})try(java.io.InputStream in=getAssets().open("licenses/"+name+"-license.txt")){value+="\n\n"+name+"\n"+readUtf8(in);}TextView t=text(value,12,INK,false);t.setPadding(dp(20),dp(10),dp(20),dp(10));ScrollView scroll=new ScrollView(this);scroll.addView(t);new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Close",null).show();}catch(Exception e){error("License files could not be opened.");}}
    private static String readUtf8(java.io.InputStream in) throws java.io.IOException { java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8); }
    private void error(String message){if(!destroyed&&!isFinishing())new AlertDialog.Builder(this).setTitle("Please check").setMessage(message).setPositiveButton("OK",null).show();}
    private void toast(String s){if(!destroyed)Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private static String duration(long millis){long sec=Math.max(0,millis/1000);return sec>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",sec/3600,(sec/60)%60,sec%60):String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private static String size(long bytes){return String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0);}
    private static String date(long time){return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new java.util.Date(time));}
    @Override public void onBackPressed(){if(selectedId!=null){selectedId=null;draw();}else if(library){library=false;draw();}else super.onBackPressed();}
    private final class Meter extends View {
        float level;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Meter(){super(MainActivity.this);setContentDescription("Live recording audio level");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(0xffd6dfbe);float center=getHeight()/2f;int bars=35;float spacing=getWidth()/(float)bars;
            for(int i=0;i<bars;i++){float envelope=(float)Math.sin((i+1)*Math.PI/(bars+1));float height=dp(3)+Math.min(1,level)*dp(36)*envelope;c.drawRoundRect(i*spacing,center-height/2,i*spacing+Math.max(dp(2),spacing-dp(4)),center+height/2,dp(2),dp(2),paint);}}
    }
}
