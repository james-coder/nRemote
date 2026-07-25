# nRemote launcher & icon

An app icon (`nremote.ico`) and helpers so you can start nRemote from a
desktop shortcut instead of a terminal.

## Files
- **`../nremote.ico`** / `nremote.ico`: the Windows application icon (16-256 px).
- **`nRemote.bat`**: double-click launcher. Put it in the same folder as
  `nRemote.jar` (the TI-Nspire software's `lib` / `Java` folder). It uses the
  TI-bundled Java 7 JRE and sets the native-library path.
- **`Create-Desktop-Shortcut.ps1`**: makes a Desktop shortcut named *nRemote*
  with the icon, pointing at the TI JRE + `nRemote.jar`.

## Setup
1. Install/keep the TI-Nspire Computer Link or Computer Software.
2. Copy `nRemote.jar` **and** `nremote.ico` into the TI software's Java/lib
   folder (where `commproxy.jar`, `navnet.jar`, … live), per the main README.
3. Create the shortcut (auto-detects the TI install):
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\Create-Desktop-Shortcut.ps1
   ```
   or copy `nRemote.bat` next to the jar and double-click it.

Always launch the TI-Nspire software **first**, then start nRemote.
