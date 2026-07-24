--------------------------
nRemote v1.9.0 (July 24th, 2026)
--------------------------
Original authors : Adriweb, Levak  
Thanks to Jim Bauwens for some calc<->computer protocol ([en/de]code algorithms)  
http://tiplanet.org


0   - About this fork  
I   - About  
II  - How to install  
III - How to use  
IV  - Known bugs  
V   - Changelog  
VI  - License  


#0 - About this fork :
---------------------
This repository is a fork of the original [adriweb/nRemote](https://github.com/adriweb/nRemote) by Adriweb and Levak of [TI-Planet](http://tiplanet.org). The upstream project received its last commit in October 2015 — nearly 11 years ago now — and while it appears to no longer be actively maintained, it remains a genuinely clever piece of work that this fork owes everything to. All credit for the concept and the original implementation belongs to its authors; this fork simply picks up where it left off.

Starting with v1.9.0, this fork integrates a substantial round of bug fixes (issue numbers refer to [this fork's issue tracker](https://github.com/james-coder/nRemote/issues?q=is%3Aissue)):

* Sticky Shift/Ctrl modifier state that made dialog OK buttons intermittently unresponsive to the click key (issue 1)
* Screen scaling that pushed the keypad out of view when the main window was maximized (issue 2)
* A rebuilt screen-refresh pipeline: background fetching, thread-safe Swing updates, serialized NavNet transport access so keystrokes are no longer silently dropped mid-refresh, and a shorter poll interval (issues 3, 8, 10)
* Window sizing when toggling "Disable Screen" (issue 4)
* Sequence recording/playback robustness: saving via a file chooser with proper error reporting, playback off the UI thread (issues 5, 6)
* Stale device-list handling when handhelds are swapped, and crash guards when a handheld drops mid-refresh (issues 7, 11)
* Physical Tab key support and other keyboard-input cleanups (issues 9, 12)
* A retry dialog at startup instead of exiting when the TI software isn't running yet (issue 13)

Work that requires a connected handheld to investigate or verify (pointer/mouse translation, the historically dead exp() key, refresh-rate tuning) is tracked under the `needs-hardware` label.


#I - About :
-----------
nRemote is a Java program designed to remote control one or multiple TI-Nspire handhelds when connected to your PC or Mac, whether directly via USB, or via the Navigator Wireless system.  
nRemote also features sequence recording and playing in order to easily execute a set of key presses.  
nRemote can be used for educational purpose in order to synchronise every student's handheld state or by showing a demonstration for a program...

![Overall preview](http://i.imgur.com/IhVB1.jpg)

#II - How to install :
---------------------
1. Install Java JRE 1.8 if your system doesn't have it already.
2. You may have installed any 3.6/3.9 version of TI-Nspire Computer Software (Navigator or not, Teacher or Student does not matter) before using nRemote. This in fact restrains the usage of nRemote to PC and Mac users only. Linux users may find workarounds with WINE.
3. Browse to the folder where TI-Nspire family computer software is installed (for example in C:\Program Files (x86)\TI Education\TI-Nspire CAS Teacher Software\  ;  use "Show package Contents" on Mac)), and go inside where the Java files are ("Java" folder inside, probably).
4. Copy and paste the file "nRemote.jar" there, with all the other TI .jar files.
Note: It's possible that the software refuses to launch with that new file in there. If so, just launch the software first then put it there once it has opened correctly.


#III - How to use :
------------------
1. *Launch your TI-Nspire family computer software FIRST*
2. Open "nRemote.jar"
    
For any platform, you may also try to launch it via terminal ("java -jar [path_to_the_folder]/nRemote.jar")
It can be interesting to create a shortcut of "nRemote.jar" anywhere you want.

**Graphical faceplate (v1.10.0):** launch with `--faceplate` (i.e. `java -jar nRemote.jar --faceplate`) to open a clickable picture of the TI-Nspire Touchpad instead of the text keyboard. Click a key to press it — modifiers, arrows/click, and the trig/π/symbols palettes behave exactly like the text keyboard — and the handheld's live screen is shown right in the faceplate's screen area. You can also just **type on your computer keyboard** (A→A, 1→1, Enter, arrows, Ctrl/Shift, Backspace, Tab, …) and it goes straight to the calculator. A control bar below the calculator holds the program functions that have no key on the device — sequence **Record/Stop** and **Load**, **Disable Screen**, **All/Selection** device targeting and **Devices…** — and you can still drag-and-drop `.tns` files onto the window to transfer them. It resizes freely and will drive the built-in emulator too, once that lands.


#IV - Known Issues :
-----------------
* PC :  
    Q1: nRemote can't connect and TI-Nspire Computer Software can't see my handhelds !  
    A1: It appears you launched nRemote before launching TI-Nspire Computer Software. Since v1.9.0, nRemote offers a retry dialog: launch the TI software, then click Yes to retry. If the TI software itself got stuck, kill java.exe/javaw.exe and TI-Nspire Computer Software via the Task Manager (or restart Windows in extreme cases).  

* Mac :  
    Q2: The GUI may look flat with red dots.  
    A2: There may be a Java version conflict (1.6/1.7). Open a Terminal window, try "java -jar [the nRemote.jar full path]".  

* General :  
    Q3: nRemote says (in its title) that one (or more) device is connected, but there is none.  
    A3: Largely fixed in v1.9.0 (the device list now refreshes on membership changes, not just count changes). If it still happens, use the refresh option in TI-Nspire Computer Software — the Navigator Wireless System has a window listing connected devices with a Refresh button.  
    Q4 : Some keys don't work.  
    A4: Investigated against TI's key table and verified on a real handheld (OS 3.6) in v1.9.0:  
      - The 10^x button silently sent an invalid key name (`~10_power_x~` instead of TI's `~ten_power_x~`) — fixed and verified working.  
      - Shift+click sent the invalid `~shift_hold_click~` — now sends TI's `~shift_grab~`.  
      - e^x (`~e_power_x~`) is a valid protocol name but the handheld's firmware ignores it (its ctrl variant types ln) — a TI limitation nRemote cannot work around.  
      - The `!`, `$`, `\` and `%` symbol-palette buttons never had a keycode in TI's protocol; they are now disabled with an explanatory tooltip.  
    Q5 : The touchpad center-click doesn't select menu items or dialog buttons.  
    A5: Fixed in v1.10.1. The handheld ignores the remote `~click~` keystroke for selection (verified on-device, even with a full press+release), so the center-click / CLIC button now sends Enter, which activates the highlighted item exactly like the physical center-click.


#V - Changelog :
----------------
- v0.9 : *Private*. No GUI, Console Only. Basic sendEvents.
- v0.99 : *Private*. Basic GUI. Bugfixes etc.
- v1.0 : *Private*. Improved GUI. Bugfixes etc.
- v1.01 : *Private*. Improved GUI. Bugfixes etc.
- v1.02 : *Private*. Improved GUI. Bugfixes etc.
- v1.1 : *Private*. "--no-screenshots" CLI option added to allow no-delay text typing, - Smaller overall code, Shift-Hold-xxxxx keys now working, Meta-key support (i.e : Mac's Cmd => Nspire's Ctrl), Version displayed in the frame
- v1.2 : *Private*. interface redone from scratch : better resizing. GUI option to disable screen.
- v1.3 : *Public Release*. Reduced delays. Sequences. Bugfixes etc.
- v1.4 : *Public* Error msg fixed. Drag and Drop transfer any files. Calculator target(s) selection. Fixed the missing 1.6 java target flag.
- v1.5 : *Public* Read devices selection done. Application icon added. Overall code cleaned.
- v1.6 : *Public* Screen auto-scaling when the window is being resized.
- v1.7 : *Public* Additional, separate Screen frame ; improvements. "Private" background work on two-way communication (calc<->computer) : Internet access working (tested : calc-calc and IRC chat, web browser, wolfram alpha API call)
- v1.7.1 : *Public* Fixed the always-focused window.
- v1.7.1c : *Public* Cleaned some prints, rebuilt (I hope) for 1.6, finally the changed version number in the window
- v1.8.0a : *Public* Quickly made it compatible with 3.6/3.9 (not compatible with older versions anymore). Not tested on Windows. Real-time screen seems broken, not sure why.
- v1.8.1a : *Public* Fixed Real-time screen (TI had encapsulated the screen object).
- v1.9.0 : *Fork* First release of this fork. Integrates the bug-fix round described in "About this fork" above: sticky modifier state, maximize scaling, refresh pipeline rebuild (background fetch + EDT-safe updates + transport lock), Disable Screen sizing, sequence save/playback robustness, device-list staleness, keyboard input fixes, startup retry.
- v1.10.0 : *Fork* Added an optional clickable TI-Nspire Touchpad faceplate (`--faceplate`): a scalable image of the calculator with every key mapped and the live handheld screen overlaid in its screen bezel. Also: physical-keyboard typing, a control bar (record/load/screen/device) and drag-drop in faceplate mode.
- v1.10.1 : *Fork* The touchpad center-click (and CLIC button) now selects/activates the highlighted item — the handheld ignores the remote ~click~ keystroke, so it is sent as Enter (verified on-device).
- v1.10.2 : *Fork* Faceplate: the live screen no longer double-exposes with the faceplate's built-in screen graphic — the LCD area is painted over first, then the real 4:3 screen is drawn centred with its aspect ratio preserved.

Future :
- Mouse/pointer translation from the computer screen to the calculator (needs protocol investigation with a handheld)  
- Verifying the historically dead keys (exp() etc.) against a real handheld  
- Refresh-rate measurement and tuning with a real handheld  
- Internal Sequence Editor (upstream's idea, still a good one)  


#VI - License :
-------------
WTFPL License ( http://sam.zoy.org/wtfpl/ ). But also thank the original authors. And visit http://tiplanet.org :)
