/* _DEFAULT_SOURCE so usleep() is declared under -std=c11 (Firebird's flags). */
#define _DEFAULT_SOURCE 1
/*
 * nremote_bridge.c  --  TCP bridge that exposes Firebird's core to the
 * nRemote frontend, so the existing nRemote GUI (src/EmulatorBridge.java) can
 * drive an emulated TI-Nspire instead of a physical handheld. Tracking: #19.
 *
 * This file is written to be dropped into the Firebird source tree (the
 * nspire-emus/firebird emulator, https://github.com/nspire-emus/firebird) and
 * compiled with its core. It HAS been compiled and linked into Firebird's
 * headless target here (nremote_bridge_start resolves against the real
 * keypad_set_key / lcd_draw_frame / touchpad_set_state); it has not yet been
 * exercised against a booted OS, which needs a boot1 image. Everything it calls
 * is real Firebird core API (core/keypad.h, core/lcd.h); the key matrix
 * positions are taken verbatim from Firebird's keymap.h. See README.md and
 * BOOT.md in this folder for how to wire it in and run it.
 *
 * Wire protocol (matches EmulatorBridge.java):
 *     client -> "SHOT\n"              server -> "IMG <len>\n" + <len> PNG bytes
 *     client -> "KEY <name>\n"        server -> "OK\n"
 * <name> is nRemote's own key string, exactly as it goes out to NavNet:
 *   bare chars ("a", "7", "*"), names ("~esc~", "~enter~"), an uppercase
 *   letter meaning shift+letter ("A"), or a composite ("~ctrl_0~",
 *   "~shift_enter~"). We decode those here and pulse the matrix.
 *
 * Threading: Firebird's core is driven by the emulation thread and is NOT
 * thread-safe. Like Firebird's own Qt keypad bridge, this calls keypad/touchpad
 * functions directly, but every core touch is wrapped in bridge_lock() /
 * bridge_unlock(). Wire those to the same mutex the GUI uses before relying on
 * this under load (see README).
 */

#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <zlib.h>          /* compress2(), crc32() -- Firebird already links zlib */

#ifdef _WIN32
  #include <winsock2.h>
  typedef int socklen_t;
  #define BR_CLOSE(s) closesocket(s)
  #define BR_SLEEP_MS(ms) Sleep(ms)
#else
  #include <sys/socket.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <unistd.h>
  #include <pthread.h>
  #define BR_CLOSE(s) close(s)
  #define BR_SLEEP_MS(ms) usleep((ms) * 1000)
#endif

/* ---- Firebird core API we drive (see core/keypad.h, core/lcd.h) ---- */
/*
 * Debugger access. Firebird already has a full native debugger (core/debug.c):
 * registers, memory dump, disassembly, stack backtrace, read/write/exec
 * breakpoints, single step, MMU tables, memory search. Rather than reimplement
 * any of that we drive it, exactly the way the Qt front end does.
 *
 * The mechanics, which drive the design below:
 *   - The debugger runs ON THE EMULATION THREAD. native_debugger() blocks in a
 *     loop asking the GUI for a command through gui_debugger_request_input().
 *   - That loop calls gui_do_stuff() every 100 ms while it waits, so our tick
 *     keeps running and the socket threads stay alive while the guest is halted.
 *   - Setting EVENT_DEBUG_STEP in cpu_events makes the CPU enter the debugger
 *     at the next instruction boundary. That is how the gdb stub halts, and it
 *     is safer than calling debugger() ourselves from an arbitrary point.
 */
extern uint32_t cpu_events __asm__("cpu_events");
#define BR_EVENT_DEBUG_STEP 8       /* EVENT_DEBUG_STEP, core/emu.h */
extern bool in_debugger;            /* core/debug.h */

extern void keypad_set_key(int row, int col, bool state);
extern void keypad_on_pressed(void);
extern void touchpad_set_state(float x, float y, bool contact, bool down);
extern void lcd_draw_frame(uint8_t *buffer);

/* USB-link helpers, implemented in nremote_os.cpp (Firebird's queue header is
 * C++ only). Used by the OS / PUT commands to install an OS or push a file. */
extern void nremote_send_os(const char *path);
extern void nremote_put_file(const char *local, const char *remote);
extern int  nremote_usblink_ready(void);
extern int  nremote_save_flash(const char *path);

