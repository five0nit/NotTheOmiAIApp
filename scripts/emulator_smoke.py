#!/usr/bin/env python3
"""UI smoke on dedicated disposable emulator only; never targets physical phones.
Captures only synthetic test UI. Install APK before running. Grants microphone
and notification permissions only for this test app in the disposable emulator.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--adb',required=True)
p.add_argument('--port',type=int,required=True)
p.add_argument('--serial',required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--snapshot-jar',help='Shell-only no-idle snapshot helper on disposable emulator')
a=p.parse_args()
assert a.port!=5037, 'Refuse shared ADB server'
assert a.serial.startswith('emulator-'), 'Refuse physical device'
a.output.mkdir(parents=True,exist_ok=True)
base=[a.adb,'-P',str(a.port),'-s',a.serial]
pkg='app.nottheomi.ai'
events=[]
def adb(*args,timeout=40,binary=False):
    r=subprocess.run(base+list(args),capture_output=True,text=not binary,timeout=timeout)
    if r.returncode:raise RuntimeError(str(args)+': '+str(r.stderr)[:500])
    return r.stdout
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Refuse non-emulator'
def event(name,**data):
    events.append({'step':name,**data});print(json.dumps(events[-1]),flush=True)
    (a.output/'ui-events.json').write_text(json.dumps(events,indent=2)+'\n')
def dump():
    s=(adb('exec-out','env','CLASSPATH='+a.snapshot_jar,'app_process','/system/bin','UiSnapshot',timeout=20)
       if a.snapshot_jar else adb('exec-out','uiautomator','dump','--compressed','/proc/self/fd/1',timeout=20))
    at=s.find('<?xml')
    if at<0:raise RuntimeError('No UI XML: '+s[:180])
    return ET.fromstring(s[at:s.rfind('</hierarchy>')+12])
def node(text,description=False,partial=False):
    root=dump()
    for n in root.iter('node'):
        s=n.attrib.get('content-desc' if description else 'text','')
        if (text.casefold() in s.casefold() if partial else text.casefold()==s.casefold()):return n
    return None
def wait_node(text,seconds=30,**kw):
    end=time.monotonic()+seconds;last=''
    while time.monotonic()<end:
        try:
            n=node(text,**kw)
            if n is not None:return n
        except (RuntimeError,subprocess.TimeoutExpired) as e:last=str(e)
        time.sleep(.4)
    raise AssertionError('UI missing: '+text+' '+last)
def tap_node(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def tap(text,**kw):tap_node(wait_node(text,**kw))
def scroll_tap(text,**kw):
    for _ in range(10):
        n=node(text,**kw)
        if n is not None:
            tap_node(n);return
        adb('shell','input','swipe','280','710','280','270','250')
    raise AssertionError('Scrollable UI missing: '+text)
def shot(name):
    (a.output/(name+'.xml')).write_text(ET.tostring(dump(),encoding='unicode'))
    path=a.output/(name+'.png');path.write_bytes(adb('exec-out','screencap','-p',binary=True));return str(path)

adb('shell','am','force-stop',pkg)
adb('shell','pm','grant',pkg,'android.permission.RECORD_AUDIO')
adb('shell','pm','grant',pkg,'android.permission.POST_NOTIFICATIONS')
adb('shell','am','start','-W','-n',pkg+'/.MainActivity')
wait_node('NotTheOmiAIApp')
event('launch',screenshot=shot('01-home'))
scroll_tap('Source:',partial=True)
tap('Omi wearable')
# Connect opens discovery; cancellation never starts/falls back to phone audio.
tap('Connect Omi')
wait_node('Continue');tap('Cancel')
wait_node('Connect your Omi')
assert node('No Omi selected') is not None
assert node('Stop & save',partial=True) is None
assert 'OmiCaptureService' not in adb('shell','dumpsys','activity','services',pkg)
assert 'CaptureService' not in adb('shell','dumpsys','activity','services',pkg)
read=node('Read current brightness')
if read is not None:assert read.attrib.get('enabled')=='false'
event('omi-no-device-gate',screenshot=shot('01a-omi-controls'))
# Permission remains ungranted. Explicit back cancels the connect request.
tap('‹  Back to home')
wait_node('NotTheOmiAIApp')
scroll_tap('Source:',partial=True)
tap('Phone microphone')
event('explicit-phone-fallback',screenshot=shot('01b-phone-fallback'))
tap('●  Start recording')
wait_node('■  Stop & save')
# Model preparation happens on-device; inspect actual service status.
wait_node('Recording',seconds=120,partial=True)
time.sleep(3)
service=adb('shell','dumpsys','activity','services',pkg)
assert 'CaptureService' in service and 'isForeground=true' in service
# Home does not stop foreground recording; no host microphone used by emulator.
adb('shell','input','keyevent','3')
time.sleep(2)
service=adb('shell','dumpsys','activity','services',pkg)
assert 'isForeground=true' in service
event('foreground-capture-continues-after-home',service=True)
adb('shell','am','start','-W','-n',pkg+'/.MainActivity')
wait_node('■  Stop & save')
event('recording',screenshot=shot('02-recording'))
tap('■  Stop & save')
wait_node('●  Start recording',seconds=45)
service=adb('shell','dumpsys','activity','services',pkg)
assert 'isForeground=true' not in service
event('stop-releases-foreground-service')
scroll_tap('Open saved transcript ',description=True,partial=True)
tap('‹  Back to home')
event('saved-transcript-reachable-from-home')
tap('≡  Library')
tap('Open recording ',description=True,partial=True)
wait_node('TRANSCRIPT')
event('saved-detail',screenshot=shot('03-detail'))
tap('Rename');title=wait_node('android.widget.EditText',seconds=1) if False else None
root=dump();edit=next(n for n in root.iter('node') if n.attrib.get('class')=='android.widget.EditText')
tap_node(edit)
adb('shell','input','keyevent','KEYCODE_MOVE_END')
adb('shell','input','keyevent','--longpress','KEYCODE_DEL')
# Select-all from the rename field handles text replacement without clipboard.
adb('shell','input','keycombination','113','29')
adb('shell','input','text','Synthetic%ssmoke%snote')
tap('Save')
wait_node('Synthetic smoke note')
tap('▶  Play recording');wait_node('■  Stop playback');tap('■  Stop playback')
event('rename-and-playback')
tap('Export text');wait_node('Export an unencrypted copy?');tap('Cancel')
event('export-requires-explicit-plaintext-consent')
tap('‹  Back to library')
search=wait_node('Search recordings',description=True);tap_node(search);adb('shell','input','text','Synthetic');adb('shell','input','keyevent','4')
wait_node('Synthetic smoke note');event('title-search',screenshot=shot('04-library'))
tap('Open recording Synthetic smoke note',description=True)
tap('Delete recording');wait_node('Delete this recording?');tap('Delete')
wait_node('No matches');event('explicit-delete')
crashes=adb('logcat','-d','-b','crash','-v','brief')
assert pkg not in crashes,crashes[-2000:]
event('PASS',crashes_for_package=0)
