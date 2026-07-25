@echo off
rem ---------------------------------------------------------------------------
rem  nRemote launcher. Place this .bat (and nremote.ico) in the SAME folder as
rem  nRemote.jar and the other TI .jar files (the TI-Nspire software's "lib" /
rem  "Java" folder, per the README). Double-click it, or make a shortcut to it.
rem
rem  It prefers the TI-bundled Java 7 JRE (which carries the NavNet native libs)
rem  and falls back to whatever "javaw" is on PATH.
rem ---------------------------------------------------------------------------
setlocal
set "HERE=%~dp0"

rem TI Computer Link / Software layout is  <install>\lib\  and  <install>\jre\
set "JW=%HERE%..\jre\bin\javaw.exe"
if not exist "%JW%" set "JW=%HERE%jre\bin\javaw.exe"
if not exist "%JW%" set "JW=javaw"

rem Explicit classpath (the manifest Class-Path targets the full Computer
rem Software; this also works for the lighter Computer Link).
set "CP=%HERE%nRemote.jar;%HERE%commproxy.jar;%HERE%navnet.jar;%HERE%navnetcommproxy.jar;%HERE%upgrade.jar"
start "nRemote" "%JW%" -Djava.library.path="%HERE%" -cp "%CP%" nRemote %*
endlocal
