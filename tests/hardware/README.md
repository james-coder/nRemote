# On-device tools

Three tools that talk to a **real, connected TI-Nspire** through the same NavNet
calls nRemote uses. None of them can run in CI: they need the proprietary TI
libraries (from the TI software install) and a device attached.

| Tool | What it is for |
| --- | --- |
| [`DumpBoot1.java`](DumpBoot1.java) | **Dump your own boot1**, so you can emulate your handheld. Guided, backs up first, cleans up after. Start here. |
| [`NspireCtl.java`](NspireCtl.java) | General remote control: screenshots, keys, and file transfer both ways. |
| [`Probe.java`](Probe.java) | Minimal read-only probe for checking key names, click-vs-Enter, and screen timing. |

## Safety protocol (read first)

The handheld may belong to someone who has **not backed up their work**. For
anything other than a deliberate dump:

- Only drive the **Scratchpad** (discardable) or **read-only** dialogs.
- Never open, modify, save, or transfer **Documents**.
- Erase anything you type.
- Finish on the **Home** screen so the calculator is left exactly as found.
- Take a screenshot before and after, and compare, to confirm no change.

`DumpBoot1` is the deliberate exception: it necessarily writes to the device.
It backs everything up first, only ever deletes files it created itself, and
verifies the filesystem afterwards. Read its own warnings before running it.

---

# Dumping boot1 (the easy path)

To emulate your handheld you need its **boot1** bootrom. It cannot be generated
and it is not legally distributable, so you have to dump it from your own
device. `DumpBoot1` does everything except the three things that genuinely need
a human at the calculator.

## What it does and does not change

**Does:** installs Ndless (on OS 3.6 that is RAM only, and a reboot removes it),
creates an `ndless` folder, writes about 2.5 MB of dumps, then deletes all of it.

**Does not:** touch your documents, or write to boot1, boot2 or manuf. PolyDumper
only reads those.

**Cannot undo:** flash wear from those writes, and the recent-documents list.
"Exactly as found" is functional, not byte for byte.

**One-way door:** opening the Ndless installer on a classic handheld clobbers a
signature copy that an OS monitor thread watches. If the install does not
complete, the OS can wipe itself (documents survive; it needs a USB OS
reinstall). So once you open the installer, either finish the install or reboot.
Do not open it and wander off.

## Before you start

1. **Fit fresh batteries.** Below roughly 25% the handheld refuses an OS
   install and below 20% it refuses the maintenance menu, which turns a small
   problem into a big one.
2. **Have the handheld in front of you.** Three steps need a physical press.
3. **Check your OS version** on the handheld: Settings, then Status. Ndless
   r2022 lists these classic (non-CX) builds as supported:
   `3.1.0.392`, `3.6.0.546`, `3.9.0.463`, `3.9.1.38`.

   `DumpBoot1` only picks an installer **automatically for 3.6.0.546**, the one
   combination that has been run end to end. For the others it stops and makes
   you name the installer file, because guessing is dangerous: 3.9.0 ships two
   variants (`_classic` and `_classic_new`, chosen by your boot1 version) and
   r2022 ships no 3.1 installer at all. Check the Ndless release notes for your
   exact OS, then pass the file:
   `DumpBoot1 <workdir> ndless_installer_3.9.0_classic.tns`.

   (The handheld reports its version as e.g. `3.60.0.546`, which is how Ndless
   writes `3.6.0.546`.)
4. **Download the OS matching your handheld** if you also want to build a flash
   later, and keep it: you may want it as a recovery image.

## Get the two tools

Put both in one working folder, together:

- **Ndless r2022** from <https://github.com/ndless-nspire/Ndless/releases> .
  Unzip and take `ndless_resources.tns` plus the installer for your OS
  (`ndless_installer_3.6.tns` for 3.6.0.546).
- **PolyDumper 5.0** from TI-Planet archive id 3829,
  <https://tiplanet.org/forum/archives_voir.php?id=3829> . Unzip and take
  `polydumper_5/polyDumper.tns`.

## Build and run

Point `LIB` at the TI install's `lib` folder and use its bundled JRE (Java 7,
which carries the NavNet native libraries). Launch the TI-Nspire software first.

