<div align="center">

# nRemote

### Control your TI‑Nspire™ handheld from your computer.
Mirror its live screen, click an accurate on‑screen faceplate, or just type on your keyboard — for one handheld or a whole classroom.

[![Build nRemote.jar](https://github.com/james-coder/nRemote/actions/workflows/build.yml/badge.svg)](https://github.com/james-coder/nRemote/actions/workflows/build.yml)
[![License: WTFPL](https://img.shields.io/badge/license-WTFPL-brightgreen.svg)](http://www.wtfpl.net/)
![Platform: Windows | macOS](https://img.shields.io/badge/platform-Windows%20%7C%20macOS-blue)
![Java](https://img.shields.io/badge/Java-1.8%2B-orange)

<img src="docs/faceplate.png" width="300" alt="nRemote's clickable TI-Nspire faceplate showing the handheld's live screen">

</div>

---

## What is nRemote?

**nRemote** is a small Java program that remote‑controls one or more **TI‑Nspire** handhelds connected to your PC or Mac — directly over USB, or through the TI‑Navigator wireless system. It mirrors the handheld's screen in real time and sends key presses back to it, so you can drive the calculator entirely from your computer. It's handy for **classroom demonstrations**, **screen projection**, keeping every student's device **in sync**, or recording and replaying a set of keystrokes.

- 🖥️ **Live screen mirroring** — see the handheld's display update as you work.
- 🎛️ **Clickable faceplate** — an accurate picture of the TI‑Nspire Touchpad; click any key. *(`--faceplate`)*
- ⌨️ **Type on your keyboard** — A→A, 1→1, Enter, arrows, Ctrl/Shift… go straight to the calculator.
- ⏺️ **Record & replay** key sequences to a file.
- 📁 **Drag‑and‑drop** `.tns` files onto the window to transfer them.
- 👥 **One or many** handhelds — send to all connected devices or a selected subset.
- ➗ **Math palettes** for trig, π, symbols and conditionals.

---

## A maintained fork

This repository is a **fork of [adriweb/nRemote](https://github.com/adriweb/nRemote)** by **Adriweb** and **Levak** of [TI‑Planet](https://tiplanet.org), with calc↔computer protocol help from Jim Bauwens.

> The upstream project received its **last commit in October 2015 — nearly 11 years ago** — and appears to be no longer maintained. It remains a genuinely clever piece of work that this fork owes everything to; all credit for the concept and original implementation belongs to its authors.

This fork picks up where it left off. Since **v1.9.0** it verifies behaviour against a real handheld (OS 3.6) and adds a substantial round of fixes and features:

- Fixed sticky Shift/Ctrl state that made dialog buttons unresponsive, maximize scaling that hid the keypad, and a rebuilt, thread‑safe screen‑refresh pipeline so keystrokes are no longer silently dropped.
- Corrected key names against TI's real key table (e.g. `10ˣ`, shift‑click), and made the touchpad **center‑click select** the highlighted item (the handheld ignores the raw click, so it sends Enter).
- Robust sequence save/playback, device‑list handling, physical‑keyboard support, and a startup retry dialog.
- New **graphical faceplate** (`--faceplate`), a **build workflow**, and a **desktop icon/launcher**.

See the full history in [Changelog](#changelog).

---

## Live screen mirroring

The handheld's screen is shown in real time — menus, dialogs, calculations, everything:

<div align="center">
<img src="docs/screens.png" width="760" alt="Live TI-Nspire screens: home menu, settings, and the handheld status dialog">
</div>

---

## Requirements

- **Java JRE 1.8+** (the app itself targets Java 7 so it also runs on the JRE bundled with the TI software).
- **TI‑Nspire Computer Software or Computer Link, version 3.6 / 3.9** (Navigator or not, Teacher or Student — any works). This is what provides the USB/wireless link, so nRemote runs on **PC and Mac** only. Linux users may find workarounds with WINE.

## Install

1. Install the TI‑Nspire software above (if you don't already have it).
2. Grab `nRemote.jar` — download it from the [latest build](https://github.com/james-coder/nRemote/actions/workflows/build.yml) (Artifacts), a [release](https://github.com/james-coder/nRemote/releases), or `out/artifacts/nRemote_noLibs/nRemote.jar` in this repo.
3. Browse to the folder where the TI‑Nspire software is installed (e.g. `C:\Program Files (x86)\TI Education\TI-Nspire … \` on Windows; *Show Package Contents* on Mac) and go into its Java / `lib` folder — the one containing the other TI `.jar` files.
4. Copy **`nRemote.jar`** there, alongside those TI `.jar` files.

> If the TI software then refuses to launch, start it first and drop `nRemote.jar` in afterwards.

## Usage

1. **Launch the TI‑Nspire software first.**
2. Open **`nRemote.jar`** (double‑click, or `java -jar nRemote.jar`).
3. To use the graphical faceplate instead of the text keyboard, launch with **`--faceplate`**:
   ```
   java -jar nRemote.jar --faceplate
   ```

**Desktop shortcut & icon (Windows):** copy `nRemote.jar` and [`nremote.ico`](nremote.ico) into that TI Java/`lib` folder, then run [`launcher/Create-Desktop-Shortcut.ps1`](launcher/Create-Desktop-Shortcut.ps1) (or drop [`launcher/nRemote.bat`](launcher/nRemote.bat) next to the jar and double‑click it). You get a *nRemote* shortcut with the app icon that launches through the TI‑bundled Java. See [`launcher/README.md`](launcher/README.md).

---

## Building from source

Every push is built by GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml)); tagging a `v*` release attaches the jar to the release.

The real TI NavNet libraries are proprietary and **not** in this repo, so the code is compiled against signature‑only stubs in [`ci/stubs/`](ci/stubs) whose method descriptors match the real classes. The result is a **“no‑libs” jar** — only nRemote's own classes — which resolves the real TI classes at runtime once dropped next to them, exactly as the install steps describe. It is compiled to **Java 7 bytecode** because the TI software bundles a Java 7 JRE.

```bash
# roughly what CI does (JDK 8):
javac -source 1.7 -target 1.7 -encoding UTF-8 -cp src/lib/Alpha.jar \
      -d build $(find ci/stubs -name '*.java') src/*.java
rm -rf build/com                                  # drop the compile-only stubs
cp src/load.png src/nremote.png src/faceplate.png build/
jar cfm nRemote.jar src/META-INF/MANIFEST.MF -C build .
```

---

## Known issues

| Symptom | Status |
| --- | --- |
| Stuck connecting / TI software can't see handhelds | Usually nRemote was launched **before** the TI software. Since v1.9.0 a retry dialog lets you start the TI software and retry. If the TI software itself got stuck, kill `java.exe`/`javaw.exe` + the TI software in Task Manager. |
| Mac GUI looks flat with red dots | Java 1.6/1.7 conflict — run from a terminal: `java -jar nRemote.jar`. |
| Title says a device is connected but there isn't | Largely fixed in v1.9.0 (the list refreshes on membership changes). Otherwise use the Refresh option in the TI software. |
| Some keys (e.g. `e^x`) do nothing | The handheld's firmware ignores a few remote keystrokes — a TI limitation, not an nRemote bug. Affected buttons carry a tooltip. |

---

## Changelog

**Highlights:** v1.9.0 revived the fork with a large bug‑fix round (verified on real hardware); v1.10 added the graphical faceplate, physical‑keyboard typing, a control bar and center‑click‑selects‑item; v1.11 made the faceplate accurate to the real device and added a build workflow + desktop icon.

<details>
<summary>Full version history</summary>

- **v1.11.0** — *Fork.* Faceplate accuracy pass: two‑per‑row science/operator keys with their legends, the minus key, the undo symbol above esc, del's backspace arrow, and grey `EE`/`π`/`,`/`?!`/flag/return keys with the flag key corrected. Added a build workflow, CI stubs, and a startup icon/launcher.
- **v1.10.2** — *Fork.* Faceplate live screen no longer double‑exposes with the built‑in screen graphic (LCD painted first, then the 4:3 screen centred).
- **v1.10.1** — *Fork.* Touchpad center‑click (and the CLIC button) now selects/activates the highlighted item — sent as Enter, since the handheld ignores the remote `~click~`.
- **v1.10.0** — *Fork.* Optional clickable TI‑Nspire Touchpad faceplate (`--faceplate`) with the live screen overlaid; physical‑keyboard typing; a control bar (record/load/screen/device) and drag‑drop in faceplate mode.
- **v1.9.0** — *Fork.* First fork release: sticky‑modifier fix, maximize scaling, rebuilt refresh pipeline (background fetch + EDT‑safe updates + transport lock), Disable‑Screen sizing, sequence save/playback robustness, device‑list staleness, keyboard‑input fixes, startup retry, on‑device key‑name verification.
- **v1.8.1a** — *Public.* Fixed real‑time screen (TI had encapsulated the screen object).
- **v1.8.0a** — *Public.* Made compatible with 3.6/3.9 (dropped older versions).
- **v1.7.x** — *Public.* Additional separate screen frame; background work on two‑way calc↔computer communication (IRC, web, Wolfram Alpha).
- **v1.6** — *Public.* Screen auto‑scaling on resize.
- **v1.5** — *Public.* Read‑device selection; application icon; code cleanup.
- **v1.4** — *Public.* Drag‑and‑drop file transfer; calculator target selection.
- **v1.3** — *Public release.* Reduced delays; sequences.
- **v1.0 – v1.2** — *Private.* GUI, resizing, screen toggle.
- **v0.9 – v0.99** — *Private.* Console only; basic sendEvents.

</details>

---

## Credits & License

- **Original authors:** Adriweb & Levak — [TI‑Planet](https://tiplanet.org). Protocol help: Jim Bauwens.
- Released under the **[WTFPL](http://www.wtfpl.net/)**. Do what you want — but also thank the original authors, and visit [tiplanet.org](https://tiplanet.org). 🙂

*TI‑Nspire and TI‑Navigator are trademarks of Texas Instruments. This is an independent, unofficial tool.*
