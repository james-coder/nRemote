import javax.swing.*;
import java.awt.image.BufferedImage;

/**
 * Window hosting the clickable {@link FaceplatePanel}. Keys are routed to the
 * given listener (NspireKeyboard), so the graphical faceplate drives the same
 * remote as the text keyboard — and, later, the emulator.
 */
public class FaceplateFrame extends javax.swing.JFrame {

    private final FaceplatePanel panel;

    public FaceplateFrame(FaceplatePanel.Listener listener) {
        this(listener, false);
    }

    /**
     * @param primary when true, this is the app's main window (faceplate-only
     *                mode), so closing it quits nRemote; otherwise it just hides.
     */
    public FaceplateFrame(FaceplatePanel.Listener listener, boolean primary) {
        java.awt.Image face = new ImageIcon(getClass().getResource("faceplate.png")).getImage();
        panel = new FaceplatePanel(face);
        panel.setListener(listener);
        setContentPane(panel);
        setTitle("nRemote — TI-Nspire Faceplate");
        ImageIcon icn = new ImageIcon(getClass().getResource("nremote.png"));
        setIconImage(icn.getImage());
        setDefaultCloseOperation(primary ? WindowConstants.EXIT_ON_CLOSE : WindowConstants.HIDE_ON_CLOSE);
        pack();
    }

    public void setScreenImage(BufferedImage img) {
        panel.setScreenImage(img);
    }
}
