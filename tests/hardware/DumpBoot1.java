import com.ti.et.education.commproxy.INodeID;
import com.ti.et.education.commproxy.INodeInfo;
import com.ti.et.navnetcommproxy.FileAttributesProxy;
import com.ti.et.navnetcommproxy.NavNetCommProxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

/**
 * Guided boot1 dumper for a classic (non-CX) TI-Nspire.
 *
 * Firebird needs a boot1 bootrom to emulate a handheld, and boot1 cannot be
 * generated or legally downloaded: you have to dump it from your own device.
 * Doing that by hand is fiddly and has sharp edges, so this walks the whole
 * thing: it checks the handheld is a supported model and OS, copies every
 * document off and checks each copy is the right size, transfers the tools,
 * tells you exactly which three things to do on the device, retrieves and
 * validates the dumps, then removes what it added and compares the filesystem
 * against how it started.
 *
 * It deliberately does NOT drive the calculator's file browser for you. Menu
 * row positions depend on your own document tree, and a mis-aimed Enter is the
 * one mistake worth avoiding here, so the three on-device actions are yours.
 * Everything else is automatic, including waiting for and detecting each step.
 *
 * WHAT THIS CHANGES ON YOUR CALCULATOR
 *   - Installs Ndless. On OS 3.6 that is RAM only and a reboot removes it; it
 *     writes nothing to the boot partitions.
 *   - Creates and then deletes an "ndless" folder and about 2.5 MB of dumps.
 *   - Your documents are never opened or modified, and are copied off first.
 *   - Flash wear from those writes is not reversible, and the recent-documents
 *     list changes. "Exactly as found" is functional, not byte for byte.
 *
 * THE ONE-WAY DOOR. Opening the Ndless installer on a classic handheld
 * clobbers a signature copy that an OS monitor thread watches. If the install
 * does not then complete, the OS can wipe itself: your documents survive but
 * the handheld needs an OS reinstall over USB. So once you open the installer,
 * either finish the install or reboot the handheld. Do not open it and wander
 * off, and have the matching OS .tno downloaded before you start.
 *
 * BEFORE YOU START: fit fresh batteries. Below roughly 25% the handheld refuses
 * an OS install and below 20% it refuses the maintenance menu, which turns a
 * small problem into a large one. Have the handheld in front of you.
 *
 * Build (needs the real TI jars and the TI-bundled JRE, which is Java 7):
 *   LIB="…/TI-Nspire Computer Link/lib"
 *   javac -source 1.7 -target 1.7 -cp "$LIB/commproxy.jar:$LIB/navnet.jar:$LIB/navnetcommproxy.jar" \
 *         -d out tests/hardware/DumpBoot1.java
 *
 * Run (launch the TI software first, then):
 *   java -Djava.library.path="$LIB" -cp "out:$LIB/*" DumpBoot1 <workdir> [installer.tns]
 *
 * <workdir> must already contain, from the Ndless release zip and TI-Planet:
 *   ndless_installer_<ver>.tns, ndless_resources.tns, polyDumper.tns
 * See tests/hardware/README.md for where to get them.
 */
public class DumpBoot1 {

    /**
     * The one combination that has actually been run end to end, so it is the
     * only one we will pick an installer for automatically.
     */
    private static final String VERIFIED_VERSION = "3.60.0.546";
    private static final String VERIFIED_INSTALLER = "ndless_installer_3.6.tns";

    /**
     * Other classic builds Ndless r2022 lists as supported, in the form the
     * link reports them. We deliberately do NOT map these to an installer:
     * 3.9.0 ships two variants (`_classic` and `_classic_new`) chosen by your
     * boot1 version, and r2022 ships no 3.1 installer at all. Guessing wrong
     * means running a mismatched exploit, so for these you name the file.
     */
    private static final String[] OTHER_SUPPORTED = { "3.90.0.463", "3.90.1.38", "3.10.0.392" };

    /**
     * SHA-256 of the exact files used in the run this tool was built from, so a
     * wrong or tampered download is caught before it is opened on the handheld.
     * A mismatch warns rather than refuses: other Ndless builds are legitimate
     * for other OS versions, and only these three have been through a real run.
     */
    private static final String[][] KNOWN_GOOD = {
        { "ndless_installer_3.6.tns", "96da59d817074bf54be514224960755f5d067a29a93d7f876a3694fa54b20f6b" },
        { "ndless_resources.tns",     "5994e5096d16e2c4289bc9bc976f0e50f6703c6c603cf9476e043c5a41a41330" },
        { "polyDumper.tns",           "437e423f801089b618d483d86bcb0a8b2bcb7e466449e0173e9cada29986d7ee" },
    };

    /** Files PolyDumper writes on a classic handheld, next to its own .tns. */
    private static final String[] DUMPS = {
        "boot1.img.tns", "boot1alt.img.tns", "boot2.img.tns",
        "diags.img.tns", "manuf.img.tns",
    };

    private static final int BOOT1_SIZE = 0x80000; // 524288

