import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A debugger for the emulated handheld: registers, disassembly, memory, stack
 * and breakpoints, driven through {@link DebugClient}.
 *
 * All of the actual debugging is Firebird's own native debugger. This window
 * issues its commands and presents the results, so the interesting work here is
 * parsing and presentation rather than emulation.
 *
 * Everything that talks to the emulator runs on a worker thread; the UI is only
 * touched back on the EDT. A debugger command can block for a moment (the
 * emulator has to reach an instruction boundary), and freezing the window while
 * that happens would be miserable.
 */
public class DebuggerFrame extends JFrame {

    // A dark palette, chosen so the numbers are what stands out.
    static final Color BG        = new Color(0x1E1E1E);
    static final Color PANEL     = new Color(0x252526);
    static final Color GRID      = new Color(0x333337);
    static final Color FG        = new Color(0xD4D4D4);
    static final Color DIM       = new Color(0x858585);
    static final Color ADDR      = new Color(0x569CD6);   // blue: addresses
    static final Color VALUE     = new Color(0xB5CEA8);   // green: numbers
    static final Color CHANGED   = new Color(0xF0A050);   // orange: just changed
    static final Color PC_ROW    = new Color(0x2D4F3C);   // green wash: the PC
    static final Color BP_COLOR  = new Color(0xE05252);   // red: breakpoints
    static final Color MNEMONIC  = new Color(0xC586C0);
    static final Color ACCENT    = new Color(0x4EC9B0);

    private static final Font MONO = pickMono(12);
    private static final Font MONO_SMALL = pickMono(11);

    private final DebugClient dbg;

    private final JLabel status = new JLabel("not connected");
    private final RegModel regModel = new RegModel();
    private final JTable regTable = new JTable(regModel);
    private final JTextArea disasm = new JTextArea();
    private final JTextArea memory = new JTextArea();
    private final JTextArea stack = new JTextArea();
    private final JTextArea breaks = new JTextArea();
    private final JTextArea console = new JTextArea();
    private final JTextField memAddr = new JTextField("sp", 12);
    private final JTextField disAddr = new JTextField("pc", 12);
    private final JTextField cmdLine = new JTextField();
    private final JLabel cpsrLabel = new JLabel(" ");

    private final JButton bHalt, bCont, bStep, bOver, bRefresh;

    /** Register values from the previous stop, to highlight what changed. */
    private Map<String, String> prevRegs = new HashMap<String, String>();
    private String pcValue = null;
    private final Set<String> bpAddrs = new HashSet<String>();
    private volatile boolean halted = false;
    private volatile boolean busy = false;
    /** Guards the divider auto-placement from being mistaken for a user drag. */
    private boolean applyingDividers = false;
    private boolean userMovedDividers = false;

    public DebuggerFrame(String host, int port) {
        super("nRemote - Emulator Debugger");
        dbg = new DebugClient(host, port);

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        try {
            setIconImage(new ImageIcon(getClass().getResource("nremote.png")).getImage());
        } catch (Exception ignored) { }

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);

        // ---- toolbar ----------------------------------------------------
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        bar.setBackground(PANEL);
        bHalt    = toolButton("Halt",      "Stop the guest and enter the debugger");
        bCont    = toolButton("Continue",  "Resume until a breakpoint is hit");
        bStep    = toolButton("Step",      "Execute one instruction");
        bOver    = toolButton("Step Over", "Run to the next instruction (over calls)");
        bRefresh = toolButton("Refresh",   "Re-read registers, disassembly and memory");
        bar.add(bHalt); bar.add(bCont); bar.add(bStep); bar.add(bOver);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(bRefresh);
        bar.add(Box.createHorizontalStrut(16));
        status.setForeground(DIM);
        status.setFont(MONO_SMALL);
        bar.add(status);
        root.add(bar, BorderLayout.NORTH);

