package app.nottheomi.ai;

import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;
import static app.nottheomi.ai.LedBleTest.field;
import static app.nottheomi.ai.LedBleTest.audioUnaffected;

/** Actual production OmiBle, shared deterministic Android/GATT clock and real codec.
 * Transport acknowledgements do not establish physical vibration or Android acceptance. */
public final class HapticBleTest {
    static final UUID SERVICE = UUID.fromString("cab1ab95-2ea5-4f4d-bb56-874b72cfc984");
    static final UUID HAPTIC = UUID.fromString("cab1ab96-2ea5-4f4d-bb56-874b72cfc984");
    static final UUID FEATURES_SERVICE = UUID.fromString("19b10020-e8f2-537e-4f6c-d104768a1214");
    static final UUID FEATURES = UUID.fromString("19b10021-e8f2-537e-4f6c-d104768a1214");
    static final BooleanSupplier ALLOW = () -> true;

    static BluetoothGattCharacteristic addHaptic(Fixture f) {
        if (f.remote.getService(FEATURES_SERVICE) == null) addFeatures(f);
        BluetoothGattService service = new BluetoothGattService(SERVICE);
        BluetoothGattCharacteristic c = characteristic(HAPTIC, false);
        c.properties = BluetoothGattCharacteristic.PROPERTY_WRITE;
        service.add(c); f.remote.services.put(SERVICE, service); return c;
    }
    static BluetoothGattCharacteristic haptic(Fixture f) {
        return f.remote.getService(SERVICE).getCharacteristic(HAPTIC);
    }
    static BluetoothGattCharacteristic addFeatures(Fixture f) {
        BluetoothGattService service = new BluetoothGattService(FEATURES_SERVICE);
        BluetoothGattCharacteristic c = characteristic(FEATURES, false);
        c.properties = BluetoothGattCharacteristic.PROPERTY_READ;
        service.add(c); f.remote.services.put(FEATURES_SERVICE, service); return c;
    }
    static BluetoothGattCharacteristic features(Fixture f) {
        BluetoothGattService service = f.remote.getService(FEATURES_SERVICE);
        return service == null ? null : service.getCharacteristic(FEATURES);
    }
    static void begin(Fixture f, BooleanSupplier guard) { f.ble.requestDoublePulse(guard); Handler.drain(); }
    static void queueFeatures(Fixture f, byte[] value, int status, boolean legacy) {
        BluetoothGattCharacteristic c = features(f);
        check(c != null && f.remote.pending == c, "feature response corresponds to exact outstanding native READ");
        f.remote.pending = null;
        if (legacy) { c.setValue(value); f.remote.callback.onCharacteristicRead(f.remote, c, status); }
        else f.remote.callback.onCharacteristicRead(f.remote, c, value, status);
        if (value != null && value.length > 0) value[0] ^= 32; // Callback owns its snapshot.
    }
    static void readFeatures(Fixture f, byte[] value, int status, boolean legacy) {
        queueFeatures(f, value, status, legacy); Handler.drain();
    }
    static void allowFeatures(Fixture f) {
        BluetoothGattCharacteristic c = features(f);
        if (c != null && f.remote.pending == c) readFeatures(f, event(32, 4), 0, false);
    }
    static void disableOnPreparation(Fixture f, boolean[] enabled, int preparation) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(HAPTIC) {
            int count;
            @Override public boolean setValue(byte[] value) {
                if (++count == preparation) enabled[0] = false;
                return super.setValue(value);
            }
        };
        c.properties = BluetoothGattCharacteristic.PROPERTY_WRITE;
        f.remote.getService(SERVICE).add(c);
    }
    static Fixture ready(boolean controls) {
        Fixture f = new Fixture(); addHaptic(f); LedBleTest.addLed(f);
        if (controls) {
            f.remote.services.remove(OmiBle.SERVICE);
            ControlsBleTest.readyControls(f);
        } else f.ready();
        return f;
    }
    static void request(Fixture f) { request(f, ALLOW); }
    static void request(Fixture f, BooleanSupplier guard) { begin(f, guard); allowFeatures(f); }
    static void stop(Fixture f) { f.ble.stop(); Handler.drain(); }
    static void queueAck(Fixture f, int status) {
        BluetoothGattCharacteristic c = haptic(f);
        check(f.remote.pending == c, "fixture ACK must correspond to an outstanding native haptic write");
        f.remote.pending = null; f.remote.callback.onCharacteristicWrite(f.remote, c, status);
    }
    static void ack(Fixture f, int status) { queueAck(f, status); Handler.drain(); }
    static void operation(Fixture f, int expected, String label) throws Exception {
        check(field(f, "operation").equals(expected), label + ": operation=" + expected);
    }
    static void released(Fixture f, String label) throws Exception {
        operation(f, 6, label);
        check(field(f, "deadline") == null && field(f, "hapticGap") == null,
            label + ": no retained haptic timeout/gap");
        check(field(f, "hapticCharacteristic") == null && field(f, "hapticAllowed") == null && field(f, "hapticFeatures") == null,
            label + ": targets and guard released");
    }
    static void exactWrites(Fixture f, int count) {
        check(f.remote.commands.size() == count, "exact native submission count " + count);
        for (int i = 0; i < count; i++) {
            check(f.remote.commandTargets.get(i) == haptic(f), "exact haptic characteristic object, write " + i);
            check(Arrays.equals(f.remote.commands.get(i), new byte[]{1}), "one-byte 100ms command {1}, write " + i);
        }
        if (count > 0) check(haptic(f).writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            "acknowledged WRITE_TYPE_DEFAULT, never no-response");
    }
    static void finish(Fixture f) throws Exception {
        ack(f, 0); operation(f, 14, "first ACK reserves gap");
        Handler.advance(220); operation(f, 15, "second write"); ack(f, 0); released(f, "both ACKs");
    }
    static void traffic(Fixture f, boolean controls, String label) {
        int buttons = f.sink.buttons.size(); long generation = f.ble.connectionGeneration();
        f.notify(f.button, event(1, 8));
        check(f.sink.buttons.size() == buttons + 1 && f.sink.buttons.get(buttons) == 1,
            label + ": fresh button notification delivered once");
        check(f.sink.buttonGenerations.get(buttons) == generation, label + ": button generation unchanged");
        if (controls) ControlsBleTest.noAudio(f, f.remote); else f.checkAudio(label);
    }

    // Deliberately literal expectations: deriving these from HapticPatterns would
    // let a changed catalogue and a changed transport agree on the same defect.
    static final String[] PATTERN_IDS = {"short", "double", "triple", "long", "unknown", null};
    static final int[][] PATTERN_PRESETS = {{1}, {1, 1}, {1, 1, 1}, {3}, {1, 1}, {1, 1}};
    static void beginPattern(Fixture f, String id, BooleanSupplier guard) {
        f.ble.requestPattern(id, guard); Handler.drain();
    }
    static void requestPattern(Fixture f, String id, BooleanSupplier guard) {
        beginPattern(f, id, guard); allowFeatures(f);
    }
    static void patternWrites(Fixture f, int offset, int... presets) {
        check(f.remote.commands.size() == offset + presets.length, "exact pattern submission count, offset=" + offset);
        for (int i = 0; i < presets.length; i++) {
            check(f.remote.commandTargets.get(offset + i) == haptic(f), "pattern uses exact native haptic target " + i);
            check(Arrays.equals(f.remote.commands.get(offset + i), new byte[]{(byte)presets[i]}),
                "individual one-byte preset {" + presets[i] + "}, never a two-byte pattern/duration " + i);
        }
        if (presets.length > 0) check(haptic(f).writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            "all patterns use acknowledged writes");
    }
    static void completePattern(Fixture f, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            operation(f, i == 0 ? 13 : 15, "pattern pulse pending " + i); ack(f, 0);
            if (i + 1 < count) { operation(f, 14, "pattern gap " + i); Handler.advance(220); }
        }
        released(f, "whole pattern acknowledged");
        check(field(f, "hapticPattern") == null && field(f, "hapticPulse").equals(0), "pattern snapshot/cursor released");
    }
    static void rejectsIndex(Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IndexOutOfBoundsException expected) { rejected = true; }
        check(rejected, label + ": invalid index throws IndexOutOfBoundsException");
    }

    static void patternCatalogue() {
        scenario("public Android-free catalogue has stable ordered IDs/default, labels and defensive arrays");
        String[] ids = {"short", "double", "triple", "long"};
        String[] labels = {"Short pulse", "Double pulse", "Triple pulse", "Long pulse"};
        check(HapticPatterns.SHORT.equals("short") && HapticPatterns.DOUBLE.equals("double")
            && HapticPatterns.TRIPLE.equals("triple") && HapticPatterns.LONG.equals("long"), "persisted public constants exact");
        check(HapticPatterns.DEFAULT.equals("double"), "legacy double remains default");
        check(Arrays.equals(HapticPatterns.ids(), ids) && Arrays.equals(HapticPatterns.labels(), labels), "exact catalogue order/labels");
        String[] mutableIds = HapticPatterns.ids(), mutableLabels = HapticPatterns.labels();
        check(mutableIds != HapticPatterns.ids() && mutableLabels != HapticPatterns.labels() && mutableIds != mutableLabels,
            "every exported array has distinct identity");
        Arrays.fill(mutableIds, "corrupted"); Arrays.fill(mutableLabels, null);
        check(Arrays.equals(HapticPatterns.ids(), ids) && Arrays.equals(HapticPatterns.labels(), labels), "array mutations cannot corrupt catalogue");
        for (int i = 0; i < ids.length; i++) {
            check(HapticPatterns.isKnown(new String(ids[i])), "known IDs compare by value, not identity");
            check(HapticPatterns.normalize(new String(ids[i])).equals(ids[i]), "canonical ID retained");
            check(HapticPatterns.label(ids[i]).equals(labels[i]), "matching human label");
        }
        for (String id : new String[]{null, "", "unknown", "SHORT", "Double", "Short pulse", " short", "long ", "triple\n", "1", "{1,1}"}) {
            check(!HapticPatterns.isKnown(id), "noncanonical ID is not known: " + id);
            check(HapticPatterns.normalize(id).equals("double") && HapticPatterns.label(id).equals("Double pulse"),
                "unknown/null/label/case/whitespace falls back without guessing: " + id);
            check(HapticPatterns.pulseCount(id) == 2, "unknown count remains double");
            for (int pulse = 0; pulse < 2; pulse++) check(HapticPatterns.preset(id, pulse) == 1
                && HapticPatterns.pulseDurationMs(id, pulse) == 100 && HapticPatterns.gapAfterMs(id, pulse) == 220,
                "unknown pattern is two short presets");
        }
        for (int i = 0; i < PATTERN_IDS.length; i++) {
            String id = PATTERN_IDS[i]; int[] presets = PATTERN_PRESETS[i]; int count = HapticPatterns.pulseCount(id);
            scenario("bounded preset/duration/gap/index contract: " + id);
            check(count == presets.length && count >= 1 && count <= 3, "stock pattern count is exact and bounded");
            for (int pulse = 0; pulse < count; pulse++) {
                long duration = presets[pulse] == 3 ? 500 : 100;
                check(HapticPatterns.preset(id, pulse) == presets[pulse], "exact stock preset at index " + pulse);
                check(HapticPatterns.pulseDurationMs(id, pulse) == duration, "nominal duration is 100ms or 500ms");
                check(HapticPatterns.gapAfterMs(id, pulse) == duration + 120, "ACK-relative delay includes 120ms quiet interval");
            }
            for (int pulse : new int[]{Integer.MIN_VALUE, -1, count, count + 1, Integer.MAX_VALUE}) {
                rejectsIndex(() -> HapticPatterns.preset(id, pulse), "preset " + id + "/" + pulse);
                rejectsIndex(() -> HapticPatterns.pulseDurationMs(id, pulse), "duration " + id + "/" + pulse);
                rejectsIndex(() -> HapticPatterns.gapAfterMs(id, pulse), "gap " + id + "/" + pulse);
            }
        }
    }

    static void exactPatternsAndLane() throws Exception {
        for (boolean controls : new boolean[]{false, true}) for (int p = 0; p < PATTERN_IDS.length; p++) {
            String id = PATTERN_IDS[p]; int[] presets = PATTERN_PRESETS[p];
            scenario("exact bounded pattern, ACKs, both gaps, busy lane and live traffic: " + id + " controls=" + controls);
            Fixture f = ready(controls); int gaps = f.sink.gaps; Object decoder = field(f, "decoder");
            beginPattern(f, id, ALLOW); operation(f, 16, "every pattern needs feature READ"); patternWrites(f, 0);
            int reads = f.remote.reads.size(); allowFeatures(f);
            for (int pulse = 0; pulse < presets.length; pulse++) {
                operation(f, pulse == 0 ? 13 : 15, "individual write " + pulse);
                patternWrites(f, 0, Arrays.copyOf(presets, pulse + 1));
                Object timer = field(f, "deadline");
                check(timer != null && f.remote.pending == haptic(f), "one native write waits for own ACK");
                LedBleTest.requestRead(f); LedBleTest.set(f, 70);
                for (String other : PATTERN_IDS) f.ble.requestPattern(other, ALLOW);
                Handler.drain(); Handler.advance(300);
                check(field(f, "deadline") == timer && f.remote.pending == haptic(f) && f.remote.reads.size() == reads,
                    "all competing IDs and LED requests leave pending write and deadline unchanged");
                patternWrites(f, 0, Arrays.copyOf(presets, pulse + 1)); traffic(f, controls, "pending pattern pulse " + pulse);
                ack(f, 0);
                if (pulse + 1 < presets.length) {
                    operation(f, 14, "ACK reserves inter-pulse lane"); Object gap = field(f, "hapticGap");
                    check(gap != null && field(f, "deadline") == null && f.remote.pending == null, "gap reserves logical but not native ATT");
                    for (int status : new int[]{0, 133}) f.remote.callback.onCharacteristicWrite(f.remote, haptic(f), status);
                    for (String other : PATTERN_IDS) f.ble.requestPattern(other, ALLOW);
                    LedBleTest.requestRead(f); LedBleTest.set(f, 70); Handler.drain();
                    check(field(f, "hapticGap") == gap && f.remote.reads.size() == reads, "duplicate ACKs/bursts do not shorten or replace gap");
                    traffic(f, controls, "pattern gap " + pulse);
                    Handler.advance(219); patternWrites(f, 0, Arrays.copyOf(presets, pulse + 1));
                    Handler.advance(1); patternWrites(f, 0, Arrays.copyOf(presets, pulse + 2));
                    check(field(f, "deadline") != timer && field(f, "hapticGap") == null, "next pulse receives independent deadline at ACK+220");
                }
            }
            released(f, "last ACK releases immediately without trailing gap");
            check(field(f, "hapticPattern") == null && field(f, "hapticPulse").equals(0), "completed pattern snapshot cleared");
            Handler.advance(2500); patternWrites(f, 0, presets);
            check(f.remote.reads.size() == reads && f.remote.pending == null, "no hidden replay/read/retry after completion");
            check(f.sink.gaps == gaps && field(f, "decoder") == decoder && f.context.adapter.connections == 1 && !f.remote.closed,
                "patterns preserve decoder, capture continuity and current connection"); stop(f);
        }
        for (boolean controls : new boolean[]{false, true}) for (String stage : List.of("second-gap", "third-write")) {
            scenario("triple reserves optional lane across due BAS poll: " + stage + " controls=" + controls);
            Fixture f = ready(controls); BatteryBleTest.addBattery(f);
            if (!controls) LedBleTest.known(f, 20);
            Handler.advance(stage.equals("second-gap") ? 600 : 500);
            requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220); ack(f, 0);
            if (stage.equals("third-write")) Handler.advance(220);
            int op = stage.equals("second-gap") ? 14 : 15;
            Object timer = field(f, "deadline"), gap = field(f, "hapticGap");
            int reads = f.remote.reads.size(), writes = f.remote.commands.size();
            LedBleTest.requestRead(f); LedBleTest.set(f, 50); beginPattern(f, "long", ALLOW);
            Handler.advance(1000 - Handler.elapsed()); operation(f, op, "due BAS cannot steal triple lane");
            check(f.remote.reads.size() == reads && f.remote.commands.size() == writes && field(f, "deadline") == timer
                && field(f, "hapticGap") == gap, "BAS/LED/new pattern cannot interleave or alter triple timers");
            if (stage.equals("second-gap")) Handler.advance(40);
            ack(f, 0); released(f, "triple after poll"); exactWrites(f, 3);
            traffic(f, controls, "triple completion after poll");
            if (!controls) { LedBleTest.known(f, 35); check(f.sink.led().brightness == 35, "LED recovers after triple"); }
            stop(f);
        }
    }

    static void patternFeatureGate() throws Exception {
        for (boolean controls : new boolean[]{false, true}) for (boolean legacy : new boolean[]{false, true})
            for (int p = 0; p < PATTERN_IDS.length; p++)
                for (String mode : List.of("missing", "null", "empty", "one-byte", "two-byte", "three-byte", "five-byte",
                        "bit-off", "other-bits", "big-endian", "high-byte", "ack-error", "supported", "all-bits")) {
                    String id = PATTERN_IDS[p];
                    scenario("all IDs require exact four-byte LE bit5 capability: " + id + " " + mode
                        + " legacy=" + legacy + " controls=" + controls);
                    Fixture f = ready(controls); int reads = f.remote.reads.size();
                    if (mode.equals("missing")) f.remote.services.remove(FEATURES_SERVICE);
                    beginPattern(f, id, ALLOW); patternWrites(f, 0);
                    if (mode.equals("missing")) check(f.remote.reads.size() == reads, "legacy haptic UUID alone never authorizes any ID");
                    else {
                        operation(f, 16, "capability gate before every ID");
                        check(f.remote.reads.size() == reads + 1 && f.remote.pending == features(f), "one exact request-time capability READ");
                        byte[] value = event(32, 4); int status = 0;
                        switch (mode) {
                            case "null": value = null; break;
                            case "empty": value = new byte[0]; break;
                            case "one-byte": value = event(32, 1); break;
                            case "two-byte": value = new byte[]{32, 3}; break;
                            case "three-byte": value = event(32, 3); break;
                            case "five-byte": value = event(32, 5); break;
                            case "bit-off": value = event(0, 4); break;
                            case "other-bits": value = event(0xffffffdfL, 4); break;
                            case "big-endian": value = new byte[]{0, 0, 0, 32}; break;
                            case "high-byte": value = new byte[]{0, 32, 0, 0}; break;
                            case "ack-error": status = 133; break;
                            case "all-bits": value = event(0xffffffffL, 4); break;
                        }
                        readFeatures(f, value, status, legacy);
                    }
                    boolean supported = mode.equals("supported") || mode.equals("all-bits");
                    if (supported) { completePattern(f, PATTERN_PRESETS[p].length); patternWrites(f, 0, PATTERN_PRESETS[p]); }
                    else { released(f, "unverified capability"); patternWrites(f, 0); }
                    check(!((Boolean)field(f, "ledQuarantined")), "known capability result is not an uncertain native write");
                    int writes = f.remote.commands.size(); Handler.advance(700);
                    check(f.remote.commands.size() == writes, "capability result causes neither retry nor reinterpretation"); stop(f);
                }
    }

    static void patternSnapshotAndAdmission() throws Exception {
        for (boolean controls : new boolean[]{false, true}) for (int p = 0; p < PATTERN_IDS.length; p++) {
            String id = PATTERN_IDS[p];
            scenario("request snapshots selected pattern across worker/feature/gap dispatch: " + id + " controls=" + controls);
            Fixture f = ready(controls); String[] selection = {id};
            f.ble.requestPattern(selection[0], ALLOW); selection[0] = "long";
            for (String other : PATTERN_IDS) f.ble.requestPattern(other, ALLOW);
            Handler.drain(); operation(f, 16, "first ingress request wins idle burst");
            check(Objects.equals(field(f, "hapticPattern"), p >= 4 ? "double" : id), "selected normalized ID captured, not last queued request");
            check(field(f, "hapticRequestedAt").equals(0L), "request timestamp captured before worker dispatch");
            queueFeatures(f, event(32, 4), 0, false); f.ble.requestPattern("short", ALLOW); Handler.drain();
            completePattern(f, PATTERN_PRESETS[p].length); patternWrites(f, 0, PATTERN_PRESETS[p]);
            Handler.advance(700); patternWrites(f, 0, PATTERN_PRESETS[p]);
            int offset = f.remote.commands.size(), reads = f.remote.reads.size();
            // New explicit selection must replace the old snapshot/cursor and re-read permission.
            beginPattern(f, "long", ALLOW); check(f.remote.reads.size() == reads + 1, "new pattern rechecks feature gate");
            allowFeatures(f); completePattern(f, 1); patternWrites(f, offset, 3);
            offset++; request(f); finish(f); patternWrites(f, offset, 1, 1); // Compatibility delegate still means double.
            stop(f);
        }
        for (String id : PATTERN_IDS) {
            scenario("all pattern IDs are disconnected no-ops and guards fail closed: " + id);
            Fixture f = new Fixture(); addHaptic(f); f.ble.requestPattern(id, ALLOW); Handler.drain();
            check(field(f, "handler") == null && field(f, "worker") == null && f.context.adapter.connections == 0
                && f.context.adapter.scanner.starts == 0 && f.remote.calls.isEmpty(), "pattern API never creates transport/worker");
            f.ready();
            for (BooleanSupplier guard : new BooleanSupplier[]{null, () -> false, () -> { throw new IllegalStateException("pattern guard"); }}) {
                int reads = f.remote.reads.size(); beginPattern(f, id, guard); released(f, "unpermitted pattern");
                check(f.remote.reads.size() == reads && f.remote.commands.isEmpty(), "guard rejects before native feature read/write");
            }
            stop(f); f.ble.requestPattern(id, ALLOW); Handler.drain();
            check(field(f, "worker") == null && f.remote.commands.isEmpty(), "pattern request cannot reopen stopped worker");
        }
    }

    static void thirdPulseCancellation() throws Exception {
        for (boolean controls : new boolean[]{false, true})
            for (String mode : List.of("before-second-ACK", "second-ACK-queued", "during-second-gap", "generation", "throws", "before-native")) {
                scenario("third pulse rechecks guard with no replay: " + mode + " controls=" + controls);
                Fixture f = ready(controls); boolean[] enabled = {true}, throwsNow = {false}; long[] generation = {9};
                BooleanSupplier guard = () -> {
                    if (throwsNow[0]) throw new IllegalStateException("third pulse guard");
                    return enabled[0] && generation[0] == 9;
                };
                if (mode.equals("before-native")) disableOnPreparation(f, enabled, 3);
                requestPattern(f, "triple", guard); ack(f, 0); Handler.advance(220);
                if (mode.equals("before-second-ACK")) enabled[0] = false;
                if (mode.equals("second-ACK-queued")) { queueAck(f, 0); enabled[0] = false; Handler.drain(); }
                else ack(f, 0);
                if (mode.equals("during-second-gap")) enabled[0] = false;
                if (mode.equals("generation")) generation[0]++;
                if (mode.equals("throws")) throwsNow[0] = true;
                Handler.advance(220); released(f, mode); exactWrites(f, 2);
                check(!((Boolean)field(f, "ledQuarantined")) && f.remote.pending == null, "known third-pulse cancellation releases, not quarantines");
                enabled[0] = true; throwsNow[0] = false; generation[0] = 9;
                Handler.advance(2500); exactWrites(f, 2); traffic(f, controls, "cancelled third");
                requestPattern(f, "triple", ALLOW); completePattern(f, 3); exactWrites(f, 5); stop(f);
            }
        for (boolean controls : new boolean[]{false, true}) for (String mode : List.of("replacement", "lost-write", "removed-service")) {
            scenario("third pulse validates native characteristic identity/properties: " + mode + " controls=" + controls);
            Fixture f = ready(controls); BluetoothGattCharacteristic original = haptic(f);
            requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220); ack(f, 0);
            if (mode.equals("replacement")) addHaptic(f);
            else if (mode.equals("lost-write")) original.properties = 4;
            else f.remote.services.remove(SERVICE);
            Handler.advance(220); released(f, mode);
            check(f.remote.commands.size() == 2 && f.remote.commandTargets.equals(List.of(original, original)),
                "third write never migrates to replacement/nonwritable/missing characteristic");
            check(!((Boolean)field(f, "ledQuarantined")), "changed target after known ACK does not quarantine");
            addHaptic(f); Handler.advance(2500); check(f.remote.commands.size() == 2, "target restoration cannot replay cancelled third");
            requestPattern(f, "long", ALLOW); completePattern(f, 1); patternWrites(f, 2, 3); stop(f);
        }
        for (boolean controls : new boolean[]{false, true}) for (long thirdAge : new long[]{1999, 2000, 2001}) {
            scenario("third write uses original ingress freshness boundary: age=" + thirdAge + " controls=" + controls);
            Fixture f = ready(controls); f.ble.requestPattern("triple", ALLOW);
            Handler.elapseWithoutDispatch(thirdAge - 440); Handler.drain(); allowFeatures(f);
            ack(f, 0); Handler.advance(220); ack(f, 0); Handler.advance(219); exactWrites(f, 2);
            Handler.advance(1); exactWrites(f, thirdAge <= 2000 ? 3 : 2);
            if (thirdAge <= 2000) ack(f, 0);
            released(f, "third freshness bound");
            check(!((Boolean)field(f, "ledQuarantined")), "freshness cancellation is known, not quarantine"); stop(f);
        }
        scenario("worker-stalled second gap cannot refresh or replay third pulse");
        Fixture f = ready(true); requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220); ack(f, 0);
        Handler.elapseWithoutDispatch(1781); Handler.drain(); exactWrites(f, 2); released(f, "overdue third"); stop(f);
    }

    static void thirdPulseCallbacksAndTimers() throws Exception {
        for (boolean controls : new boolean[]{false, true}) {
            scenario("triple has independent gap/deadline identities, foreign callbacks cannot ACK third: controls=" + controls);
            Fixture f = ready(controls); beginPattern(f, "triple", ALLOW);
            Runnable featureTimer = (Runnable)field(f, "deadline"); allowFeatures(f);
            Runnable firstTimer = (Runnable)field(f, "deadline"); ack(f, 0);
            Runnable firstGap = (Runnable)field(f, "hapticGap"); Handler.advance(220);
            Runnable secondTimer = (Runnable)field(f, "deadline"); Handler.advance(410); exactWrites(f, 2);
            ack(f, 0); Runnable secondGap = (Runnable)field(f, "hapticGap");
            check(firstGap != secondGap, "second gap has a distinct runnable identity");
            firstGap.run(); firstTimer.run(); featureTimer.run(); secondTimer.run();
            operation(f, 14, "stale work cannot shorten second gap");
            check(field(f, "hapticGap") == secondGap && field(f, "deadline") == null, "current gap remains unchanged");
            Handler.advance(219); exactWrites(f, 2); Handler.advance(1); exactWrites(f, 3);
            Object thirdTimer = field(f, "deadline");
            check(thirdTimer != secondTimer && thirdTimer != firstTimer, "third pulse gets fresh timer despite reused operation 15");
            firstGap.run(); secondGap.run(); featureTimer.run(); firstTimer.run(); secondTimer.run();
            for (int status : new int[]{0, 133}) {
                f.remote.callback.onCharacteristicWrite(f.remote, characteristic(HAPTIC, false), status);
                f.remote.callback.onCharacteristicWrite(new BluetoothGatt(), haptic(f), status);
                f.remote.callback.onCharacteristicWrite(f.remote, null, status);
                f.remote.callback.onCharacteristicWrite(f.remote, features(f), status);
                f.remote.callback.onCharacteristicWrite(f.remote, LedBleTest.led(f), status);
                f.remote.callback.onCharacteristicRead(f.remote, haptic(f), new byte[]{1, 1}, status);
                f.remote.callback.onCharacteristicRead(f.remote, features(f), event(32, 4), status);
            }
            f.remote.callback.onCharacteristicChanged(f.remote, haptic(f), new byte[]{1, 1}); Handler.drain();
            operation(f, 15, "third requires exact native ACK, never read/notify/two-byte pseudo-ACK");
            check(field(f, "deadline") == thirdTimer && f.remote.pending == haptic(f)
                && !((Boolean)field(f, "ledQuarantined")), "foreign and stale callbacks preserve third pending write/deadline");
            traffic(f, controls, "third ACK outstanding"); queueAck(f, 0);
            for (String id : PATTERN_IDS) f.ble.requestPattern(id, ALLOW);
            Handler.drain(); released(f, "third ACK complete");
            f.remote.callback.onCharacteristicWrite(f.remote, haptic(f), 0); Handler.drain();
            ((Runnable)thirdTimer).run(); Handler.advance(2500); exactWrites(f, 3);
            check(f.remote.pending == null && !((Boolean)field(f, "ledQuarantined")), "busy-at-entry requests/late ACKs/timers cannot replay after third");
            stop(f);
        }
    }

    static void patternKnownFailures() throws Exception {
        for (boolean controls : new boolean[]{false, true}) for (int p = 0; p < PATTERN_IDS.length; p++)
            for (int pulse = 0; pulse < PATTERN_PRESETS[p].length; pulse++)
                for (String failure : List.of("prepare-false", "submit-false", "ack-error")) {
                    String id = PATTERN_IDS[p];
                    scenario("known failure cancels remaining pattern without retry: " + id + " pulse=" + pulse
                        + " " + failure + " controls=" + controls);
                    Fixture f = ready(controls);
                    if (pulse > 0) {
                        requestPattern(f, id, ALLOW);
                        for (int i = 0; i < pulse; i++) { ack(f, 0); if (i + 1 < pulse) Handler.advance(220); }
                    }
                    if (failure.equals("prepare-false")) haptic(f).rejectValue = true;
                    if (failure.equals("submit-false")) f.remote.rejectCommand = true;
                    Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder"); int gaps = f.sink.gaps;
                    if (pulse == 0) requestPattern(f, id, ALLOW); else Handler.advance(220);
                    if (failure.equals("ack-error")) ack(f, 133);
                    int submitted = pulse + (failure.equals("prepare-false") ? 0 : 1);
                    patternWrites(f, 0, Arrays.copyOf(PATTERN_PRESETS[p], submitted)); released(f, failure);
                    check(!((Boolean)field(f, "ledQuarantined")) && f.remote.pending == null, "known failure leaves no uncertain native operation");
                    audioUnaffected(f, gaps, watch, decoder, 0, failure); traffic(f, controls, "failed pattern");
                    Handler.advance(2500); patternWrites(f, 0, Arrays.copyOf(PATTERN_PRESETS[p], submitted));
                    haptic(f).rejectValue = false; f.remote.rejectCommand = false;
                    if (!controls) { LedBleTest.known(f, 45); check(f.sink.led().brightness == 45, "LED usable after known pattern failure"); }
                    requestPattern(f, "long", ALLOW); completePattern(f, 1); patternWrites(f, submitted, 3); stop(f);
                }
    }

    static void thirdPulseUncertainty() throws Exception {
        for (boolean controls : new boolean[]{false, true}) for (String failure : List.of("timeout", "runtime", "security", "after-submit")) {
            scenario("uncertain third write quarantines all optional controls, not audio/buttons: " + failure + " controls=" + controls);
            Fixture f;
            if (failure.equals("after-submit")) {
                f = new Fixture(); addHaptic(f); LedBleTest.addLed(f);
                BluetoothGatt uncertain = new BluetoothGatt() {
                    @Override public boolean writeCharacteristic(BluetoothGattCharacteristic c) {
                        boolean accepted = super.writeCharacteristic(c);
                        if (HAPTIC.equals(c.getUuid()) && commands.size() == 3)
                            throw new IllegalStateException("fixture third write submitted before throw");
                        return accepted;
                    }
                };
                uncertain.services.putAll(f.remote.services); f.remote = uncertain; f.context.adapter.next = uncertain;
                if (controls) { f.remote.services.remove(OmiBle.SERVICE); ControlsBleTest.readyControls(f); } else f.ready();
            } else f = ready(controls);
            BatteryBleTest.addBattery(f);
            if (!controls) {
                Handler.advance(1000); BatteryBleTest.read(f, new byte[]{88}, 0, false); LedBleTest.known(f, 40);
            }
            requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220); ack(f, 0);
            if (failure.equals("runtime")) f.remote.commandFailure = new IllegalStateException("fixture third uncertain submit");
            if (failure.equals("security")) f.remote.commandFailure = new SecurityException("fixture third permission loss");
            Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder"); int gaps = f.sink.gaps;
            Handler.advance(220); exactWrites(f, 3); Runnable timer = (Runnable)field(f, "deadline");
            if (failure.equals("timeout")) {
                Handler.advance(1499); operation(f, 15, "third timeout not early");
                check(!((Boolean)field(f, "ledQuarantined")), "third write remains unquarantined at 1499ms");
                Handler.advance(1);
            }
            released(f, "third uncertainty");
            check((Boolean)field(f, "ledQuarantined") && field(f, "batteryPoll") == null
                && f.sink.battery() == -1 && !f.sink.led().supported && f.sink.led().brightness == -1,
                "third uncertainty clears BAS/LED telemetry and cancels shared-lane polling");
            if (failure.equals("timeout") || failure.equals("after-submit"))
                check(f.remote.pending == haptic(f), "uncertain third submission remains outstanding natively");
            audioUnaffected(f, gaps, watch, decoder, 0, failure);
            int reads = f.remote.reads.size();
            for (String id : PATTERN_IDS) beginPattern(f, id, ALLOW);
            LedBleTest.requestRead(f); LedBleTest.set(f, 50);
            check(f.remote.reads.size() == reads, "quarantine blocks feature and LED reads for every ID"); exactWrites(f, 3);
            f.remote.commandFailure = null; f.remote.pending = null;
            for (int status : new int[]{0, 133}) f.remote.callback.onCharacteristicWrite(f.remote, haptic(f), status);
            f.remote.callback.onCharacteristicRead(f.remote, features(f), event(32, 4), 0); Handler.drain();
            if (timer != null) timer.run();
            if (controls) Handler.advance(120000); else BatteryBleTest.liveAdvance(f, 120000);
            for (String id : PATTERN_IDS) beginPattern(f, id, ALLOW);
            exactWrites(f, 3);
            check(f.remote.reads.size() == reads && (Boolean)field(f, "ledQuarantined"), "late ACK/timer/permission result cannot unquarantine third failure");
            traffic(f, controls, "third uncertainty");
            check(f.context.adapter.connections == 1 && !f.remote.closed && f.sink.gaps == gaps, "optional third failure never reconnects or flushes capture");
            BluetoothGatt old = f.remote; BluetoothGattCharacteristic oldTarget = haptic(f);
            old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
            f.nextRemote(); addHaptic(f); LedBleTest.addLed(f); Handler.advance(1000);
            if (controls) { ControlsBleTest.discoverControls(f); f.ack(f.buttonCcc, 0); }
            else { f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0); }
            check(!((Boolean)field(f, "ledQuarantined")), "only naturally replaced GATT clears third quarantine");
            requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220); ack(f, 0); Handler.advance(220);
            Object freshTimer = field(f, "deadline");
            old.callback.onCharacteristicWrite(old, oldTarget, 0); old.callback.onCharacteristicWrite(old, oldTarget, 133);
            if (timer != null) timer.run(); Handler.drain();
            check(field(f, "deadline") == freshTimer && f.remote.pending == haptic(f), "old third ACK/deadline cannot satisfy replacement third write");
            ack(f, 0); released(f, "natural recovery triple"); exactWrites(f, 3); stop(f);
        }
    }

    static void thirdPulseLifecycle() throws Exception {
        for (boolean controls : new boolean[]{false, true}) for (String invalidator : List.of("stop", "selection", "native-loss"))
            for (String stage : List.of("second-ACK-queued", "second-gap-due", "third-pending", "third-ACK-queued")) {
                scenario("third-pulse lifecycle fencing: " + invalidator + " " + stage + " controls=" + controls);
                Fixture f = ready(controls); requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220);
                Runnable oldTimer = (Runnable)field(f, "deadline"), oldGap = null;
                if (stage.equals("second-ACK-queued")) queueAck(f, 0);
                else {
                    ack(f, 0); oldGap = (Runnable)field(f, "hapticGap");
                    if (stage.equals("second-gap-due")) Handler.elapseWithoutDispatch(220);
                    else {
                        Handler.advance(220); oldTimer = (Runnable)field(f, "deadline");
                        if (stage.equals("third-ACK-queued")) queueAck(f, 0);
                    }
                }
                BluetoothGatt old = f.remote; BluetoothGattCharacteristic target = haptic(f), capability = features(f);
                int submitted = old.commands.size(); long generation = f.ble.connectionGeneration();
                if (invalidator.equals("stop")) f.ble.stop();
                else if (invalidator.equals("selection")) {
                    f.nextRemote(); addHaptic(f);
                    if (controls) f.ble.connectControls("11:22:33:44:55:66"); else f.ble.connect("11:22:33:44:55:66");
                } else old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED);
                check(f.ble.connectionGeneration() != generation, "third work invalidated synchronously before worker runs");
                Handler.drain();
                check(old.closed && old.commands.size() == submitted && field(f, "hapticAllowed") == null
                    && field(f, "hapticGap") == null && field(f, "hapticPattern") == null && field(f, "hapticPulse").equals(0),
                    "close releases pattern and cancels old third work");
                if (!invalidator.equals("stop")) {
                    if (invalidator.equals("native-loss")) { f.nextRemote(); addHaptic(f); Handler.advance(1000); }
                    if (controls) { ControlsBleTest.discoverControls(f); f.ack(f.buttonCcc, 0); }
                    else { f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0); }
                    check(f.remote.commands.isEmpty(), "third pulse never replays onto replacement GATT");
                    requestPattern(f, "triple", ALLOW); ack(f, 0); Handler.advance(220); ack(f, 0); Handler.advance(220);
                }
                if (oldGap != null) oldGap.run(); oldTimer.run();
                old.callback.onCharacteristicWrite(old, target, 0); old.callback.onCharacteristicWrite(old, target, 133);
                old.callback.onCharacteristicRead(old, capability, event(32, 4), 0); Handler.drain();
                if (invalidator.equals("stop")) {
                    Handler.advance(2500); beginPattern(f, "triple", ALLOW);
                    check(field(f, "handler") == null && field(f, "worker") == null && f.context.adapter.connections == 1,
                        "old third callbacks cannot recreate worker after Stop");
                } else {
                    operation(f, 15, "old third callbacks cannot ACK new third pulse");
                    check(f.remote.pending == haptic(f), "new third write still awaits its own ACK");
                    ack(f, 0); released(f, "new third ACK"); exactWrites(f, 3); traffic(f, controls, "replacement triple"); stop(f);
                }
                check(old.commands.size() == submitted, "invalidated native GATT receives no deferred third write");
            }
    }

    static void disconnectedAndSetup() throws Exception {
        scenario("disconnected/new/stopped haptics never create worker, scan or native connection");
        Fixture f = new Fixture(); addHaptic(f);
        for (int i = 0; i < 20; i++) f.ble.requestDoublePulse(ALLOW);
        Handler.drain(); Handler.advance(10000);
        check(field(f, "handler") == null && field(f, "worker") == null, "no worker created by disconnected requests");
        check(f.context.adapter.connections == 0 && f.context.adapter.scanner.starts == 0 && f.remote.calls.isEmpty(),
            "no connection, scan or GATT action for disconnected requests");
        f.ready(); stop(f); request(f); Handler.advance(10000);
        check(field(f, "handler") == null && field(f, "worker") == null && f.context.adapter.connections == 1,
            "post-stop haptic cannot reopen connection or worker");
        check(f.remote.commands.isEmpty(), "post-stop request not submitted");

        scenario("scanning is not connected; haptics do not select a device");
        f = new Fixture(); addHaptic(f); f.ble.scan(); Handler.drain(); request(f);
        check(f.context.adapter.connections == 0 && f.context.adapter.scanner.starts == 1 && f.remote.commands.isEmpty(),
            "request preserves existing scan without connect"); stop(f);

        scenario("capture setup stages 1/2/3/4/5/7 reject requests without changing ATT/deadlines");
        f = new Fixture(); addHaptic(f); f.connect(); rejectDuringSetup(f, 1);
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        rejectDuringSetup(f, 2);
        f.remote.pending = null; f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain(); rejectDuringSetup(f, 3);
        f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain(); rejectDuringSetup(f, 4);
        f.remote.pending = null; f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20}, 0);
        Handler.drain(); rejectDuringSetup(f, 5); f.ack(f.audioCcc, 0); rejectDuringSetup(f, 7);
        f.remote.pending = null; f.remote.callback.onDescriptorWrite(f.remote, f.buttonCcc, 0);
        f.ble.requestDoublePulse(ALLOW); Handler.drain();
        check(f.remote.commands.isEmpty(), "busy-at-entry request cannot replay after queued setup completion");
        request(f); finish(f); exactWrites(f, 2); stop(f);

        scenario("required controls CCC rejects pulse until button readiness, with no audio fallback");
        f = new Fixture(); addHaptic(f); f.remote.services.remove(OmiBle.SERVICE);
        ControlsBleTest.connectControls(f); rejectDuringSetup(f, 1); ControlsBleTest.discoverControls(f); rejectDuringSetup(f, 7);
        f.ack(f.buttonCcc, 0); check(f.remote.commands.isEmpty(), "setup requests discarded, not buffered");
        request(f); finish(f); ControlsBleTest.noAudio(f, f.remote); stop(f);
    }
    static void rejectDuringSetup(Fixture f, int op) throws Exception {
        operation(f, op, "setup fixture"); Object pending = f.remote.pending, timer = field(f, "deadline");
        List<String> calls = new ArrayList<>(f.remote.calls);
        for (int i = 0; i < 5; i++) f.ble.requestDoublePulse(ALLOW);
        Handler.drain(); operation(f, op, "setup preserved");
        check(f.remote.commands.isEmpty() && f.remote.calls.equals(calls) && f.remote.pending == pending,
            "busy setup receives no additional ATT operation");
        check(field(f, "deadline") == timer, "haptic cannot extend setup deadline");
    }

    static void unsupportedAndIdentity() throws Exception {
        for (String mode : List.of("no-service", "wrong-service", "no-characteristic", "wrong-characteristic",
                "no-properties", "read-only", "notify-only", "write-no-response")) {
            scenario("unsupported haptic capability never guessed: " + mode);
            Fixture f = ready(false); BluetoothGattCharacteristic c = haptic(f);
            switch (mode) {
                case "no-service": f.remote.services.remove(SERVICE); break;
                case "wrong-service":
                    BluetoothGattService wrong = new BluetoothGattService(BUTTON_SERVICE); wrong.add(c);
                    f.remote.services.put(SERVICE, wrong); break;
                case "no-characteristic": f.remote.services.get(SERVICE).chars.clear(); break;
                case "wrong-characteristic":
                    BluetoothGattCharacteristic wrongTarget = characteristic(AUDIO, false); wrongTarget.properties = 8;
                    f.remote.services.get(SERVICE).chars.put(HAPTIC, wrongTarget); break;
                case "no-properties": c.properties = 0; break;
                case "read-only": c.properties = 2; break;
                case "notify-only": c.properties = 16; break;
                case "write-no-response": c.properties = 4; break;
            }
            int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            request(f); released(f, mode);
            check(f.remote.commands.isEmpty() && !((Boolean)field(f, "ledQuarantined")), "unsupported is a harmless no-op");
            audioUnaffected(f, gaps, watch, decoder, 0, mode);
            LedBleTest.known(f, 30); check(f.sink.led().brightness == 30, "unsupported haptic leaves LED usable");
            traffic(f, false, mode); stop(f);
        }
        for (String mode : List.of("replacement", "lost-write", "removed-service")) {
            scenario("second pulse revalidates exact current target: " + mode);
            Fixture f = ready(false); BluetoothGattCharacteristic original = haptic(f);
            request(f); ack(f, 0);
            if (mode.equals("replacement")) addHaptic(f);
            else if (mode.equals("lost-write")) original.properties = 4;
            else f.remote.services.remove(SERVICE);
            Handler.advance(220); released(f, mode);
            check(f.remote.commands.size() == 1 && !((Boolean)field(f, "ledQuarantined")), "changed target cannot receive second pulse");
            stop(f);
        }
    }

    static void featureCapabilities() throws Exception {
        for (String mode : List.of("no-service", "wrong-service", "no-characteristic", "wrong-characteristic",
                "no-properties", "write-only", "notify-only")) {
            scenario("MODERN feature gate unavailable: " + mode);
            Fixture f = ready(false); BluetoothGattCharacteristic c = features(f);
            switch (mode) {
                case "no-service": f.remote.services.remove(FEATURES_SERVICE); break;
                case "wrong-service":
                    BluetoothGattService wrong = new BluetoothGattService(SERVICE); wrong.add(c);
                    f.remote.services.put(FEATURES_SERVICE, wrong); break;
                case "no-characteristic": f.remote.getService(FEATURES_SERVICE).chars.clear(); break;
                case "wrong-characteristic":
                    f.remote.getService(FEATURES_SERVICE).chars.put(FEATURES, characteristic(HAPTIC, false)); break;
                case "no-properties": c.properties = 0; break;
                case "write-only": c.properties = 8; break;
                case "notify-only": c.properties = 16; break;
            }
            int reads = f.remote.reads.size(), gaps = f.sink.gaps;
            Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            begin(f, ALLOW); released(f, mode); exactWrites(f, 0);
            check(f.remote.reads.size() == reads && !((Boolean)field(f, "ledQuarantined")),
                "unknown capability never guessed or submitted; optional lane stays usable");
            audioUnaffected(f, gaps, watch, decoder, 0, mode); traffic(f, false, mode);
            LedBleTest.known(f, 25); check(f.sink.led().brightness == 25, "missing feature does not disable LED"); stop(f);
        }
        for (boolean legacy : new boolean[]{false, true}) {
            for (String mode : List.of("null", "empty", "legacy-byte", "short-two", "short-three", "long-five", "long-eight",
                    "bit-off", "other-bits", "big-endian", "high-byte", "status-failure", "supported", "supported-extra-bits")) {
                scenario("strict four-byte LE feature bit5, callback legacy=" + legacy + ": " + mode);
                Fixture f = ready(false); begin(f, ALLOW); operation(f, 16, "feature READ pending"); exactWrites(f, 0);
                byte[] value = event(32, 4); int status = 0;
                switch (mode) {
                    case "null": value = null; break;
                    case "empty": value = new byte[0]; break;
                    case "legacy-byte": value = event(32, 1); break;
                    case "short-two": value = event(32, 2); break;
                    case "short-three": value = event(32, 3); break;
                    case "long-five": value = event(32, 5); break;
                    case "long-eight": value = event(32, 8); break;
                    case "bit-off": value = event(0, 4); break;
                    case "other-bits": value = event(0xffffffdfL, 4); break;
                    case "big-endian": value = new byte[]{0, 0, 0, 32}; break;
                    case "high-byte": value = new byte[]{0, 32, 0, 0}; break;
                    case "status-failure": status = 133; break;
                    case "supported-extra-bits": value = event(0xffffffffL, 4); break;
                }
                int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
                readFeatures(f, value, status, legacy);
                if (mode.startsWith("supported")) { operation(f, 13, "modern capability authorizes first write"); finish(f); exactWrites(f, 2); }
                else { released(f, mode); exactWrites(f, 0); }
                check(!((Boolean)field(f, "ledQuarantined")), "known feature result never quarantines lane");
                audioUnaffected(f, gaps, watch, decoder, 0, mode); traffic(f, false, mode);
                int writes = f.remote.commands.size(), reads = f.remote.reads.size();
                Handler.advance(500); check(f.remote.commands.size() == writes && f.remote.reads.size() == reads,
                    "feature result does not cause a read or write retry");
                LedBleTest.known(f, 45); check(f.sink.led().brightness == 45, "feature result leaves unrelated LED usable"); stop(f);
            }
        }
    }

    static void featureTargetChanges() throws Exception {
        for (String mode : List.of("replacement", "lost-read", "removed-service", "haptic-replacement", "haptic-lost-write")) {
            scenario("feature ACK revalidates current feature and haptic identity: " + mode);
            Fixture f = ready(false); begin(f, ALLOW); BluetoothGattCharacteristic target = features(f);
            queueFeatures(f, event(32, 4), 0, false);
            if (mode.equals("replacement")) addFeatures(f);
            else if (mode.equals("lost-read")) target.properties = BluetoothGattCharacteristic.PROPERTY_WRITE;
            else if (mode.equals("removed-service")) f.remote.services.remove(FEATURES_SERVICE);
            else if (mode.equals("haptic-replacement")) addHaptic(f);
            else haptic(f).properties = 4; // Android PROPERTY_WRITE_NO_RESPONSE; shared stub omits constant.
            Handler.drain(); released(f, mode); exactWrites(f, 0);
            check(!((Boolean)field(f, "ledQuarantined")), "completed feature READ with changed target is not uncertainty");
            traffic(f, false, "changed target"); stop(f);
        }
        scenario("feature ACK gives first write a new deadline; old READ timeout cannot quarantine the write");
        Fixture f = ready(true); begin(f, ALLOW); Runnable featureDeadline = (Runnable)field(f, "deadline");
        Handler.advance(1490); allowFeatures(f); operation(f, 13, "feature result just before timeout"); exactWrites(f, 1);
        Object writeDeadline = field(f, "deadline");
        check(writeDeadline != null && writeDeadline != featureDeadline, "first write receives independent deadline");
        featureDeadline.run(); Handler.advance(10); operation(f, 13, "expired feature timer cannot affect first write");
        check(!((Boolean)field(f, "ledQuarantined")) && field(f, "deadline") == writeDeadline,
            "stale feature timer cannot replace/quarantine write");
        finish(f); exactWrites(f, 2); stop(f);
    }

    static void featureSerializationAndGuard() throws Exception {
        for (boolean controls : new boolean[]{false, true}) {
            scenario("request-time feature READ owns optional lane, foreign callbacks ignored; controls=" + controls);
            Fixture f = ready(controls); BatteryBleTest.addBattery(f);
            if (!controls) LedBleTest.known(f, 20);
            Handler.advance(800); int reads = f.remote.reads.size(); begin(f, ALLOW);
            operation(f, 16, "feature gate"); exactWrites(f, 0);
            check(f.remote.reads.size() == reads + 1 && f.remote.pending == features(f), "one exact feature READ before any pulse");
            Object timer = field(f, "deadline");
            f.remote.callback.onCharacteristicRead(f.remote, characteristic(FEATURES, false), event(32, 4), 0);
            f.remote.callback.onCharacteristicRead(new BluetoothGatt(), features(f), event(32, 4), 0);
            f.remote.callback.onCharacteristicRead(f.remote, null, event(32, 4), 0);
            f.remote.callback.onCharacteristicWrite(f.remote, features(f), 0);
            f.remote.callback.onCharacteristicChanged(f.remote, features(f), event(32, 4)); Handler.drain();
            LedBleTest.requestRead(f); LedBleTest.set(f, 50);
            for (int i = 0; i < 30; i++) f.ble.requestDoublePulse(ALLOW);
            Handler.advance(200); operation(f, 16, "feature gate remains sole ATT operation"); exactWrites(f, 0);
            check(f.remote.reads.size() == reads + 1 && field(f, "deadline") == timer && f.remote.pending == features(f),
                "BAS poll, LED, busy burst and foreign callbacks cannot replace gate/deadline");
            traffic(f, controls, "feature gate pending");
            queueFeatures(f, event(32, 4), 0, false); f.ble.requestDoublePulse(ALLOW); Handler.drain();
            operation(f, 13, "feature ACK starts only original request"); finish(f); exactWrites(f, 2);
            Handler.advance(500); exactWrites(f, 2);
            int completedReads = f.remote.reads.size(); begin(f, ALLOW); operation(f, 16, "fresh request rechecks features");
            check(f.remote.reads.size() == completedReads + 1 && f.remote.pending == features(f), "permission is never reused on same GATT");
            readFeatures(f, event(0, 4), 0, false); released(f, "feature bit revoked"); exactWrites(f, 2); stop(f);
        }
        for (String mode : List.of("off-before-ACK", "off-after-ACK-queued", "off-on-generation", "throw-before-ACK")) {
            scenario("feature ACK rechecks original generation-bound guard: " + mode);
            Fixture f = ready(true); boolean[] enabled = {true}, throwsNow = {false}; long[] generation = {3};
            BooleanSupplier guard = () -> {
                if (throwsNow[0]) throw new IllegalStateException("fixture feature guard");
                return enabled[0] && generation[0] == 3;
            };
            begin(f, guard); operation(f, 16, "guarded feature request");
            if (mode.equals("off-after-ACK-queued")) queueFeatures(f, event(32, 4), 0, false);
            enabled[0] = false;
            if (mode.equals("off-on-generation")) { generation[0]++; enabled[0] = true; }
            if (mode.equals("throw-before-ACK")) { enabled[0] = true; throwsNow[0] = true; }
            if (!mode.equals("off-after-ACK-queued")) queueFeatures(f, event(32, 4), 0, false);
            Handler.drain(); released(f, mode); exactWrites(f, 0);
            check(!((Boolean)field(f, "ledQuarantined")), "ACK cancellation is known completed ATT, not quarantine");
            enabled[0] = true; throwsNow[0] = false; Handler.advance(3000); exactWrites(f, 0);
            request(f); finish(f); exactWrites(f, 2); stop(f);
        }
        for (int age : new int[]{1999, 2000, 2001}) {
            scenario("feature ACK cannot refresh original request freshness: age=" + age);
            Fixture f = ready(true); f.ble.requestDoublePulse(ALLOW); Handler.elapseWithoutDispatch(800); Handler.drain();
            operation(f, 16, "late dispatch starts feature READ"); Handler.advance(age - 800);
            readFeatures(f, event(32, 4), 0, false); exactWrites(f, age <= 2000 ? 1 : 0);
            if (age <= 2000) { ack(f, 0); Handler.advance(220); }
            released(f, "original freshness retained through feature read"); stop(f);
        }
    }

    static void featureFailuresAndReconnect() throws Exception {
        for (String failure : List.of("read-reject", "timeout", "runtime", "security", "after-submit")) {
            scenario("feature READ failure optional, uncertainty quarantines shared lane: " + failure);
            Fixture f = new Fixture(); addHaptic(f); LedBleTest.addLed(f); BatteryBleTest.addBattery(f);
            if (failure.equals("after-submit")) {
                BluetoothGatt uncertain = new BluetoothGatt() {
                    @Override public boolean readCharacteristic(BluetoothGattCharacteristic c) {
                        boolean accepted = super.readCharacteristic(c);
                        if (FEATURES.equals(c.getUuid())) throw new IllegalStateException("fixture feature read submitted then threw");
                        return accepted;
                    }
                };
                uncertain.services.putAll(f.remote.services); f.remote = uncertain; f.context.adapter.next = uncertain;
            }
            f.ready(); Handler.advance(1000); BatteryBleTest.read(f, new byte[]{80}, 0, false); LedBleTest.known(f, 25);
            if (failure.equals("read-reject")) f.remote.rejectRead = true;
            if (failure.equals("runtime")) f.remote.readFailure = new IllegalStateException("fixture feature read uncertain");
            if (failure.equals("security")) f.remote.readFailure = new SecurityException("fixture feature read denied");
            int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            begin(f, ALLOW); Runnable deadline = (Runnable)field(f, "deadline");
            if (failure.equals("timeout")) {
                Handler.advance(1499); operation(f, 16, "feature deadline not early");
                check(!((Boolean)field(f, "ledQuarantined")), "feature READ not quarantined before deadline"); Handler.advance(1);
            }
            released(f, failure); exactWrites(f, 0);
            boolean uncertain = !failure.equals("read-reject");
            check(field(f, "ledQuarantined").equals(uncertain), "only uncertain feature submission/completion quarantines");
            audioUnaffected(f, gaps, watch, decoder, 0, failure);
            f.remote.rejectRead = false; f.remote.readFailure = null;
            if (uncertain) {
                check(field(f, "batteryPoll") == null && f.sink.battery() == -1 && !f.sink.led().supported,
                    "uncertain gate invalidates all optional telemetry and cancels BAS");
                if (failure.equals("timeout") || failure.equals("after-submit"))
                    check(f.remote.pending == features(f), "uncertain feature READ remains natively outstanding");
                int reads = f.remote.reads.size(); begin(f, ALLOW); LedBleTest.requestRead(f); LedBleTest.set(f, 40);
                check(f.remote.reads.size() == reads, "quarantine prevents all optional submissions");
                f.remote.pending = null;
                for (int status : new int[]{0, 133}) f.remote.callback.onCharacteristicRead(f.remote, features(f), event(32, 4), status);
                Handler.drain(); if (deadline != null) deadline.run();
                begin(f, ALLOW); LedBleTest.requestRead(f); LedBleTest.set(f, 40); BatteryBleTest.liveAdvance(f, 120000);
                exactWrites(f, 0); check(f.remote.reads.size() == reads && (Boolean)field(f, "ledQuarantined"),
                    "late feature callbacks, stale timeout and elapsed BAS intervals cannot revive lane");
                traffic(f, false, "quarantined feature gate");
                check(!f.remote.closed && f.context.adapter.connections == 1 && f.sink.gaps == gaps,
                    "feature uncertainty neither reconnects nor disrupts ongoing capture");
                f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
                f.nextRemote(); addHaptic(f); LedBleTest.addLed(f); BatteryBleTest.addBattery(f);
                Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
                check(!((Boolean)field(f, "ledQuarantined")), "natural replacement GATT releases feature uncertainty quarantine");
                begin(f, ALLOW); operation(f, 16, "natural reconnect still requires a feature READ");
                allowFeatures(f); finish(f); exactWrites(f, 2); LedBleTest.known(f, 35);
                Handler.advance(780); BatteryBleTest.read(f, new byte[]{70}, 0, false);
                check(f.sink.battery() == 70 && f.sink.led().brightness == 35,
                    "haptic, LED and BAS recover only on fresh naturally reconnected GATT");
            } else { LedBleTest.known(f, 45); request(f); finish(f); exactWrites(f, 2); }
            stop(f);
        }
        for (String mode : List.of("missing", "bit-off", "supported")) {
            scenario("MODERN haptic permission is not cached across natural reconnect: " + mode);
            Fixture f = ready(true); request(f); finish(f); BluetoothGatt old = f.remote;
            BluetoothGattCharacteristic oldFeatures = features(f);
            old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
            f.nextRemote(); addHaptic(f); Handler.advance(1000); ControlsBleTest.discoverControls(f); f.ack(f.buttonCcc, 0);
            if (mode.equals("missing")) f.remote.services.remove(FEATURES_SERVICE);
            begin(f, ALLOW); exactWrites(f, 0);
            old.callback.onCharacteristicRead(old, oldFeatures, event(32, 4), 0); Handler.drain(); exactWrites(f, 0);
            if (mode.equals("missing")) released(f, "missing modern features on replacement GATT");
            else {
                operation(f, 16, "replacement GATT requires its own fresh feature result");
                readFeatures(f, event(mode.equals("supported") ? 32 : 0, 4), 0, false);
                if (mode.equals("supported")) { finish(f); exactWrites(f, 2); }
                else { released(f, "feature bit off on replacement GATT"); exactWrites(f, 0); }
            }
            stop(f);
        }
    }

    static void happyTimingAndBurst() throws Exception {
        for (boolean controls : new boolean[]{false, true}) {
            scenario("exact two acknowledged {1} writes, ACK-relative 220ms gap and busy bursts; controls=" + controls);
            Fixture f = ready(controls);
            for (int i = 0; i < 30; i++) f.ble.requestDoublePulse(ALLOW);
            Handler.drain(); operation(f, 16, "burst shares a single feature READ");
            exactWrites(f, 0); allowFeatures(f); exactWrites(f, 1); operation(f, 13, "first pending");
            Object timer = field(f, "deadline");
            f.remote.callback.onCharacteristicWrite(f.remote, characteristic(HAPTIC, false), 0);
            f.remote.callback.onCharacteristicWrite(new BluetoothGatt(), haptic(f), 0);
            f.remote.callback.onCharacteristicWrite(f.remote, null, 0);
            f.remote.callback.onCharacteristicRead(f.remote, haptic(f), new byte[]{1}, 0);
            f.remote.callback.onCharacteristicWrite(f.remote, LedBleTest.led(f), 0); Handler.drain();
            check(field(f, "deadline") == timer && f.remote.pending == haptic(f), "foreign target/GATT/null/read callbacks cannot ACK a pulse");
            traffic(f, controls, "first pending");
            Handler.advance(700); exactWrites(f, 1); operation(f, 13, "no second without successful first ACK");
            ack(f, 0); operation(f, 14, "gap reserved");
            check(field(f, "deadline") == null && field(f, "hapticGap") != null && f.remote.pending == null,
                "gap reserves logical lane without native outstanding operation");
            Object gap = field(f, "hapticGap");
            for (int i = 0; i < 30; i++) f.ble.requestDoublePulse(ALLOW);
            f.remote.callback.onCharacteristicWrite(f.remote, haptic(f), 0); Handler.drain();
            check(field(f, "hapticGap") == gap, "busy requests and duplicate first ACK cannot restart gap");
            traffic(f, controls, "inter-pulse gap"); Handler.advance(219); exactWrites(f, 1);
            Handler.advance(1); exactWrites(f, 2); operation(f, 15, "second at ACK+220 exactly");
            check(field(f, "deadline") != null && field(f, "deadline") != timer && field(f, "hapticGap") == null,
                "second write owns fresh timeout, not first deadline");
            traffic(f, controls, "second pending"); queueAck(f, 0);
            for (int i = 0; i < 30; i++) f.ble.requestDoublePulse(ALLOW);
            Handler.drain(); released(f, "completion"); exactWrites(f, 2);
            Handler.advance(2000); exactWrites(f, 2);
            check(f.context.adapter.connections == 1 && !f.remote.closed, "burst never reconnects or queues deferred pulse");
            request(f); finish(f); exactWrites(f, 4); stop(f);
        }
    }

    static void optionalSerialization() throws Exception {
        for (int stage : new int[]{8, 9, 10, 11}) {
            scenario("haptic cannot interleave with LED/BAS or replay after their queued completion: operation=" + stage);
            Fixture f = ready(false); BatteryBleTest.addBattery(f);
            if (stage == 8) LedBleTest.requestRead(f);
            else if (stage == 11) Handler.advance(1000);
            else { LedBleTest.known(f, 20); LedBleTest.set(f, 40); if (stage == 10) LedBleTest.ack(f, 0); }
            operation(f, stage, "optional operation fixture");
            int writes = f.remote.commands.size(), reads = f.remote.reads.size();
            Object timer = field(f, "deadline"), pending = f.remote.pending;
            for (int i = 0; i < 10; i++) f.ble.requestDoublePulse(ALLOW);
            Handler.drain(); operation(f, stage, "busy optional unchanged");
            check(f.remote.commands.size() == writes && f.remote.reads.size() == reads
                && field(f, "deadline") == timer && f.remote.pending == pending, "no interleaved ATT or deadline extension");
            if (stage == 9) LedBleTest.ack(f, 0);
            BluetoothGattCharacteristic target = stage == 11 ? BatteryBleTest.battery(f) : LedBleTest.led(f);
            f.remote.pending = null; f.remote.callback.onCharacteristicRead(f.remote, target, new byte[]{40}, 0);
            f.ble.requestDoublePulse(ALLOW); Handler.drain();
            check(f.remote.commands.size() == writes, "busy-at-entry haptic discarded even when completion precedes worker dispatch");
            request(f); finish(f); check(f.remote.commands.size() == writes + 2, "fresh explicit request works after optional completion"); stop(f);
        }
        for (int stage : new int[]{13, 14, 15}) {
            scenario("first/gap/second reserves optional lane across due BAS poll and LED requests: operation=" + stage);
            Fixture f = ready(false); BatteryBleTest.addBattery(f); LedBleTest.known(f, 20);
            if (stage == 13 || stage == 14) Handler.advance(800);
            request(f);
            if (stage != 13) ack(f, 0);
            if (stage == 15) Handler.advance(220);
            operation(f, stage, "haptic fixture");
            Object timer = field(f, "deadline"), gap = field(f, "hapticGap");
            int reads = f.remote.reads.size(), writes = f.remote.commands.size();
            LedBleTest.requestRead(f); LedBleTest.set(f, 50); request(f);
            Handler.advance(1000 - Handler.elapsed());
            operation(f, stage, "poll cannot steal lane");
            check(f.remote.reads.size() == reads && f.remote.commands.size() == writes,
                "due BAS and explicit LED/haptic requests create no ATT work during pair");
            check(field(f, "deadline") == timer && field(f, "hapticGap") == gap, "optional requests cannot replace haptic timeout/gap");
            if (stage == 13) finish(f);
            else if (stage == 14) { Handler.advance(20); ack(f, 0); }
            else ack(f, 0);
            released(f, "pair completes"); exactWrites(f, 2);
            LedBleTest.known(f, 35); check(f.sink.led().brightness == 35, "LED returns after pair");
            stop(f);
        }
    }

    static void guardAndFreshness() throws Exception {
        for (String mode : List.of("null", "false", "throws", "off-before-dispatch", "off-on-generation", "off-before-native")) {
            scenario("guard prevents first native write: " + mode);
            Fixture f = ready(true); boolean[] enabled = {true}; long[] generation = {1};
            BooleanSupplier guard = () -> enabled[0] && generation[0] == 1;
            if (mode.equals("null")) guard = null;
            else if (mode.equals("false")) guard = () -> false;
            else if (mode.equals("throws")) guard = () -> { throw new IllegalStateException("fixture guard"); };
            else if (mode.equals("off-before-native")) disableOnPreparation(f, enabled, 1);
            f.ble.requestDoublePulse(guard);
            if (mode.equals("off-before-dispatch")) enabled[0] = false;
            if (mode.equals("off-on-generation")) { enabled[0] = false; generation[0]++; enabled[0] = true; }
            Handler.drain(); allowFeatures(f); released(f, mode); exactWrites(f, 0);
            check(!((Boolean)field(f, "ledQuarantined")), "guard cancellation does not quarantine completed/idle lane");
            enabled[0] = true; request(f); finish(f); exactWrites(f, 2); stop(f);
        }
        for (String mode : List.of("off-before-first-ACK", "off-during-gap", "off-on-generation", "throws-during-gap", "off-before-native")) {
            scenario("guard prevents second native write and cannot replay after re-enable: " + mode);
            Fixture f = ready(true); boolean[] enabled = {true}, throwsNow = {false}; long[] generation = {7};
            BooleanSupplier guard = () -> {
                if (throwsNow[0]) throw new IllegalStateException("fixture changed guard");
                return enabled[0] && generation[0] == 7;
            };
            if (mode.equals("off-before-native")) disableOnPreparation(f, enabled, 2);
            request(f, guard);
            if (mode.equals("off-before-first-ACK")) enabled[0] = false;
            ack(f, 0);
            if (mode.equals("off-during-gap")) enabled[0] = false;
            if (mode.equals("off-on-generation")) { enabled[0] = false; generation[0]++; enabled[0] = true; }
            if (mode.equals("throws-during-gap")) throwsNow[0] = true;
            Handler.advance(220); released(f, mode); exactWrites(f, 1);
            enabled[0] = true; throwsNow[0] = false; Handler.advance(3000); exactWrites(f, 1);
            request(f); finish(f); exactWrites(f, 3); stop(f);
        }
        for (long age : new long[]{1999, 2000, 2001}) {
            scenario("first-write request freshness at worker dispatch: age=" + age);
            Fixture f = ready(true); f.ble.requestDoublePulse(ALLOW); Handler.elapseWithoutDispatch(age); Handler.drain(); allowFeatures(f);
            exactWrites(f, age <= 2000 ? 1 : 0);
            if (age <= 2000) { ack(f, 0); Handler.advance(220); }
            released(f, "expired request or second pulse");
            check(!((Boolean)field(f, "ledQuarantined")), "expiry without uncertain native operation is not quarantine"); stop(f);
        }
        for (long delay : new long[]{1780, 1781}) {
            scenario("second pulse freshness retains original ingress time: queue delay=" + delay);
            Fixture f = ready(true); f.ble.requestDoublePulse(ALLOW); Handler.elapseWithoutDispatch(delay); Handler.drain(); allowFeatures(f);
            ack(f, 0); Handler.advance(219); exactWrites(f, 1); Handler.advance(1);
            exactWrites(f, delay == 1780 ? 2 : 1);
            if (delay == 1780) ack(f, 0);
            released(f, "original freshness bound"); stop(f);
        }
        scenario("overdue gap dispatch cannot send stale second pulse even after timely first ACK");
        Fixture f = ready(true); request(f); ack(f, 0); Handler.elapseWithoutDispatch(2001); Handler.drain();
        exactWrites(f, 1); released(f, "worker-stalled gap"); stop(f);
    }

    static void knownFailures() throws Exception {
        for (int stage : new int[]{13, 15}) for (String failure : List.of("prepare-false", "submit-false", "ack-error")) {
            scenario("known haptic failure releases lane, no retry or follow-up: stage=" + stage + " " + failure);
            Fixture f = ready(false); BluetoothGattCharacteristic c = haptic(f);
            if (stage == 15) { request(f); ack(f, 0); }
            if (failure.equals("prepare-false")) c.rejectValue = true;
            if (failure.equals("submit-false")) f.remote.rejectCommand = true;
            int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            if (stage == 13) request(f); else Handler.advance(220);
            if (failure.equals("ack-error")) ack(f, 133);
            int expected = (stage == 15 ? 1 : 0) + (failure.equals("prepare-false") ? 0 : 1);
            exactWrites(f, expected); released(f, failure);
            check(!((Boolean)field(f, "ledQuarantined")), "known rejection/error does not poison optional lane");
            audioUnaffected(f, gaps, watch, decoder, 0, failure); traffic(f, false, failure);
            Handler.advance(3000); exactWrites(f, expected);
            c.rejectValue = false; f.remote.rejectCommand = false;
            LedBleTest.known(f, 45); check(f.sink.led().brightness == 45, "LED still usable after known failure");
            request(f); finish(f); exactWrites(f, expected + 2); stop(f);
        }
    }

    static void uncertainFailures() throws Exception {
        for (int stage : new int[]{13, 15}) for (String failure : List.of("timeout", "runtime", "security", "after-submit")) {
            scenario("uncertain haptic permanently quarantines ALL optional controls on current GATT: stage=" + stage + " " + failure);
            Fixture f = new Fixture(); addHaptic(f); LedBleTest.addLed(f); BatteryBleTest.addBattery(f);
            if (failure.equals("after-submit")) {
                BluetoothGatt uncertain = new BluetoothGatt() {
                    @Override public boolean writeCharacteristic(BluetoothGattCharacteristic c) {
                        boolean accepted = super.writeCharacteristic(c);
                        if (HAPTIC.equals(c.getUuid()) && commands.size() == (stage == 13 ? 1 : 2))
                            throw new IllegalStateException("fixture failure after actual native submission");
                        return accepted;
                    }
                };
                uncertain.services.putAll(f.remote.services); f.remote = uncertain; f.context.adapter.next = uncertain;
            }
            f.ready(); Handler.advance(1000); BatteryBleTest.read(f, new byte[]{87}, 0, false); LedBleTest.known(f, 40);
            if (stage == 15) { request(f); ack(f, 0); }
            if (failure.equals("runtime")) f.remote.commandFailure = new IllegalStateException("fixture uncertain submit");
            if (failure.equals("security")) f.remote.commandFailure = new SecurityException("fixture permission lost");
            int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            if (stage == 13) request(f); else Handler.advance(220);
            Runnable timeout = (Runnable)field(f, "deadline");
            if (failure.equals("timeout")) {
                Handler.advance(1499); operation(f, stage, "no early haptic timeout");
                check(!((Boolean)field(f, "ledQuarantined")), "1500ms deadline not early");
                Handler.advance(1);
            }
            released(f, failure);
            check((Boolean)field(f, "ledQuarantined") && field(f, "batteryPoll") == null,
                "uncertainty quarantines shared ATT and cancels BAS polling");
            check(f.sink.battery() == -1 && !f.sink.led().supported && f.sink.led().brightness == -1,
                "quarantine invalidates prior LED/BAS values");
            if (failure.equals("timeout") || failure.equals("after-submit"))
                check(f.remote.pending == haptic(f), "uncertain native write intentionally remains outstanding");
            audioUnaffected(f, gaps, watch, decoder, 0, failure);
            int writes = f.remote.commands.size(), reads = f.remote.reads.size();
            request(f); LedBleTest.requestRead(f); LedBleTest.set(f, 20);
            check(f.remote.commands.size() == writes && f.remote.reads.size() == reads,
                "haptic, LED read and LED set all blocked while native outcome uncertain");
            f.remote.commandFailure = null; f.remote.pending = null;
            for (int status : new int[]{0, 133}) f.remote.callback.onCharacteristicWrite(f.remote, haptic(f), status);
            f.remote.callback.onCharacteristicRead(f.remote, LedBleTest.led(f), new byte[]{30}, 0);
            f.remote.callback.onCharacteristicWrite(f.remote, LedBleTest.led(f), 0);
            f.remote.callback.onCharacteristicRead(f.remote, BatteryBleTest.battery(f), new byte[]{55}, 0);
            f.remote.callback.onDescriptorWrite(f.remote, f.buttonCcc, 0); Handler.drain();
            if (timeout != null) timeout.run();
            request(f); LedBleTest.requestRead(f); LedBleTest.set(f, 20); BatteryBleTest.liveAdvance(f, 120000);
            check(f.remote.commands.size() == writes && f.remote.reads.size() == reads
                && (Boolean)field(f, "ledQuarantined") && f.sink.battery() == -1,
                "late callbacks/stale timers/elapsed polls cannot revive any optional control");
            traffic(f, false, "quarantined transport");
            check(f.context.adapter.connections == 1 && !f.remote.closed && f.sink.gaps == gaps,
                "haptic failure never triggers reconnect, audio gap or hidden write retry");
            f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
            f.nextRemote(); addHaptic(f); LedBleTest.addLed(f); BatteryBleTest.addBattery(f);
            Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
            check(!((Boolean)field(f, "ledQuarantined")), "only fresh natural GATT clears quarantine");
            request(f); finish(f); exactWrites(f, 2); LedBleTest.known(f, 25);
            Handler.advance(780); BatteryBleTest.read(f, new byte[]{66}, 0, false);
            check(f.sink.battery() == 66 && f.sink.led().brightness == 25, "all optional controls recover on fresh connection"); stop(f);
        }
    }

    static void otherOptionalQuarantine() throws Exception {
        for (String source : List.of("led-read", "led-write", "led-readback", "battery", "button")) {
            scenario("pre-existing optional timeout also blocks haptic: " + source);
            Fixture f = new Fixture(); addHaptic(f); LedBleTest.addLed(f); BatteryBleTest.addBattery(f);
            f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
            if (!source.equals("button")) f.ack(f.buttonCcc, 0);
            if (source.equals("led-read")) LedBleTest.requestRead(f);
            if (source.equals("led-write") || source.equals("led-readback")) {
                LedBleTest.known(f, 30); LedBleTest.set(f, 40);
                if (source.equals("led-readback")) LedBleTest.ack(f, 0);
            }
            if (source.equals("battery")) Handler.advance(1000);
            BatteryBleTest.liveAdvance(f, 15000);
            check((Boolean)field(f, "ledQuarantined"), "fixture has uncertain shared ATT");
            int writes = f.remote.commands.size(), reads = f.remote.reads.size(); request(f);
            check(f.remote.commands.size() == writes && f.remote.reads.size() == reads,
                "haptic cannot bypass earlier LED/BAS/button quarantine"); stop(f);
        }
    }

    static void lifecycle() throws Exception {
        for (String invalidator : List.of("stop", "selection", "native-loss"))
            for (String stage : List.of("queued", "feature-pending", "feature-ack-queued", "first-ack-queued", "gap-due", "second-ack-queued")) {
                scenario("connection invalidation fences haptic work and callbacks: " + invalidator + " " + stage);
                Fixture f = ready(true); BluetoothGatt old = f.remote; BluetoothGattCharacteristic target = haptic(f);
                Runnable oldGap = null, oldDeadline = null;
                BluetoothGattCharacteristic oldFeatures = features(f);
                if (stage.equals("queued")) f.ble.requestDoublePulse(ALLOW);
                else if (stage.startsWith("feature-")) {
                    begin(f, ALLOW); operation(f, 16, "old feature gate"); oldDeadline = (Runnable)field(f, "deadline");
                    if (stage.equals("feature-ack-queued")) queueFeatures(f, event(32, 4), 0, false);
                }
                else {
                    request(f); oldDeadline = (Runnable)field(f, "deadline");
                    if (stage.equals("first-ack-queued")) queueAck(f, 0);
                    else {
                        ack(f, 0); oldGap = (Runnable)field(f, "hapticGap");
                        if (stage.equals("gap-due")) Handler.elapseWithoutDispatch(220);
                        else { Handler.advance(220); oldDeadline = (Runnable)field(f, "deadline"); queueAck(f, 0); }
                    }
                }
                int submitted = old.commands.size(); long generation = f.ble.connectionGeneration();
                if (invalidator.equals("stop")) f.ble.stop();
                else if (invalidator.equals("selection")) {
                    f.nextRemote(); addHaptic(f); f.ble.connectControls("11:22:33:44:55:66");
                } else old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED);
                check(f.ble.connectionGeneration() != generation, "invalidator revokes generation synchronously before worker drains");
                Handler.drain();
                check(old.commands.size() == submitted && old.closed, "old request cannot write after invalidation");
                check(field(f, "hapticGap") == null && field(f, "hapticAllowed") == null
                    && field(f, "hapticCharacteristic") == null && field(f, "hapticFeatures") == null,
                    "old request references released on close");
                if (!invalidator.equals("stop")) {
                    if (invalidator.equals("native-loss")) {
                        f.nextRemote(); addHaptic(f); Handler.advance(1000);
                    }
                    ControlsBleTest.discoverControls(f); f.ack(f.buttonCcc, 0);
                    check(f.remote.commands.isEmpty(), "no haptic alert replayed on replacement/reconnect");
                    request(f); operation(f, 13, "new request pending");
                }
                if (oldGap != null) oldGap.run();
                if (oldDeadline != null) oldDeadline.run();
                old.callback.onCharacteristicWrite(old, target, 0);
                old.callback.onCharacteristicWrite(old, target, 133);
                old.callback.onCharacteristicRead(old, oldFeatures, event(32, 4), 0);
                old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
                if (invalidator.equals("stop")) {
                    Handler.advance(3000); request(f);
                    check(field(f, "handler") == null && field(f, "worker") == null && f.context.adapter.connections == 1,
                        "late callbacks and request cannot recreate stopped worker");
                } else {
                    operation(f, 13, "old GATT/timers cannot satisfy new first write"); finish(f); exactWrites(f, 2); stop(f);
                }
                check(old.commands.size() == submitted, "no deferred command reaches old native GATT");
            }
    }

    static void watchdogAndRetry() throws Exception {
        scenario("haptic ACKs do not replace startup audio watchdog or forgive retry debt");
        Fixture f = ready(false); f.remote.callback.onConnectionStateChange(f.remote, 133, 0); Handler.drain();
        f.nextRemote(); addHaptic(f); Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
        check(field(f, "retry").equals(1), "fixture carries natural reconnect debt");
        Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder"); int gaps = f.sink.gaps;
        request(f); finish(f); audioUnaffected(f, gaps, watch, decoder, 1, "successful haptic is not PCM recovery");
        Handler.advance(29779); check(!f.remote.closed, "startup audio timeout not early"); Handler.advance(1);
        check(f.remote.closed && f.sink.status().contains("retry in 2 seconds"), "startup audio deadline and next backoff preserved");
        stop(f);

        scenario("haptic uncertainty cannot hide established-audio loss");
        f = ready(false); f.checkAudio("established PCM"); Handler.advance(13000); request(f); Handler.advance(1500);
        check((Boolean)field(f, "ledQuarantined") && !f.remote.closed, "haptic timeout is optional, connection remains live");
        Handler.advance(499); check(!f.remote.closed, "15s audio watchdog not early"); Handler.advance(1);
        check(f.remote.closed && f.sink.status().contains("15 seconds"), "original established-audio watchdog still closes lost audio"); stop(f);

        scenario("controls-only haptic completion stays connected without manufacturing PCM or audio deadlines");
        f = ready(true); request(f); finish(f); Handler.advance(180000);
        ControlsBleTest.noAudio(f, f.remote);
        check(!f.remote.closed && f.context.adapter.connections == 1 && f.remote.reads.equals(List.of(features(f))),
            "controls pulse never opts into audio, BAS polling or reconnect"); stop(f);
    }

    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275]; int length = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(length > 0, "real Concentus encoded test payload"); ButtonBleTest.opus = Arrays.copyOf(encoded, length);
        disconnectedAndSetup(); unsupportedAndIdentity(); featureCapabilities(); featureTargetChanges(); featureSerializationAndGuard();
        featureFailuresAndReconnect(); happyTimingAndBurst(); optionalSerialization();
        guardAndFreshness(); knownFailures(); uncertainFailures(); otherOptionalQuarantine(); lifecycle(); watchdogAndRetry();
        patternCatalogue(); exactPatternsAndLane(); patternFeatureGate(); patternSnapshotAndAdmission();
        thirdPulseCancellation(); thirdPulseCallbacksAndTimers(); patternKnownFailures(); thirdPulseUncertainty(); thirdPulseLifecycle();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " haptic/BLE host scenarios");
        System.out.println("HOST ONLY: production OmiBle + Concentus; deterministic shared Android/GATT/Handler fakes; no physical vibration acceptance");
    }
}