/* Wrap every core access with Firebird's emulation-thread lock. Left as no-ops
 * in this draft; point them at the same guard the GUI uses (README). */
static void bridge_lock(void)   { /* TODO: acquire Firebird emu mutex */ }
static void bridge_unlock(void) { /* TODO: release Firebird emu mutex */ }

/* How long a "tap" holds a key down so the guest's matrix scan sees it. The
 * emulation thread runs concurrently with this one, so a real delay is needed.
 * ~30 ms is a safe start; tune against the guest if taps are missed. */
#define BR_TAP_MS 30

/* Classic (non-CX) Nspire panel. lcd_draw_frame packs 2 px per byte, 4bpp. */
#define LCD_W 320
#define LCD_H 240
#define LCD_PACKED_BYTES ((LCD_W / 2) * LCD_H)   /* 160 * 240 = 38400 */

/* If the mirrored screen comes out photo-negative, flip this. Classic Nspire
 * grayscale palette index 0 is treated here as white (blank LCD). */
#define BR_INVERT_GRAY 1

/* Modifier keys in the matrix (keymap.h). */
#define ROW_CTRL  7
#define COL_CTRL  9
#define ROW_SHIFT 7
#define COL_SHIFT 8

/* ---- nRemote base token -> keypad matrix (row, col), from keymap.h ---- *
 * These are the de-tilded, de-modifier'd tokens: "esc", "a", "7", "*", ...   */
typedef struct { const char *name; int row, col; } KeyPos;
static const KeyPos BASE_KEYS[] = {
    /* framing / editing keys */
    {"esc", 6, 7}, {"enter", 0, 1}, {"newline", 0, 0}, {"home", 0, 9},
    {"menu", 6, 5}, {"tab", 6, 9}, {"var", 5, 1}, {"backspace", 5, 9},
    {"del", 5, 9}, {"cat", 5, 7}, {"ee", 2, 8}, {"flag", 6, 0},
    {"neg", 0, 3}, {"square", 2, 9}, {"e_power_x", 3, 9}, {"ten_power_x", 1, 10},
    {"doc", 6, 3}, {"matrix", 5, 8}, {"pad", 5, 10},
    /* digits */
    {"0", 0, 7}, {"1", 1, 7}, {"2", 6, 4}, {"3", 1, 3}, {"4", 2, 7},
    {"5", 5, 6}, {"6", 2, 3}, {"7", 3, 7}, {"8", 6, 6}, {"9", 3, 3},
    /* operators / punctuation */
    {".", 5, 4}, {",", 7, 10}, {"+", 6, 2}, {"-", 5, 2}, {"*", 4, 8},
    {"/", 3, 8}, {"=", 4, 7}, {"^", 4, 9}, {"(", 5, 5}, {")", 5, 3},
    {" ", 0, 4},
    /* letters a..z  (Firebird labels these aa..az) */
    {"a", 4, 6}, {"b", 4, 5}, {"c", 4, 4}, {"d", 4, 2}, {"e", 4, 1},
    {"f", 4, 0}, {"g", 3, 6}, {"h", 3, 5}, {"i", 3, 4}, {"j", 3, 2},
    {"k", 3, 1}, {"l", 3, 0}, {"m", 2, 6}, {"n", 2, 5}, {"o", 2, 4},
    {"p", 2, 2}, {"q", 2, 1}, {"r", 2, 0}, {"s", 1, 6}, {"t", 1, 5},
    {"u", 1, 4}, {"v", 1, 2}, {"w", 1, 1}, {"x", 1, 0}, {"y", 0, 6},
    {"z", 0, 5},
};
static const int BASE_KEYS_N = (int)(sizeof(BASE_KEYS) / sizeof(BASE_KEYS[0]));

static const KeyPos *lookup_key(const char *tok) {
    for (int i = 0; i < BASE_KEYS_N; i++)
        if (strcmp(BASE_KEYS[i].name, tok) == 0) return &BASE_KEYS[i];
    return NULL;
}

