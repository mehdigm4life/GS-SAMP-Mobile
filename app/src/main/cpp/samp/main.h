#pragma once

#include <cstdlib>
#include <cstdio>
#include <vector>
#include <list>
#include <string>
#include <sstream>
#include <unistd.h>
#include "log.h"
#include <jni.h>
#include <cstring>
#include "game/common.h"
#include "vendor/bass/bass.h"
#include "vendor/bass/bass_fx.h"

extern char* g_pszStorage;

#ifndef SAFE_DELETE
	#define SAFE_DELETE(p) { if (p) { delete (p); (p) = NULL; } }
#endif

#ifndef ARRAY_SIZE
#define ARRAY_SIZE(a) ( sizeof((a)) / sizeof(*(a)) )
#endif

#define SAMP_VERSION	"0.3.7"

#define SAMP_ARCHIVE_PATH "/Android/data/com.samp.mobile/samp.data"
#define FONT_NAME "arial_bold.ttf"

#define RAKSAMP_CLIENT
#define NETCODE_CONNCOOKIELULZ 0x6969

extern uintptr_t g_libGTASA;

extern JavaVM* javaVM;

uint32_t GetTickCount();
void LogVoice(const char* fmt, ...);

void FLog(const char* fmt, ...);

// Records how far the startup got. The name is written to
// /storage/emulated/0/GS-SAMP/stage.txt so the next start can report where the
// previous one stopped, and the same line carries the process RSS, peak RSS and
// the system's available memory (the value that decides an OOM kill).
void GameStage(const char* name);
void MyLog(const char* fmt, ...);
void MyLog2(const char* fmt, ...);
void ChatLog(const char* fmt, ...);

template<typename ... Args>
std::string string_format( const std::string& format, Args ... args )
{
    int size_s = snprintf( nullptr, 0, format.c_str(), args ... ) + 1; // Extra space for '\0'
    // A formatting failure used to throw from every caller on the init and
    // render path, where nothing catches and the process aborts. An empty
    // string is handled everywhere (it is only ever used to build paths).
    if( size_s <= 0 ){ return std::string(); }
    auto size = static_cast<size_t>( size_s );
    std::unique_ptr<char[]> buf( new char[ size ] );
    snprintf( buf.get(), size, format.c_str(), args ... );
    return std::string( buf.get(), buf.get() + size - 1 ); // We don't want the '\0' inside
}