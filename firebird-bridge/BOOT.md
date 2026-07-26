# Booting the emulator and driving it from nRemote

This is the end-to-end runbook for running an emulated TI-Nspire (via
[Firebird](https://github.com/nspire-emus/firebird)) and controlling it with the
nRemote GUI, using the bridge in this folder. Tracking issue #19.

## What is proven vs pending

Verified on Linux (WSL2), g++ / make / zlib, no Qt:

- Firebird's **headless** core builds (`headless/Makefile`, portable ARM
  interpreter + x86-64 JIT).
- The nRemote **bridge compiles and links into that binary**
  (`nremote_bridge_start` resolves against the real `keypad_set_key` /
  `lcd_draw_frame` / `touchpad_set_state`).
- A **flash image is created** from boot2 + OS with `tools/mkflash`. Firebird
  **generates the manuf area itself** (product/keypad/LCD/clocks), so no manuf
  dump is needed. `tools/flashinfo` reads it back as product `0x0E0` (Touchpad),
  32 MB SDRAM, with the BOOT2 and TI-Nspire tags at their partition offsets.
- With a placeholder boot1 the emulator accepts the flash and enters the
  emulation loop.

Pending, and the reason this is not yet a full boot:

- A **boot1 image** (see below). It is the one input that cannot be generated.
- Exercising the bridge against the booted OS (screen mirror + keys), and wiring
  `bridge_lock` / `bridge_unlock` to Firebird's emulation-thread guard.

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
the calculator's ASIC, it is TI's code, and Firebird cannot generate it, so it is
not created by any step above and it is not included here. You have to supply a
`boot1.img` (a ~512 KB image) yourself. Options, your call:

- If you already have a boot1 from earlier Nspire work, use it.
- Choose a source you are comfortable with, the same way the OS `.tno` was
  sourced.
- It can be dumped from a physical Nspire with Ndless, but that installs software
  on the device, so do not do that to the borrowed handheld (it would break the
  "leave it exactly as found" rule in `../tests/hardware/README.md`).

boot1 is model-family specific; use a classic (non-CX) Nspire boot1 to match the
`0x0E0` flash above.

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