/*
 * Why arrow presses have to be short, and measured in GUEST time.
 *
 * The guest OS does its own key auto-repeat while a touchpad direction is held
 * (Firebird's own input code notes "calc os must handle it on its own"), and
 * the repeat starts fast. Measured against a booted OS by stepping a file list
 * and reading back the selected row:
 *
 *     8 ms, 10 ms, 12 ms  ->  exactly one item, 5 times out of 5
 *     15 ms               ->  double-stepped 1 time in 5
 *     20 ms, 30 ms, 40 ms ->  always two items
 *
 * Those are guest milliseconds. Holding for the same span of WALL-CLOCK time
 * does not work, because the emulator throttles in 10 ms slices of virtual
 * time and runs flat out inside each slice: how much guest time a wall-clock
 * hold covers depends on host load. See the queue below, which counts virtual
 * slices instead and so behaves identically whatever else is running.
 */

/*
 * Where the "finger" lands for each direction, as 0..1 fractions of the pad.
 * These are the extremes, matching Firebird's own keyboard-arrow handler
 * (qtkeypadbridge.cpp), which sets x or y to 0 or TOUCHPAD_*_MAX and the other
 * axis to the midpoint.
 *
 * Note the y axis is flipped on the way in: touchpad_set_state computes
 * new_y = MAX - y*MAX, so y=0 here is the TOP of the pad (up) and y=1 is the
 * bottom (down). Getting that backwards silently inverts up and down.
 *
 * Center-click is not handled here: nRemote already sends it as "~enter~".
 */
static bool touchpad_dir(const char *tok, float *x, float *y) {
    if (!strcmp(tok, "up"))    { *x = 0.5f; *y = 0.0f; return true; }
    if (!strcmp(tok, "down"))  { *x = 0.5f; *y = 1.0f; return true; }
    if (!strcmp(tok, "left"))  { *x = 0.0f; *y = 0.5f; return true; }
    if (!strcmp(tok, "right")) { *x = 1.0f; *y = 0.5f; return true; }
    return false;
}

/* Pulse one matrix key, optionally holding a modifier around it. */
static void tap_matrix(int row, int col, int mod_row, int mod_col) {
    bridge_lock();
    if (mod_row >= 0) keypad_set_key(mod_row, mod_col, true);
    keypad_set_key(row, col, true);
    bridge_unlock();
    BR_SLEEP_MS(BR_TAP_MS);
    bridge_lock();
    keypad_set_key(row, col, false);
    if (mod_row >= 0) keypad_set_key(mod_row, mod_col, false);
    bridge_unlock();
}

/* ---- debugger state (used by nremote_bridge_tick below) ---- */
#define DBG_CMD_MAX 240

#ifndef _WIN32
static pthread_mutex_t dbg_m  = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  dbg_cv = PTHREAD_COND_INITIALIZER;
#define DBG_LOCK()   pthread_mutex_lock(&dbg_m)
#define DBG_UNLOCK() pthread_mutex_unlock(&dbg_m)
#else
#define DBG_LOCK()
#define DBG_UNLOCK()
#endif

typedef void (*br_debug_cb)(const char *);
static br_debug_cb dbg_cb = NULL;   /* set while the debugger wants a command */
static char   dbg_cmd[DBG_CMD_MAX];
static int    dbg_cmd_pending = 0;   /* a command is waiting to be handed over */
static int    dbg_busy = 0;          /* a socket thread owns the debugger */
static int    dbg_done = 0;          /* its output is complete */
static int    dbg_capture = 0;       /* currently collecting output */
static char  *dbg_buf = NULL;
static size_t dbg_len = 0, dbg_cap = 0;
static int    dbg_halt_request = 0;
static int    dbg_entered = 0;       /* guest is halted in the debugger */

/*
 * Arrow presses are queued here and applied by nremote_bridge_tick() on the
 * emulation thread, NOT held with a sleep on this thread.
 *
 * Sleeping here does not work. The emulator throttles in 10 ms slices of
 * VIRTUAL time: between throttle points the guest runs as fast as the host
 * allows, then sleeps whatever is left of the slice. So a wall-clock hold
 * covers an unpredictable amount of guest time, which decides how many
 * auto-repeats the guest sees. Load the host (nRemote polling SHOT for the
 * screen, say) and the same hold suddenly spans a different amount of guest
 * time, so arrows move one item sometimes and race away other times.
 *
 * Counting virtual quanta instead makes the hold exactly one 10 ms slice of
 * guest time every single press, whatever the host is doing.
 */
#define BR_ARROW_HOLD_QUANTA 1   /* press lasts one 10 ms virtual slice */
#define BR_ARROW_GAP_QUANTA  1   /* idle slice after release, so presses separate */
#define BR_ARROW_QUEUE 32

