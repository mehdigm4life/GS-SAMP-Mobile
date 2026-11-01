#include <jni.h>
#include <pthread.h>
#include <syscall.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>

#include "main.h"
#include "game/game.h"
#include "net/netgame.h"
#include "gui/gui.h"
#include "playertags.h"
#include "audiostream.h"
#include "java/jniutil.h"
#include <dlfcn.h>
#include "StackTrace.h"
#include "diagnostics/CrashReportOwner.h"

// voice
#include "voice_new/Plugin.h"

#include "vendor/armhook/patch.h"
#include "vendor/str_obfuscator/str_obfuscator.hpp"

#include "settings.h"

#include "crashlytics.h"

/*
Peerapol Unarak
*/

JavaVM* javaVM;

// Fallback storage root. It used to start as nullptr and was only assigned
// from libGTASA's StorageRootBuffer inside the NvUtilInit hook. Anything that
// touched g_pszStorage before that hook fired (FLog, ChatLog, InitGui's font
// path) then formatted a null pointer. Defaulting to the documented cache
// location keeps those paths valid from the very first instruction; the hook
// still overrides this pointer once the game root is known.
char* g_pszStorage = (char*)"/storage/emulated/0/GTA/";

// ---------------------------------------------------------------------------
// Diagnostic log location.
//
// This is intentionally NOT derived from g_pszStorage:
//   * g_pszStorage is replaced by libGTASA's own StorageRootBuffer and is used
//     to build every game asset path (SAMP/main.scm, data/*.dat, ...), so it
//     must keep pointing at the game cache.
//   * Logs must live somewhere the user can actually open. Android/data/* is
//     hidden from file managers, MTP and USB on Android 11+.
//
// Everything therefore goes to a plain folder on the shared storage that any
// file manager can browse. StorageGateActivity creates it and writes its
// lifecycle/crash lines to the same file.
static const char* g_pszLogDir = "/storage/emulated/0/GS-SAMP";

// Opens <g_pszLogDir>/<name> for appending, creating the folder when needed.
// Returns nullptr while the app has no "All files access" yet; every caller
// treats that as "logging unavailable" and simply retries on the next call.
static FILE* OpenLogFile(const char* name)
{
	mkdir(g_pszLogDir, 0770);

	char path[0xFF];
	snprintf(path, sizeof(path), "%s/%s", g_pszLogDir, name);
	return fopen(path, "a");
}

// "HH:MM:SS.mmm" prefix so the Java and native lines in the same file can be
// ordered against each other.
static void LogTimestamp(char* out, size_t outSize)
{
	struct timeval tv;
	gettimeofday(&tv, nullptr);
	struct tm tmv;
	time_t sec = tv.tv_sec;
	localtime_r(&sec, &tmv);
	snprintf(out, outSize, "%02d:%02d:%02d.%03d",
	         tmv.tm_hour, tmv.tm_min, tmv.tm_sec, (int)(tv.tv_usec / 1000));
}

UI* pUI = nullptr;
CGame *pGame = nullptr;

CNetGame *pNetGame = nullptr;
CPlayerTags* pPlayerTags = nullptr;
CSnapShotHelper* pSnapShotHelper = nullptr;
CAudioStream* pAudioStream = nullptr;
CJavaWrapper* pJavaWrapper = nullptr;
CSettings* pSettings = nullptr;
//CVoice* pVoice = nullptr;

MaterialTextGenerator* pMaterialTextGenerator = nullptr;

bool bDebug = false;
bool bGameInited = false;
bool bNetworkInited = false;

uintptr_t g_libGTASA = 0x00;
uintptr_t g_libSAMP = 0x00;

void ApplyGlobalPatches();
void ApplyPatches_level0();
void ApplyMultiTouchPatches();
void InstallGlobalHooks();
void InstallSpecialHooks();
void InitRenderWareFunctions();
void FLog(const char* fmt, ...);
//void MyLog(const char* fmt, ...);

int work = 0;

void ReadSettingFile()
{
	/*char path[255] = { 0 };
	//sprintf(path, "%ssamp.set", g_pszStorage);
	sprintf(path, "%sNickName.ini", g_pszStorage);

	FILE* fp = fopen(path, "r");
	if (fp == NULL) return;

	char buf[1024];

	// nickname
	if (fgets(buf, 1024, fp) != NULL) {
		buf[strcspn(buf, "\n\r")] = 0;
		strcpy(g_nick, buf);
	}

	fclose(fp);*/

	pSettings = new CSettings();

	firebase::crashlytics::SetUserId(pSettings->Get().szNickName);
}

int hashing(const char* str) {
	int hashing = 5381;
	int c;
	while (c = *str++) {
		hashing = ((hashing << 5) + hashing) + c; /* hash * 33 + c */
		if (hashing < 0) hashing = 100;
	}
	if (hashing < 0) hashing = 100;
	return hashing;
}

