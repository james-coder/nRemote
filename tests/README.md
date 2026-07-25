# Tests

Two suites: one that runs without a calculator (in CI), and one that needs a
handheld attached (run by hand).

## Without a calculator (runs in CI)

**`FaceplateMapTest.java`** checks the clickable faceplate, which is the most
edited and most error-prone part of the app:

- every `KEY:` action is a real TI key name (from [`valid-keys.txt`](valid-keys.txt), the
  authoritative list extracted from TI's `NspireVirtualKeyStroke`), and every
  `ARROW` / `PALETTE` / modifier action is a recognised type;
- clicking the centre of each button resolves to that button (no gaps, no wrong
  overlaps);
- no button has a zero or negative-size hit box.

This would have caught real bugs from this project's history, such as the
`~10_power_x~` typo (the real name is `~ten_power_x~`) and the flag key that was
mislabelled as a backspace.

Run it:

```bash
javac -d build src/FaceplatePanel.java tests/FaceplateMapTest.java
java -Djava.awt.headless=true -cp build FaceplateMapTest .
```

It prints `FaceplateMapTest: OK ...` and exits 0 on success, or lists failures
and exits non-zero. The GitHub Actions workflow runs it on every push.

## With a calculator attached (run by hand)

**`hardware/Probe.java`** drives a real handheld through the same NavNet calls
nRemote uses. It cannot run in CI (it needs the proprietary TI libraries and a
connected device), but it is how the on-device findings in this project were
made and verified: which key names actually work, that the touchpad click is
ignored in dialogs (so nRemote sends Enter), and the screen-fetch timing.

See [`hardware/README.md`](hardware/README.md) for how to build and run it, and
for the **safety protocol** (Scratchpad only, never touch Documents, restore
state) that any on-device testing must follow, since the calculator's owner may
not have backed up their work.
