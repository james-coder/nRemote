#!/usr/bin/env bash
#
# build-and-run.sh -- build Firebird's headless emulator with the nRemote bridge
# baked in, and build the mkflash / flashinfo helper tools. Verified on Linux
# (WSL2) with g++, make, and zlib; no Qt or qmake required.
#
# This produces two things:
#   <workdir>/firebird/headless/firebird-headless   (emulator + nRemote bridge)
#   <workdir>/firebird/mkflash, .../flashinfo         (flash tools)
#
# It does NOT download any TI images. See BOOT.md for obtaining boot2 + OS (to
# build a flash) and boot1 (the one image that must come from you).
#
# Usage: ./build-and-run.sh [workdir]   (default workdir: ./fb-build)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:-$HERE/fb-build}"
FB="$WORK/firebird"
JOBS="$(nproc 2>/dev/null || echo 2)"

mkdir -p "$WORK"

if [ ! -d "$FB/.git" ]; then
    echo ">> cloning Firebird into $FB"
    git clone --depth 1 https://github.com/nspire-emus/firebird.git "$FB"
fi
echo ">> fetching submodules (gif-h)"
git -C "$FB" submodule update --init --recursive

echo ">> installing the nRemote bridge into the headless build"
cp "$HERE/nremote_bridge.c" "$FB/headless/nremote_bridge.c"
cp "$HERE/nremote_os.cpp"   "$FB/headless/nremote_os.cpp"

MK="$FB/headless/Makefile"
grep -q 'nremote_bridge.c' "$MK" || \
    sed -i 's#\.\./core/os/os-linux\.c#../core/os/os-linux.c nremote_bridge.c#' "$MK"
grep -q 'nremote_os.cpp' "$MK" || \
    sed -i 's#main\.cpp \\#main.cpp nremote_os.cpp \\#' "$MK"
grep -q -- '-lpthread' "$MK" || sed -i 's#^LIBS := -lz#LIBS := -lz -lpthread#' "$MK"

MAIN="$FB/headless/main.cpp"
grep -q 'nremote_bridge_start' "$MAIN" || {
    python3 - "$MAIN" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p).read()

# File-scope declarations: a C++ extern "C" linkage spec cannot live inside a
# function body, and gui_do_stuff() below needs the tick declared before it.
s = s.replace('#include "core/usblink_queue.h"',
              '#include "core/usblink_queue.h"\n\n'
              '// nRemote bridge (nremote_bridge.c, C linkage).\n'
              'extern "C" void nremote_bridge_start(int port);\n'
              'extern "C" void nremote_bridge_tick(void);', 1)

# Start the server after emu_start(), just before the emulation loop. Turbo off
# so the guest runs at roughly real speed.
s = s.replace("turbo_mode = true;",
              "nremote_bridge_start(3334);\n\tturbo_mode = false;", 1)

# The core calls gui_do_stuff() once per virtual 10 ms slice on the emulation
# thread. The bridge applies queued arrow presses there, so a press lasts a
# fixed amount of GUEST time regardless of host load.
s = s.replace("void gui_do_stuff(bool wait)\n{\n}",
              "void gui_do_stuff(bool wait)\n{\n    (void)wait;\n    nremote_bridge_tick();\n}", 1)

# Headless stubs this as a no-op, so nothing ever throttles: the guest clock
# races, its auto-power-down fires constantly, and input timing goes haywire.
s = s.replace("void throttle_timer_wait(unsigned int usec) {}",
              "void throttle_timer_wait(unsigned int usec) { if(usec) usleep(usec); }", 1)

if "#include <unistd.h>" not in s:
    s = "#include <unistd.h>\n" + s

open(p, "w").write(s)
PYEOF
}

echo ">> building firebird-headless"
make -C "$FB/headless" -j"$JOBS"

echo ">> building mkflash / flashinfo"
g++ -std=c++11 -O2 -I"$FB" -c "$HERE/tools/mkflash.cpp"   -o "$WORK/mkflash.o"
g++ -std=c++11 -O2 -I"$FB" -c "$HERE/tools/flashinfo.cpp" -o "$WORK/flashinfo.o"
# reuse the core objects the headless build just produced (main.o excluded)
g++ -std=c++11 -O2 "$WORK/mkflash.o"   "$FB"/core/*.o "$FB"/core/os/*.o -lz -lpthread -o "$FB/mkflash"
g++ -std=c++11 -O2 "$WORK/flashinfo.o" "$FB"/core/*.o "$FB"/core/os/*.o -lz -lpthread -o "$FB/flashinfo"

cat <<EOF

Done. Built:
  $FB/headless/firebird-headless   (emulator with the nRemote bridge on :3334)
  $FB/mkflash                       (create a flash from boot2 + OS)
  $FB/flashinfo                     (verify a flash)

Next (see BOOT.md):
  1. Get boot2.img + TI-Nspire.img (extract from a 3.6 .tno).
  2. $FB/mkflash boot2.img TI-Nspire.img nspire.flash 0x0E0
  3. Supply a boot1 image (the one piece not generated; see BOOT.md).
  4. $FB/headless/firebird-headless --boot1 boot1.img --flash nspire.flash
  5. java -jar nRemote.jar --emulator      # drives it over :3334
EOF