void DoDebugLoop()
{
	// ...
}

void DoDebugStuff()
{
	// ...

	RwMatrix mat = pGame->FindPlayerPed()->m_pPed->GetMatrix().ToRwMatrix();
	
	for (int i = 0; i < 100; i++)
	{
		CPlayerPed* ped = pGame->NewPlayer(i, mat.pos.x + i, mat.pos.y, mat.pos.z, 0.0f, false, false);
		//ped->SetCollisionChecking(false);
		//ped->SetGravityProcessing(false);
	}
}
struct sigaction act_old;
struct sigaction act1_old;
struct sigaction act2_old;
struct sigaction act3_old;
struct sigaction act4_old;
struct sigaction act5_old;

// ---------------------------------------------------------------------------
// Stage tracking and crash reporting
//
// A start that dies without a trace is impossible to fix, and the report used to
// be lost: the handlers chained to the previous handler (debuggerd) *first* and
// only then called FLog(), which needs fopen/fprintf/malloc - none of them
// async-signal-safe. When the fault happened inside the allocator the handler
// deadlocked, and when the chained handler terminated the process the report was
// never written at all. That is exactly how one start ended right after
// "Loading script.img.." with no SIGSEGV/SIGABRT line in logcat.txt.
//
// Everything below the reporter line only uses open()/write()/fsync()/close() on
// fixed paths and formats numbers by hand, then restores the default action and
// re-raises the signal so the system crash reporter still runs.
// ---------------------------------------------------------------------------
static const char* kCrashLogPath  = "/storage/emulated/0/GS-SAMP/crash.txt";
static const char* kStagePath     = "/storage/emulated/0/GS-SAMP/stage.txt";

// Read by the signal handler, so only ever assigned a string literal.
static const char* g_currentStage = "libsamp loaded";

// The markers below are copied into a fixed buffer by hand, and at -O2 LLVM is
// free to turn such a copy of a short literal into immediate stores - which
// removes the literal from the binary. Then the diagnostics still work but
// nothing in the built library proves that the reporter is linked in, so the
// markers are pinned with "used" and are read from the name, not inlined.
__attribute__((used)) static const char kCrashMarker[]       = "\n=== CRASH signal=";
__attribute__((used)) static const char kExitMarker[]        = "\n=== EXIT atexit stage=";
__attribute__((used)) static const char kKilledMarkerText[] = "previous session ended without a crash/exit report";
__attribute__((used)) static const char kCleanMarkerText[]  = "previous session ended cleanly";

static size_t SsLen(const char* s) { size_t n = 0; while (s && s[n]) n++; return n; }

static char* SsStr(char* out, const char* s) { while (s && *s) *out++ = *s++; return out; }

static char* SsHex(char* out, uintptr_t v)
{
	*out++ = '0';
	*out++ = 'x';
	if (v == 0)
	{
		*out++ = '0';
		return out;
	}

	char tmp[16];
	int n = 0;
	while (v)
	{
		const uintptr_t d = v & 0xF;
		tmp[n++] = (char)(d < 10 ? ('0' + d) : ('a' + (d - 10)));
		v >>= 4;
	}
	while (n)
		*out++ = tmp[--n];

	return out;
}

static char* SsDec(char* out, long v)
{
	char tmp[24];
	int n = 0;

	if (v < 0)
	{
		*out++ = '-';
		v = -v;
	}
	if (v == 0)
	{
		*out++ = '0';
		return out;
	}
	while (v)
	{
		tmp[n++] = (char)('0' + (v % 10));
		v /= 10;
	}
	while (n)
		*out++ = tmp[--n];

	return out;
}

// The absolute pc means nothing without the module bases, and those move on
// every start (ASLR). Subtracting the base recorded at load time turns a crash
// into "libsamp.so + 0x1b9bfc", which is one lookup in llvm-nm away from a name
// and is what identified the crash right after a server accepted the client.
static const char* ModuleAndOffset(uintptr_t addr, uintptr_t* offsetOut)
{
	*offsetOut = 0;

	struct { uintptr_t base; const char* name; } modules[] = {
		{ g_libSAMP,  "libsamp.so" },
		{ g_libGTASA, "libGTASA.so" },
	};

	for (const auto& m : modules)
	{
		// A generous window: libGTASA.so is ~9 MB, libsamp.so ~3.6 MB.
		if (m.base != 0 && addr >= m.base && addr - m.base < 0x2000000)
		{
			*offsetOut = addr - m.base;
			return m.name;
		}
	}

	return "unknown";
}

static void SsWriteAll(int fd, const char* buf, size_t len)
{
	while (len)
	{
		const ssize_t w = write(fd, buf, len);
		if (w <= 0)
			return;
		buf += w;
		len -= (size_t)w;
	}
}

