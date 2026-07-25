import javax.swing.*;
import java.awt.image.BufferedImage;

/**
 * Window hosting the clickable {@link FaceplatePanel}. Keys are routed to the
 * given listener (NspireKeyboard), so the graphical faceplate drives the same
 * remote as the text keyboard, and later the emulator.
 */
public class FaceplateFrame extends javax.swing.JFrame {

    private final FaceplatePanel panel;

    public FaceplateFrame(FaceplatePanel.Listener listener) {
        this(listener, false, null);
    }

    public FaceplateFrame(FaceplatePanel.Listener listener, boolean primary) {
        this(listener, primary, null);
    }

    /**
     * @param primary  when true, this is the app's main window (faceplate-only
     *                 mode), so closing it quits nRemote; otherwise it just hides.
     * @param controls optional panel of extra controls (record/load/device
     *                 selection/…) that have no key on the calculator image;
     *                 placed below the faceplate. May be null.
     */
    public FaceplateFrame(FaceplatePanel.Listener listener, boolean primary, java.awt.Component controls) {
        java.awt.Image face = new ImageIcon(getClass().getResource("faceplate.png")).getImage();
        panel = new FaceplatePanel(face);
        panel.setListener(listener);
        getContentPane().setLayout(new java.awt.BorderLayout());
        getContentPane().add(panel, java.awt.BorderLayout.CENTER);
        if (controls != null) {
            getContentPane().add(controls, java.awt.BorderLayout.SOUTH);
        }
        setTitle("nRemote - TI-Nspire Faceplate");
        ImageIcon icn = new ImageIcon(getClass().getResource("nremote.png"));
        setIconImage(icn.getImage());
        setDefaultCloseOperation(primary ? WindowConstants.EXIT_ON_CLOSE : WindowConstants.HIDE_ON_CLOSE);
        // Whenever this window is focused, keep keyboard focus on the faceplate
        // (not the control-bar buttons) so physical typing reaches the calc.
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            public void windowGainedFocus(java.awt.event.WindowEvent e) {
                panel.focusForKeys();
            }
        });
        pack();
    }

    public void focusForKeys() {
        panel.focusForKeys();
    }

    public void setScreenImage(BufferedImage img) {
        panel.setScreenImage(img);
    }
}