static struct { float x, y; } br_queue[BR_ARROW_QUEUE];
static int br_q_head = 0, br_q_tail = 0;
static int br_hold = 0, br_gap = 0;
static bool br_pressed = false;
static float br_cur_x = 0.f, br_cur_y = 0.f;

#ifndef _WIN32
static pthread_mutex_t br_q_mut = PTHREAD_MUTEX_INITIALIZER;
#define BR_QLOCK()   pthread_mutex_lock(&br_q_mut)
#define BR_QUNLOCK() pthread_mutex_unlock(&br_q_mut)
#else
#define BR_QLOCK()
#define BR_QUNLOCK()
#endif

static void tap_touchpad(float x, float y) {
    BR_QLOCK();
    int next = (br_q_tail + 1) % BR_ARROW_QUEUE;
    if (next != br_q_head) {            /* drop silently if the queue is full */
        br_queue[br_q_tail].x = x;
        br_queue[br_q_tail].y = y;
        br_q_tail = next;
    }
    BR_QUNLOCK();
}

/*
 * Called from the emulation thread once per virtual 10 ms slice (wired up from
 * gui_do_stuff). Applies at most one state change per slice, so a press always
 * lasts exactly BR_ARROW_HOLD_QUANTA slices of guest time.
 *
 * This also means touchpad_set_state runs on the emulation thread rather than
 * on a socket thread, which is where it belongs.
 */
void nremote_bridge_tick(void) {
    /* A HALT asked for from a socket thread is applied here, on the emulation
     * thread. cpu_events is how the gdb stub does it too: the CPU notices at
     * the next instruction boundary and enters the debugger itself, rather
     * than us calling debugger() from the middle of a throttle callback. */
    if (dbg_halt_request && !in_debugger) {
        dbg_halt_request = 0;
        cpu_events |= BR_EVENT_DEBUG_STEP;
    }

    if (br_pressed) {
        if (--br_hold <= 0) {
            touchpad_set_state(br_cur_x, br_cur_y, false, false);   /* release */
            br_pressed = false;
            br_gap = BR_ARROW_GAP_QUANTA;
        }
        return;
    }
    if (br_gap > 0) { br_gap--; return; }

    float x = 0.f, y = 0.f;
    bool have = false;
    BR_QLOCK();
    if (br_q_head != br_q_tail) {
        x = br_queue[br_q_head].x;
        y = br_queue[br_q_head].y;
        br_q_head = (br_q_head + 1) % BR_ARROW_QUEUE;
        have = true;
    }
    BR_QUNLOCK();
    if (have) {
        touchpad_set_state(x, y, true, true);   /* press at the extreme edge */
        br_cur_x = x;
        br_cur_y = y;
        br_pressed = true;
        br_hold = BR_ARROW_HOLD_QUANTA;
    }
}

/*
 * Decode one nRemote key name and drive the emulator. Returns true if handled.
 *
 * Grammar handled:
 *   "A".."Z"            -> shift + that letter
 *   "~ctrl_<rest>~"     -> ctrl held + <rest>
 *   "~shift_<rest>~"    -> shift held + <rest>   (also "~shift_grab~", drag)
 *   "~<name>~" / bare   -> a base key, a touchpad direction, or home/on
 */