// Preserve the registers and mappings needed to diagnose heap corruption and
// driver/plugin crashes. Do not dereference the possibly corrupt SP/FP here.
// open/read/write/close are signal-safe; avoid dladdr, malloc and backtrace.
static void WriteCrashContext(int fd, void* contextPtr)
{
#if defined(__aarch64__)
    if (contextPtr)
    {
        const auto* ctx = static_cast<const ucontext_t*>(contextPtr);
        for (unsigned i = 0; i < 31; ++i)
        {
            char line[96];
            char* p = SsStr(line, "register[");
            p = SsHex(p, i);
            p = SsStr(p, "]=");
            p = SsHex(p, ctx->uc_mcontext.regs[i]);
            p = SsStr(p, "\n");
            SsWriteAll(fd, line, static_cast<size_t>(p - line));
        }
    }
#endif
    const char marker[] = "=== memory mappings at crash ===\n";
    SsWriteAll(fd, marker, sizeof(marker) - 1);
    const int maps = open("/proc/self/maps", O_RDONLY);
    if (maps >= 0)
    {
        char chunk[1024];
        // Bound work even if the process has unusually many mappings.
        for (unsigned i = 0; i < 256; ++i)
        {
            const ssize_t n = read(maps, chunk, sizeof(chunk));
            if (n <= 0) break;
            SsWriteAll(fd, chunk, static_cast<size_t>(n));
        }
        close(maps);
    }
    const char end[] = "=== end memory mappings ===\n";
    SsWriteAll(fd, end, sizeof(end) - 1);
}

// Async-signal-safe: no allocation, no stdio.
//
// [fatal] decides whether the signal is re-raised with the default action. The
// crash signals always are. SIGQUIT and SIGTERM are not: SIGQUIT is what the
// system sends to dump the stack of an app it considers stuck, and killing the
// process there would turn a survivable ANR dump into a dead app.
static CrashReportOwner g_crashReportOwner;

static void ReportCrash(int signum, siginfo_t* info, void* contextPtr, bool fatal)
{
    const int tid = static_cast<int>(syscall(SYS_gettid));
    if (fatal)
    {
        const auto ownership = g_crashReportOwner.TryAcquire(tid);
        if (ownership == CrashReportOwner::Result::Reentered)
            _exit(128 + signum); // A fault in the reporter must not recurse.
        if (ownership == CrashReportOwner::Result::OtherThread)
        {
            // Do not write into the owner's report or terminate it mid-write.
            // Its default fatal signal will terminate the whole process.
            for (;;) pause();
        }
    }

	char buf[768];
	char* p = buf;

	p = SsStr(p, kCrashMarker);
	p = SsHex(p, (uintptr_t)signum);
	p = SsStr(p, " fault=");
	p = SsHex(p, info ? (uintptr_t)info->si_addr : 0);
	p = SsStr(p, " code=");

	uintptr_t pc = 0, lr = 0, sp = 0;
	if (contextPtr)
	{
		const ucontext_t* ctx = (const ucontext_t*)contextPtr;
#if defined(__aarch64__)
		pc = (uintptr_t)ctx->uc_mcontext.pc;
		lr = (uintptr_t)ctx->uc_mcontext.regs[30];
		sp = (uintptr_t)ctx->uc_mcontext.sp;
#else
		pc = (uintptr_t)ctx->uc_mcontext.arm_pc;
		lr = (uintptr_t)ctx->uc_mcontext.arm_lr;
		sp = (uintptr_t)ctx->uc_mcontext.arm_sp;
#endif
	}
	p = SsHex(p, info ? (uintptr_t)info->si_code : 0);
	p = SsStr(p, " pc=");
	p = SsHex(p, pc);
	p = SsStr(p, " lr=");
	p = SsHex(p, lr);
	p = SsStr(p, " sp=");
	p = SsHex(p, sp);

	uintptr_t pcOff = 0, lrOff = 0;
	const char* pcModule = ModuleAndOffset(pc, &pcOff);
	const char* lrModule = ModuleAndOffset(lr, &lrOff);
	p = SsStr(p, " at=");
	p = SsStr(p, pcModule);
	p = SsStr(p, "+");
	p = SsHex(p, pcOff);
	p = SsStr(p, " caller=");
	p = SsStr(p, lrModule);
	p = SsStr(p, "+");
	p = SsHex(p, lrOff);

	p = SsStr(p, " tid=");
    p = SsHex(p, static_cast<uintptr_t>(tid));
    p = SsStr(p, " stage=");
	p = SsStr(p, g_currentStage ? g_currentStage : "?");
	p = SsStr(p, "\n");
	*p = 0;   // __android_log_write() below needs a terminated string

	const int fd = open(kCrashLogPath, O_WRONLY | O_CREAT | O_APPEND, 0660);
	if (fd >= 0)
	{
		SsWriteAll(fd, buf, (size_t)(p - buf));
		if (fatal) WriteCrashContext(fd, contextPtr);
		fsync(fd);
		close(fd);
	}

	__android_log_write(ANDROID_LOG_FATAL, "SAMP", buf);

	if (!fatal)
		return;

	// Let the system produce its own tombstone as well, then die for real.
	signal(signum, SIG_DFL);
	raise(signum);
}

