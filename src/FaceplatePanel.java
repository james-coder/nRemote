import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A clickable TI-Nspire Touchpad faceplate. Draws the calculator image scaled
 * to any size (letterboxed, aspect preserved), hit-tests the button regions,
 * highlights the one under the cursor, and overlays the live handheld screen
 * into the faceplate's screen bezel.
 *
 * Button rectangles are defined in the image's native 1536x1024 pixel space and
 * mapped to the current component size on the fly, so clicks stay accurate at
 * any window size. Each button carries an action token dispatched by the
 * {@link Listener} (implemented by NspireKeyboard) through its existing
 * modifier/record/refresh-aware key paths.
 */
public class FaceplatePanel extends JPanel {

    public interface Listener {
        void faceplateAction(String action);
        /** A physical key press while the faceplate has focus, to type on the calc. */
        void faceplateKeyPressed(java.awt.event.KeyEvent e);
    }

    private static final int REF_W = 1536, REF_H = 1024;
    // The drawn LCD area in reference pixels: {x1, y1, x2, y2}. Measured from the
    // faceplate's own screen graphic (aspect ~1.52, wider than the calc's 4:3).
    private static final int[] SCREEN = {538, 104, 984, 397};
    // Fill colour for the LCD area so the faceplate's built-in screen picture is
    // hidden; the live 4:3 screen is centred on top, leaving clean margins that
    // blend with the calculator's off-white display instead of a second image.
    private static final Color LCD_BG = new Color(0xEB, 0xF1, 0xE4);

    private final Image faceplate;
    private BufferedImage screenImage;
    private Listener listener;
    private final List<Btn> buttons = new ArrayList<Btn>();
    private Btn hover;