static bool handle_key(const char *raw) {
    char t[64];
    strncpy(t, raw, sizeof(t) - 1);
    t[sizeof(t) - 1] = '\0';

    int mod_row = -1, mod_col = -1;

    /* Bare single uppercase letter = shift + letter. */
    if (strlen(t) == 1 && t[0] >= 'A' && t[0] <= 'Z') {
        mod_row = ROW_SHIFT; mod_col = COL_SHIFT;
        t[0] = (char)tolower((unsigned char)t[0]);
    }

    /* Strip one surrounding ~...~ wrapper if present. */
    size_t n = strlen(t);
    if (n >= 2 && t[0] == '~' && t[n - 1] == '~') {
        memmove(t, t + 1, n - 2);
        t[n - 2] = '\0';
    }

    /* Composite modifier prefixes, as nRemote builds them in sendEvent(). */
    if (strncmp(t, "ctrl_", 5) == 0) {
        mod_row = ROW_CTRL; mod_col = COL_CTRL;
        memmove(t, t + 5, strlen(t + 5) + 1);
    } else if (strncmp(t, "shift_", 6) == 0) {
        mod_row = ROW_SHIFT; mod_col = COL_SHIFT;
        memmove(t, t + 6, strlen(t + 6) + 1);
    }

    /* shift+click grab and shift+hold-<dir> drag (best-effort; see README). */
    if (strcmp(t, "grab") == 0) {
        bridge_lock();
        touchpad_set_state(0.5f, 0.5f, true, true);  /* press-hold to select */
        bridge_unlock();
        BR_SLEEP_MS(BR_TAP_MS);
        bridge_lock();
        touchpad_set_state(0.5f, 0.5f, true, false);
        bridge_unlock();
        return true;
    }
    if (strncmp(t, "hold_", 5) == 0) {
        float x, y;
        if (touchpad_dir(t + 5, &x, &y)) { tap_touchpad(x, y); return true; }
    }

    /* Touchpad navigation. */
    float x, y;
    if (touchpad_dir(t, &x, &y)) { tap_touchpad(x, y); return true; }

    /* Base matrix key (optionally with the held modifier). */
    const KeyPos *k = lookup_key(t);
    if (k) { tap_matrix(k->row, k->col, mod_row, mod_col); return true; }

    fprintf(stderr, "nremote_bridge: unknown key '%s'\n", raw);
    return false;
}

/* =====================================================================
 * Debugger bridge
 *
 * A socket thread posts a command and blocks. The emulation thread, sitting
 * inside native_debugger(), collects it through nremote_dbg_take_command(),
 * and everything the debugger prints while running it is captured by
 * nremote_dbg_output() (wired to gui_debug_vprintf). The socket thread is woken
 * when the command finishes, which we detect either when the debugger asks for
 * the NEXT command or when it leaves the debugger entirely (as "c" and "s" do).
 *
 * Nothing here ever blocks the emulation thread: it only ever takes a mutex
 * briefly. The socket side always waits with a timeout, so a wedged or
 * never-halting emulator degrades to an error instead of a hung UI.
 * ===================================================================== */


/* Append captured debugger output. Called on the emulation thread. */
void nremote_dbg_output(const char *text, int n) {
    if (!text || n <= 0) return;
    DBG_LOCK();
    if (dbg_capture) {
        if (dbg_len + (size_t)n + 1 > dbg_cap) {
            size_t want = (dbg_len + (size_t)n + 1) * 2;
            char *nb = (char *)realloc(dbg_buf, want);
            if (nb) { dbg_buf = nb; dbg_cap = want; }
        }
        if (dbg_buf && dbg_len + (size_t)n + 1 <= dbg_cap) {
            memcpy(dbg_buf + dbg_len, text, (size_t)n);
            dbg_len += (size_t)n;
            dbg_buf[dbg_len] = '\0';
        }
    }
    DBG_UNLOCK();
}

/* Finish the in-flight command, if any. Caller holds the lock. */
static void dbg_finish_locked(void) {
    if (dbg_busy && dbg_capture) {
        dbg_capture = 0;
        dbg_done = 1;
#ifndef _WIN32
        pthread_cond_broadcast(&dbg_cv);
#endif
    }
}

/*
 * Called from gui_debugger_request_input() on the emulation thread.
 *
 * The contract is the one Firebird's Qt front end implements: STORE the
 * callback and return immediately. The debugger then waits on a condition
 * variable, releasing its input mutex, and expects some other thread to invoke
 * the callback when a command is available. Calling it inline from here would
 * deadlock on that mutex, and never calling it leaves the debugger waiting
 * forever.
 *
 * A non-NULL callback also means the previous command has finished printing,
 * because the debugger only asks again after process_debug_cmd() returned.
 */
void nremote_dbg_set_callback(void *cb) {
    DBG_LOCK();
    dbg_cb = (br_debug_cb)cb;
    if (cb) dbg_finish_locked();
    DBG_UNLOCK();
}

/* Called from gui_debugger_entered_or_left() on the emulation thread. */
void nremote_dbg_entered(int entered) {
    DBG_LOCK();
    dbg_entered = entered ? 1 : 0;
    if (!entered) dbg_finish_locked();   /* "c" and "s" leave the debugger */
    DBG_UNLOCK();
}