// ---------------------------------------------------------------------------
// Stage recording: tells the next start the last point reached, even when the
// process ends without a signal/exit report. It cannot identify the cause.
// ---------------------------------------------------------------------------
static long ReadProcKb(const char* path, const char* key)
{
	FILE* f = fopen(path, "r");
	if (!f)
		return -1;

	char line[256];
	const size_t keyLen = SsLen(key);
	long value = -1;

	while (fgets(line, sizeof(line), f))
	{
		if (strncmp(line, key, keyLen) != 0)
			continue;

		const char* p = line + keyLen;
		while (*p == ' ' || *p == '\t' || *p == ':')
			p++;
		value = strtol(p, nullptr, 10);
		break;
	}

	fclose(f);
	return value;
}

void GameStage(const char* name)
{
	if (!name)
		return;

	g_currentStage = name;

	// Record a memory snapshot for diagnosis. It is evidence from the last
	// stage, not proof that memory pressure caused any later termination.
	FILE* f = fopen(kStagePath, "w");
	if (f)
	{
		fprintf(f, "%s\nfree=%ldkB rss=%ldkB\n",
		        name,
		        ReadProcKb("/proc/meminfo", "MemAvailable"),
		        ReadProcKb("/proc/self/status", "VmRSS"));
		fclose(f);
	}

	FLog("STAGE %s | rss=%ldkB peak=%ldkB free=%ldkB",
	     name,
	     ReadProcKb("/proc/self/status", "VmRSS"),
	     ReadProcKb("/proc/self/status", "VmPeak"),
	     ReadProcKb("/proc/meminfo", "MemAvailable"));
}

// Called once per process start: reports how the previous run ended.
//
// A fatal signal appends to crash.txt. A plain exit() is caught by the atexit
// reporter below and appends there too. If neither report is newer than the
// stage marker, the process ended without a captured report; the available
// evidence cannot distinguish an OS kill, task removal, or an unreported fault.
static void LogPreviousStage()
{
	mkdir("/storage/emulated/0/GS-SAMP", 0770);

	FILE* f = fopen(kStagePath, "r");
	if (!f)
	{
		FLog("previous session: no stage recorded (first start)");
		return;
	}

	char line[128] = { 0 };
	char detail[128] = { 0 };
	const bool ok = fgets(line, sizeof(line), f) != nullptr;
	if (ok)
		fgets(detail, sizeof(detail), f);
	fclose(f);
	if (!ok)
		return;

	for (char* c = line; *c; ++c)
		if (*c == '\n' || *c == '\r') { *c = 0; break; }
	for (char* c = detail; *c; ++c)
		if (*c == '\n' || *c == '\r') { *c = 0; break; }

	const char* last = line[0] ? line : "(empty)";
	FLog("previous session ended at stage: %s (%s)", last, detail[0] ? detail : "no memory data");

	if (strcmp(last, "clean exit") == 0)
	{
		FLog("%s", kCleanMarkerText);
		return;
	}

	// One-second mtime granularity: only treat the stage as newer when it is
	// strictly newer; equal timestamps are ambiguous and should not hide a report.
	struct stat stageStat = { 0 };
	struct stat crashStat = { 0 };
	const bool haveStage = stat(kStagePath, &stageStat) == 0;
	const bool haveCrash = stat(kCrashLogPath, &crashStat) == 0;

	if (!haveCrash || (haveStage && stageStat.st_mtime > crashStat.st_mtime))
	{
		FLog("%s at stage: %s - termination cause is unknown; it may be an OS kill, "
		     "task removal, or a fault that bypassed the native reporter. "
		     "%s is only the last recorded memory snapshot",
		     kKilledMarkerText, last, detail[0] ? detail : "stage.txt");
	}
	else
	{
		FLog("previous session left a crash report, see %s", kCrashLogPath);
	}
}

// exit() is the third way a start can end without a trace: the engine's own
// shutdown path calls it directly, and the process simply disappears. atexit()
// runs before that final teardown and uses nothing but open()/write(), so the
// report survives even when the rest of the process is already falling apart.
// A genuine clean exit is skipped, so the file only ever names real surprises.
static void ReportExitAtExit()
{
	if (g_currentStage && strcmp(g_currentStage, "clean exit") == 0)
		return;

	char buf[256];
	char* p = buf;
	p = SsStr(p, kExitMarker);
	p = SsStr(p, g_currentStage ? g_currentStage : "(none)");
	p = SsStr(p, " rss=");
	p = SsDec(p, ReadProcKb("/proc/self/status", "VmRSS"));
	p = SsStr(p, "kB free=");
	p = SsDec(p, ReadProcKb("/proc/meminfo", "MemAvailable"));
	p = SsStr(p, "kB\n");

	const int fd = open(kCrashLogPath, O_WRONLY | O_CREAT | O_APPEND, 0660);
	if (fd >= 0)
	{
		SsWriteAll(fd, buf, (size_t)(p - buf));
		fsync(fd);
		close(fd);
	}
}

