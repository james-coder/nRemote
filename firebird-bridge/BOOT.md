# Booting the emulator and driving it from nRemote

This is the end-to-end runbook for running an emulated TI-Nspire (via
[Firebird](https://github.com/nspire-emus/firebird)) and controlling it with the
nRemote GUI, using the bridge in this folder. Tracking issue #19.

## What is proven

Verified on Linux (WSL2), g++ / make / zlib, no Qt, against a real dumped boot1:

- Firebird's **headless** core builds (`headless/Makefile`, portable ARM
  interpreter + x86-64 JIT), with the nRemote **bridge compiled and linked in**
  (`nremote_bridge_start` resolves against the real `keypad_set_key` /
  `lcd_draw_frame` / `touchpad_set_state`).
- A **flash image is created** from boot2 + OS with `tools/mkflash`. Firebird
  **generates the manuf area itself** (product/keypad/LCD/clocks), so a manuf
  dump is optional; pass one as argv[5] to use the real thing instead.
  `tools/flashinfo` reads it back as product `0x0E0` (Touchpad), 32 MB SDRAM.
- **The machine boots.** With a real boot1 the log shows
  `Boot Loader Stage 1 (1.1.8916)` -> `Boot Loader Stage 2 (3.01.131)`
  ("Using production keys") -> `NAND Flash ID: ST Micro NAND256R3A` ->
  Datalight Reliance filesystem -> `Filesystem ready`, and the LCD shows the
  TI-Nspire splash with a progress bar.
- **The bridge works against real emulated hardware.** `SHOT` returns valid PNGs
  of the boot splash (decoded by Java `ImageIO`), and `KEY i` drives boot2's raw
  keypad read to trigger the factory-image install. This is a capability the
  NavNet path does *not* have: the bridge sets the emulated keypad matrix
  registers directly, so it can answer pre-OS prompts that ignore OS-level key
  events.

## Still to finish

- Getting the OS all the way installed: after the factory-image step boot2 can
  report `Error loading OS image. Removing OS remnants.` and land on
  "Operating System not found. Install OS now.". The bridge's `OS <path.tno>`
  command streams an OS over the emulated USB link (Firebird's `usblink` queue)
  to get past this; see `README.md`.
- `turbo_mode` is on in the headless runner, so the guest's auto-power-down
  timer fires in seconds of wall clock: expect periodic
  `Received TI_OFFSYNC_APD_REQ` + reset. Send a key periodically to keep it
  awake, or drop turbo.
- Wiring `bridge_lock` / `bridge_unlock` to Firebird's emulation-thread guard.

## 1. Build

```
./build-and-run.sh            # builds into ./fb-build by default
```
Produces `fb-build/firebird/headless/firebird-headless` (emulator + bridge on
port 3334) and `fb-build/firebird/mkflash` + `flashinfo`.

## 2. Get boot2 and the OS

Both live inside a 3.6 OS `.tno` (the same OS your handheld runs). A `.tno` is a
63-byte text header followed by a standard ZIP; extract it and take two members:

```
# os.tno is the downloaded 3.6 OS file
tail -c +64 os.tno > os.zip                 # strip the 63-byte header
unzip os.zip boot2.img TI-Nspire.img        # boot2 (~977 KB), OS (~9.4 MB)
```
See [`../docs/EMULATION.md`](../docs/EMULATION.md) for the full `.tno` layout.

## 3. Create the flash

```
fb-build/firebird/mkflash boot2.img TI-Nspire.img nspire.flash 0x0E0
fb-build/firebird/flashinfo nspire.flash    # expect: product=0xE0 ... 32MB
```
`0x0E0` is the non-CAS Touchpad (Firebird's own product code for it). The manuf
area is synthesized; you do not supply one.

## 4. boot1 (the one piece you must provide)

Firebird also needs **boot1**, the Nspire's first-stage bootrom. It is baked into
the calculator's ASIC and it is TI's code, so Firebird cannot generate it and it
is **not** included here. It is also not legitimately downloadable: Firebird's
own setup wiki and Hackspire both state that distributing calculator ROM images
is not legal and that you must dump it from **your own** device.

Dumping it (the supported route, and the one used here):

1. Install **Ndless** (r2022 covers classic Clickpad/Touchpad on OS 3.1.0.392,
   3.6.0.546, 3.9.0.463, 3.9.1.38). Put `ndless_installer_<ver>.tns` and
   `ndless_resources.tns` in a top-level folder named exactly `ndless`, open the
   installer, press **menu**. On 3.6 the install is **non-persistent**: a reboot
   removes it entirely, and it writes nothing to NAND.
2. Run **PolyDumper 5.0** (TI-Planet archive id 3829). On a classic it writes
   `boot1.img.tns`, `boot1alt.img.tns`, `boot2.img.tns`, `diags.img.tns` and
   `manuf.img.tns` **next to its own .tns**.
3. Copy them back to the PC. `boot1.img.tns` is a **raw** dump (no `.tns`
   wrapper is added), so Firebird takes it as-is; renaming to `boot1.img` is
   cosmetic.

Two things that bite when doing this remotely over the TI link:

- PolyDumper ends with `while(!any_key_pressed())`, and libndls'
  `any_key_pressed()` reads the **raw keypad matrix** and the I2C touchpad, not
  the OS event queue. Remotely injected keys can never satisfy it, so **a human
  must press one physical key** to let it exit. The dump files are already
  written and closed before that pause.
- While a native Ndless program runs it owns USB, so the calculator disappears
  from the link (`getConnectedNodes()` returns 0). That is normal, not a crash.

**Sanity-check the result:** a classic boot1 is exactly **0x80000 (524,288)
bytes** and starts with an ARM vector table, typically `18 f0 9f e5` repeated
(`LDR PC, [PC, #0x18]`). Entropy around 6 bits/byte. All-`0xFF`/all-`0x00`
means an empty dump.

boot1 is model-family specific; use a classic (non-CX) boot1 to match the
`0x0E0` flash above.

> If the handheld is borrowed, note that installing Ndless is a device
> modification. It is reversible (reboot clears it) and touches no boot
> partition, but flash wear from ~2.5 MB of dump writes is not, so "exactly as
> found" is functional rather than byte-for-byte. Get the owner's consent, back
> up the documents first, and see `../tests/hardware/README.md`.

## 5. Run it and connect nRemote

```
fb-build/firebird/headless/firebird-headless --boot1 boot1.img --flash nspire.flash
# in another terminal:
java -jar nRemote.jar --emulator            # talks to 127.0.0.1:3334
```
nRemote then mirrors the emulated screen (SHOT -> PNG) and sends keys
(KEY <name>) exactly as it does for a real handheld, through the same GUI.

## Notes

- `turbo_mode` is on in the headless runner, so it boots as fast as the host
  allows.
- If the mirrored screen is photo-negative, flip `BR_INVERT_GRAY` in
  `nremote_bridge.c`. If keys are missed, raise `BR_TAP_MS`.
- The three composite keys `~ctrl_home~` / `~ctrl_0~` / `~ctrl_*~` are decoded
  literally (ctrl + key); if the doc / scratchpad / templates menus do not open,
  remap them to Firebird's dedicated `doc` (6,3) / `matrix` (5,8) keys. See
  [`README.md`](README.md).
- The bridge calls core functions from its own thread. For casual use this
  matches how Firebird's Qt keypad bridge already behaves; for reliability wire
  `bridge_lock` / `bridge_unlock` to Firebird's emu mutex.
