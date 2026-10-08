package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

/** Discovery never connects or records. Controls borrow only the live capture connection. */
public final class OmiSettingsActivity extends Activity {
    public static final String CONNECT_AFTER_SELECTION="connect_after_selection";
    private boolean connectAfterSelection, initialScan;
    private static final int INK=0xff191c18, MUTED=0xff646a61, PAPER=0xfff5f2eb;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> seen=new HashSet<>();
    private LinearLayout page, devices;
    private TextView selected, scanStatus, live, led, desired, presses;
    private Button scanButton, forgetButton, readButton, applyButton, singleButton, doubleButton;
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
            if(OmiCaptureService.active)return;
            preferences(this).edit().remove("address").remove("name").apply();refresh();
        });
        scanStatus=label(connectAfterSelection?"Choose your Omi to connect and start recording automatically. Scanning alone does not record.":"Select a device, then tap Connect Omi on Home. Scanning does not record audio.",13,MUTED);
        devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);page.addView(devices);
        label("LIVE CONNECTION",12,MUTED);live=label("Disconnected",14,INK);
        label("Light brightness",22,INK);
        label("Available only while recording, if your firmware supports it. First read the current value. Changing the wearable light does not stop audio capture.",13,MUTED);
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
                .setMessage((value==0?"This requests minimum brightness; firmware-owned indicators may remain. ":"")+"Audio recording continues, with a visible phone notification. Readback confirms the current value, not persistence after power-off.")
                .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->OmiCaptureService.setLedBrightness(value)).show();
        });
        label("Button presses",22,INK);
        label("Local actions while recording. Choose them before starting. These do not rewrite firmware. Long press / power actions stay with the device; buttons cannot start a stopped connection.",13,MUTED);
        singleButton=addButton("",()->chooseAction("single_action","Single press","bookmark"));
        doubleButton=addButton("",()->chooseAction("double_action","Double press","stop"));
        presses=label("",14,INK);
        label("Compatibility: stock Omi BLE with 16 kHz Opus audio. Unsupported codecs and missing controls are reported, never guessed. Gaps stop and save rather than silently joining missing audio.",13,MUTED);
        refresh();
    }
    @Override protected void onResume(){super.onResume();resumed=true;main.post(ticker);if(initialScan){initialScan=false;main.post(this::scan);}}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(ticker);stopScan();super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(10),0,dp(6));if(size>=22)t.setTypeface(null,Typeface.BOLD);page.addView(t);return t;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(INK);GradientDrawable bg=new GradientDrawable();bg.setColor(0xffe5e8df);bg.setCornerRadius(dp(12));b.setBackground(bg);b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setMinHeight(dp(50));b.setOnClickListener(v->action.run());return b;}
    private Button addButton(String title,Runnable action){Button b=button(title,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);page.addView(b,p);return b;}
    private void refresh(){
        SharedPreferences p=preferences(this);String address=p.getString("address","");
        selected.setText(address.isEmpty()?"No Omi selected":p.getString("name","Omi")+"\n"+address);
        boolean active=OmiCaptureService.active;
        scanButton.setEnabled(!active);scanButton.setText(scanning?"Stop scanning":"Find nearby Omi");
        forgetButton.setEnabled(!active&&!address.isEmpty());
        live.setText(OmiCaptureService.transport+(OmiCaptureService.battery>=0?" · Battery "+OmiCaptureService.battery+"%":" · Battery unknown"));
        OmiBle.LedState value=OmiCaptureService.ledState;
        led.setText(value==null?"Not connected":value.message+(value.brightness>=0?"\nReported brightness: "+value.brightness+"%":""));
        readButton.setEnabled(active&&value!=null&&!value.busy);
        boolean writable=active&&value!=null&&value.supported&&!value.busy&&value.brightness>=0;
        applyButton.setEnabled(writable);brightness.setEnabled(writable);
        singleButton.setText("Single press: "+actionLabel(p.getString("single_action","bookmark")));
        doubleButton.setText("Double press: "+actionLabel(p.getString("double_action","stop")));
        singleButton.setEnabled(!active);doubleButton.setEnabled(!active);
        presses.setText(OmiCaptureService.lastButton);
    }
    private static String actionLabel(String value){return "bookmark".equals(value)?"Bookmark":"stop".equals(value)?"Stop & save":"No action";}
    private void chooseAction(String key,String title,String fallback){
        String[] values={"bookmark","stop","ignore"}, labels={"Bookmark in transcript","Stop & save","No action"};
        String current=preferences(this).getString(key,fallback);int index=0;for(int i=0;i<values.length;i++)if(values[i].equals(current))index=i;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,index,(d,w)->{preferences(this).edit().putString(key,values[w]).apply();d.dismiss();refresh();}).setNegativeButton("Cancel",null).show();
    }
    private void scan(){
        if(scanning){stopScan();scanStatus.setText("Scan stopped.");return;}
        if(OmiCaptureService.active){scanStatus.setText("Stop & save before changing devices.");return;}
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
                        if(OmiCaptureService.active)return;
                        preferences(OmiSettingsActivity.this).edit().putString("address",address).putString("name",name).apply();stopScan();
                        if(connectAfterSelection){setResult(RESULT_OK);finish();}
                        else{scanStatus.setText("Device selected. Tap Connect Omi on Home to begin.");refresh();}
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
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==31){if(permitted(this)){initialScan=true;if(resumed){initialScan=false;main.post(this::scan);}}else scanStatus.setText("Permission denied. Enable it in app settings to use your Omi.");}}
}