        bHalt.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doHalt(); }
        });
        bCont.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { runCmd("c", true); }
        });
        bStep.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { runCmd("s", true); }
        });
        bOver.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { runCmd("n", true); }
        });
        bRefresh.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { refreshAll(); }
        });

        // ---- registers ---------------------------------------------------
        styleTable(regTable);
        JPanel regPane = titled("Registers", new JScrollPane(regTable));
        cpsrLabel.setFont(MONO_SMALL);
        cpsrLabel.setForeground(ACCENT);
        cpsrLabel.setBorder(new EmptyBorder(4, 8, 6, 8));
        regPane.add(cpsrLabel, BorderLayout.SOUTH);
        regPane.setMinimumSize(new Dimension(180, 200));

        // ---- disassembly ---------------------------------------------------
        styleArea(disasm);
        JPanel disPane = titled("Disassembly", new JScrollPane(disasm));
        disPane.add(addrBar(disAddr, new ActionListener() {
            public void actionPerformed(ActionEvent e) { refreshAll(); }
        }, "Go"), BorderLayout.SOUTH);

        // Click a disassembly line to toggle an execution breakpoint on it.
        disasm.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) toggleBreakpointAtCaret();
            }
        });

        // ---- memory ---------------------------------------------------------
        styleArea(memory);
        JPanel memPane = titled("Memory", new JScrollPane(memory));
        memPane.add(addrBar(memAddr, new ActionListener() {
            public void actionPerformed(ActionEvent e) { refreshAll(); }
        }, "Dump"), BorderLayout.SOUTH);

        // ---- lower tabs -------------------------------------------------------
        styleArea(stack); styleArea(breaks); styleArea(console);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(PANEL);
        tabs.setForeground(FG);
        tabs.setFont(MONO_SMALL);
        tabs.addTab("Stack", new JScrollPane(stack));
        tabs.addTab("Breakpoints", new JScrollPane(breaks));
        tabs.addTab("Console", consolePane());

        // ---- layout -------------------------------------------------------------
        final JSplitPane midSplit = split(JSplitPane.HORIZONTAL_SPLIT, disPane, memPane, 0.58);
        final JSplitPane topSplit = split(JSplitPane.HORIZONTAL_SPLIT, regPane, midSplit, 0.22);
        final JSplitPane all = split(JSplitPane.VERTICAL_SPLIT, topSplit, tabs, 0.68);
        root.add(all, BorderLayout.CENTER);

        // Divider placement. Proportional locations interact badly across three
        // nested splits, so place them in pixels from the real size. Re-applied
        // whenever the window is resized, but only until the user drags a
        // divider themselves, after which their choice is left alone.
        java.beans.PropertyChangeListener dragWatch = new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                if (!applyingDividers) userMovedDividers = true;
            }
        };
        all.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, dragWatch);
        topSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, dragWatch);
        midSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, dragWatch);

        final JPanel rootRef = root;
        addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (userMovedDividers || getWidth() < 400 || getHeight() < 300) return;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        int w = rootRef.getWidth(), h = rootRef.getHeight();
                        if (w < 400 || h < 300) return;
                        applyingDividers = true;
                        int regW = 260;
                        all.setDividerLocation(Math.max(220, (int) (h * 0.60)));
                        topSplit.setDividerLocation(regW);
                        midSplit.setDividerLocation(Math.max(240, (int) ((w - regW) * 0.56)));
                        applyingDividers = false;
                    }
                });
            }
        });

        setContentPane(root);
        setSize(1180, 720);
        setLocationByPlatform(true);
        setControlsEnabled(false);
        pollState();
    }

    // ================================================================ actions

    private void doHalt() {
        setBusy(true, "halting...");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    dbg.halt();
                    // The CPU stops at the next instruction boundary, which is
                    // usually immediate but is not guaranteed to be.
                    for (int i = 0; i < 40 && !dbg.isHalted(); i++) {
                        try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                    }
                } catch (IOException e) {
                    err = e.toString();
                }
                final String fe = err;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, null);
                        if (fe != null) { status.setText("halt failed: " + fe); return; }
                        refreshAll();
                    }
                });
            }
        }, "dbg-halt").start();
    }

    /** Run a debugger command; when refresh is set, re-read every pane after. */
    private void runCmd(final String cmd, final boolean refresh) {
        if (busy) return;
        setBusy(true, "running '" + cmd + "'...");
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    out = dbg.debug(cmd);
                } catch (IOException e) {
                    out = "error: " + e;
                }
                final String fo = out;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        if (fo != null && fo.length() > 0) appendConsole(fo);
                        setBusy(false, null);
                        if (refresh) {
                            // "c" runs on; give it a moment to hit a breakpoint
                            // before we ask what the state is.
                            Timer t = new Timer(250, new ActionListener() {
                                public void actionPerformed(ActionEvent e) { refreshAll(); }
                            });
                            t.setRepeats(false);
                            t.start();
                        }
                    }
                });
            }
        }, "dbg-cmd").start();
    }

    /** Re-read state and repopulate every pane. */
    public void refreshAll() {
        if (busy) return;
        setBusy(true, "reading state...");
        new Thread(new Runnable() {
            public void run() {
                boolean h = false;
                String regs = "", dis = "", mem = "", bt = "", bps = "";
                String err = null;
                try {
                    h = dbg.isHalted();
                    if (h) {
                        regs = dbg.debug("r");
                        dis  = dbg.debug("u " + expr(disAddr.getText()));
                        mem  = dbg.debug("d " + expr(memAddr.getText()));
                        bt   = dbg.debug("b");
                        bps  = dbg.debug("k");
                    }
                } catch (IOException e) {
                    err = e.toString();
                }
                final boolean fh = h;
                final String fr = regs, fd = dis, fm = mem, fb = bt, fk = bps, fe = err;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        halted = fh;
                        setBusy(false, null);
                        if (fe != null) {
                            status.setText("not reachable: " + fe);
                            setControlsEnabled(false);
                            return;
                        }
                        setControlsEnabled(true);
                        if (!fh) {
                            status.setText("running  (Halt to inspect)");
                            return;
                        }
                        applyRegisters(fr);
                        disasm.setText(fd);
                        disasm.setCaretPosition(0);
                        memory.setText(fm);
                        memory.setCaretPosition(0);
                        stack.setText(fb.length() == 0 ? "(no frames)" : fb);
                        breaks.setText(fk.length() == 0 ? "(none)" : fk);
                        parseBreakpoints(fk);
                        status.setText("halted at " + (pcValue == null ? "?" : pcValue));
                    }
                });
            }
        }, "dbg-refresh").start();
    }

    /** Poll every couple of seconds so the window notices a breakpoint hit. */
    private void pollState() {
        Timer t = new Timer(2000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (busy || !isVisible()) return;
                new Thread(new Runnable() {
                    public void run() {
                        boolean h;
                        try { h = dbg.isHalted(); } catch (IOException ex) { return; }
                        if (h && !halted) {                 // just stopped: a breakpoint
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() { refreshAll(); }
                            });
                        } else if (!h && halted) {
                            halted = false;
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() { status.setText("running"); }
                            });
                        }
                    }
                }, "dbg-poll").start();
            }
        });
        t.start();
    }

    private void toggleBreakpointAtCaret() {
        String line = caretLine(disasm);
        if (line == null) return;
        int colon = line.indexOf(':');
        if (colon <= 0) return;
        final String addr = line.substring(0, colon).trim();
        if (addr.length() == 0) return;
        final boolean on = bpAddrs.contains(addr.toLowerCase());
        runCmd("k " + addr + (on ? " -x" : " +x"), true);
    }

    // ================================================================ parsing

    /**
     * Pull "name=value" pairs out of the debugger's register dump and note which
     * changed since the last stop. Seeing at a glance what an instruction just
     * altered is the single most useful thing a register pane does.
     */
    private void applyRegisters(String text) {
        Map<String, String> now = new HashMap<String, String>();
        String cpsrExtra = "";
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            int paren = ln.indexOf('(');
            if (paren >= 0 && ln.indexOf("cpsr") >= 0) cpsrExtra = ln.substring(paren).trim();
            String[] parts = ln.replace('(', ' ').split("\\s+");
            for (int j = 0; j < parts.length; j++) {
                int eq = parts[j].indexOf('=');
                if (eq > 0) {
                    String k = parts[j].substring(0, eq).trim();
                    String v = parts[j].substring(eq + 1).trim();
                    if (k.length() > 0 && v.length() > 0) now.put(k, v);
                }
            }
        }
        if (now.containsKey("pc")) pcValue = now.get("pc");
        regModel.update(now, prevRegs);
        prevRegs = now;
        cpsrLabel.setText(cpsrExtra.length() > 0 ? cpsrExtra : " ");
    }

    private void parseBreakpoints(String text) {
        bpAddrs.clear();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.length() == 0) continue;
            int sp = t.indexOf(' ');
            String a = (sp > 0 ? t.substring(0, sp) : t).trim().toLowerCase();
            if (a.length() > 0) bpAddrs.add(a);
        }
    }

    /** Let the address boxes accept "pc", "sp" and friends as well as hex. */
    private String expr(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() == 0) return "pc";
        if (t.equalsIgnoreCase("pc") && pcValue != null) return pcValue;
        if (prevRegs.containsKey(t.toLowerCase())) return prevRegs.get(t.toLowerCase());
        return t;
    }

    // ================================================================ plumbing

    private void setBusy(boolean b, String msg) {
        busy = b;
        if (msg != null) status.setText(msg);
        setControlsEnabled(!b && true);
    }

    private void setControlsEnabled(boolean on) {
        bCont.setEnabled(on && halted && !busy);
        bStep.setEnabled(on && halted && !busy);
        bOver.setEnabled(on && halted && !busy);
        bHalt.setEnabled(on && !halted && !busy);
        bRefresh.setEnabled(!busy);
    }

    private void appendConsole(String s) {
        console.append(s);
        if (!s.endsWith("\n")) console.append("\n");
        console.setCaretPosition(console.getDocument().getLength());
    }

    private static String caretLine(JTextArea a) {
        try {
            int line = a.getLineOfOffset(a.getCaretPosition());
            return a.getText().split("\n")[line];
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ chrome

    private JPanel consolePane() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.add(new JScrollPane(console), BorderLayout.CENTER);
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(PANEL);
        row.setBorder(new EmptyBorder(4, 6, 4, 6));
        JLabel l = new JLabel("cmd:");
        l.setForeground(DIM);
        l.setFont(MONO_SMALL);
        cmdLine.setFont(MONO);
        cmdLine.setBackground(BG);
        cmdLine.setForeground(FG);
        cmdLine.setCaretColor(ACCENT);
        cmdLine.setToolTipText("Any Firebird debugger command: r, u, d <addr>, b, k, s, n, c, mmu, ss, pr, pw");
        cmdLine.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String c = cmdLine.getText().trim();
                if (c.length() == 0) return;
                appendConsole("> " + c);
                cmdLine.setText("");
                runCmd(c, true);
            }
        });
        row.add(l, BorderLayout.WEST);
        row.add(cmdLine, BorderLayout.CENTER);
        p.add(row, BorderLayout.SOUTH);
        return p;
    }

    private JPanel addrBar(JTextField f, ActionListener go, String label) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(PANEL);
        row.setBorder(new EmptyBorder(4, 6, 4, 6));
        JLabel l = new JLabel("addr:");
        l.setForeground(DIM);
        l.setFont(MONO_SMALL);
        f.setFont(MONO);
        f.setBackground(BG);
        f.setForeground(FG);
        f.setCaretColor(ACCENT);
        f.setToolTipText("A hex address, or a register name such as pc, sp, lr, r4");
        f.addActionListener(go);
        JButton b = toolButton(label, null);
        b.addActionListener(go);
        row.add(l, BorderLayout.WEST);
        row.add(f, BorderLayout.CENTER);
        row.add(b, BorderLayout.EAST);
        return row;
    }

    private static JPanel titled(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        JLabel l = new JLabel(title);
        l.setForeground(ACCENT);
        l.setFont(MONO_SMALL.deriveFont(Font.BOLD));
        l.setBorder(new EmptyBorder(6, 8, 4, 8));
        l.setOpaque(true);
        l.setBackground(PANEL);
        p.add(l, BorderLayout.NORTH);
        if (inner instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) inner;
            sp.setBorder(BorderFactory.createLineBorder(GRID));
            sp.getViewport().setBackground(BG);
        }
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private static JSplitPane split(int orient, JComponent a, JComponent b, double weight) {
        JSplitPane sp = new JSplitPane(orient, a, b);
        sp.setResizeWeight(weight);
        sp.setBorder(null);
        sp.setBackground(BG);
        sp.setDividerSize(4);
        return sp;
    }

    private static JButton toolButton(String text, String tip) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setFont(MONO_SMALL);
        b.setBackground(new Color(0x333337));
        b.setForeground(FG);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID),
                new EmptyBorder(3, 10, 3, 10)));
        if (tip != null) b.setToolTipText(tip);
        return b;
    }

    private static void styleArea(JTextArea a) {
        a.setEditable(false);
        a.setFont(MONO);
        a.setBackground(BG);
        a.setForeground(FG);
        a.setCaretColor(ACCENT);
        a.setTabSize(8);
    }

    private void styleTable(JTable t) {
        t.setFont(MONO);
        t.setBackground(BG);
        t.setForeground(FG);
        t.setGridColor(GRID);
        t.setRowHeight(20);
        t.setShowVerticalLines(false);
        t.getTableHeader().setBackground(PANEL);
        t.getTableHeader().setForeground(DIM);
        t.getTableHeader().setFont(MONO_SMALL);
        t.setDefaultRenderer(Object.class, new RegRenderer());
    }

    private static Font pickMono(int size) {
        String[] want = { "Consolas", "DejaVu Sans Mono", "Menlo", "Monospaced" };
        String[] have = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (int i = 0; i < want.length; i++) {
            for (int j = 0; j < have.length; j++) {
                if (want[i].equalsIgnoreCase(have[j])) return new Font(want[i], Font.PLAIN, size);
            }
        }
        return new Font("Monospaced", Font.PLAIN, size);
    }

    // ================================================================ model

    /** Registers, with a flag per row for "changed since the last stop". */
    private static class RegModel extends AbstractTableModel {
        private final List<String> names = new ArrayList<String>();
        private final List<String> values = new ArrayList<String>();
        private final List<Boolean> changed = new ArrayList<Boolean>();

        private static final String[] ORDER = {
            "r0","r1","r2","r3","r4","r5","r6","r7",
            "r8","r9","r10","r11","r12","sp","lr","pc","cpsr","spsr"
        };

        void update(Map<String, String> now, Map<String, String> before) {
            names.clear(); values.clear(); changed.clear();
            for (int i = 0; i < ORDER.length; i++) {
                String k = ORDER[i];
                if (!now.containsKey(k)) continue;
                String v = now.get(k);
                names.add(k);
                values.add(v);
                String old = before.get(k);
                changed.add(Boolean.valueOf(old != null && !old.equals(v)));
            }
            fireTableDataChanged();
        }

        boolean isChanged(int row) {
            return row >= 0 && row < changed.size() && changed.get(row).booleanValue();
        }

        public int getRowCount() { return names.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int c) { return c == 0 ? "reg" : "value"; }
        public Object getValueAt(int r, int c) { return c == 0 ? names.get(r) : values.get(r); }
    }

    private class RegRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            c.setFont(MONO);
            boolean ch = regModel.isChanged(row);
            String name = String.valueOf(regModel.getValueAt(row, 0));
            if (sel) {
                c.setBackground(new Color(0x264F78));
                c.setForeground(Color.WHITE);
            } else {
                c.setBackground("pc".equals(name) ? PC_ROW : BG);
                if (col == 0) c.setForeground(DIM);
                else c.setForeground(ch ? CHANGED : VALUE);
            }
            setBorder(new EmptyBorder(0, 8, 0, 8));
            return c;
        }
    }
}
