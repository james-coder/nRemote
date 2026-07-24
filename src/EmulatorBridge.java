import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP client for a Firebird emulator bridge, so the existing nRemote frontend
 * can drive an emulated TI-Nspire instead of a physical handheld.
 *
 * Wire protocol (ASCII control lines, length-prefixed binary payload):
 *   client -> "SHOT\n"                 server -> "IMG <len>\n" + <len> PNG bytes
 *   client -> "KEY <nRemote-name>\n"   server -> "OK\n"
 *
 * The emulator end (a small patch to Firebird) owns the mapping from an
 * nRemote key name to the physical keypad matrix (keypad_set_key) or the
 * touchpad (touchpad_set_state), and encodes the lcd_draw_frame framebuffer
 * (320x240 grayscale on the classic non-CX Nspire) as PNG.
 *
 * All access is serialized: getScreen() runs on nRemote's screen-fetch thread
 * while sendKey() runs on the EDT, and both share one socket.
 */
public class EmulatorBridge {

    private final String host;
    private final int port;
    private final Object lock = new Object();

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public EmulatorBridge(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Lazily (re)establishes the socket. Returns false if the bridge is unreachable. */
    private boolean ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return true;
        }
        closeQuietly();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setTcpNoDelay(true);
            this.socket = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    private void closeQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        in = null;
        out = null;
    }

    /** Reads one '\n'-terminated ASCII control line without buffering past it. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        throw new IOException("bridge closed the connection");
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("short read from bridge");
            }
            off += n;
        }
    }

    /**
     * Requests the current framebuffer. Returns null on any transport failure so
     * the frontend keeps its last good frame (matching the NavNet path).
     */
    public BufferedImage getScreen() {
        synchronized (lock) {
            if (!ensureConnected()) {
                return null;
            }
            try {
                out.write("SHOT\n".getBytes("US-ASCII"));
                out.flush();
                String header = readLine();
                if (!header.startsWith("IMG ")) {
                    throw new IOException("unexpected reply to SHOT: " + header);
                }
                int len = Integer.parseInt(header.substring(4).trim());
                byte[] png = new byte[len];
                readFully(png);
                return ImageIO.read(new ByteArrayInputStream(png));
            } catch (IOException | NumberFormatException e) {
                System.err.println("nRemote: emulator screen fetch failed: " + e);
                closeQuietly();
                return null;
            }
        }
    }

    /** Sends an nRemote key name (e.g. "~enter~", "a", "~click~") to the emulator. */
    public void sendKey(String keyName) throws IOException {
        synchronized (lock) {
            if (!ensureConnected()) {
                throw new IOException("emulator bridge unreachable at " + host + ":" + port);
            }
            try {
                out.write(("KEY " + keyName + "\n").getBytes("US-ASCII"));
                out.flush();
                String reply = readLine();
                if (!"OK".equals(reply)) {
                    throw new IOException("bridge rejected key '" + keyName + "': " + reply);
                }
            } catch (IOException e) {
                closeQuietly();
                throw e;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closeQuietly();
        }
    }
}