    private static final class Btn {
        final String action;
        final int x1, y1, x2, y2;
        Btn(String action, int x1, int y1, int x2, int y2) {
            this.action = action;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
        boolean hit(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }

    public FaceplatePanel(Image faceplate) {
        this.faceplate = faceplate;
        setBackground(new Color(28, 28, 30));
        setPreferredSize(new Dimension(REF_W / 2, REF_H / 2));
        setMinimumSize(new Dimension(REF_W / 4, REF_H / 4));
        // Receive physical keystrokes; keep Tab out of focus traversal so it
        // reaches the calculator like the other keys.
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        defineButtons();
        MouseAdapter ma = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow(); // keep keyboard focus on the faceplate
                Btn b = at(e.getX(), e.getY());
                if (b != null && listener != null) {
                    listener.faceplateAction(b.action);
                }
            }
            public void mouseMoved(MouseEvent e) {
                Btn b = at(e.getX(), e.getY());
                if (b != hover) {
                    hover = b;
                    setCursor(Cursor.getPredefinedCursor(
                            b != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }
            public void mouseExited(MouseEvent e) {
                if (hover != null) { hover = null; repaint(); }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (listener != null) {
                    listener.faceplateKeyPressed(e);
                }
            }
        });
    }

    /** Grabs keyboard focus so physical typing goes to the calculator. */
    public void focusForKeys() {
        requestFocusInWindow();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setScreenImage(BufferedImage img) {
        this.screenImage = img;
        repaint();
    }

    private void add(String action, int x1, int y1, int x2, int y2) {
        buttons.add(new Btn(action, x1, y1, x2, y2));
    }

    private void defineButtons() {
        // Touchpad: center click first so it wins over the directional edges.
        add("ARROW:click", 734, 486, 800, 526);
        add("ARROW:up",    702, 452, 832, 486);
        add("ARROW:down",  702, 526, 832, 560);
        add("ARROW:left",  676, 476, 722, 536);
        add("ARROW:right", 812, 476, 860, 536);
        // Keys framing the touchpad
        add("KEY:~esc~",       562, 424, 640, 462);
        add("KEY:~home~",      898, 428, 974, 464);
        add("KEY:~ctrl_0~",    556, 484, 622, 520); // scratchpad/book key
        add("KEY:~ctrl_home~", 908, 484, 982, 520); // doc
        add("KEY:~tab~",       562, 536, 636, 572);
        add("KEY:~menu~",      900, 536, 972, 572);
        // Modifier / secondary row
        add("CTRL",           552, 590, 604, 624);
        add("SHIFT",          646, 594, 720, 626);
        add("KEY:~var~",      818, 594, 888, 626);
        add("KEY:~backspace~",936, 594, 982, 626); // del
        // Science column — two keys per row (matches the handheld)
        add("KEY:=",            544, 631, 592, 669);
        add("PALETTE:trig",     598, 631, 646, 669);
        add("KEY:^",            544, 672, 592, 710);
        add("KEY:~square~",     598, 672, 646, 710);
        add("KEY:~e_power_x~",  544, 712, 592, 750);
        add("KEY:~ten_power_x~",598, 712, 646, 750);
        add("KEY:(",            544, 755, 592, 793);
        add("KEY:)",            598, 755, 646, 793);
        // Number pad
        add("KEY:7", 650, 630, 718, 670);
        add("KEY:4", 650, 672, 718, 710);
        add("KEY:1", 650, 712, 718, 750);
        add("KEY:0", 650, 754, 718, 796);
        add("KEY:8", 734, 630, 802, 670);
        add("KEY:5", 734, 672, 802, 710);
        add("KEY:2", 734, 712, 802, 750);
        add("KEY:.", 734, 754, 802, 796);
        add("KEY:9", 816, 630, 884, 670);
        add("KEY:6", 816, 672, 884, 710);
        add("KEY:3", 816, 712, 884, 750);
        add("KEY:~neg~", 816, 754, 884, 796);
        // Operator column — templates/catalog, then two keys per row
        add("KEY:~ctrl_*~", 890, 631, 934, 669);
        add("KEY:~cat~",    942, 631, 986, 669);
        add("KEY:*", 890, 672, 934, 710);
        add("KEY:/", 942, 672, 986, 710);
        add("KEY:+", 890, 712, 934, 750);
        add("KEY:-", 942, 712, 986, 750);
        add("KEY:~enter~", 889, 755, 987, 793);
        // Alpha keyboard, row A
        add("KEY:~ee~",    556, 824, 600, 862);
        add("KEY:a", 604, 824, 648, 862);
        add("KEY:b", 650, 824, 694, 862);
        add("KEY:c", 698, 824, 742, 862);
        add("KEY:d", 746, 824, 790, 862);
        add("KEY:e", 792, 824, 836, 862);
        add("KEY:f", 840, 824, 884, 862);
        add("KEY:g", 888, 824, 930, 862);
        add("PALETTE:sym", 938, 824, 980, 862);
        // Row B
        add("PALETTE:pi",  556, 862, 600, 900);
        add("KEY:h", 604, 862, 648, 900);
        add("KEY:i", 650, 862, 694, 900);
        add("KEY:j", 698, 862, 742, 900);
        add("KEY:k", 746, 862, 790, 900);
        add("KEY:l", 792, 862, 836, 900);
        add("KEY:m", 840, 862, 884, 900);
        add("KEY:n", 888, 862, 930, 900);
        add("KEY:~flag~", 938, 862, 980, 900);
        // Row C
        add("KEY:,",  556, 900, 600, 938);
        add("KEY:o", 604, 900, 648, 938);
        add("KEY:p", 650, 900, 694, 938);
        add("KEY:q", 698, 900, 742, 938);
        add("KEY:r", 746, 900, 790, 938);
        add("KEY:s", 792, 900, 836, 938);
        add("KEY:t", 840, 900, 884, 938);
        add("KEY:u", 888, 900, 930, 938);
        add("KEY:~newline~", 938, 900, 980, 938);
        // Row D
        add("KEY:v", 604, 938, 648, 978);
        add("KEY:w", 650, 938, 694, 978);
        add("KEY:x", 698, 938, 742, 978);
        add("KEY:y", 746, 938, 790, 978);
        add("KEY:z", 792, 938, 836, 978);
        add("KEY: ", 842, 938, 928, 978); // space bar
    }

    private double scale() {
        return Math.min(getWidth() / (double) REF_W, getHeight() / (double) REF_H);
    }

    private double offX() {
        return (getWidth() - REF_W * scale()) / 2.0;
    }

    private double offY() {
        return (getHeight() - REF_H * scale()) / 2.0;
    }

    private Btn at(int px, int py) {
        double s = scale();
        if (s <= 0) return null;
        double rx = (px - offX()) / s;
        double ry = (py - offY()) / s;
        for (Btn b : buttons) {
            if (b.hit(rx, ry)) return b;
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = scale();
        int ox = (int) Math.round(offX());
        int oy = (int) Math.round(offY());
        int dw = (int) Math.round(REF_W * s);
        int dh = (int) Math.round(REF_H * s);
        g2.drawImage(faceplate, ox, oy, dw, dh, this);

        if (screenImage != null && screenImage.getWidth() > 0) {
            int sx = (int) Math.round(ox + SCREEN[0] * s);
            int sy = (int) Math.round(oy + SCREEN[1] * s);
            int sw = (int) Math.round((SCREEN[2] - SCREEN[0]) * s);
            int sh = (int) Math.round((SCREEN[3] - SCREEN[1]) * s);
            // Cover the faceplate's built-in screen graphic first…
            g2.setColor(LCD_BG);
            g2.fillRect(sx, sy, sw, sh);
            // …then draw the live screen centred, aspect ratio preserved.
            double r = Math.min(sw / (double) screenImage.getWidth(),
                                sh / (double) screenImage.getHeight());
            int iw = (int) Math.round(screenImage.getWidth() * r);
            int ih = (int) Math.round(screenImage.getHeight() * r);
            g2.drawImage(screenImage, sx + (sw - iw) / 2, sy + (sh - ih) / 2, iw, ih, this);
        }

        if (hover != null) {
            int hx = (int) Math.round(ox + hover.x1 * s);
            int hy = (int) Math.round(oy + hover.y1 * s);
            int hw = (int) Math.round((hover.x2 - hover.x1) * s);
            int hh = (int) Math.round((hover.y2 - hover.y1) * s);
            g2.setColor(new Color(255, 220, 0, 70));
            g2.fillRoundRect(hx, hy, hw, hh, 10, 10);
            g2.setColor(new Color(255, 200, 0, 190));
            g2.drawRoundRect(hx, hy, hw, hh, 10, 10);
        }
    }
}
