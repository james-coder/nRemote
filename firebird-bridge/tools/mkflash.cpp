/*
 * mkflash -- create a Firebird NAND flash image from boot2 + OS, the way the
 * Firebird GUI's flash wizard does, but headless. Firebird generates the manuf
 * area itself (from the product code), so no manuf dump is needed. Build it by
 * linking against Firebird's compiled core objects (see ../build-and-run.sh).
 *
 *   mkflash <boot2.img> <os.img> <out.flash> [productHex=0E0]
 *
 * 0x0E0 = classic non-CAS Touchpad (Firebird's own product code for it).
 */
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <cstring>
#include <cerrno>
#include "core/debug.h"
#include "core/emu.h"
#include "core/flash.h"

/* gui_* / throttle_* stubs the core links against (as in headless/main.cpp). */
void gui_do_stuff(bool){}
void do_stuff(int){}
void gui_debug_printf(const char *fmt, ...){ va_list ap; va_start(ap,fmt); vprintf(fmt,ap); va_end(ap); }
void gui_debug_vprintf(const char *fmt, va_list ap){ vprintf(fmt,ap); }
void gui_status_printf(const char *fmt, ...){ va_list ap; va_start(ap,fmt); vprintf(fmt,ap); va_end(ap); putchar('\n'); }
void gui_perror(const char *msg){ printf("%s: %s\n", msg, strerror(errno)); }
void gui_debugger_entered_or_left(bool){}
void gui_debugger_request_input(debug_input_cb){}
void gui_putchar(char c){ putc(c, stdout); }
int  gui_getchar(){ return -1; }
void gui_set_busy(bool){}
void gui_show_speed(double){}
void gui_usblink_changed(bool){}
void throttle_timer_off(){}
void throttle_timer_on(){}
void throttle_timer_wait(unsigned int){}

int main(int argc, char **argv){
    if(argc < 4){ fprintf(stderr,"usage: mkflash <boot2.img> <os.img> <out.flash> [productHex=0E0]\n"); return 2; }
    const char *boot2=argv[1], *os=argv[2], *out=argv[3];
    unsigned int product = (argc>4)? (unsigned)strtoul(argv[4],nullptr,16) : 0x0E0;
    bool is_cx = product >= 0x0F0;
    /* order: manuf(gen), boot2, diags(none), os  -- matches qmlbridge createFlash */
    const char *preload[4] = { nullptr, boot2, nullptr, os };
    uint8_t *nand=nullptr; size_t size=0;
    if(!flash_create_new(is_cx, preload, product, 0, is_cx, &nand, &size)){
        fprintf(stderr,"flash_create_new FAILED\n"); free(nand); return 1;
    }
    FILE *f=fopen(out,"wb");
    if(!f || fwrite(nand,1,size,f)!=size){ perror("write"); return 3; }
    fclose(f); free(nand);
    printf("OK: wrote %s  (%zu bytes, product=0x%X, is_cx=%d)\n", out, size, product, is_cx);
    return 0;
}
