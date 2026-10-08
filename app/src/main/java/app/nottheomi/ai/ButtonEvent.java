package app.nottheomi.ai;

/** Notification-only stock Omi button payload decoder. No action/remote writes.
 * Protocol pin: BasedHardware/omi d1fdcb4cc4fbd021eac799630e476f144e6bc18a.
 * omi/firmware/{omi/src/lib/core,devkit/src}/button.c notifies an 8-byte
 * int final_button_state[2]; the first LE32 word is the event, not byte zero.
 * The reserved second word remains zero. Require the exact verified firmware
 * layout, not the official Flutter caller's permissive >=4-byte decoder.
 * Never feed characteristic reads here: reads replay the retained last event.
 */
final class ButtonEvent {
    static final int NONE = 0, SINGLE = 1, DOUBLE = 2;
    private ButtonEvent() {}

    static int decode(byte[] value) {
        if (value == null || value.length != 8) return NONE;
        for (int i = 4; i < 8; i++) if (value[i] != 0) return NONE;
        long event = (value[0] & 255L) | ((value[1] & 255L) << 8)
            | ((value[2] & 255L) << 16) | ((value[3] & 255L) << 24);
        // Ignore NONE, LONG=3, PRESS=4, RELEASE=5 and unknown uint32 values.
        // In particular, leave long-press/power-off behavior to stock firmware.
        return event == SINGLE ? SINGLE : event == DOUBLE ? DOUBLE : NONE;
    }
}
