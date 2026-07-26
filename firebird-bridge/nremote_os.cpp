/*
 * nremote_os.cpp -- tiny C++ shim so the C bridge (nremote_bridge.c) can reach
 * Firebird's USB-link queue, which is a C++-only header (std::string).
 *
 * This is what lets us install an OS into a freshly created flash: boot2 stops
 * at "Operating System not found. Install OS now." with its USB download service
 * up, and this streams a .tno to it exactly as TI's Computer Link would.
 *
 * usblink_queue_do() is already pumped from the emulation loop (core/emu.cpp),
 * so enqueuing is all we have to do.
 */
#include <string>
#include <cstdio>

#include "core/usblink.h"
#include "core/usblink_queue.h"
#include "core/flash.h"

extern "C" void nremote_send_os(const char *path)
{
    fprintf(stderr, "nremote_bridge: queueing OS send '%s' (usblink_connected=%d)\n",
            path, (int)usblink_connected);
    usblink_queue_send_os(std::string(path), nullptr, nullptr);
}

extern "C" void nremote_put_file(const char *local, const char *remote)
{
    fprintf(stderr, "nremote_bridge: queueing put '%s' -> '%s'\n", local, remote);
    usblink_queue_put_file(std::string(local), std::string(remote), nullptr, nullptr);
}

/* 1 once the guest's USB stack has come up; the queue is only drained then. */
extern "C" int nremote_usblink_ready(void)
{
    return usblink_connected ? 1 : 0;
}

/*
 * Persist the NAND to a file. The emulator maps the flash copy-on-write, so an
 * OS you just installed lives only in memory and is lost on exit. Saving once
 * after the first-boot install turns the flash into a ready-to-run image that
 * boots straight to the OS.
 */
extern "C" int nremote_save_flash(const char *path)
{
    int r = flash_save_as(path);
    fprintf(stderr, "nremote_bridge: flash_save_as('%s') -> %d\n", path, r);
    return r;
}
