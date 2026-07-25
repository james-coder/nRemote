# Firebird bridge for nRemote (draft)

This folder holds the **Firebird side** of the emulator backend (issue #19): a
small TCP server that lets the nRemote GUI drive an emulated TI-Nspire instead of
a physical handheld. The nRemote side is `src/EmulatorBridge.java`; the design is
in [`../docs/EMULATION.md`](../docs/EMULATION.md).

`nremote_bridge.c` is a **reviewed draft**. It is written against Firebird's real
core API and the exact key matrix from Firebird's `keymap.h`, and its two tricky
parts are tested (the key decode against the matrix, the PNG output against Java
`ImageIO`). It has **not** been compiled into Firebird or run against a booted
OS, because that needs a Firebird build and a bootable flash (still blocked on a
`manuf` image).

## What it does

One socket, plain-text control lines, one length-prefixed PNG for the screen:

| Client sends           | Server replies                       |
|------------------------|--------------------------------------|
| `SHOT\n`               | `IMG <len>\n` then `<len>` PNG bytes |
| `KEY <nRemote-name>\n` | `OK\n`                               |

- **`SHOT`** grabs the LCD with `lcd_draw_frame()` (classic non-CX Nspire is
  4bpp grayscale, 320x240), expands to 8-bit gray, and sends a PNG (encoded with
  zlib, which Firebird already links).
- **`KEY`** decodes the nRemote key name and pulses the keypad matrix with
  `keypad_set_key(row, col, state)`, or taps the touchpad for the arrows.

## Integrating into Firebird

1. Drop `nremote_bridge.c` into Firebird's `core/` and add it to the core build
   (the `.pro` / CMake source list). It needs zlib (already a Firebird dep).
2. Declare and call the entry point once at startup, after the core is up:
   ```c
   extern void nremote_bridge_start(int port);   /* 0 -> default 3334 */
   nremote_bridge_start(3334);
   ```
3. **Thread safety.** Firebird's core runs on the emulation thread and is not
   thread-safe. The bridge serves clients on its own thread and wraps every core
   call in `bridge_lock()` / `bridge_unlock()`, which are no-ops in the draft.
   Point them at the same guard Firebird's GUI uses before relying on this under
   load. (Firebird's Qt keypad bridge calls `keypad_set_key` directly from the
   GUI thread, so direct calls mostly work, but the lock is the correct fix.)
4. On Windows, spawn the accept loop with `CreateThread` / `_beginthread`
   instead of pthreads (there is a `_WIN32` branch stubbed in already).

Then launch nRemote against it:
```
java -jar nRemote.jar --emulator            # localhost:3334
```

## Key map (nRemote name to keypad matrix)

Rows 0-7, cols 0-10, straight from Firebird `keymap.h`. Modifiers: **ctrl** =
(7,9), **shift** = (7,8). Letters `a`..`z` are Firebird's `aa`..`az`.

| nRemote name        | key         | row,col | nRemote name    | key    | row,col |
|---------------------|-------------|---------|-----------------|--------|---------|
| `~esc~`             | esc         | 6,7     | `0`             | n0     | 0,7     |
| `~enter~`           | enter       | 0,1     | `1`             | n1     | 1,7     |
| `~newline~`         | ret         | 0,0     | `2`             | n2     | 6,4     |
| `~home~`            | on/home     | 0,9     | `3`             | n3     | 1,3     |
| `~menu~`            | menu        | 6,5     | `4`             | n4     | 2,7     |
| `~tab~`             | tab         | 6,9     | `5`             | n5     | 5,6     |
| `~var~`             | var         | 5,1     | `6`             | n6     | 2,3     |
| `~backspace~`       | del         | 5,9     | `7`             | n7     | 3,7     |
| `~cat~`             | catalog     | 5,7     | `8`             | n8     | 6,6     |
| `~ee~`              | EE          | 2,8     | `9`             | n9     | 3,3     |
| `~flag~`            | flag        | 6,0     | `.`             | dot    | 5,4     |
| `~neg~`             | (-)         | 0,3     | `,`             | comma  | 7,10    |
| `~square~`          | x squared   | 2,9     | `+`             | plus   | 6,2     |
| `~e_power_x~`       | e^x         | 3,9     | `-`             | minus  | 5,2     |
| `~ten_power_x~`     | 10^x        | 1,10    | `*`             | mult   | 4,8     |
| `^`                 | pow         | 4,9     | `/`             | div    | 3,8     |
| `(`                 | pleft       | 5,5     | `=`             | equ    | 4,7     |
| `)`                 | pright      | 5,3     | (space)         | space  | 0,4     |

Composites arrive already assembled by nRemote's `sendEvent()` and are decoded
here:

| nRemote sends      | decoded as                        |
|--------------------|-----------------------------------|
| `A`..`Z`           | shift + that letter               |
| `~ctrl_<x>~`       | ctrl held + `<x>`                 |
| `~shift_<x>~`      | shift held + `<x>`               |
| `~up/down/left/right~` | touchpad edge tap             |
| `~shift_grab~`     | touchpad press-hold (select)      |

## To re-check on a booted image

The decode is faithful to the key *names*; a few behaviours can only be
confirmed once it boots:

- **`~ctrl_home~`, `~ctrl_0~`, `~ctrl_*~`** are decoded literally as ctrl+home,
  ctrl+0, ctrl+multiply. On the faceplate those buttons are labelled doc,
  scratchpad, and templates. Firebird has dedicated `doc` (6,3) and `matrix`
  (5,8) keys already in the table; if the literal ctrl-combo does not produce the
  intended menu, remap those three names to the dedicated keys.
- **Grayscale polarity.** `BR_INVERT_GRAY` in `nremote_bridge.c` assumes palette
  index 0 is a blank white LCD. If the mirror comes out photo-negative, flip it.
- **Tap timing.** `BR_TAP_MS` (30 ms) is how long a key is held so the guest's
  matrix scan catches it. Raise it if taps are missed.
- **Touchpad arrows.** Edge-tap coordinates are a starting guess; adjust if a tap
  moves the selection by the wrong amount.

## Still blocked

Firebird boots a flash image assembled from boot2 + OS + a **manuf** image. We
have boot2 and the OS (from the `.tno`, see `../docs/EMULATION.md`); the manuf
image still has to be sourced or synthesized. Until then this bridge cannot be
exercised end to end.
