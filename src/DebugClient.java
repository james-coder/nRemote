import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Talks to the debugger side of the Firebird bridge (firebird-bridge/).
 *
 * Deliberately opens a fresh connection per command instead of holding one:
 * {@link EmulatorBridge} keeps its socket busy fetching the screen many times a
 * second, and a debugger request must not queue behind a frame (or make the
 * screen stutter). The bridge serves each connection on its own thread, so this
 * is cheap and keeps the two concerns independent.
 *
 * Every call is blocking and should be made off the EDT.
 */
public class DebugClient {

    private final String host;
    private final int port;

    public DebugClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Ask the emulator to halt into the debugger. */
    public void halt() throws IOException {
        request("HALT", false);
    }

    /** True when the guest is stopped in the debugger. */
    public boolean isHalted() throws IOException {
        String s = request("DBGSTATE", false).trim();
        return s.startsWith("HALTED") && s.endsWith("1");
    }

    /**
     * Run one Firebird debugger command (see firebird-bridge/README.md) and
     * return everything it printed. Returns an empty string for commands that
     * print nothing, such as "c" and "s".
     */
    public String debug(String command) throws IOException {
        return request("DBG " + command, true);
    }

    // ------------------------------------------------------------------

    private String request(String line, boolean expectBlock) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(15000);
            OutputStream os = s.getOutputStream();
            os.write((line + "\n").getBytes("UTF-8"));
            os.flush();

            InputStream is = s.getInputStream();
            String header = readLine(is);
            if (header == null) return "";
            if (!expectBlock) return header;

            // "OUT <len>" then exactly <len> bytes.
            if (header.startsWith("OUT ")) {
                int len;
                try {
                    len = Integer.parseInt(header.substring(4).trim());
                } catch (NumberFormatException e) {
                    return "";
                }
                byte[] buf = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = is.read(buf, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(buf, 0, off, "UTF-8");
            }
            return header;   // an ERR line, most likely
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = is.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (b.size() == 0 && c < 0) return null;
        return new String(b.toByteArray(), "UTF-8");
    }
}
