/**
 * Nspire Remote Controller
 * Adriweb 2012
 * Date: 14/08/12 (first build)
 * Date: 20/08/12 (v1.1)
 * Date: 21/08/12 (v1.2 and v1.3)
 * Date: 22/03/13 (v1.4)
 */

import com.ti.eps.navnet.ConnectionHandle;
import com.ti.eps.navnet.NavNet;
import com.ti.eps.navnet.NodeHandle;
import com.ti.et.education.commproxy.IEvent;
import com.ti.et.education.commproxy.INodeID;
import com.ti.et.education.commproxy.INodeInfo;
import com.ti.et.education.commproxy.NspireVirtualKeyStroke;
import com.ti.et.navnetcommproxy.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class Remote {

    static NavNetCommProxy nncp = null;
    static volatile INodeID[] theCalcs = null;

    // Serializes all NavNet transport use: a key send that collides with a
    // concurrent screen grab would otherwise fail to connect and be dropped.
    private static final Object NAVNET_LOCK = new Object();

    // Emulator mode (opt-in via `nRemote --emulator`): routes screen and key
    // traffic to a Firebird bridge instead of NavNet. The whole GUI drives the
    // emulator unchanged because it still sees one INodeID device here. The
    // NavNet path below is untouched when emulatorMode is false (the default).
    static volatile boolean emulatorMode = false;
    static EmulatorBridge emulator = null;
    private static final INodeID EMULATOR_NODE = new EmulatorNodeID();

    static File tmpDir;

    /** A single synthetic device standing in for the emulated handheld. */
    static class EmulatorNodeID implements INodeID {
        public Object getHandle() {
            return this;
        }

        public byte getNodeTypeCode() {
            return INodeID.UNIT_NSPIRE; // 30 — classic non-CX TI-Nspire
        }

        public String getIDDisplayString() {
            return "Emulator";
        }
    }

    /** Switches nRemote to drive a Firebird emulator bridge at host:port. */
    public static void enableEmulator(String host, int port) {
        emulator = new EmulatorBridge(host, port);
        emulatorMode = true;
        theCalcs = new INodeID[]{EMULATOR_NODE};
        System.out.println("nRemote: emulator mode -> " + host + ":" + port);
    }

    /**
     * Synthesizes the INodeInfo the GUI reads for the emulated device. INodeInfo
     * has 18 methods, so a reflection Proxy supplies the few the GUI actually
     * uses (name, serial) and returns defaults for the rest; getNodeSWVersionsInfo
     * returns null, which the device-table code already tolerates (see #11).
     */
    private static INodeInfo emulatorNodeInfo() {
        return (INodeInfo) Proxy.newProxyInstance(
                Remote.class.getClassLoader(),
                new Class[]{INodeInfo.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String m = method.getName();
                        if (m.equals("getName")) {
                            return "TI-Nspire (Emulator)";
                        }
                        if (m.equals("getSerialNumber") || m.equals("getElectronicId")) {
                            return "EMULATOR";
                        }
                        if (m.equals("getNodeTypeCode")) {
                            return INodeID.UNIT_NSPIRE;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == int.class || rt == byte.class) {
                            return 0;
                        }
                        if (rt == long.class) {
                            return 0L;
                        }
                        if (rt == boolean.class) {
                            return false;
                        }
                        return null;
                    }
                });
    }

    static {
        String tempPath = System.getProperty("java.io.tmpdir");
        tmpDir = new File(tempPath);
    }

    public static void Initialize() throws Exception {
        //System.out.println("initialize");
        nncp = NavNetCommProxy.init(null, tmpDir.getPath(), 0, 0, "", "");
        //System.out.println("nncp inited");
        nncp = NavNetCommProxy.getInstance();
        //System.out.println("nncp instanced");
        Thread.sleep(1000);
        connect();
    }

    public static void connect() throws Exception {
        refreshNodes();
        System.out.println(getNumberOfDevices() + " device(s) connected");
    }

    /**
     * Re-reads the connected node list from NavNet and returns true when the
     * membership changed (not merely the count: swapping one handheld for
     * another used to go unnoticed and left stale node handles behind).
     */
    public static boolean refreshNodes() {
        if (emulatorMode) {
            if (theCalcs == null || theCalcs.length == 0) {
                theCalcs = new INodeID[]{EMULATOR_NODE};
                return true;
            }
            return false;
        }
        if (nncp == null) {
            return false;
        }
        INodeID[] fresh = nncp.getConnectedNodes();
        if (fresh == null) {
            fresh = new INodeID[0];
        }
        INodeID[] old = theCalcs;
        theCalcs = fresh;
        if (old == null || old.length != fresh.length) {
            return true;
        }
        for (int i = 0; i < fresh.length; i++) {
            boolean present = false;
            for (int j = 0; j < old.length; j++) {
                if (old[j].equals(fresh[i])) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return true;
            }
        }
        return false;
    }

    static int getNumberOfDevices() {
        INodeID[] calcs = theCalcs;
        return (calcs != null) ? calcs.length : 0;
    }

    static INodeInfo getDeviceInfo(INodeID nodeID) {
        if (emulatorMode) {
            return emulatorNodeInfo();
        }
        try {
            return nncp.getNodeInfo(nodeID);
        } catch (Exception e) {
            return null;
        }
    }

    static BufferedImage getScreen(INodeID nodeID) {
        if (emulatorMode) {
            return (emulator != null) ? emulator.getScreen() : null;
        }
        Object screen = null;
        try {
            synchronized (NAVNET_LOCK) {
                screen = nncp.getScreen(nodeID, true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (screen instanceof BufferedImage)
        {
            return (BufferedImage)screen;
        } else {
            if (screen != null) {
                return (BufferedImage)((NavNetNodeScreen) screen).getScreen();
            }
        }
        return null;
    }

    public static void sendEvent(String keyStr, INodeID nodeID) throws Exception {
        if (emulatorMode) {
            if (emulator != null) {
                emulator.sendKey(keyStr);
            }
            return;
        }
        NodeHandle hdl = nncp.getHandle(nodeID);
        int status = sendEventToNode(hdl, new NspireVirtualKeyStroke(keyStr));
        if (status != 1) {
            System.err.println("nRemote: key '" + keyStr + "' was not delivered (status " + status + ")");
        }
    }

    public static void sendEvent(String keyStr) throws Exception {
        NodeHandle hdl;
        if (emulatorMode) {
            if (emulator != null) {
                emulator.sendKey(keyStr);
            }
            return;
        }
        if (theCalcs == null) {
            System.out.println("No calc(s) to send events to.");
            return;
        }
        for (INodeID nodeID : theCalcs) {
            hdl = nncp.getHandle(nodeID);
            int status = sendEventToNode(hdl, new NspireVirtualKeyStroke(keyStr));
            if (status != 1) {
                System.err.println("nRemote: key '" + keyStr + "' was not delivered (status " + status + ")");
            }
            // sleep needed ? I don't have enough calcs to test a classroom setup....
        }
    }

    public static void sendString(String str) throws Exception {
        for (char ch : str.toCharArray()) {
            sendEvent(Character.toString(ch));
        }
    }

    public static int sendEventToNode(NodeHandle calcHandle, IEvent event)
            throws Exception {
        int status = 0;
        if (calcHandle != null) {
            NspireVirtualKeyStroke key = (NspireVirtualKeyStroke) event;
            byte[] keyBytesCode = key.getKeyCode();

            if (keyBytesCode != null) {
                synchronized (NAVNET_LOCK) {
                    ConnectionHandle ch = new ConnectionHandle();
                    status = NavNet.connect(calcHandle, 16450, ch);

                    if (status == 1) {
                        status = NavNet.write(ch, NspireVirtualKeyStroke.VIRTUAL_KEY_STROKE_EVENT_COMMAND, NspireVirtualKeyStroke.VIRTUAL_KEY_STROKE_EVENT_COMMAND.length);

                        if (status == 1) {
                            byte[] keyEvent = {0, 0, 0, 0, (byte) (key.getEventType() & 0xFF), 2, (byte) (keyBytesCode[0] & 0xFF), 0, (byte) (keyBytesCode[1] & 0xFF), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) (keyBytesCode[2] & 0xFF), 0};
                            status = NavNet.write(ch, keyEvent, keyEvent.length);
                            Thread.sleep(80L);
                        }
                        NavNet.disconnect(ch);
                    }
                }
            } else {
                // NspireVirtualKeyStroke did not recognize the key name.
                System.err.println("nRemote: no keycode for this event (unsupported key name?)");
            }
        }
        return status;
    }

    public static void sendCharFromByte(byte number) throws Exception {
        byte[] theBytes = {number, 0, 0};
        sendKeyBytes(theBytes);
    }

    public static void sendKeyBytesFromString(String str) throws Exception {
        byte[] theBytes = {0, 0, 0};
        for (byte b : str.getBytes()) {
            theBytes[0] = b;
            sendKeyBytes(theBytes);
        }
    }

    public static void sendKeyBytes(byte[] keyBytesCode) throws Exception {
        NodeHandle hdl;
        if (theCalcs == null) {
            System.out.println("No calc(s) to send key bytes to.");
            return;
        }
        for (INodeID nodeID : theCalcs) {
            hdl = nncp.getHandle(nodeID);
            int status = sendKeyBytesToNode(hdl, keyBytesCode);
            if (status != 1) {
                System.err.println("nRemote: key bytes were not delivered (status " + status + ")");
            }
            // sleep needed ? I don't have enough calcs to test a classroom setup....
        }
    }

    public static int sendKeyBytesToNode(NodeHandle calcHandle, byte[] keyBytesCode)
            throws Exception {
        int status = 0;
        if (calcHandle != null) {
            if (keyBytesCode != null) {
                synchronized (NAVNET_LOCK) {
                    ConnectionHandle ch = new ConnectionHandle();
                    status = NavNet.connect(calcHandle, 16450, ch);

                    if (status == 1) {
                        status = NavNet.write(ch, NspireVirtualKeyStroke.VIRTUAL_KEY_STROKE_EVENT_COMMAND, NspireVirtualKeyStroke.VIRTUAL_KEY_STROKE_EVENT_COMMAND.length);

                        if (status == 1) {
                            byte[] keyEvent = {0, 0, 0, 0, 8, 2, (byte) (keyBytesCode[0] & 0xFF), 0, (byte) (keyBytesCode[1] & 0xFF), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) (keyBytesCode[2] & 0xFF), 0};
                            status = NavNet.write(ch, keyEvent, keyEvent.length);
                            Thread.sleep(85L);
                        }
                        NavNet.disconnect(ch);
                    }
                }
            }
        }
        return status;
    }

    public static void sendFile(INodeID nodeID, String computerPath, String calcPath)
            throws Exception {
        if (nodeID != null)
            nncp.sendFileToNode(nodeID, computerPath, calcPath, null);
    }

}