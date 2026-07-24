/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

import javax.swing.*;

/**
 * @author Levak and Adriweb
 */
public class nRemote {

    public static JavaIRC ircHandler;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        boolean noScreenshots = false;
        boolean scan = false;

        if (args.length > 0) {
            for (String str : args) {
                if (str.equals("--no-screenshots")) {
                    noScreenshots = true;
                    System.out.println("-------Screenshots disabled-------");
                }
                if (str.equals("--screen-scan")) {
                    scan = true;
                    System.out.println("-------Screen scanning enabled-------");
                }
            }
        }

        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(NspireKeyboard.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(NspireKeyboard.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(NspireKeyboard.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(NspireKeyboard.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }

        NspireKeyboard k = new NspireKeyboard(noScreenshots, scan);
        k.setVisible(true);
        try {
            Remote.Initialize();
            if (scan) {
                try {
                    //ircHandler = new JavaIRC("TI-Nspire", "#tiplanet-admin", "bwns.be", 4237);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error. Launch a Nspire Computer Software first (See readme)");
            System.exit(0);
        }
        k.RefreshSreen();

        // Polling loop. Network work (node list, screen requests) stays on
        // this thread; all Swing updates are marshalled onto the EDT.
        final NspireKeyboard keyboard = k;
        while (true) {
            boolean changed;
            try {
                changed = Remote.refreshNodes();
            } catch (Exception e) {
                changed = false;
            }
            if (changed) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        keyboard.updateDeviceList();
                        keyboard.updateFields();
                    }
                });
            }
            if (Remote.getNumberOfDevices() > 0) {
                k.RefreshSreen();
                Thread.sleep(150L);
            } else {
                Thread.sleep(2000L);
            }
        }
    }
}
