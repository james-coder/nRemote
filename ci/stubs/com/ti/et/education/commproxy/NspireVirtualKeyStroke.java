package com.ti.et.education.commproxy;
public class NspireVirtualKeyStroke implements IEvent {
    public static final byte[] VIRTUAL_KEY_STROKE_EVENT_COMMAND = new byte[]{1, 0, 0, -128};
    public NspireVirtualKeyStroke(String key) {}
    public NspireVirtualKeyStroke(String key, byte type) {}
    public byte[] getKeyCode() { return null; }
    public byte getEventType() { return 0; }
}
