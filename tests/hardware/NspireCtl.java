import com.ti.eps.navnet.ConnectionHandle;
import com.ti.eps.navnet.NavNet;
import com.ti.eps.navnet.NodeHandle;
import com.ti.et.education.commproxy.ICommproxyNodeScreen;
import com.ti.et.education.commproxy.INodeID;
import com.ti.et.education.commproxy.INodeInfo;
import com.ti.et.education.commproxy.NspireVirtualKeyStroke;
import com.ti.et.navnetcommproxy.FileAttributesProxy;
import com.ti.et.navnetcommproxy.NavNetCommProxy;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Vector;

/**
 * Remote control and file transfer for a connected TI-Nspire, over the same
 * NavNet API nRemote uses. This is {@link Probe} plus the file operations, which
 * is what makes it useful for anything involving getting data on or off the
 * handheld (see {@link DumpBoot1}).
 *
 * REQUIRES a connected handheld and the TI software running, so it cannot run
 * in CI. Build against the real TI jars with the TI-bundled JRE (Java 7):
 *
 *   LIB="…/TI-Nspire Computer Link/lib"
 *   javac -source 1.7 -target 1.7 -cp "$LIB/commproxy.jar:$LIB/navnet.jar:$LIB/navnetcommproxy.jar" \
 *         -d out tests/hardware/NspireCtl.java
 *   java -Djava.library.path="$LIB" -cp "out:$LIB/*" NspireCtl <commands…>
 *
 * Commands, applied in order:
 *   shot=&lt;file.png&gt;            grab the screen (read-only)
 *   key=&lt;name&gt;                 send a key-down for an nRemote key name
 *   keyup=&lt;name&gt;               send the matching key-up
 *   sleep=&lt;ms&gt;                 wait
 *   ls=&lt;calcpath&gt;              list a directory (read-only)
 *   tree=&lt;calcpath&gt;            list recursively (read-only)
 *   mkdir=&lt;calcpath&gt;           create a folder
 *   rmdir=&lt;calcpath&gt;           remove a folder
 *   send=&lt;local&gt;::&lt;calcpath&gt;   copy a file to the handheld
 *   recv=&lt;calcpath&gt;::&lt;local&gt;   copy a file from the handheld
 *   del=&lt;calcpath&gt;             delete a file
 *
 * SAFETY: this can write to and delete from someone's calculator. Unless you
 * are deliberately doing a dump (see DumpBoot1), stick to the read-only
 * commands and the protocol in README.md: drive only the Scratchpad, never
 * modify Documents, and finish on the Home screen.
 */
public class NspireCtl {
    static NavNetCommProxy nncp;
    static INodeID[] calcs;

    public static void main(String[] args) throws Exception {
        NavNetCommProxy.init(null, System.getProperty("java.io.tmpdir"), 0, 0, "", "");
        nncp = NavNetCommProxy.getInstance();
        Thread.sleep(1500L);
        calcs = nncp.getConnectedNodes();
        int n = (calcs == null) ? 0 : calcs.length;
        System.out.println("devices=" + n);
        if (n == 0) {
            System.out.println("No device connected; abort.");
            System.exit(1);
        }
        for (int i = 0; i < calcs.length; i++) {
            try {
                INodeInfo info = nncp.getNodeInfo(calcs[i]);
                System.out.println("device[" + i + "]: name=" + info.getName()
                        + " sid=" + info.getSerialNumber()
                        + " ver=" + info.getNodeSWVersionsInfo().getVersion());
            } catch (Exception e) {
                System.out.println("device[" + i + "]: info error: " + e);
            }
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String cmd = eq < 0 ? arg : arg.substring(0, eq);
            String val = eq < 0 ? "" : arg.substring(eq + 1);
            try {
                if (cmd.equals("shot")) shot(val);
                else if (cmd.equals("key")) key(val, (byte) 8);
                else if (cmd.equals("keyup")) key(val, (byte) 16);
                else if (cmd.equals("sleep")) Thread.sleep(Long.parseLong(val));
                else if (cmd.equals("ls")) ls(val, false);
                else if (cmd.equals("tree")) ls(val, true);
                else if (cmd.equals("mkdir")) { nncp.mkDirs(calcs[0], val); System.out.println("mkdir: " + val + " OK"); }
                else if (cmd.equals("rmdir")) { nncp.rmDir(calcs[0], val); System.out.println("rmdir: " + val + " OK"); }
                else if (cmd.equals("del")) { nncp.deleteFileFromNode(calcs[0], val); System.out.println("del: " + val + " OK"); }
                else if (cmd.equals("send")) {
                    String[] p = split(val);
                    nncp.sendFileToNode(calcs[0], p[0], p[1]);
                    System.out.println("send: " + p[0] + " -> " + p[1] + " OK");
                } else if (cmd.equals("recv")) {
                    String[] p = split(val);
                    nncp.receiveFileFromNode(calcs[0], p[0], p[1]);
                    System.out.println("recv: " + p[0] + " -> " + p[1] + " OK");
                } else {
                    System.out.println("unknown cmd: " + cmd);
                }
            } catch (Exception e) {
                System.out.println("ERR " + cmd + "(" + val + "): " + e);
            }
        }
        System.exit(0);
    }

    private static String[] split(String val) {
        String[] p = val.split("::", 2);
        if (p.length != 2) throw new IllegalArgumentException("expected <a>::<b>, got '" + val + "'");
        return p;
    }

    /**
     * List a directory. Uses enumDirectory rather than listChildren so we get
     * the directory flag and size; note isFile() is unreliable (it can report
     * true for a path that does not exist).
     */
    static void ls(String path, boolean recurse) throws Exception {
        Vector<FileAttributesProxy> entries = nncp.enumDirectory(calcs[0], path);
        System.out.println("ls '" + path + "': " + (entries == null ? 0 : entries.size()));
        if (entries == null) return;
        for (int i = 0; i < entries.size(); i++) {
            FileAttributesProxy fa = entries.get(i);
            String nm = fa.getName();
            if (nm == null || nm.length() == 0 || ".".equals(nm) || "..".equals(nm)) continue;
            String child = path.endsWith("/") ? path + nm : path + "/" + nm;
            System.out.println("  " + (fa.isDirectory() ? "d " : "- ") + child
                    + (fa.isDirectory() ? "" : "  " + fa.getSize()));
            if (recurse && fa.isDirectory()) ls(child, true);
        }
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
}
