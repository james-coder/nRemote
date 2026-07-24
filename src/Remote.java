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

public class Remote {

    static NavNetCommProxy nncp = null;
    static volatile INodeID[] theCalcs = null;

    // Serializes all NavNet transport use: a key send that collides with a
    // concurrent screen grab would otherwise fail to connect and be dropped.
    private static final Object NAVNET_LOCK = new Object();

    static File tmpDir;

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
        try {
            return nncp.getNodeInfo(nodeID);
        } catch (Exception e) {
            return null;
        }
    }

    static BufferedImage getScreen(INodeID nodeID) {
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
        NodeHandle hdl = nncp.getHandle(nodeID);
        int status = sendEventToNode(hdl, new NspireVirtualKeyStroke(keyStr));
        if (status != 1) {
            System.err.println("nRemote: key '" + keyStr + "' was not delivered (status " + status + ")");
        }
    }

    public static void sendEvent(String keyStr) throws Exception {
        NodeHandle hdl;
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