```bash
LIB="/path/to/TI Education/TI-Nspire Computer Link/lib"
JRE="/path/to/TI Education/TI-Nspire Computer Link/jre"

"$JRE/bin/javac" -source 1.7 -target 1.7 \
    -cp "$LIB/commproxy.jar:$LIB/navnet.jar:$LIB/navnetcommproxy.jar" \
    -d out tests/hardware/DumpBoot1.java

"$JRE/bin/java" -Djava.library.path="$LIB" -cp "out:$LIB/*" \
    DumpBoot1 /path/to/your/workfolder
```

On Windows use `;` between classpath entries and `javaw`/`java` from the same
`jre\bin`.

## What you will be asked to do

The tool prints each step and waits. Your three jobs:

1. **Install Ndless.** On the handheld: `home`, `2` for My Documents, open the
   `ndless` folder, open the installer, press `menu`. The screen blanks and the
   OS restarts (normal). You should land on the home screen with
   *"Ndless successfully installed!"*.
2. **Run PolyDumper.** Open `polyDumper` in the same folder. The USB link goes
   quiet while it runs, which is expected: a native Ndless program owns USB.
3. **Press any key on the handheld** when PolyDumper shows
   *"Complete - press any key ..."*. It reads the physical keypad directly, so a
   key sent over USB cannot dismiss it. Your dumps are already written by then.

Everything else (backup, transfer, waiting, detection, retrieval, validation,
cleanup, verification) is automatic.

## When it finishes

You get `dumps/boot1.img` (exactly 524288 bytes, starting with an ARM vector
table) plus `boot1alt`, `boot2`, `diags` and `manuf`, and a full copy of your
documents in `backup/`. Keep all of it: that is a low-level backup of your
calculator you probably never had.

**Then reboot the handheld** (reset button on the back, or pull a battery for a
few seconds). Ndless lives in RAM, so rebooting removes it completely.

To turn boot1 into a running emulator, see
[`../../firebird-bridge/BOOT.md`](../../firebird-bridge/BOOT.md).

## If something goes wrong

Least invasive first:

1. Wait a minute. The installer legitimately blanks the screen.
2. Press `esc` on the handheld.
3. Press the reset button on the back for a few seconds (a reboot; saved
   documents survive).
4. Pull a battery for five seconds and reinsert.
5. If the handheld says *"Operating System not found"*, reinstall the OS over
   USB from the TI software (Tools, then Install OS) using the `.tno` that
   matches your handheld's build. Documents are preserved by this.

Your documents are in the `backup/` folder either way.

---

# NspireCtl (general remote control)

```bash
"$JRE/bin/java" -Djava.library.path="$LIB" -cp "out:$LIB/*" \
    NspireCtl ls=/ shot=before.png key=~home~ sleep=800 shot=after.png
```

| Command | Effect |
| --- | --- |
| `shot=<file.png>` | Grab the screen to a PNG (read-only). |
| `key=<name>` | Send a key-down for an nRemote key name (`~enter~`, `a`, `~click~`, …). |
| `keyup=<name>` | Send the matching key-up (event type 16). |
| `sleep=<ms>` | Wait. |
| `ls=<calcpath>` / `tree=<calcpath>` | List a directory, or list it recursively (read-only). |
| `mkdir=` / `rmdir=` / `del=` | Create or remove a folder, delete a file. |
| `send=<local>::<calcpath>` | Copy a file to the handheld. |
| `recv=<calcpath>::<local>` | Copy a file from the handheld. |

Note `isFile()` in TI's API is unreliable (it can report true for a path that
does not exist), which is why listings go through `enumDirectory`.

---

# Probe (read-only checks)

```bash
"$JRE/bin/java" -Djava.library.path="$LIB" -cp "out:$LIB/*" \
    Probe key=~home~ sleep=800 shot=before.png key=a sleep=800 shot=after.png
```

| Command | Effect |
| --- | --- |
| `shot=<file.png>` | Grab the screen to a PNG. |
| `key=<name>` / `keyup=<name>` | Send a key-down / key-up. |
| `sleep=<ms>` | Wait. |
| `time=<n>` | Time `n` screen grabs; print average / min / max. |

Suggested checks:

- **Key names:** `key=<name> sleep shot=…` for any key you changed, and confirm
  the expected glyph appears in the Scratchpad. A missing keycode prints
  `NO KEYCODE (invalid name)`.
- **Click vs Enter:** open a read-only dialog, `key=~click~` (nothing happens),
  then `key=~enter~` (activates). This is why nRemote sends Enter for
  center-click.
- **Refresh timing:** `time=15` to measure the screen-grab latency the poll loop
  is tuned against.
