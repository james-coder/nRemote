--------------------------
nRemote v1.9.0 (July 24th, 2026)
--------------------------
Original authors : Adriweb, Levak
Thanks to Jim Bauwens for some calc<->computer protocol ([en/de]code algorithms)
http://tiplanet.org

This is a maintained fork of the original project by Adriweb and Levak
(https://github.com/adriweb/nRemote), whose last commit dates from October
2015 - nearly 11 years ago. All credit for the concept and the original
implementation belongs to its authors; this fork picks up where it left
off and integrates a substantial round of bug fixes in v1.9.0 (sticky
modifier state, maximize scaling, refresh pipeline rebuild, sequence
save/playback robustness, device-list staleness, keyboard input fixes,
startup retry, and more).

Full documentation, the changelog, and the issue tracker live at:
https://github.com/james-coder/nRemote


How to install :
----------------
1. Install Java JRE 1.8 if your system doesn't have it already.
2. Install any 3.6/3.9 version of TI-Nspire Computer Software (Navigator
   or not, Teacher or Student does not matter). This restrains the usage
   of nRemote to PC and Mac users; Linux users may find workarounds with
   WINE.
3. Browse to the folder where the TI-Nspire family computer software is
   installed (for example C:\Program Files (x86)\TI Education\TI-Nspire
   CAS Teacher Software\ ; use "Show Package Contents" on Mac), and go
   inside where the Java files are (a "Java" folder, probably).
4. Copy "nRemote.jar" there, with all the other TI .jar files.
   Note: if the TI software refuses to launch with that new file there,
   launch the software first, then add the file once it has opened.


How to use :
------------
1. Launch your TI-Nspire family computer software FIRST.
2. Open "nRemote.jar".

You may also launch it via terminal:
    java -jar [path_to_the_folder]/nRemote.jar


License :
---------
WTFPL License ( http://sam.zoy.org/wtfpl/ ). But also thank the original
authors. And visit http://tiplanet.org :)