// All four handlers go through the async-signal-safe reporter first, then let
// the system handle the crash. The detailed native backtrace that used to run
// here (CStackTrace + FLog) is gone: it needed malloc/fopen, so it deadlocked
// exactly when it was needed most and the start died without a single line.
static void HandleFatal(int signum, siginfo_t* info, void* contextPtr)
{
	ReportCrash(signum, info, contextPtr, true);

	// ReportCrash() re-raises with the default action; reaching this point means
	// the signal was blocked, so fall back to the previous handler.
	const struct sigaction* old = nullptr;
	switch (signum)
	{
		case SIGSEGV: old = &act_old;  break;
		case SIGABRT: old = &act1_old; break;
		case SIGFPE:  old = &act2_old; break;
		case SIGBUS:  old = &act3_old; break;
		case SIGILL:  old = &act5_old; break;
		default: break;
	}

	if (old && old->sa_sigaction)
		old->sa_sigaction(signum, info, contextPtr);
}

void handler(int signum, siginfo_t *info, void* contextPtr)
{
	HandleFatal(signum, info, contextPtr);
}

void handler1(int signum, siginfo_t *info, void* contextPtr)
{
	HandleFatal(signum, info, contextPtr);
}

void handler2(int signum, siginfo_t *info, void* contextPtr)
{
	HandleFatal(signum, info, contextPtr);
}

void handler3(int signum, siginfo_t *info, void* contextPtr)
{
	HandleFatal(signum, info, contextPtr);
}

// SIGQUIT (the system asking for a stack dump of a "stuck" app) and SIGTERM are
// recorded but never turned into a death by this code.
void handlerSoft(int signum, siginfo_t *info, void* contextPtr)
{
	ReportCrash(signum, info, contextPtr, false);

	if (act4_old.sa_sigaction)
		act4_old.sa_sigaction(signum, info, contextPtr);
}

/**
 * The server to connect to comes from settings.ini: [client] host and port.
 *
 * It used to be hardcoded - "94.23.168.153", 2305 - so the file the player was
 * told to edit had no effect at all and no server ever accepted the connection.
 *
 * settings.ini is hand-edited, so every shape a player actually types is
 * accepted: "1.2.3.4", "1.2.3.4:7777", "samp.example.com:7777", quotes, spaces,
 * a "samp://" or "udp://" prefix. A port inside the host key wins over the port
 * key, because that is the more specific thing the player wrote.
 */
