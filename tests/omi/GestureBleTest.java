package app.nottheomi.ai;
import android.os.Handler;
import android.bluetooth.*;
import java.util.*;
import static app.nottheomi.ai.ButtonBleTest.*;

/** Synthetic tap timings through the production transport; not physical acceptance. */
public final class GestureBleTest {
    static void taps(Fixture f, int... events) { for(int e:events) f.notify(f.button,event(e,8)); }
    static List<int[]> partitions(int count) {
        List<int[]> out=new ArrayList<>();
        if(count==0){out.add(new int[0]);return out;}
        for(int first=1;first<=2&&first<=count;first++)for(int[] rest:partitions(count-first)){
            int[] sequence=new int[rest.length+1];sequence[0]=first;System.arraycopy(rest,0,sequence,1,rest.length);out.add(sequence);
        }
        return out;
    }
    static int expectedGesture(int count){
        switch(count){case 1:return ButtonEvent.SINGLE;case 2:return ButtonEvent.DOUBLE;
            case 3:return ButtonEvent.TRIPLE;case 4:return ButtonEvent.QUADRUPLE;default:return ButtonEvent.QUINTUPLE;}
    }
    static void higherCounts(){
        for(int max:new int[]{4,5}){
            long window=ButtonGestures.windowMs(max);
            check(window==(max==4?950:1250),"published window exact");
            for(int count=1;count<=max;count++)for(int[] partition:partitions(count)){
                scenario("max="+max+" all finalized-event partitions "+Arrays.toString(partition));
                Fixture f=new Fixture();f.ready();f.sink.maxTaps=max;taps(f,partition);
                if(count<max){
                    check(f.sink.buttons.isEmpty(),"lower gesture waits for higher-count window");
                    Handler.advance(window-1);check(f.sink.buttons.isEmpty(),"not one ms early");Handler.advance(1);
                }
                check(f.sink.buttons.equals(List.of(expectedGesture(count))),"only exact count dispatched");
                Handler.advance(window+1);check(f.sink.buttons.size()==1,"deadline cannot repeat action");
                f.ble.stop();Handler.drain();
            }
            scenario("max="+max+" window is fixed at first ingress, not sliding");
            Fixture f=new Fixture();f.ready();f.sink.maxTaps=max;taps(f,2);Handler.advance(window-1);taps(f,1);
            check(f.sink.buttons.isEmpty(),"triple deferred for higher count");Handler.advance(1);
            check(f.sink.buttons.equals(List.of(ButtonEvent.TRIPLE)),"late interior tap did not extend deadline");f.ble.stop();Handler.drain();
            scenario("max="+max+" exact boundary remains independent under backlog");
            f=new Fixture();f.ready();f.sink.maxTaps=max;
            f.remote.callback.onCharacteristicChanged(f.remote,f.button,event(2,8));
            Handler.elapseWithoutDispatch(window);
            f.remote.callback.onCharacteristicChanged(f.remote,f.button,event(1,8));Handler.drain();Handler.advance(window);
            check(f.sink.buttons.equals(List.of(2,1)),"outside window cannot manufacture triple");f.ble.stop();Handler.drain();
            scenario("max="+max+" stop discards queued partial count");
            f=new Fixture();f.ready();f.sink.maxTaps=max;taps(f,2);f.ble.stop();Handler.drain();Handler.advance(window+1);
            check(f.sink.buttons.isEmpty(),"no post-stop action");
            scenario("max="+max+" profile generation discards queued partial count");
            f=new Fixture();f.ready();f.sink.maxTaps=max;taps(f,2);f.sink.configuration++;taps(f,1);Handler.advance(window);
            check(f.sink.buttons.equals(List.of(1)),"old-profile partial cannot combine with new profile");f.ble.stop();Handler.drain();
            scenario("max="+max+" overflowing double remains indivisible");
            f=new Fixture();f.ready();f.sink.maxTaps=max;
            if(max==4)taps(f,2,1,2);else taps(f,2,2,2);
            Handler.advance(window);
            check(f.sink.buttons.equals(List.of(expectedGesture(max-1),2)),"overflow preserves lower grouped count and whole double");
            f.ble.stop();Handler.drain();
            scenario("max="+max+" synthetic wire values cannot inject grouped actions");
            f=new Fixture();f.ready();f.sink.maxTaps=max;taps(f,ButtonEvent.TRIPLE,ButtonEvent.QUADRUPLE,ButtonEvent.QUINTUPLE);
            Handler.advance(window);check(f.sink.buttons.isEmpty(),"local-only gesture IDs rejected on wire");f.ble.stop();Handler.drain();
        }
    }
    public static void main(String[] args) {
        scenario("default passthrough keeps immediate legacy single/double behavior");
        Fixture f=new Fixture();f.ready();taps(f,1,2);
        check(f.sink.buttons.equals(List.of(1,2)),"disabled immediate");f.ble.stop();Handler.drain();
        scenario("combined double then single suppresses component actions");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,2);Handler.advance(300);taps(f,1);
        check(f.sink.buttons.equals(List.of(ButtonEvent.TRIPLE)),"only triple");Handler.advance(1000);
        check(f.sink.buttons.size()==1,"no stale timer action");f.ble.stop();Handler.drain();
        scenario("single then double and three singles are recognized");
        for(int[] events:new int[][]{{1,2},{1,1,1}}) {
            f=new Fixture();f.ready();f.sink.triple=true;taps(f,events);
            check(f.sink.buttons.equals(List.of(ButtonEvent.TRIPLE)),"combined sequence");f.ble.stop();Handler.drain();
        }
        scenario("single/double expiry is bounded and exactly once");
        for(int e:new int[]{1,2}) {
            f=new Fixture();f.ready();f.sink.triple=true;taps(f,e);Handler.advance(649);
            check(f.sink.buttons.isEmpty(),"not early");Handler.advance(1);
            check(f.sink.buttons.equals(List.of(e)),"deadline delivers original");Handler.advance(100);
            check(f.sink.buttons.size()==1,"only once");f.ble.stop();Handler.drain();
        }
        scenario("late third tap cannot steal expired double");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,2);Handler.advance(650);taps(f,1);Handler.advance(650);
        check(f.sink.buttons.equals(List.of(2,1)),"exact boundary exclusive");f.ble.stop();Handler.drain();
        scenario("disconnect and configuration disable discard pending actions");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,2);f.ble.stop();Handler.drain();Handler.advance(1000);
        check(f.sink.buttons.isEmpty(),"stop cancelled pending");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,1);f.sink.triple=false;Handler.advance(650);
        check(f.sink.buttons.isEmpty(),"disable cancelled pending");taps(f,2);
        check(f.sink.buttons.equals(List.of(2)),"disabled returns immediate");f.ble.stop();Handler.drain();
        scenario("worker backlog cannot merge notifications outside ingress window");
        f=new Fixture();f.ready();f.sink.triple=true;
        f.remote.callback.onCharacteristicChanged(f.remote,f.button,event(2,8));
        Handler.elapseWithoutDispatch(800);
        f.remote.callback.onCharacteristicChanged(f.remote,f.button,event(1,8));
        Handler.drain();Handler.advance(650);
        check(f.sink.buttons.equals(List.of(2,1)),"outside ingress window remains independent");
        f.ble.stop();Handler.drain();
        scenario("worker backlog preserves inside-window notification before overdue timer");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,2);
        Handler.elapseWithoutDispatch(100);
        f.remote.callback.onCharacteristicChanged(f.remote,f.button,event(1,8));
        Handler.elapseWithoutDispatch(800);Handler.drain();
        check(f.sink.buttons.equals(List.of(ButtonEvent.TRIPLE)),"ingress before deadline remains triple");
        f.ble.stop();Handler.drain();
        scenario("disable and re-enable invalidates a pending partial gesture");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,2);
        f.sink.triple=false;f.sink.configuration++;f.sink.triple=true;f.sink.configuration++;
        taps(f,1);Handler.advance(650);
        check(f.sink.buttons.equals(List.of(1)),"old double cannot become triple in new mapping");
        f.ble.stop();Handler.drain();
        scenario("mapping generation invalidates queued ingress and overdue timer");
        f=new Fixture();f.ready();f.sink.triple=true;
        f.remote.callback.onCharacteristicChanged(f.remote,f.button,event(2,8));
        f.sink.configuration++;Handler.drain();Handler.advance(650);
        check(f.sink.buttons.isEmpty(),"queued old mapping discarded");
        taps(f,1);f.sink.configuration++;Handler.advance(650);
        check(f.sink.buttons.isEmpty(),"timer from old mapping discarded");
        f.ble.stop();Handler.drain();
        scenario("wire format never accepts synthetic gesture codes");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,100,101,3,4,5);Handler.advance(650);
        check(f.sink.buttons.isEmpty(),"synthetic code injection rejected");f.ble.stop();Handler.drain();
        scenario("overflow double-double remains two doubles, never triple");
        f=new Fixture();f.ready();f.sink.triple=true;taps(f,2,2);Handler.advance(650);
        check(f.sink.buttons.equals(List.of(2,2)),"four taps not triple");f.ble.stop();Handler.drain();
        higherCounts();
        System.out.println("HOST-ONLY GestureBleTest PASS: "+scenarios+" scenarios, "+assertions+" assertions");
    }
}
