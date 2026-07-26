/*
 * flashinfo -- open a Firebird NAND flash image and print what Firebird's own
 * parser reads back from its manuf area (product code, SDRAM size). Used to
 * verify a flash built by mkflash. Links against Firebird's core objects.
 *
 *   flashinfo <flash>
 */
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <cstring>
#include <cerrno>
#include "core/debug.h"
#include "core/emu.h"
#include "core/flash.h"
void gui_do_stuff(bool){} void do_stuff(int){}
void gui_debug_printf(const char*f,...){va_list a;va_start(a,f);vprintf(f,a);va_end(a);}
void gui_debug_vprintf(const char*f,va_list a){vprintf(f,a);}
void gui_status_printf(const char*f,...){va_list a;va_start(a,f);vprintf(f,a);va_end(a);putchar('\n');}
void gui_perror(const char*m){printf("%s: %s\n",m,strerror(errno));}
void gui_debugger_entered_or_left(bool){} void gui_debugger_request_input(debug_input_cb){}
void gui_putchar(char c){putc(c,stdout);} int gui_getchar(){return -1;}
void gui_set_busy(bool){} void gui_show_speed(double){} void gui_usblink_changed(bool){}
void throttle_timer_off(){} void throttle_timer_on(){} void throttle_timer_wait(unsigned int){}
int main(int argc,char**argv){
  if(!flash_open(argv[1])){ printf("flash_open FAILED\n"); return 1; }
  uint32_t sdram=0,product=0,features=0,flags=0;
  bool ok=flash_read_settings(&sdram,&product,&features,&flags);
  printf(ok?"flash_read_settings OK: product=0x%X features=0x%X sdram=%uMB asic_flags=0x%X\n"
           :"flash_read_settings FAILED (product=0x%X)\n",
         product, features, sdram/1024/1024, flags);
  return ok?0:2;
}