static void ResolveConfiguredServer(const stSettings& settings, char* hostOut, size_t hostSize, int* portOut)
{
	if (hostOut == nullptr || hostSize == 0 || portOut == nullptr)
		return;

	hostOut[0] = 0;
	*portOut = 7777;

	char raw[192];
	size_t n = 0;
	for (const char* src = settings.szHost; src && *src && n + 1 < sizeof(raw); ++src)
		raw[n++] = *src;
	raw[n] = 0;

	char* start = raw;
	while (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n' || *start == '"' || *start == '\'')
		start++;

	char* end = start + strlen(start);
	while (end > start && (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\r' || end[-1] == '\n' || end[-1] == '"' || end[-1] == '\''))
		*--end = 0;

	if (strncmp(start, "samp://", 7) == 0)
		start += 7;
	else if (strncmp(start, "udp://", 6) == 0)
		start += 6;

	int port = settings.iPort;

	// "host:port". A second colon would make this IPv6, which is left untouched.
	char* colon = strrchr(start, ':');
	if (colon != nullptr && strchr(colon + 1, ':') == nullptr && colon[1] != 0)
	{
		bool digits = true;
		for (const char* c = colon + 1; *c; ++c)
		{
			if (*c < '0' || *c > '9')
			{
				digits = false;
				break;
			}
		}
		if (digits)
		{
			const int parsed = atoi(colon + 1);
			if (parsed > 0 && parsed < 65536)
			{
				port = parsed;
				*colon = 0;
			}
		}
	}

	if (port <= 0 || port > 65535)
		port = 7777;

	snprintf(hostOut, hostSize, "%s", start[0] ? start : "127.0.0.1");
	*portOut = port;
}

void DoInitStuff()
{
	if (bGameInited == false)
	{
		pPlayerTags = new CPlayerTags();
		pSnapShotHelper = new CSnapShotHelper();
		pMaterialTextGenerator = new MaterialTextGenerator();
		pAudioStream = new CAudioStream();
		pAudioStream->Initialize();

		pUI->splashscreen()->setVisible(false);
		pUI->chat()->setVisible(true);
		pUI->buttonpanel()->setVisible(true);

		pGame->Initialize();
		pGame->SetMaxStats();
		pGame->ToggleThePassingOfTime(false);

		// voice
		/*LogVoice("[dbg:samp:load] : module loading...");

		for (const auto& loadCallback : Samp::loadCallbacks) {
			if (loadCallback != nullptr) {
				loadCallback();
			}
		}

		Samp::loadStatus = true;*/

		LogVoice("[dbg:samp:load] : module loaded");

		if (bDebug)
		{
            CCamera& TheCamera = *reinterpret_cast<CCamera*>(g_libGTASA + (VER_x32 ? 0x00951FA8 : 0xBBA8D0));
            //TheCamera.Restore();
            CCamera::SetBehindPlayer();
			pGame->DisplayHUD(true);
			pGame->EnableClock(false);

			DoDebugStuff();
		}

		bGameInited = true;
	}

	if (!bNetworkInited && !bDebug)
	{
		//ReadSettingFile();

		// The address is read from settings.ini on every start. It used to be a
		// hardcoded IP here, which is why no server configured in the file could
		// ever be reached.
		char szServerHost[128];
		int iServerPort = 7777;
		ResolveConfiguredServer(pSettings->Get(), szServerHost, sizeof(szServerHost), &iServerPort);
		FLog("net: configured server %s:%d (from app-private settings.ini)", szServerHost, iServerPort);

		pNetGame = new CNetGame(szServerHost, iServerPort, pSettings->Get().szNickName, pSettings->Get().szPassword);
		bNetworkInited = true;

        FLog("DoInitStuff end");
	}
}

extern "C" {
	JNIEXPORT void JNICALL Java_com_samp_mobile_game_SAMP_setSettingsIniPath(JNIEnv *pEnv, jobject thiz, jstring settingsIniPath)
	{
		if (settingsIniPath == nullptr) return;

		const char* path = pEnv->GetStringUTFChars(settingsIniPath, nullptr);
		if (path != nullptr)
		{
			SetSettingsIniPath(path);
			pEnv->ReleaseStringUTFChars(settingsIniPath, path);
		}
	}

	JNIEXPORT void JNICALL Java_com_samp_mobile_game_SAMP_initializeSAMP(JNIEnv *pEnv, jobject thiz)
	{
		pJavaWrapper = new CJavaWrapper(pEnv, thiz);
	}
	JNIEXPORT void JNICALL Java_com_samp_mobile_game_SAMP_onInputEnd(JNIEnv *pEnv, jobject thiz, jbyteArray str)
	{
		if(pUI)
		{
			pUI->keyboard()->sendForGB(pEnv, thiz, str);
		}
	}
	JNIEXPORT void JNICALL Java_com_samp_mobile_game_SAMP_onEventBackPressed(JNIEnv *pEnv, jobject thiz)
	{
		if(pSettings)
		{
			if(pSettings->Get().iAndroidKeyboard)
				pJavaWrapper->HideKeyboard();
		}
	}
	JNIEXPORT void JNICALL Java_com_samp_mobile_game_ui_dialog_DialogManager_sendDialogResponse(JNIEnv* pEnv, jobject thiz, jint i3, jint i, jint i2, jbyteArray str)
	{
		jboolean isCopy = true;

		jbyte* pMsg = pEnv->GetByteArrayElements(str, &isCopy);
		jsize length = pEnv->GetArrayLength(str);

		std::string szStr((char*)pMsg, length);
		
		if(pNetGame) {
			pNetGame->SendDialogResponse(i, i3, i2, (char*)szStr.c_str());
			//pGame->FindPlayerPed()->TogglePlayerControllableWithoutLock(true);
		}

		pEnv->ReleaseByteArrayElements(str, pMsg, JNI_ABORT);
	}
}

void MainLoop()
{
	if (pGame->bIsGameExiting) return;

	DoInitStuff();

	if (bDebug) {
		DoDebugLoop();
	}

	if (pNetGame) {
		pNetGame->Process();
	}

	if (pAudioStream) {
		pAudioStream->Process();
	}

}

void InitGui()
{
	// new voice
	Plugin::OnPluginLoad();
	Plugin::OnSampLoad();

	std::string font_path = string_format("%sSAMP/fonts/%s", g_pszStorage, FONT_NAME);
	pUI = new UI(ImVec2(RsGlobal->maximumWidth, RsGlobal->maximumHeight), font_path.c_str());
	pUI->initialize();
	pUI->performLayout();

	GameStage("ui ready");
}

#include "game/multitouch.h"
#include "armhook/patch.h"
#include "util/CUtil.h"
void SetUpGLHooks();
// ---------------------------------------------------------------------------
// JNI_OnLoad is how ART initialises this library, and it is the ONLY place
// where the game is bootstrapped: CHook::InitHookStuff(), InstallSpecialHooks(),
// ApplyPatches_level0(), InitRenderWareFunctions(), MultiTouch::initialize(),
// `pGame = new CGame()` and the SIGSEGV/SIGABRT/SIGFPE/SIGBUS handlers all
// live here.
//
// It used to be declared as a plain C++ function:
//
//     jint JNI_OnLoad(JavaVM* vm, void* reserved)
//
// Two things then went wrong, and together they stopped it from ever running:
//
//   1. No `extern "C"` -> C++ linkage -> the symbol is emitted mangled as
//      _Z10JNI_OnLoadP7_JavaVMPv, while ART looks up the literal name
//      "JNI_OnLoad".
//   2. The build also passes -fvisibility=hidden, so even the mangled symbol
//      was not placed in .dynsym.
//
// The result: DynamicLoader::LoadNativeLibrary() found no JNI_OnLoad, the whole
// native bootstrap was skipped, and the app quit right after the activity
// opened. The same symbol is also looked up by CUtil::FindLib("libsamp.so"),
// so exporting it fixes g_libSAMP as well.
//
// JNIEXPORT (== __attribute__((visibility("default")))) guarantees the export
// even when -fvisibility=hidden is in effect.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
	javaVM = vm;
	LOGI("SA-MP library loaded! Build time: " __DATE__ " " __TIME__);

	g_libGTASA = CUtil::FindLib("libGTASA.so");
	if (g_libGTASA == 0x00) {
		LOGE("libGTASA.so address was not found! ");
		return JNI_VERSION_1_6;
	}

	g_libSAMP = CUtil::FindLib("libsamp.so");
	if (g_libSAMP == 0x00) {
		LOGE("libsamp.so address was not found! ");
		return JNI_VERSION_1_6;
	}

	// Kept disabled on purpose (the upstream GTA-2.11 branch disables it too).
	// This call never actually ran before, because JNI_OnLoad was unreachable,
	// so it is untested code in this build. Initialising the native Crashlytics
	// SDK is also pointless here: the crash reports go to the original
	// developer's Firebase project, which this fork has no access to. The
	// firebase::crashlytics::Log()/SetCustomKey() calls below degrade to no-ops
	// when the native SDK is not initialised.
	//firebase::crashlytics::Initialize();

	uintptr_t libgtasa = CUtil::FindLib("libGTASA.so");
	uintptr_t libsamp = CUtil::FindLib("libsamp.so");
	uintptr_t libc = CUtil::FindLib("libc.so");

	// %p, not %x: a truncated base cannot be subtracted from a crash pc.
	FLog("libGTASA.so: %p", (void*)libgtasa);
	FLog("libsamp.so: %p", (void*)libsamp);
	FLog("libc.so: %p", (void*)libc);

	char str[100];

	sprintf(str, "0x%x", libgtasa);
	firebase::crashlytics::SetCustomKey("libGTASA.so", str);
	
	sprintf(str, "0x%x", libsamp);
	firebase::crashlytics::SetCustomKey("libsamp.so", str);

	sprintf(str, "0x%x", libc);
	firebase::crashlytics::SetCustomKey("libc.so", str);

	CHook::InitHookStuff();
	InstallSpecialHooks();
	ApplyPatches_level0();
    //SetUpGLHooks();
    InitRenderWareFunctions();
    MultiTouch::initialize();

	pGame = new CGame();

	//pVoice = new CVoice();
	//pVoice->Initialize(VOICE_FREQUENCY, CODEC_FREQUENCY, VOICE_SENDRRATE);

	//pthread_t thread;
	//pthread_create(&thread, 0, Init, 0);

#if !defined(GS_NATIVE_ASAN)
	struct sigaction act;
	act.sa_sigaction = handler;
	sigemptyset(&act.sa_mask);
	act.sa_flags = SA_SIGINFO;
	sigaction(SIGSEGV, &act, &act_old);

	struct sigaction act1;
	act1.sa_sigaction = handler1;
	sigemptyset(&act1.sa_mask);
	act1.sa_flags = SA_SIGINFO;
	sigaction(SIGABRT, &act1, &act1_old);

	struct sigaction act2;
	act2.sa_sigaction = handler2;
	sigemptyset(&act2.sa_mask);
	act2.sa_flags = SA_SIGINFO;
	sigaction(SIGFPE, &act2, &act2_old);

	struct sigaction act3;
	act3.sa_sigaction = handler3;
	sigemptyset(&act3.sa_mask);
	act3.sa_flags = SA_SIGINFO;
	sigaction(SIGBUS, &act3, &act3_old);

	// SIGILL is a crash like the others. SIGQUIT and SIGTERM are the signals the
	// system sends to a "stuck" app: recorded, then handed to the previous
	// handler - the process must not be killed by this code.
	struct sigaction act5;
	act5.sa_sigaction = handler;
	sigemptyset(&act5.sa_mask);
	act5.sa_flags = SA_SIGINFO;
	sigaction(SIGILL, &act5, &act5_old);

	struct sigaction act4;
	act4.sa_sigaction = handlerSoft;
	sigemptyset(&act4.sa_mask);
	act4.sa_flags = SA_SIGINFO;
	sigaction(SIGTERM, &act4, &act4_old);
	sigaction(SIGQUIT, &act4, nullptr);

#endif // ASan must retain its own signal handlers.

	// Report where the previous run stopped before this one goes any further.
	LogPreviousStage();
#if defined(GS_NATIVE_ASAN)
    FLog("ASAN diagnostic build active; reports: /storage/emulated/0/GS-SAMP/asan.txt");
#endif
	GameStage("libsamp loaded");

	// Registered after the stage is known so a plain exit() names a stage too.
	atexit(ReportExitAtExit);

	return JNI_VERSION_1_6;
}

uint32_t GetTickCount()
{
    return CTimer::m_snTimeInMillisecondsNonClipped;
}	

void FLog(const char* fmt, ...)
{
	char buffer[0xFF];

	// Function-local static: while the storage permission is still missing
	// OpenLogFile() returns nullptr, so the open is retried on every call
	// instead of being given up on permanently.
	static FILE* flLog = nullptr;

	if (flLog == nullptr)
	{
		flLog = OpenLogFile("logcat.txt");
	}

	memset(buffer, 0, sizeof(buffer));

	va_list arg;
	va_start(arg, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, arg);
	va_end(arg);

	LOGI("%s", buffer);
	firebase::crashlytics::Log(buffer);

	if (flLog == nullptr) return;

	char ts[24];
	LogTimestamp(ts, sizeof(ts));
	fprintf(flLog, "[%s] %s\n", ts, buffer);
	fflush(flLog);

	return;
}

void ChatLog(const char* fmt, ...)
{
	char buffer[0xFF];
	static FILE* flLog = nullptr;

	if (flLog == nullptr)
	{
		flLog = OpenLogFile("chat_log.txt");
	}

	memset(buffer, 0, sizeof(buffer));

	va_list arg;
	va_start(arg, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, arg);
	va_end(arg);

	if (flLog == nullptr) return;

	char ts[24];
	LogTimestamp(ts, sizeof(ts));
	fprintf(flLog, "[%s] %s\n", ts, buffer);
	fflush(flLog);

	return;
}

void MyLog(const char* fmt, ...)
{
	char buffer[0xFF];
	static FILE* flLog = nullptr;
	const char* pszStorage = g_pszStorage;


	if (flLog == nullptr && pszStorage != nullptr)
	{
		sprintf(buffer, "%s/samp_log.txt", pszStorage);
		//LOGI("buffer: %s", buffer);
		flLog = fopen(buffer, "a");
	}

	memset(buffer, 0, sizeof(buffer));

	va_list arg;
	va_start(arg, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, arg);
	va_end(arg);

	if (flLog == nullptr) return;
	fprintf(flLog, "%s\n", buffer);
	fflush(flLog);

	return;
}

void MyLog2(const char* fmt, ...)
{
	char buffer[0xFF];
	static FILE* flLog = nullptr;
	const char* pszStorage = g_pszStorage;


	if (flLog == nullptr && pszStorage != nullptr)
	{
		sprintf(buffer, "%s/samp_log.txt", pszStorage);
		//LOGI("buffer: %s", buffer);
		flLog = fopen(buffer, "a");
	}

	memset(buffer, 0, sizeof(buffer));

	va_list arg;
	va_start(arg, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, arg);
	va_end(arg);

	if (pUI) pUI->chat()->addDebugMessage(buffer);

	if (flLog == nullptr) return;
	fprintf(flLog, "%s\n", buffer);
	fflush(flLog);
	return;
}

void LogVoice(const char* fmt, ...)
{
	char buffer[0xFF];
	static FILE* flLog = nullptr;
	const char* pszStorage = g_pszStorage;

	if (flLog == nullptr && pszStorage != nullptr)
	{
		sprintf(buffer, "%sSAMP/%s", pszStorage, SV::kLogFileName);
		flLog = fopen(buffer, "w");
	}

	memset(buffer, 0, sizeof(buffer));

	va_list arg;
	va_start(arg, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, arg);
	va_end(arg);

	__android_log_write(ANDROID_LOG_INFO, "AXL", buffer);

	if (flLog == nullptr) return;
	fprintf(flLog, "%s\n", buffer);
	fflush(flLog);

	return;
}