    private static NavNetCommProxy nncp;
    private static INodeID calc;
    /** Serial of the handheld we started with; every later operation re-checks it. */
    private static String serial;
    /** Every path this run created on the handheld. Only these are ever deleted. */
    private static final List<String> weCreated = new ArrayList<String>();
    /** True once anything has been written to the device. */
    private static boolean deviceModified = false;
    /** True once the user has opened the Ndless installer (the one-way door). */
    private static boolean doorOpened = false;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: DumpBoot1 <workdir> [ndless_installer_....tns]");
            System.out.println("  <workdir> holds the Ndless + PolyDumper .tns files and receives");
            System.out.println("  backup/ and dumps/. Name the installer only if this tool asks.");
            System.exit(2);
        }
        try {
            run(new File(args[0]), args.length > 1 ? args[1] : null);
        } catch (Throwable t) {
            System.out.println();
            System.out.println("  UNEXPECTED FAILURE: " + t);
            leftoverEpilogue("The run stopped early because of the error above.");
            System.exit(1);
        }
    }

    private static void run(File work, String explicitInstaller) throws Exception {
        File backupDir = new File(work, "backup");
        File dumpDir = new File(work, "dumps");

        banner();

        // ---- 1. connect and identify --------------------------------------
        step(1, "Connecting to the handheld");
        connect();
        INodeInfo info = nncp.getNodeInfo(calc);
        String name = safe(info.getName());
        String version = String.valueOf(info.getNodeSWVersionsInfo().getVersion()).trim();
        serial = safe(info.getSerialNumber());
        System.out.println("    model   : " + name);
        System.out.println("    OS      : " + version);
        System.out.println("    serial  : " + serial);

        // The OS version alone does not identify the family, and a CX or CX II
        // needs entirely different files. Require a positive identification.
        String lower = name.toLowerCase();
        if (lower.indexOf("cx") >= 0 || lower.indexOf("cm") >= 0) {
            System.out.println();
            System.out.println("  STOP. This reports as '" + name + "', which is not a classic");
            System.out.println("  (non-CX) handheld. CX, CM and CX II need different Ndless and");
            System.out.println("  PolyDumper files and a different Firebird product code, and this");
            System.out.println("  tool has only been run against a classic Touchpad.");
            System.exit(1);
        }
        if (lower.indexOf("nspire") < 0) {
            System.out.println();
            System.out.println("  STOP. Could not positively identify this as a TI-Nspire");
            System.out.println("  (it reports as '" + name + "'). Refusing to guess.");
            System.exit(1);
        }

        String installer = chooseInstaller(version, explicitInstaller);
        System.out.println("    installer: " + installer);

        // ---- 2. check the local tool files ---------------------------------
        step(2, "Checking the files you need are in " + work.getAbsolutePath());
        File fInstaller = new File(work, installer);
        File fResources = new File(work, "ndless_resources.tns");
        File fPoly = new File(work, "polyDumper.tns");
        boolean missing = false;
        missing |= requireFile(fInstaller, "from the Ndless r2022 release zip");
        missing |= requireFile(fResources, "from the Ndless r2022 release zip");
        missing |= requireFile(fPoly, "from TI-Planet archive id 3829 (polydumper_5/polyDumper.tns)");
        if (missing) {
            System.out.println("  See tests/hardware/README.md for the download links.");
            System.exit(1);
        }
        // A stale backup or dumps folder must never be mistaken for this run's.
        requireEmptyOrAbsent(backupDir, "backup");
        requireEmptyOrAbsent(dumpDir, "dumps");

        // ---- 3. copy every document off, checking sizes ---------------------
        step(3, "Copying every document to " + backupDir.getAbsolutePath());
        List<String> before = new ArrayList<String>();
        long[] tally = new long[2];              // {files, bytes}
        backup("/", backupDir, before, tally);
        Collections.sort(before);
        System.out.println("    copied " + tally[0] + " file(s), " + tally[1] + " bytes");
        System.out.println("    every copy matched the size the handheld reported");
        if (tally[0] == 0) {
            System.out.println("    (the handheld reported no documents at all)");
        }

        // ---- 4. informed consent --------------------------------------------
        step(4, "Confirm");
        System.out.println("  About to install Ndless and run PolyDumper on this handheld.");
        System.out.println();
        System.out.println("  Reversible : " + ramOnlyClaim(version));
        System.out.println("  Permanent  : about 2.5 MB of flash writes (wear), and the");
        System.out.println("               recent-documents list changes.");
        System.out.println();
        System.out.println("  WORST CASE : opening the Ndless installer clobbers a signature copy");
        System.out.println("               that an OS monitor thread watches. If the install does");
        System.out.println("               not complete, the OS can wipe itself. Your documents");
        System.out.println("               survive, but the handheld then needs an OS reinstall");
        System.out.println("               over USB using the .tno for its exact build. Download");
        System.out.println("               that .tno NOW if you have not already.");
        System.out.println();
        System.out.println("  Batteries  : fresh cells, please. Below ~25% the handheld refuses an");
        System.out.println("               OS install; below ~20% it refuses the maintenance menu.");
        System.out.println();
        System.out.println("  Your documents are copied to " + backupDir.getAbsolutePath());
        if (!confirm("  Type 'yes' to continue: ")) {
            System.out.println("  Aborted. Nothing was changed on the handheld.");
            System.exit(0);
        }

        // ---- 5. transfer the tools -------------------------------------------
        step(5, "Transferring the tools");
        // Never clobber something that was already there. Ndless hardcodes the
        // folder name "ndless", so if the user already has one (very likely if
        // they have used Ndless before) we borrow it but must not delete it,
        // and we refuse to overwrite anything inside it.
        boolean ndlessExisted = exists("/ndless");
        String[][] transfers = {
            { fInstaller.getAbsolutePath(), "/ndless/" + installer },
            { fResources.getAbsolutePath(), "/ndless/ndless_resources.tns" },
            // PolyDumper writes its dumps beside itself, so keep it in the same
            // folder: everything we create then lives under /ndless.
            { fPoly.getAbsolutePath(), "/ndless/polyDumper.tns" },
        };
        if (ndlessExisted) {
            System.out.println("    an 'ndless' folder already exists on the handheld;");
            System.out.println("    it will be used but NOT deleted.");
            boolean clash = false;
            for (int i = 0; i < transfers.length; i++) {
                if (exists(transfers[i][1])) {
                    System.out.println("    CLASH: " + transfers[i][1] + " already exists.");
                    clash = true;
                }
            }
            // Dumps land in the same folder and get deleted at cleanup, so a
            // pre-existing one would be someone else's file that we would eat.
            for (int i = 0; i < DUMPS.length; i++) {
                if (exists("/ndless/" + DUMPS[i])) {
                    System.out.println("    CLASH: /ndless/" + DUMPS[i] + " already exists.");
                    clash = true;
                }
            }
            if (clash) {
                System.out.println();
                System.out.println("  STOP. Refusing to overwrite files that are already on your");
                System.out.println("  handheld. Move or rename them (or the whole 'ndless' folder)");
                System.out.println("  and run this again. Nothing has been changed.");
                System.exit(1);
            }
        } else {
            deviceModified = true;
            mkdir("/ndless");
        }
        for (int i = 0; i < transfers.length; i++) {
            deviceModified = true;
            send(new File(transfers[i][0]), transfers[i][1]);
        }
        System.out.println("    done. On the handheld these are in the 'ndless' folder.");
        System.out.println("    (Dismiss any 'Document Received' popups with the OK button.)");

        // ---- 6. you install Ndless --------------------------------------------
        step(6, "YOUR TURN: install Ndless");
        System.out.println("  On the handheld:");
        System.out.println("    1. Press [home], then 2 for My Documents.");
        System.out.println("    2. Open the 'ndless' folder.");
        System.out.println("    3. Open '" + stripTns(installer) + "'.   <-- this is the one-way door");
        System.out.println("    4. Press the [menu] key.");
        System.out.println();
        System.out.println("  The screen blanks and the OS restarts. That is normal and takes a");
        System.out.println("  few seconds. You should land on the home screen with");
        System.out.println("      'Ndless successfully installed!'");
        System.out.println();
        System.out.println("  IF IT HANGS, in this order:");
        System.out.println("    - wait about 60 seconds; the blank screen is part of the install");
        System.out.println("    - press [esc]");
        System.out.println("    - hold the reset button on the back for a few seconds");
        System.out.println("    - pull a battery for 5 seconds and reinsert");
        System.out.println("    - if it says 'Operating System not found', reinstall the OS over");
        System.out.println("      USB from the TI software using the .tno for this exact build;");
        System.out.println("      your documents are preserved by that, and a copy is in");
        System.out.println("      " + backupDir.getAbsolutePath());
        System.out.println();
        doorOpened = true;   // from here on, assume the installer may be open
        if (!confirm("  Did you see 'Ndless successfully installed!'? Type 'yes': ")) {
            System.out.println();
            System.out.println("  ==================================================================");
            System.out.println("  REBOOT THE HANDHELD NOW, before anything else.");
            System.out.println("  Press the reset button on the back (or pull a battery for 5 s).");
            System.out.println("  The installer was opened, so the handheld must either finish the");
            System.out.println("  install or be rebooted. Do not leave it in this state.");
            System.out.println("  ==================================================================");
            System.out.println();
            confirm("  Type 'yes' once you have rebooted it: ");
            System.out.println();
            System.out.println("  Now removing the files this tool transferred (if the handheld is");
            System.out.println("  reachable again).");
            cleanup();
            leftoverEpilogue("Ndless was not installed, so no dump was taken.");
            System.exit(1);
        }

        // ---- 7. you run PolyDumper ---------------------------------------------
        step(7, "YOUR TURN: run PolyDumper");
        System.out.println("  Waiting for the handheld to come back after the restart...");
        if (!waitForLink(true, 180)) {
            leftoverEpilogue("The handheld did not reappear on USB after the Ndless install.");
            System.exit(1);
        }
        System.out.println("    handheld is back.");
        System.out.println();
        System.out.println("  Now open 'polyDumper' in the same 'ndless' folder.");
        System.out.println("  Run that copy, not one from anywhere else: PolyDumper writes its");
        System.out.println("  output next to its own file, and this tool looks for it in 'ndless'.");
        System.out.println("  It dumps boot1, boot1alt, boot2, diags and manuf.");
        System.out.println();
        System.out.println("  Waiting for it to start. The USB link goes quiet while it runs,");
        System.out.println("  because a native Ndless program owns USB; that is expected.");
        if (!waitForLink(false, 300)) {
            System.out.println("    The handheld never dropped off the link.");
            leftoverEpilogue("PolyDumper does not appear to have been started.");
            System.exit(1);
        }
        System.out.println("    The handheld has dropped off the link. That is what PolyDumper");
        System.out.println("    looks like from here. (If you did not open it, check the handheld");
        System.out.println("    has not simply reset or been unplugged.)");
        System.out.println();
        System.out.println("  WAIT for the handheld to show:  'Complete - press any key ...'");
        System.out.println("  Only then, PRESS ANY KEY ON THE HANDHELD ITSELF. It reads the");
        System.out.println("  physical keypad directly, so a key sent over USB cannot dismiss it.");
        System.out.println("  Once that message is showing, your dumps are written and closed.");
        System.out.println();
        System.out.println("  Waiting for the handheld to come back...");
        if (!waitForLink(true, 900)) {
            System.out.println("    Timed out waiting for the handheld.");
            leftoverEpilogue("PolyDumper may still be waiting for a physical key press.");
            System.out.println("  If the dumps were written, collect them WITHOUT re-running this");
            System.out.println("  tool (a re-run would repeat the install). Use NspireCtl:");
            printRecvCommands(dumpDir);
            System.exit(1);
        }
        System.out.println("    Link is back.");

        // ---- 8. retrieve ---------------------------------------------------------
        step(8, "Retrieving the dumps to " + dumpDir.getAbsolutePath());
        dumpDir.mkdirs();
        int got = 0;
        boolean boot1Retrieved = false;
        for (int i = 0; i < DUMPS.length; i++) {
            String remote = "/ndless/" + DUMPS[i];
            File local = new File(dumpDir, DUMPS[i]);
            if (local.exists() && !local.delete()) {
                System.out.println("    cannot overwrite " + local.getAbsolutePath());
                continue;
            }
            boolean present;
            try {
                present = exists(remote);
            } catch (RuntimeException e) {
                present = false;
            }
            // Track for cleanup whether or not we manage to pull it down: the
            // dump is on the device either way and it is ours to remove.
            if (present) weCreated.add(remote);
            if (!present) {
                System.out.println("    " + DUMPS[i] + "  not on the handheld");
                continue;
            }
            try {
                nncp.receiveFileFromNode(calc, remote, local.getAbsolutePath());
                if (local.isFile() && local.length() > 0) {
                    System.out.println("    " + DUMPS[i] + "  " + local.length() + " bytes");
                    got++;
                    if (DUMPS[i].equals("boot1.img.tns")) boot1Retrieved = true;
                } else {
                    System.out.println("    " + DUMPS[i] + "  retrieved 0 bytes  <-- failed");
                }
            } catch (Exception e) {
                System.out.println("    " + DUMPS[i] + "  not retrieved (" + brief(e) + ")");
            }
        }
        if (got == 0) {
            leftoverEpilogue("No dumps were retrieved. Did PolyDumper actually run?");
            System.out.println("  Nothing was deleted from the handheld, so if the dumps are there");
            System.out.println("  you can collect them without re-running this tool:");
            printRecvCommands(dumpDir);
            System.exit(1);
        }

        // ---- 9. validate boot1 -----------------------------------------------------
        step(9, "Validating boot1");
        boolean boot1ok = false;
        if (boot1Retrieved) {
            File boot1 = new File(dumpDir, "boot1.img.tns");
            boot1ok = validateBoot1(boot1);
            if (boot1ok) {
                File raw = new File(dumpDir, "boot1.img");
                copy(boot1, raw);
                System.out.println("    wrote " + raw.getAbsolutePath() + " (ready for Firebird)");
            }
        } else {
            System.out.println("    boot1.img.tns was not retrieved in this run.");
        }

        // ---- 10. clean up (only when we actually have what we came for) -------------
        step(10, "Cleaning up the handheld");
        boolean cleaned = false;
        if (boot1ok && got == DUMPS.length) {
            cleanup();
            cleaned = true;
        } else {
            System.out.println("    NOT deleting anything: boot1 did not validate, or some dumps");
            System.out.println("    were not retrieved. The files are still on the handheld so you");
            System.out.println("    do not have to repeat the install. Collect them with:");
            printRecvCommands(dumpDir);
            System.out.println("    Then delete them yourself from My Documents, or re-run the");
            System.out.println("    cleanup with NspireCtl del=/ndless/... and rmdir=/ndless.");
        }

        // ---- 11. compare the filesystem against how it started ----------------------
        step(11, "Comparing the handheld against how it started");
        List<String> after = new ArrayList<String>();
        boolean afterComplete = listTreeTolerant("/", after);
        Collections.sort(after);
        boolean identical = afterComplete && after.equals(before);
        if (identical) {
            System.out.println("    filesystem matches the baseline exactly (" + after.size() + " entries)");
        } else if (!afterComplete) {
            System.out.println("    could not list part of the filesystem, so this comparison is");
            System.out.println("    NOT proof of anything. Check the handheld yourself.");
        } else {
            System.out.println("    DIFFERENCES:");
            diff(before, after, cleaned);
        }

        // ---- done ---------------------------------------------------------------------
        System.out.println();
        System.out.println("======================================================================");
        if (boot1ok) {
            System.out.println(" boot1 dumped: " + new File(dumpDir, "boot1.img").getAbsolutePath());
            System.out.println();
            System.out.println();
            System.out.println(" Everything retrieved is in " + dumpDir.getAbsolutePath());
            System.out.println(" (the .img.tns files are raw images despite the extension).");
            System.out.println();
            System.out.println(" To turn this into a running emulator, see firebird-bridge/BOOT.md.");
            System.out.println(" You still need TWO things this tool does not fetch: the .tno OS");
            System.out.println(" image for your handheld's build, and boot2.img extracted from it");
            System.out.println(" (tail -c +64 os.tno > os.zip, then unzip boot2.img). Then, with");
            System.out.println(" mkflash built from firebird-bridge/tools:");
            System.out.println("   mkflash boot2.img os.tno nspire.flash 0x0E0 \\");
            System.out.println("           " + new File(dumpDir, "manuf.img.tns").getAbsolutePath());
            System.out.println("   firebird-headless \\");
            System.out.println("           --boot1 " + new File(dumpDir, "boot1.img").getAbsolutePath() + " \\");
            System.out.println("           --flash nspire.flash");
        } else {
            System.out.println(" boot1 did NOT validate. See the messages above.");
        }
        System.out.println();
        System.out.println(" LAST STEP: reboot the handheld (reset button on the back, or pull a");
        System.out.println(" battery for a few seconds). " + ramOnlyClaim(version));
        if (identical) {
            System.out.println(" Your documents are as they were, and a copy is in");
        } else {
            System.out.println(" The comparison above was not clean: check it, and note that a copy");
            System.out.println(" of your documents is in");
        }
        System.out.println("   " + backupDir.getAbsolutePath());
        System.out.println("======================================================================");
        System.exit(0);
    }

    // ------------------------------------------------------------------ setup

    private static void banner() {
        System.out.println("======================================================================");
        System.out.println(" nRemote: guided boot1 dump for a classic (non-CX) TI-Nspire");
        System.out.println();
        System.out.println(" Fresh batteries, handheld in front of you, TI software already running.");
        System.out.println(" Read the notes at the top of DumpBoot1.java before your first run.");
        System.out.println("======================================================================");
    }

    private static void connect() throws Exception {
        NavNetCommProxy.init(null, System.getProperty("java.io.tmpdir"), 0, 0, "", "");
        nncp = NavNetCommProxy.getInstance();
        Thread.sleep(1500L);
        INodeID[] nodes = nncp.getConnectedNodes();
        if (nodes == null || nodes.length == 0) {
            System.out.println("  No handheld found. Plug it in over USB and launch the");
            System.out.println("  TI-Nspire Computer Link software first, then try again.");
            System.exit(1);
        }
        if (nodes.length > 1) {
            System.out.println("  " + nodes.length + " handhelds are connected. Connect only the one you");
            System.out.println("  want to dump, so this cannot touch the wrong device.");
            System.exit(1);
        }
        calc = nodes[0];
    }

    /**
     * Decide which Ndless installer to use, refusing to guess. Running a build
     * meant for a different OS is the one mistake here that can cost you the OS.
     */
    private static String chooseInstaller(String version, String explicit) {
        if (explicit != null) {
            if (VERIFIED_VERSION.equals(version) && !VERIFIED_INSTALLER.equals(explicit)) {
                // We know the right answer for this OS, so a different file is
                // more likely a mistake than an informed choice.
                System.out.println();
                System.out.println("  This handheld runs " + version + ", for which the correct");
                System.out.println("  installer is '" + VERIFIED_INSTALLER + "', but you named");
                System.out.println("  '" + explicit + "'. Running a build meant for another OS is");
                System.out.println("  how people lose the OS on their calculator.");
                if (!confirm("  Type 'yes' only if you are certain: ")) {
                    System.out.println("  Aborted. Nothing was changed on the handheld.");
                    System.exit(0);
                }
            } else if (!VERIFIED_VERSION.equals(version)) {
                System.out.println();
                System.out.println("  You named an installer for an OS this tool has not been run");
                System.out.println("  against ('" + version + "'). Check against the Ndless release");
                System.out.println("  notes that '" + explicit + "' is the right build for it.");
                if (!confirm("  Type 'yes' if you have checked: ")) {
                    System.out.println("  Aborted. Nothing was changed on the handheld.");
                    System.exit(0);
                }
            }
            return explicit;
        }
        if (VERIFIED_VERSION.equals(version)) return VERIFIED_INSTALLER;

        System.out.println();
        boolean known = false;
        for (int i = 0; i < OTHER_SUPPORTED.length; i++) {
            if (OTHER_SUPPORTED[i].equals(version)) known = true;
        }
        if (known) {
            System.out.println("  OS '" + version + "' is one Ndless r2022 supports, but this tool has");
            System.out.println("  only been run end to end against " + VERIFIED_VERSION + ", and some");
            System.out.println("  versions ship more than one installer (3.9.0 has a 'classic' and a");
            System.out.println("  'classic_new' build, chosen by your boot1 version; r2022 ships no");
            System.out.println("  3.1 installer at all).");
        } else {
            System.out.println("  OS '" + version + "' is not a classic build Ndless r2022 lists as");
            System.out.println("  supported. It may be a CX or CX II, which needs different files");
            System.out.println("  entirely, or a version Ndless does not cover.");
        }
        System.out.println();
        System.out.println("  Not guessing. Check the Ndless release notes for your exact OS, then");
        System.out.println("  re-run naming the installer file:");
        System.out.println("      DumpBoot1 <workdir> <ndless_installer_....tns>");
        System.exit(1);
        return null;   // unreachable
    }

    /** The RAM-only property is only documented for 3.6; do not assert it elsewhere. */
    private static String ramOnlyClaim(String version) {
        if (VERIFIED_VERSION.equals(version)) {
            return "Ndless is in RAM only on this OS, so rebooting removes it completely.";
        }
        return "Ndless is removed by a reboot on OS 3.6; on " + version + " confirm that"
                + " against the Ndless notes for your version.";
    }

    // --------------------------------------------------------------- device io

    /**
     * Recursively copy every file to {@code destDir}, recording a path listing
     * and checking each copy is the size the handheld reported. Any failure,
     * including a directory we cannot list, aborts: this runs before anything
     * is written to the device, so aborting here is free.
     */
    private static void backup(String path, File destDir, List<String> listing, long[] tally) {
        Vector<FileAttributesProxy> entries = enumerateOrDie(path);
        destDir.mkdirs();
        for (int i = 0; i < entries.size(); i++) {
            FileAttributesProxy fa = entries.get(i);
            String nm = fa.getName();
            if (skip(nm)) continue;
            String child = join(path, nm);
            if (fa.isDirectory()) {
                listing.add("D " + child);
                backup(child, new File(destDir, nm), listing, tally);
            } else {
                long size = fa.getSize();
                listing.add("F " + child + " " + size);
                File out = new File(destDir, nm);
                if (out.exists()) {
                    die("Local file already exists and would be overwritten: "
                            + out.getAbsolutePath());
                }
                try {
                    nncp.receiveFileFromNode(calc, child, out.getAbsolutePath());
                } catch (Exception e) {
                    die("Could not copy " + child + " off the handheld (" + brief(e) + ").");
                }
                if (!out.isFile() || out.length() != size) {
                    die("Copy of " + child + " is " + (out.isFile() ? out.length() : -1)
                            + " bytes but the handheld reports " + size + ".");
                }
                tally[0]++;
                tally[1] += size;
            }
        }
    }

    /**
     * Path listing for the after-the-fact comparison. Unlike backup() this must
     * not abort (we are nearly done), so it reports whether it saw everything.
     */
    private static boolean listTreeTolerant(String path, List<String> listing) {
        Vector<FileAttributesProxy> entries;
        try {
            entries = nncp.enumDirectory(calc, path);
        } catch (Exception e) {
            System.out.println("    WARNING: cannot list " + path + " (" + brief(e) + ")");
            return false;
        }
        if (entries == null) {
            System.out.println("    WARNING: cannot list " + path);
            return false;
        }
        boolean complete = true;
        for (int i = 0; i < entries.size(); i++) {
            FileAttributesProxy fa = entries.get(i);
            String nm = fa.getName();
            if (skip(nm)) continue;
            String child = join(path, nm);
            if (fa.isDirectory()) {
                listing.add("D " + child);
                complete &= listTreeTolerant(child, listing);
            } else {
                listing.add("F " + child + " " + fa.getSize());
            }
        }
        return complete;
    }

    /** Enumerate, or stop the run. Never silently treats a failure as "empty". */
    private static Vector<FileAttributesProxy> enumerateOrDie(String path) {
        Vector<FileAttributesProxy> entries;
        try {
            entries = nncp.enumDirectory(calc, path);
        } catch (Exception e) {
            die("Could not list " + path + " on the handheld (" + brief(e) + ").");
            return null;
        }
        if (entries == null) {
            die("Could not list " + path + " on the handheld.");
        }
        return entries;
    }

    /**
     * True if {@code path} exists on the handheld. Deliberately not isFile():
     * TI's isFile() can return true for a path that does not exist, and this
     * decides whether we may write over someone's data. It also refuses to
     * guess: if the listing fails we stop rather than assume "absent".
     */
    private static boolean exists(String path) {
        int slash = path.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : path.substring(0, slash);
        String base = path.substring(slash + 1);
        Vector<FileAttributesProxy> entries = enumerateOrDie(parent);
        for (int i = 0; i < entries.size(); i++) {
            String nm = entries.get(i).getName();
            if (nm != null && nm.equalsIgnoreCase(base)) return true;
        }
        return false;
    }

    private static void mkdir(String path) throws Exception {
        nncp.mkDirs(calc, path);
        weCreated.add(path + "/");   // trailing slash marks a directory
        System.out.println("    created " + path);
    }

    private static void send(File local, String remote) throws Exception {
        nncp.sendFileToNode(calc, local.getAbsolutePath(), remote);
        weCreated.add(remote);
        System.out.println("    sent " + local.getName() + " -> " + remote);
    }

    /**
     * Delete only what this run created, files first and directories last.
     * Nothing else on the handheld is ever a candidate for deletion.
     */
    private static void cleanup() {
        for (int i = weCreated.size() - 1; i >= 0; i--) {
            String p = weCreated.get(i);
            if (p.endsWith("/")) continue;
            try {
                nncp.deleteFileFromNode(calc, p);
                System.out.println("    deleted " + p);
            } catch (Exception e) {
                System.out.println("    could not delete " + p + " (" + brief(e) + ")");
            }
        }
        for (int i = weCreated.size() - 1; i >= 0; i--) {
            String p = weCreated.get(i);
            if (!p.endsWith("/")) continue;
            String dir = p.substring(0, p.length() - 1);
            try {
                nncp.rmDir(calc, dir);
                System.out.println("    removed " + dir);
            } catch (Exception e) {
                System.out.println("    could not remove " + dir + " (" + brief(e) + ")");
            }
        }
    }

    /**
     * Poll until the handheld we started with is present (or absent). Re-checks
     * the serial number every time, so a different calculator appearing on the
     * link can never inherit the rest of the run, including the deletes.
     */
    private static boolean waitForLink(boolean wantPresent, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            INodeID match = null;
            try {
                INodeID[] nodes = nncp.getConnectedNodes();
                if (nodes != null) {
                    for (int i = 0; i < nodes.length; i++) {
                        try {
                            INodeInfo ni = nncp.getNodeInfo(nodes[i]);
                            if (ni != null && serial.equals(safe(ni.getSerialNumber()))) {
                                match = nodes[i];
                                break;
                            }
                        } catch (Exception ignored) {
                            // node not ready yet; treat as not-our-device for now
                        }
                    }
                }
            } catch (Exception e) {
                match = null;
            }
            boolean present = match != null;
            if (present) calc = match;   // the handle can change across a reset
            if (present == wantPresent) return true;
            try { Thread.sleep(2000L); } catch (InterruptedException ie) { return false; }
        }
        return false;
    }

    // -------------------------------------------------------------- validation

    /**
     * A classic boot1 is exactly 0x80000 bytes of raw ARM code starting with an
     * exception vector table. PolyDumper adds no header, so what we pull off the
     * handheld is what Firebird wants.
     */
    private static boolean validateBoot1(File f) {
        if (!f.isFile()) {
            System.out.println("    MISSING: " + f.getName());
            return false;
        }
        byte[] head = new byte[16];
        long len = f.length();
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int off = 0;                      // a single read() may return short
            while (off < head.length) {
                int r = in.read(head, off, head.length - off);
                if (r < 0) break;
                off += r;
            }
            if (off != head.length) {
                System.out.println("    too short to be boot1");
                return false;
            }
        } catch (IOException e) {
            System.out.println("    cannot read: " + e);
            return false;
        } finally {
            closeQuietly(in);
        }

        boolean ok = true;
        System.out.println("    size    : " + len + (len == BOOT1_SIZE
                ? " bytes (0x80000, correct)" : " bytes  <-- expected " + BOOT1_SIZE));
        if (len != BOOT1_SIZE) ok = false;

        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8; i++) hex.append(String.format("%02x ", head[i] & 0xFF));
        System.out.println("    header  : " + hex.toString().trim());

        // First word is little-endian: bytes are [imm, f0, 9f, e5] for
        // "LDR PC, [PC, #imm]" (0xE59FF0xx), or [.., .., .., ea] for "B".
        int b3 = head[3] & 0xFF, b2 = head[2] & 0xFF, b1 = head[1] & 0xFF;
        if (b3 == 0xE5 && b2 == 0x9F && b1 == 0xF0) {
            System.out.println("    vectors : ARM 'LDR PC, [PC, #..]' reset vector  [good]");
        } else if (b3 == 0xEA) {
            System.out.println("    vectors : ARM 'B' branch reset vector  [good]");
        } else {
            System.out.println("    vectors : unrecognised first instruction (0x"
                    + String.format("%02x%02x%02x%02x", b3, b2, b1, head[0] & 0xFF)
                    + ")  <-- suspicious");
            ok = false;
        }

        if (allSame(f)) {
            System.out.println("    content : file is entirely one repeated byte  <-- empty dump");
            ok = false;
        }
        System.out.println(ok ? "    looks like a valid boot1." : "    DOES NOT look like a valid boot1.");
        return ok;
    }

    private static boolean allSame(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int first = in.read();
            if (first < 0) return true;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if ((buf[i] & 0xFF) != first) return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Printed by every exit that happens after we have touched the device. */
    private static void leftoverEpilogue(String why) {
        System.out.println();
        System.out.println("  ------------------------------------------------------------------");
        System.out.println("  " + why);
        if (!deviceModified && !doorOpened) {
            System.out.println("  Nothing was changed on the handheld.");
            System.out.println("  ------------------------------------------------------------------");
            return;
        }
        if (!weCreated.isEmpty()) {
            System.out.println("  Still on the handheld (delete these from My Documents if you");
            System.out.println("  are not going to continue):");
            for (int i = 0; i < weCreated.size(); i++) {
                System.out.println("      " + weCreated.get(i));
            }
        }
        if (doorOpened) {
            System.out.println("  Ndless may still be resident in RAM.");
        }
        System.out.println("  REBOOT THE HANDHELD (reset button on the back, or pull a battery");
        System.out.println("  for a few seconds). That clears Ndless and leaves the OS as it was.");
        System.out.println("  ------------------------------------------------------------------");
    }

    private static void printRecvCommands(File dumpDir) {
        for (int i = 0; i < DUMPS.length; i++) {
            System.out.println("      NspireCtl recv=/ndless/" + DUMPS[i] + "::"
                    + new File(dumpDir, DUMPS[i]).getAbsolutePath());
        }
    }

    private static void requireEmptyOrAbsent(File dir, String what) {
        if (!dir.exists()) return;
        String[] kids = dir.list();
        if (kids != null && kids.length > 0) {
            System.out.println("    STOP: " + dir.getAbsolutePath() + " already has files in it.");
            System.out.println("    Move it aside so this run's " + what + " cannot be confused");
            System.out.println("    with an older one.");
            System.exit(1);
        }
    }

    private static void die(String message) {
        System.out.println();
        System.out.println("  STOP: " + message);
        leftoverEpilogue("The run stopped to avoid going on with incomplete information.");
        System.exit(1);
    }

    private static boolean skip(String nm) {
        return nm == null || nm.length() == 0 || ".".equals(nm) || "..".equals(nm);
    }

    private static void step(int n, String what) {
        System.out.println();
        System.out.println("[" + n + "] " + what);
    }

    private static boolean requireFile(File f, String where) {
        if (!f.isFile() || f.length() == 0) {
            System.out.println("    MISSING " + f.getName() + "  -- get it " + where);
            return true;
        }
        String note = "";
        for (int i = 0; i < KNOWN_GOOD.length; i++) {
            if (!KNOWN_GOOD[i][0].equals(f.getName())) continue;
            String actual = sha256(f);
            note = KNOWN_GOOD[i][1].equals(actual) ? "  [matches the known-good copy]"
                    : "  <-- DIFFERENT from the copy this tool was tested with";
            if (!KNOWN_GOOD[i][1].equals(actual)) {
                System.out.println("    found " + f.getName() + " (" + f.length() + " bytes)" + note);
                System.out.println("      expected sha256 " + KNOWN_GOOD[i][1]);
                System.out.println("      got             " + actual);
                System.out.println("      That is fine if you deliberately have a different build,");
                System.out.println("      but check it came from the official release before going on.");
                return false;
            }
        }
        System.out.println("    found " + f.getName() + " (" + f.length() + " bytes)" + note);
        return false;
    }

    private static String sha256(File f) {
        FileInputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "(could not hash: " + brief(e) + ")";
        } finally {
            closeQuietly(in);
        }
    }

    private static boolean confirm(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        StringBuilder sb = new StringBuilder();
        try {
            int c;
            while ((c = System.in.read()) >= 0 && c != '\n') {
                if (c != '\r') sb.append((char) c);
            }
        } catch (IOException e) {
            return false;
        }
        return "yes".equalsIgnoreCase(sb.toString().trim());
    }

    private static void diff(List<String> before, List<String> after, boolean cleaned) {
        for (int i = 0; i < after.size(); i++) {
            String s = after.get(i);
            if (!before.contains(s)) {
                boolean ours = s.indexOf("/ndless") >= 0;
                System.out.println("      extra  : " + s
                        + (ours ? "   <- left by this tool; delete it on the handheld" : ""));
            }
        }
        for (int i = 0; i < before.size(); i++) {
            String s = before.get(i);
            if (!after.contains(s)) {
                System.out.println("      MISSING: " + s
                        + "   <- restore from your backup folder if this matters");
            }
        }
        if (!cleaned) {
            System.out.println("      (cleanup was skipped, so 'extra' entries are expected)");
        }
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        java.io.FileOutputStream out = new java.io.FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            closeQuietly(in);
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }

    private static String join(String parent, String child) {
        return parent.endsWith("/") ? parent + child : parent + "/" + child;
    }

    private static String stripTns(String s) {
        return s.endsWith(".tns") ? s.substring(0, s.length() - 4) : s;
    }

    private static String safe(String s) {
        return s == null ? "(unknown)" : s;
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