/* Run one debugger command and send whatever it printed. Socket thread. */
static void handle_dbg(int fd, const char *cmd) {
    char hdr[48];
    int hn, waited = 0;
    br_debug_cb cb = NULL;
    size_t n;
    char *copy;

    DBG_LOCK();
    if (!dbg_entered) {
        DBG_UNLOCK();
        send(fd, "ERR not halted\n", 15, 0);
        return;
    }
    if (dbg_busy) {
        DBG_UNLOCK();
        send(fd, "ERR busy\n", 9, 0);
        return;
    }
    dbg_busy = 1;
    dbg_done = 0;
    dbg_len = 0;
    if (dbg_buf) dbg_buf[0] = '\0';
    snprintf(dbg_cmd, sizeof(dbg_cmd), "%s", cmd);
    dbg_capture = 1;
    cb = dbg_cb;
    DBG_UNLOCK();

    /* The debugger sets its callback while it waits for input. If it has not
     * asked yet, give it a moment rather than dropping the command. */
    while (!cb && waited < 2000) {
        BR_SLEEP_MS(20);
        waited += 20;
        DBG_LOCK();
        cb = dbg_cb;
        DBG_UNLOCK();
    }

    if (cb) {
        /* Hand the command over from THIS thread, which is what the debugger
         * is waiting for. Not under our lock: the callback takes Firebird's
         * own input mutex and we must not hold anything it might need. */
        cb(dbg_cmd);
    }

    DBG_LOCK();
    if (cb) {
#ifndef _WIN32
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 5;                    /* never hang the UI */
        while (!dbg_done) {
            if (pthread_cond_timedwait(&dbg_cv, &dbg_m, &ts) != 0) break;
        }
#endif
    }
    n = dbg_len;
    copy = (char *)malloc(n + 1);
    if (copy) { memcpy(copy, dbg_buf ? dbg_buf : "", n); copy[n] = '\0'; }
    dbg_capture = 0;
    dbg_cmd_pending = 0;
    dbg_busy = 0;
    dbg_len = 0;
    DBG_UNLOCK();

    hn = snprintf(hdr, sizeof(hdr), "OUT %lu\n", (unsigned long)n);
    send(fd, hdr, hn, 0);
    if (copy && n) {
        size_t off = 0;
        while (off < n) {
            int w = (int)send(fd, copy + off, (int)(n - off), 0);
            if (w <= 0) break;
            off += (size_t)w;
        }
    }
    free(copy);
}

/* ---------- minimal grayscale PNG encoder (uses zlib) ---------- */
static void put_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);  p[3] = (uint8_t)v;
}

