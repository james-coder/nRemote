# On-device test harness

`Probe.java` verifies behaviour against a **real, connected TI-Nspire**. It uses
the same NavNet calls as nRemote, so it is the tool for re-checking things that
only a handheld can answer: which key names the firmware accepts, whether a
remote touchpad click activates a dialog, and how long a screen grab takes.

It **cannot run in CI**: it needs the proprietary TI NavNet libraries (from the
TI software install) and a device attached.

## Safety protocol (read first)

The handheld may belong to someone who has **not backed up their work**. So:

- Only drive the **Scratchpad** (discardable) or **read-only** dialogs.
- Never open, modify, save, or transfer **Documents**.
- Erase anything you type.
- Finish on the **Home** screen so the calculator is left exactly as found.
- Take a screenshot before and after, and compare, to confirm no change.

## Build and run

Point `LIB` at the TI install's `lib` folder and use its bundled JRE (32-bit
Java 7, which carries the NavNet native libraries).

```bash
LIB="/path/to/TI Education/TI-Nspire Computer Link/lib"
JRE="/path/to/TI Education/TI-Nspire Computer Link/jre"

# compile
"$JRE/bin/javac" -cp "$LIB/commproxy.jar:$LIB/navnet.jar:$LIB/navnetcommproxy.jar" \
    -d out tests/hardware/Probe.java

# run: launch the TI software first, then a sequence of commands
"$JRE/bin/java" -Djava.library.path="$LIB" \
    -cp "out:$LIB/commproxy.jar:$LIB/navnet.jar:$LIB/navnetcommproxy.jar:$LIB/upgrade.jar" \
    Probe key=~home~ sleep=800 shot=before.png key=a sleep=800 shot=after.png
```

## Commands

| Command | Effect |
| --- | --- |
| `shot=<file.png>` | Grab the screen to a PNG (read-only). |
| `key=<name>` | Send a key-down for an nRemote key name (`~enter~`, `a`, `~click~`, …). |
| `keyup=<name>` | Send the matching key-up (event type 16). |
| `sleep=<ms>` | Wait. |
| `time=<n>` | Time `n` screen grabs; print average / min / max. |

## Suggested checks

- **Key names:** `key=<name> sleep shot=…` for any key you changed, and confirm the
  expected glyph/template appears in the Scratchpad. A missing keycode prints
  `NO KEYCODE (invalid name)`.
- **Click vs Enter:** open a read-only dialog, `key=~click~` (nothing happens),
  then `key=~enter~` (activates). This is why nRemote sends Enter for center-click.
- **Refresh timing:** `time=15` to measure the screen-grab latency the poll loop
  is tuned against.
