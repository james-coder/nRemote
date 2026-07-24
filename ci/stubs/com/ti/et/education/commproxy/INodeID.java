package com.ti.et.education.commproxy;
// Signature-only stub of the real TI interface (see ci/stubs/README.md).
// Constants and method descriptors match commproxy.jar so the compiled
// bytecode links against the real class at runtime. UNIT_NSPIRE (30) is the
// classic non-CX handheld the emulator seam presents as its synthetic device.
public interface INodeID {
    public static final byte NONE = 0;
    public static final byte UNIT_NSPIRE_SE = 1;
    public static final byte UNIT_NSPIRE_IPAD = 2;
    public static final byte UNIT_NSPIRE_ANDROID = 3;
    public static final byte UNIT_NSPIRE_OT = 4;
    public static final byte UNIT_NSPIRE_CE = 5;
    public static final byte UNIT_NSPIRE_DSKTP_TAPP = 6;
    public static final byte UNIT_ROSE = 7;
    public static final byte UNIT_NSPIRE_LAB_CRADLE = 13;
    public static final byte UNIT_NSPIRE_CAS = 14;
    public static final byte UNIT_NSPIRE_CAS_COLOR = 15;
    public static final byte UNIT_NSPIRE_CAS_CM = 16;
    public static final byte UNIT_NSPIRE = 30;
    public static final byte UNIT_NSPIRE_COLOR = 31;
    public static final byte UNIT_NSPIRE_CM = 32;

    public Object getHandle();
    public byte getNodeTypeCode();
    public String getIDDisplayString();
}