/* Encode w*h 8-bit grayscale pixels to a PNG. Caller frees *out. Returns len. */
static long png_encode_gray8(const uint8_t *pix, int w, int h,
                             uint8_t **out) {
    /* Raw image = each row prefixed with a filter byte (0 = none). */
    uLong raw_len = (uLong)h * (1 + w);
    uint8_t *raw = (uint8_t *)malloc(raw_len);
    if (!raw) return -1;
    for (int y = 0; y < h; y++) {
        raw[y * (1 + w)] = 0;
        memcpy(raw + y * (1 + w) + 1, pix + (size_t)y * w, w);
    }
    uLong comp_cap = compressBound(raw_len);
    uint8_t *comp = (uint8_t *)malloc(comp_cap);
    if (!comp) { free(raw); return -1; }
    if (compress2(comp, &comp_cap, raw, raw_len, 6) != Z_OK) {
        free(raw); free(comp); return -1;
    }
    free(raw);

    /* PNG = signature + IHDR + IDAT + IEND. */
    long len = 8 + (12 + 13) + (12 + (long)comp_cap) + 12;
    uint8_t *png = (uint8_t *)malloc(len);
    if (!png) { free(comp); return -1; }
    uint8_t *p = png;
    static const uint8_t SIG[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    memcpy(p, SIG, 8); p += 8;

    /* IHDR */
    put_be32(p, 13); p += 4;
    uint8_t *ihdr = p; memcpy(p, "IHDR", 4); p += 4;
    put_be32(p, (uint32_t)w); p += 4;
    put_be32(p, (uint32_t)h); p += 4;
    *p++ = 8;   /* bit depth */
    *p++ = 0;   /* color type 0 = grayscale */
    *p++ = 0; *p++ = 0; *p++ = 0;   /* compression, filter, interlace */
    put_be32(p, (uint32_t)crc32(0, ihdr, 4 + 13)); p += 4;

    /* IDAT */
    put_be32(p, (uint32_t)comp_cap); p += 4;
    uint8_t *idat = p; memcpy(p, "IDAT", 4); p += 4;
    memcpy(p, comp, comp_cap); p += comp_cap;
    put_be32(p, (uint32_t)crc32(crc32(0, idat, 4), comp, comp_cap)); p += 4;
    free(comp);

    /* IEND */
    put_be32(p, 0); p += 4;
    uint8_t *iend = p; memcpy(p, "IEND", 4); p += 4;
    put_be32(p, (uint32_t)crc32(0, iend, 4)); p += 4;

    *out = png;
    return (long)(p - png);
}

/* Grab the emulated LCD and send it as a PNG on the socket. */
static void handle_shot(int fd) {
    uint8_t packed[LCD_PACKED_BYTES];
    bridge_lock();
    lcd_draw_frame(packed);
    bridge_unlock();

    /* Expand 4bpp packed (hi nibble = left px) to 8-bit grayscale. */
    static uint8_t gray[LCD_W * LCD_H];
    for (int y = 0; y < LCD_H; y++) {
        for (int bx = 0; bx < LCD_W / 2; bx++) {
            uint8_t b = packed[y * (LCD_W / 2) + bx];
            uint8_t hi = (uint8_t)(b >> 4), lo = (uint8_t)(b & 0x0F);
#if BR_INVERT_GRAY
            hi = (uint8_t)(15 - hi); lo = (uint8_t)(15 - lo);
#endif
            gray[y * LCD_W + bx * 2]     = (uint8_t)(hi * 17);
            gray[y * LCD_W + bx * 2 + 1] = (uint8_t)(lo * 17);
        }
    }

    uint8_t *png = NULL;
    long len = png_encode_gray8(gray, LCD_W, LCD_H, &png);
    if (len < 0) { const char *e = "ERR encode\n"; send(fd, e, (int)strlen(e), 0); return; }

    char hdr[32];
    int hn = snprintf(hdr, sizeof(hdr), "IMG %ld\n", len);
    send(fd, hdr, hn, 0);
    long off = 0;
    while (off < len) {
        int w = (int)send(fd, (const char *)png + off, (int)(len - off), 0);
        if (w <= 0) break;
        off += w;
    }
    free(png);
}

/* ---------- one client connection: read lines, dispatch ---------- */
static void serve_client(int fd) {
    char buf[256];
    int used = 0;
    for (;;) {
        int r = (int)recv(fd, buf + used, (int)(sizeof(buf) - 1 - used), 0);
        if (r <= 0) break;
        used += r;
        buf[used] = '\0';

        char *nl;
        while ((nl = memchr(buf, '\n', used)) != NULL) {
            *nl = '\0';
            char line[256];
            size_t ll = (size_t)(nl - buf);
            memcpy(line, buf, ll); line[ll] = '\0';
            if (ll && line[ll - 1] == '\r') line[ll - 1] = '\0';

            if (strcmp(line, "SHOT") == 0) {
                handle_shot(fd);
            } else if (strncmp(line, "KEY ", 4) == 0) {
                handle_key(line + 4);
                send(fd, "OK\n", 3, 0);
            } else if (strncmp(line, "OS ", 3) == 0) {
                /* Install an OS (.tno) into a flash that has none yet. */
                nremote_send_os(line + 3);
                send(fd, "OK\n", 3, 0);
            } else if (strncmp(line, "PUT ", 4) == 0) {
                /* PUT <local>::<remote> pushes a file to the emulated device. */
                char *sep = strstr(line + 4, "::");
                if (sep) {
                    *sep = '\0';
                    nremote_put_file(line + 4, sep + 2);
                    send(fd, "OK\n", 3, 0);
                } else {
                    send(fd, "ERR\n", 4, 0);
                }
            } else if (strncmp(line, "SAVEFLASH ", 10) == 0) {
                /* Persist the NAND (with a freshly installed OS) to a file. */
                char b[32];
                int bn = snprintf(b, sizeof(b), "SAVED %d\n",
                                  nremote_save_flash(line + 10));
                send(fd, b, bn, 0);
            } else if (strncmp(line, "TOUCH ", 6) == 0) {
                /* Raw touchpad state: "TOUCH <x> <y> <contact> <down>", with x
                 * and y as 0..1 fractions of the pad. For tuning and debugging
                 * the arrow behaviour, which is derived from relative motion. */
                float tx = 0.f, ty = 0.f;
                int tc = 0, td = 0;
                if (sscanf(line + 6, "%f %f %d %d", &tx, &ty, &tc, &td) == 4) {
                    bridge_lock();
                    touchpad_set_state(tx, ty, tc != 0, td != 0);
                    bridge_unlock();
                    send(fd, "OK\n", 3, 0);
                } else {
                    send(fd, "ERR\n", 4, 0);
                }
            } else if (strcmp(line, "HALT") == 0) {
                /* Enter the debugger at the next instruction boundary. */
                DBG_LOCK();
                if (!dbg_entered) dbg_halt_request = 1;
                DBG_UNLOCK();
                send(fd, "OK\n", 3, 0);
            } else if (strcmp(line, "DBGSTATE") == 0) {
                char b[32];
                int bn;
                DBG_LOCK();
                bn = snprintf(b, sizeof(b), "HALTED %d\n", dbg_entered);
                DBG_UNLOCK();
                send(fd, b, bn, 0);
            } else if (strncmp(line, "DBG ", 4) == 0) {
                handle_dbg(fd, line + 4);
            } else if (strcmp(line, "STATUS") == 0) {
                char b[48];
                int bn = snprintf(b, sizeof(b), "USBLINK %d\n", nremote_usblink_ready());
                send(fd, b, bn, 0);
            } else if (line[0]) {
                send(fd, "ERR\n", 4, 0);
            }

            int rest = used - (int)(nl - buf) - 1;
            memmove(buf, nl + 1, rest);
            used = rest;
            buf[used] = '\0';
        }
        if (used >= (int)sizeof(buf) - 1) used = 0; /* overlong line: drop */
    }
    BR_CLOSE(fd);
}

/* ---------- accept loop (run on its own thread) ---------- */
static int g_port = 3334;

#ifndef _WIN32
static void *client_thread(void *arg) {
    int fd = *(int *)arg;
    free(arg);
    serve_client(fd);
    return NULL;
}
#endif

static void bridge_accept_loop(void) {
#ifdef _WIN32
    WSADATA wsa; WSAStartup(MAKEWORD(2, 2), &wsa);
#endif
    int srv = (int)socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) { perror("nremote_bridge: socket"); return; }
    int yes = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, (const char *)&yes, sizeof(yes));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); /* localhost only */
    addr.sin_port = htons((uint16_t)g_port);
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("nremote_bridge: bind"); BR_CLOSE(srv); return;
    }
    listen(srv, 4);
    fprintf(stderr, "nremote_bridge: listening on 127.0.0.1:%d\n", g_port);
    for (;;) {
        int cl = (int)accept(srv, NULL, NULL);
        if (cl < 0) continue;
        /* One connection must not lock everyone else out: nRemote holds its
         * socket open for the whole session, and a client that dies without
         * closing cleanly would otherwise make the port unusable until the
         * emulator is restarted. */
#ifdef _WIN32
        serve_client(cl);
#else
        {
            pthread_t th;
            int *arg = (int *)malloc(sizeof(int));
            if (!arg) { BR_CLOSE(cl); continue; }
            *arg = cl;
            if (pthread_create(&th, NULL, client_thread, arg) != 0) {
                free(arg);
                serve_client(cl);   /* fall back to inline handling */
            } else {
                pthread_detach(th);
            }
        }
#endif
    }
}

#ifndef _WIN32
static void *bridge_thread(void *arg) { (void)arg; bridge_accept_loop(); return NULL; }
#endif

/*
 * Public entry point. Call once from Firebird startup, after the core is up,
 * e.g. from main()/the emulator init, with the port nRemote will use (3334).
 */
void nremote_bridge_start(int port) {
    if (port > 0) g_port = port;
#ifdef _WIN32
    /* On Windows spawn with _beginthread / CreateThread instead. */
    bridge_accept_loop();
#else
    pthread_t th;
    pthread_create(&th, NULL, bridge_thread, NULL);
    pthread_detach(th);
#endif
}
