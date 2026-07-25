import com.ti.eps.navnet.ConnectionHandle;
import com.ti.eps.navnet.NavNet;
import com.ti.eps.navnet.NodeHandle;
import com.ti.et.education.commproxy.ICommproxyNodeScreen;
import com.ti.et.education.commproxy.INodeID;
import com.ti.et.education.commproxy.INodeInfo;
import com.ti.et.education.commproxy.NspireVirtualKeyStroke;
import com.ti.et.navnetcommproxy.NavNetCommProxy;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * On-device test harness. REQUIRES a connected TI-Nspire and the TI software
 * running, so it cannot run in CI. It uses the same NavNet calls nRemote does,
 * which makes it useful for verifying key names, click-vs-enter behaviour, and
 * screen-fetch timing against a real handheld.
 *
 * Build (needs the real TI jars from the TI install's lib folder, and its JRE):
 *   LIB="…/TI-Nspire Computer Link/lib"
 *   javac -cp "$LIB/commproxy.jar:$LIB/navnet.jar:$LIB/navnetcommproxy.jar" Probe.java
 *
 * Run (with the same jars + TI's bundled JRE) with a sequence of commands:
 *   shot=<file.png>   grab the screen to a PNG (read-only)
 *   key=<name>        send a key-down for an nRemote key name (e.g. ~enter~, a, ~click~)
 *   keyup=<name>      send the matching key-up (event type 16)
 *   sleep=<ms>        wait
 *   time=<n>          time n screen grabs and print avg/min/max
 *
 * SAFETY: only ever drive the Scratchpad or read-only dialogs; never open or
 * save Documents; erase anything typed; and finish on the Home screen so the
 * calculator is left exactly as found (its owner may not have backed up).
 */
public class Probe {
    static NavNetCommProxy nncp;
    static INodeID[] calcs;

    public static void main(String[] args) throws Exception {
        NavNetCommProxy.init(null, System.getProperty("java.io.tmpdir"), 0, 0, "", "");
        nncp = NavNetCommProxy.getInstance();
        Thread.sleep(1500L);
        calcs = nncp.getConnectedNodes();
        int n = (calcs == null) ? 0 : calcs.length;
        System.out.println("devices=" + n);
        if (n == 0) { System.out.println("No device connected; abort."); System.exit(1); }
        for (int i = 0; i < calcs.length; i++) {
            try {
                INodeInfo info = nncp.getNodeInfo(calcs[i]);
                System.out.println("device[" + i + "]: name=" + info.getName()
                        + " sid=" + info.getSerialNumber()
                        + " ver=" + info.getNodeSWVersionsInfo().getVersion());
            } catch (Exception e) { System.out.println("device[" + i + "]: info error: " + e); }
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String cmd = eq < 0 ? arg : arg.substring(0, eq);
            String val = eq < 0 ? "" : arg.substring(eq + 1);
            if (cmd.equals("shot")) shot(val);
            else if (cmd.equals("key")) key(val, (byte) 8);
            else if (cmd.equals("keyup")) key(val, (byte) 16);
            else if (cmd.equals("sleep")) Thread.sleep(Long.parseLong(val));
            else if (cmd.equals("time")) time(Integer.parseInt(val));
        }
        System.exit(0);
    }

    static BufferedImage grab() throws Exception {
        Object screen = nncp.getScreen(calcs[0], true);
        if (screen instanceof BufferedImage) return (BufferedImage) screen;
        if (screen instanceof ICommproxyNodeScreen) return (BufferedImage) ((ICommproxyNodeScreen) screen).getScreen();
        return null;
    }

    static void shot(String path) throws Exception {
        BufferedImage img = grab();
        if (img == null) { System.out.println("shot: null image"); return; }
        ImageIO.write(img, "png", new File(path));
        System.out.println("shot: " + path + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    static void key(String name, byte type) throws Exception {
        NspireVirtualKeyStroke k = new NspireVirtualKeyStroke(name, type);
        byte[] kb = k.getKeyCode();
        if (kb == null) { System.out.println("key: '" + name + "' -> NO KEYCODE (invalid name)"); return; }
        NodeHandle hdl = nncp.getHandle(calcs[0]);
        ConnectionHandle ch = new ConnectionHandle();
        int status = NavNet.connect(hdl, 16450, ch);
        if (status == 1) {
            status = NavNet.write(ch, NspireVirtualKeyStroke.VIRTUAL_KEY_STROKE_EVENT_COMMAND,
                    NspireVirtualKeyStroke.VIRTUAL_KEY_STROKE_EVENT_COMMAND.length);
            if (status == 1) {
                byte[] ev = {0, 0, 0, 0, (byte) (k.getEventType() & 0xFF), 2,
                        (byte) (kb[0] & 0xFF), 0, (byte) (kb[1] & 0xFF), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        (byte) (kb[2] & 0xFF), 0};
                status = NavNet.write(ch, ev, ev.length);
                Thread.sleep(80L);
            }
            NavNet.disconnect(ch);
        }
        System.out.println("key: '" + name + "' type=" + type + " status=" + status);
    }

    static void time(int count) throws Exception {
        long total = 0, min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < count; i++) {
            long t0 = System.currentTimeMillis();
            grab();
            long dt = System.currentTimeMillis() - t0;
            total += dt; min = Math.min(min, dt); max = Math.max(max, dt);
            System.out.println("grab[" + i + "]=" + dt + "ms");
        }
        System.out.println("grabs=" + count + " avg=" + (total / count) + "ms min=" + min + "ms max=" + max + "ms");
    }
}
