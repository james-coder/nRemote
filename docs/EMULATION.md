# Emulator backend — driving Firebird with the nRemote frontend

Status: **scaffolding (Java half working & tested)**. Tracking issue #19.
Not merged to `master` — needs the Firebird-side bridge and an OS image to test end-to-end.

## Goal

Let the existing nRemote GUI control an emulated TI-Nspire ([Firebird](https://github.com/nspire-emus/firebird))
instead of a physical handheld, by swapping only the backend that provides the
two things the frontend needs: **grab the screen**, and **inject a key**.

## Architecture

```
   NspireKeyboard (Swing GUI, unchanged)
            │  getScreen / sendEvent / device list
            ▼
        Remote  ── emulatorMode? ──┐
            │ no                   │ yes
            ▼                      ▼
     NavNet (real HW)      EmulatorBridge ──TCP──►  Firebird bridge patch
                                                     (lcd_draw_frame / keypad_set_key)
```

`Remote` is now a small facade. When `emulatorMode` is false (the default) the
NavNet path is byte-for-byte unchanged. When on, `getScreen`, both `sendEvent`
overloads, `getDeviceInfo`, and `refreshNodes` route to the emulator instead.

The GUI is **not modified**: in emulator mode `Remote` presents one synthetic
device — an `INodeID` implementation (`Remote.EmulatorNodeID`, type
`UNIT_NSPIRE = 30`, our device) plus a reflection `Proxy` for the 18-method
`INodeInfo`. The device-table null-guards added in #11 already tolerate the
Proxy returning `null` for the sub-info objects.

Select it at launch:

```
java -jar nRemote.jar --emulator                 # 127.0.0.1:3334
java -jar nRemote.jar --emulator=192.168.0.5:3334 # remote host / custom port
```

## Wire protocol (`EmulatorBridge` ⇄ Firebird bridge)

ASCII control lines; the image is length-prefixed binary. One socket, all access
serialized (screen fetch runs on nRemote's fetch thread, keys on the EDT).

| Client sends            | Server replies                         |
|-------------------------|----------------------------------------|
| `SHOT\n`                | `IMG <len>\n` then `<len>` PNG bytes   |
| `KEY <nRemote-name>\n`  | `OK\n`                                 |

`<nRemote-name>` is nRemote's own key string (`~enter~`, `a`, `~click~`, …). The
name→hardware mapping lives on the Firebird side, next to the keypad code.

The Java client (`src/EmulatorBridge.java`) is complete and tested against a mock
bridge: `SHOT` round-trips a 320×240 frame byte-identically and keys are ACKed.

## Firebird-side bridge (to build — external repo)

A small patch adding a TCP server thread to Firebird, using its existing core API
(confirmed in `core/lcd.h`, `core/keypad.h`):

- **`SHOT`** → `lcd_draw_frame(uint8_t *buffer)` (classic non-CX Nspire is
  grayscale, 320×240) → encode as PNG → send.
- **`KEY <name>`** → look the name up in an nRemote-name → keypad-matrix table,
  then pulse it: `keypad_set_key(row, col, true)` / `…false`. `~click~` and the
  arrows use the touchpad: `touchpad_set_state(x, y, contact, down)`. `~on~`
  uses `keypad_on_pressed()`.

Remaining piece: the **nRemote-name → (row,col) / touchpad** table for the
Nspire keypad matrix (8 rows × 11 cols).

## Booting an OS image

Firebird boots a **flash image**, not a bare `.tno`. Building one needs, in
addition to the OS file: **boot2** and a **manuf** image. Firebird can assemble
a flash from these (Flash → create). The `.tno` we obtain from
`education.ti.com` (issue #19 / the download thread) supplies only the OS layer.

## Bonus: settles an open hardware question

The emulator drives the **real keypad matrix / touchpad**, not TI's named
virtual keystrokes. That lets us finally test whether a physical touchpad-click
activates dialog OK buttons — the question from #17 that otherwise needed the
physical calculator and a human finger — by sending `~click~` (→
`touchpad_set_state`) with a dialog focused and comparing against `~enter~`.

## Checklist

- [x] `EmulatorBridge` TCP client + wire protocol (tested vs. mock)
- [x] `Remote` emulator seam (synthetic device, default-off) — compiles vs. real TI jars
- [x] `--emulator[=host:port]` launch flag
- [ ] Firebird TCP bridge patch + keypad-matrix name table
- [ ] Flash image (boot2 + manuf + `.tno`)
- [ ] End-to-end test: GUI drives the emulated screen + keys
