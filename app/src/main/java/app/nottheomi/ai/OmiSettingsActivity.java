package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opening settings/discovery never connects or records. Idle controls require an explicit tap. */
public final class OmiSettingsActivity extends Activity {
    public static final String CONNECT_AFTER_SELECTION="connect_after_selection";
    private boolean connectAfterSelection, initialScan;
    private static final int INK=0xff191c18, MUTED=0xff646a61, PAPER=0xfff5f2eb;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> seen=new HashSet<>();
    private LinearLayout page, devices;
    private TextView selected, scanStatus, live, led, desired, presses, shortcutStatus;
    private Button scanButton, forgetButton, readButton, applyButton, controlsButton, singleTarget, doubleTarget;
    private Spinner singleAction, doubleAction;
    private int pendingGesture;
    private ComponentName pendingProvider;
    private boolean pendingControls;
    private static final int SHORTCUT_REQUEST=41, CONTROLS_PERMISSION=42, NOTIFICATION_PERMISSION=43;
    private SeekBar brightness;
    private OmiBle scanner;
    private boolean resumed, scanning;
    private int scanGeneration;
    private final Runnable ticker=new Runnable(){public void run(){if(resumed){refresh();main.postDelayed(this,750);}}};

    public static SharedPreferences preferences(Context context){return context.getSharedPreferences("omi",MODE_PRIVATE);}
    public static boolean permitted(Context context){
        if(Build.VERSION.SDK_INT>=31)return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
            &&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    public static String[] permissions(){return Build.VERSION.SDK_INT>=31
        ?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}
        :new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        restorePending(state);
        connectAfterSelection=getIntent().getBooleanExtra(CONNECT_AFTER_SELECTION,false);
        initialScan=connectAfterSelection&&state==null;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22),dp(18),dp(22),dp(24));page.setBackgroundColor(PAPER);scroll.addView(page);setContentView(scroll);
        addButton("‹  Back to home",()->finish());label(connectAfterSelection?"Connect your Omi":"Omi device & controls",26,INK);
        label("Audio goes from your Omi to this phone. No Omi account, PC or cloud. Close other Omi apps before connecting.",14,MUTED);
        selected=label("",15,INK);
        scanButton=addButton("Find nearby Omi",this::scan);
        forgetButton=addButton("Forget selected device",()->{
            if(connectionBusy())return;
            preferences(this).edit().remove("address").remove("name").apply();refresh();
        });
        scanStatus=label(connectAfterSelection?"Choose your Omi to connect and start recording automatically. Scanning alone does not record.":"Select a device, then connect for buttons below, or start recording from Home. Scanning does not record audio.",13,MUTED);
        devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);page.addView(devices);
        label("LIVE CONNECTION",12,MUTED);live=label("Disconnected",14,INK);
        live.setTag("omi.connection.status");
        controlsButton=addButton("Connect for buttons — no recording",this::toggleControls);
        controlsButton.setTag("omi.controls.connect");
        label("Button-only mode stays connected to your Omi while idle. No audio is saved until you choose Start recording or Start / stop recording. Stop & save returns to button-only mode; Disconnect buttons ends the idle connection.",13,MUTED);
        label("Light brightness",22,INK);
        label("Available on an existing recording or button-only connection, if your firmware supports it. First read the current value. Changing the wearable light does not change recording mode.",13,MUTED);
        led=label("",14,INK);readButton=addButton("Read current brightness",OmiCaptureService::readLedBrightness);
        brightness=new SeekBar(this);brightness.setMax(100);brightness.setProgress(50);brightness.setContentDescription("Desired Omi light brightness");page.addView(brightness);
        desired=label("Desired brightness: 50%",14,INK);
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){desired.setText("Desired brightness: "+value+"%");}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        applyButton=addButton("Apply brightness",()->{
            int value=brightness.getProgress();
            new AlertDialog.Builder(this).setTitle("Set Omi light to "+value+"%?")
                .setMessage((value==0?"This requests minimum brightness; firmware-owned indicators may remain. ":"")+"Recording mode is unchanged, with a visible phone connection notification. Readback confirms the current value, not persistence after power-off.")
                .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->OmiCaptureService.setLedBrightness(value)).show();
        });
        label("Button presses",22,INK);
        label("Single and double press have separate settings. Changes apply to the next press, including during recording. A recording or button-only connection is required. Long press / power actions stay with the device; these settings do not rewrite firmware.",13,MUTED);
        singleAction=actionSpinner(ButtonEvent.SINGLE,"Single press");
        singleTarget=addButton("",()->chooseTarget(ButtonEvent.SINGLE));singleTarget.setTag("omi.target.single");
        doubleAction=actionSpinner(ButtonEvent.DOUBLE,"Double press");
        doubleTarget=addButton("",()->chooseTarget(ButtonEvent.DOUBLE));doubleTarget.setTag("omi.target.double");
        label("Music controls act directly, like a headset button — no app opening or notification tap. Android chooses the current media player; it must support media buttons. Pause music never requests Play. Start / stop recording starts when idle and stops and saves when recording.",13,MUTED);
        label("Phone shortcuts can open launchable apps or supported legacy shortcut providers. Android does not let this app list every launcher-managed private/dynamic shortcut. Only exported, permitted activities in the chosen app are supported; no services, broadcasts, URI permission grants or shell commands. Other apps may use the network.",13,MUTED);
        label("For Phone shortcut only: when this app is visibly open, it requests the selected activity. In the background or on the lock screen, press once then tap the phone notification to run it. Recording and music actions do not need that tap.",13,MUTED);
        shortcutStatus=label("",13,MUTED);shortcutStatus.setTag("omi.shortcut.status");
        presses=label("",14,INK);
        label("Compatibility: stock Omi BLE with 16 kHz Opus audio. Unsupported codecs and missing controls are reported, never guessed. Gaps stop and save rather than silently joining missing audio.",13,MUTED);
        refresh();
    }
    @Override protected void onResume(){super.onResume();ButtonActions.onResume(this);resumed=true;syncSpinner(singleAction,ButtonEvent.SINGLE);syncSpinner(doubleAction,ButtonEvent.DOUBLE);refresh();main.post(ticker);if(initialScan){initialScan=false;main.post(this::scan);}}
    @Override protected void onPause(){ButtonActions.onPause(this);resumed=false;main.removeCallbacks(ticker);stopScan();super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(10),0,dp(6));if(size>=22)t.setTypeface(null,Typeface.BOLD);page.addView(t);return t;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(INK);GradientDrawable bg=new GradientDrawable();bg.setColor(0xffe5e8df);bg.setCornerRadius(dp(12));b.setBackground(bg);b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setMinHeight(dp(50));b.setOnClickListener(v->action.run());return b;}
    private Button addButton(String title,Runnable action){Button b=button(title,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);page.addView(b,p);return b;}
    private void refresh(){
        SharedPreferences p=preferences(this);String address=p.getString("address","");
        selected.setText(address.isEmpty()?"No Omi selected":p.getString("name","Omi")+"\n"+address);
        boolean active=OmiCaptureService.active, busy=connectionBusy();
        scanButton.setEnabled(!busy);scanButton.setText(scanning?"Stop scanning":"Find nearby Omi");
        forgetButton.setEnabled(!busy&&!address.isEmpty());
        for(int i=0;i<devices.getChildCount();i++)devices.getChildAt(i).setEnabled(!busy);
        controlsButton.setEnabled(!active&&(OmiCaptureService.controlsActive||!address.isEmpty()));
        controlsButton.setText(OmiCaptureService.controlsActive?"Disconnect buttons":"Connect for buttons — no recording");
        live.setText(connectionStatus()+"\n"+OmiCaptureService.transport+(OmiCaptureService.battery>=0?" · Battery "+OmiCaptureService.battery+"%":" · Battery unknown"));
        OmiBle.LedState value=OmiCaptureService.ledState;
        led.setText(value==null?"Not connected":value.message+(value.brightness>=0?"\nReported brightness: "+value.brightness+"%":""));
        readButton.setEnabled(busy&&value!=null&&!value.busy);
        boolean writable=busy&&value!=null&&value.supported&&!value.busy&&value.brightness>=0;
        applyButton.setEnabled(writable);brightness.setEnabled(writable);
        refreshAction(singleAction,singleTarget,ButtonEvent.SINGLE);
        refreshAction(doubleAction,doubleTarget,ButtonEvent.DOUBLE);
        presses.setText(OmiCaptureService.lastButton);
    }

    private static boolean connectionBusy(){return OmiCaptureService.active||OmiCaptureService.controlsActive;}
    static String connectionStatus(){
        if(OmiCaptureService.active)return OmiCaptureService.state;
        if(OmiCaptureService.controlsActive)return "Button-only connection — no audio saved";
        return "Disconnected — no audio saved";
    }
    private Spinner actionSpinner(int event,String title){
        TextView heading=label(title,16,INK);
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        spinner.setId(View.generateViewId());heading.setLabelFor(spinner.getId());
        spinner.setTag(event==ButtonEvent.SINGLE?"omi.action.single":"omi.action.double");
        spinner.setContentDescription(title+" action");spinner.setSaveEnabled(false);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,ButtonActions.LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);
        spinner.setSelection(ButtonActions.index(ButtonActions.action(this,event)),false);
        spinner.setMinimumHeight(dp(48));page.addView(spinner,new LinearLayout.LayoutParams(-1,-2));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(position<0||position>=ButtonActions.IDS.length)return;
                String chosen=ButtonActions.IDS[position];
                if(!chosen.equals(ButtonActions.action(OmiSettingsActivity.this,event)))
                    ButtonActions.setAction(OmiSettingsActivity.this,event,chosen);
                if(singleTarget!=null&&doubleTarget!=null)refresh();
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        return spinner;
    }
    private void syncSpinner(Spinner spinner,int event){
        int index=ButtonActions.index(ButtonActions.action(this,event));
        if(spinner.getSelectedItemPosition()!=index)spinner.setSelection(index,false);
    }
    private void refreshAction(Spinner spinner,Button target,int event){
        // Never reset selection on the status ticker: a user selection may await its layout callback.
        spinner.setEnabled(true);
        boolean shortcut="shortcut".equals(ButtonActions.action(this,event));
        target.setVisibility(shortcut?View.VISIBLE:View.GONE);
        target.setText("Choose / change: "+ButtonActions.targetLabel(this,event));
        target.setEnabled(pendingProvider==null);
    }
    private void chooseTarget(int event){
        if(!ButtonActions.gesture(event)||pendingProvider!=null)return;
        List<ButtonActions.Choice> choices;
        try{choices=ButtonActions.choices(this);}catch(RuntimeException unavailable){shortcutStatus.setText("Phone apps could not be listed. Your previous target is unchanged.");return;}
        if(choices.isEmpty()){shortcutStatus.setText("No supported exported shortcut providers or launchable apps are available. Your previous target is unchanged.");return;}
        String[] labels=new String[choices.size()];for(int i=0;i<labels.length;i++)labels[i]=choices.get(i).toString();
        new AlertDialog.Builder(this).setTitle(event==ButtonEvent.SINGLE?"Single press phone shortcut":"Double press phone shortcut")
            .setItems(labels,(dialog,index)->beginTargetSelection(event,choices.get(index)))
            .setNegativeButton("Cancel",null).show();
    }
    void beginTargetSelection(int event,ButtonActions.Choice choice){
        if(!ButtonActions.gesture(event)||choice==null||pendingProvider!=null)return;
        if(!choice.provider){
            boolean saved=ButtonActions.saveTarget(this,event,choice.intent(),choice.label,choice.component.getPackageName());
            shortcutStatus.setText(saved?"Phone app selected for "+(event==ButtonEvent.SINGLE?"single":"double")+" press.":"App unavailable or unsupported. Your previous target is unchanged.");
            refresh();return;
        }
        if(!ButtonActions.providerAvailable(this,choice.component)){shortcutStatus.setText("Shortcut provider unavailable. Your previous target is unchanged.");return;}
        pendingGesture=event;pendingProvider=choice.component;
        try{startActivityForResult(choice.intent(),SHORTCUT_REQUEST);}
        catch(RuntimeException unavailable){pendingGesture=0;pendingProvider=null;shortcutStatus.setText("Shortcut provider could not open. Your previous target is unchanged.");}
        refresh();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SHORTCUT_REQUEST)return;
        int event=pendingGesture;ComponentName provider=pendingProvider;pendingGesture=0;pendingProvider=null;
        boolean saved=ButtonActions.acceptProviderResult(this,event,provider,result,data);
        shortcutStatus.setText(saved?"Phone shortcut selected for "+(event==ButtonEvent.SINGLE?"single":"double")+" press.":
            result==RESULT_CANCELED?"Shortcut selection cancelled. Your previous target is unchanged.":"Unsupported shortcut result. Your previous target is unchanged.");
        refresh();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        state.putInt("button.pending_gesture",pendingGesture);
        if(pendingProvider!=null)state.putString("button.pending_provider",pendingProvider.flattenToString());
        state.putBoolean("button.pending_controls",pendingControls);
    }
    void restorePending(Bundle state){
        pendingGesture=0;pendingProvider=null;pendingControls=false;
        if(state==null)return;
        int event=state.getInt("button.pending_gesture",0);String provider=state.getString("button.pending_provider","");
        if(ButtonActions.gesture(event)&&provider!=null&&provider.length()<=512){
            ComponentName component=ComponentName.unflattenFromString(provider);
            if(component!=null){pendingGesture=event;pendingProvider=component;}
        }
        pendingControls=state.getBoolean("button.pending_controls",false);
    }
    private void toggleControls(){
        if(OmiCaptureService.active){scanStatus.setText("Stop & save before disconnecting buttons.");return;}
        stopScan();
        if(OmiCaptureService.controlsActive){pendingControls=false;OmiCaptureService.requestDisconnect();refresh();return;}
        if(preferences(this).getString("address","").isEmpty()){scanStatus.setText("Select an Omi before connecting for buttons.");return;}
        pendingControls=true;continueControlsConnect();
    }
    private void continueControlsConnect(){
        if(!pendingControls)return;
        if(connectionBusy()){pendingControls=false;refresh();return;}
        if(!permitted(this)){requestPermissions(permissions(),CONTROLS_PERMISSION);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;
        }
        pendingControls=false;
        NotificationManager notifications=getSystemService(NotificationManager.class);
        if(notifications==null||!notifications.areNotificationsEnabled()){
            scanStatus.setText("Button-only connection not started. Enable app notifications, then connect again.");
            new AlertDialog.Builder(this).setTitle("Enable connection notifications")
                .setMessage("Button-only mode needs a visible connection notification. Background phone shortcuts also need a tap-to-run notification.")
                .setNegativeButton("Cancel",null).setPositiveButton("Notification settings",(d,w)->{
                    try{startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
                    catch(RuntimeException unavailable){scanStatus.setText("Open this app's notification settings on your phone, then connect again.");}
                }).show();return;
        }
        if(preferences(this).getString("address","").isEmpty()){scanStatus.setText("Select an Omi before connecting for buttons.");return;}
        try{startForegroundService(new Intent(this,OmiCaptureService.class).setAction(OmiCaptureService.ACTION_CONTROLS));scanStatus.setText("Connecting for buttons only — no audio saved.");}
        catch(RuntimeException unavailable){scanStatus.setText("Button-only connection could not start. Check Bluetooth and Nearby devices permission, then try again.");}
        refresh();
    }
    private void scan(){
        if(scanning){stopScan();scanStatus.setText("Scan stopped.");return;}
        if(connectionBusy()){scanStatus.setText("Stop & save, then disconnect buttons before changing devices.");return;}
        if(!permitted(this)){
            new AlertDialog.Builder(this).setTitle(Build.VERSION.SDK_INT>=31?"Find your Omi":"Allow Bluetooth discovery")
                .setMessage(Build.VERSION.SDK_INT>=31?"Nearby devices permission is needed to find and connect to your Omi. It is not used for location tracking.":"Android 8–11 requires Location permission and Location enabled for Bluetooth scans. This app does not collect your location.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->requestPermissions(permissions(),31)).show();return;
        }
        BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){scanStatus.setText("Bluetooth LE is unavailable on this device.");return;}
        try{
            if(!adapter.isEnabled()){scanStatus.setText("Turn Bluetooth on, then scan again.");startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
            if(Build.VERSION.SDK_INT<31){LocationManager location=getSystemService(LocationManager.class);if(location==null||(!location.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!location.isProviderEnabled(LocationManager.NETWORK_PROVIDER))){scanStatus.setText("Android requires Location enabled for discovery. Enable it in phone settings, then scan again.");return;}}
            int generation=++scanGeneration;scanning=true;seen.clear();devices.removeAllViews();
            scanner=new OmiBle(this,new OmiBle.Listener(){
                public void onDevice(String address,String name){main.post(()->{
                    if(!resumed||!scanning||generation!=scanGeneration||!seen.add(address))return;
                    Button choose=button(name+"\n"+address,()->{
                        if(connectionBusy())return;
                        preferences(OmiSettingsActivity.this).edit().putString("address",address).putString("name",name).apply();stopScan();
                        if(connectAfterSelection){setResult(RESULT_OK);finish();}
                        else{scanStatus.setText("Device selected. Connect for buttons below without recording, or start recording from Home.");refresh();}
                    });devices.addView(choose,new LinearLayout.LayoutParams(-1,-2));
                });}
                public void onPcm(short[] samples){}
                public void onStatus(String status){main.post(()->{if(resumed&&generation==scanGeneration)scanStatus.setText(status);});}
                public void onGap(){}
                public void onButton(int event){}
            });
            scanner.scan();scanStatus.setText("Scanning nearby Omi devices for 15 seconds…");refresh();
            main.postDelayed(()->{if(scanning&&generation==scanGeneration){stopScan();scanStatus.setText(seen.isEmpty()?"No Omi found. Wake the device, keep it nearby, and close other Omi apps. Then try again.":"Scan finished. Select your Omi above.");}},15000);
        }catch(SecurityException denied){stopScan();scanStatus.setText("Bluetooth permission was removed. Allow Nearby devices in app settings, then try again.");}
        catch(RuntimeException failure){stopScan();scanStatus.setText("Bluetooth discovery could not start. Check Nearby devices permission and Bluetooth.");}
    }
    private void stopScan(){++scanGeneration;scanning=false;if(scanner!=null){scanner.stop();scanner=null;}if(scanButton!=null)refresh();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==31){if(permitted(this)){initialScan=true;if(resumed){initialScan=false;main.post(this::scan);}}else scanStatus.setText("Permission denied. Enable it in app settings to use your Omi.");}
        else if(code==CONTROLS_PERMISSION){
            if(pendingControls&&permitted(this))continueControlsConnect();
            else{pendingControls=false;scanStatus.setText("Button-only connection not started. Allow Nearby devices permission, then connect again.");}
        }else if(code==NOTIFICATION_PERMISSION){
            if(pendingControls&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED))continueControlsConnect();
            else{pendingControls=false;scanStatus.setText("Button-only connection not started. Allow notifications for the visible connection and tap-to-run shortcuts, then connect again.");}
        }
    }
}
