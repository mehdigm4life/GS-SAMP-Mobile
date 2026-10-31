/*
    Animation support validates the requested library and hierarchy using the
    exported libGTASA queries, then dispatches through GTA's normal SCM opcode.
    The script opcode owns the ped task and works through both ARM ABIs; when
    32-bit private query symbols are unavailable we retain the guarded legacy
    SCM path rather than disabling animations.
*/

#include <dlfcn.h>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <strings.h>
#include <cstdint>

#include "AnimationSupport.h"

#include "../main.h"
#include "game.h"
#include "util.h"
#include "scripting.h"

#include "Entity/CPedGTA.h"

#include "Animation/AnimBlock.h"
#include "Animation/AnimBlendHierarchy.h"

extern CGame* pGame;

namespace
{
    typedef CAnimBlock* (*GetBlockFn)(const char*);
    typedef CAnimBlendHierarchy* (*GetHierarchyFn)(const char*, const CAnimBlock*);
    typedef void (*StopNonPartialAnimsFn)(CPedGTA*);

    void* ResolveSymbol(const char* name, uintptr_t arm64Offset)
    {
        void* symbol = dlsym(RTLD_DEFAULT, name);
        if (symbol != nullptr)
            return symbol;

        if (VER_x32 || arm64Offset == 0)
            return nullptr;

        return (void*)(g_libGTASA + arm64Offset);
    }

    GetBlockFn GetBlockFunction()
    {
        static GetBlockFn fn = (GetBlockFn)ResolveSymbol("_ZN12CAnimManager17GetAnimationBlockEPKc", 0x4668F0);
        return fn;
    }

    GetHierarchyFn GetHierarchyFunction()
    {
        static GetHierarchyFn fn = (GetHierarchyFn)ResolveSymbol("_ZN12CAnimManager12GetAnimationEPKcPK10CAnimBlock", 0x466A3C);
        return fn;
    }

    StopNonPartialAnimsFn GetStopNonPartialAnimsFunction()
    {
        static StopNonPartialAnimsFn fn = (StopNonPartialAnimsFn)ResolveSymbol("_ZN4CPed19StopNonPartialAnimsEv", 0x59721C);
        return fn;
    }

    /* A bounded log: the server repeats the same request every few seconds. */
    constexpr int kLogBudget = 80;
    int g_logLines = 0;

    void LogAnim(const char* fmt, ...)
    {
        if (g_logLines >= kLogBudget)
            return;

        char buffer[256];
        va_list arguments;
        va_start(arguments, fmt);
        vsnprintf(buffer, sizeof(buffer), fmt, arguments);
        va_end(arguments);

        g_logLines++;
        FLog("%s", buffer);
    }

    /* deferred applies ---------------------------------------------------- */

    struct SPendingAnimation
    {
        CPedGTA* ped;
        int pedId;
        char lib[24];
        char name[40];
        float delta;
        int32 opt1;
        int32 opt2;
        int32 opt3;
        int32 opt4;
        int32 time;
        int32 framesLeft;
    };

    constexpr int kPendingCapacity = 24;
    constexpr int kPendingFrames = 180;     /* ~3 seconds at 60 fps */

    SPendingAnimation g_pending[kPendingCapacity];
    int g_pendingCount = 0;

    void CopyString(char* destination, size_t size, const char* source)
    {
        if (size == 0)
            return;

        if (source == nullptr)
        {
            destination[0] = '\0';
            return;
        }

        size_t length = strlen(source);
        if (length >= size)
            length = size - 1;

        memcpy(destination, source, length);
        destination[length] = '\0';
    }

    bool CanonicalBlockName(const char* requestedName, char* canonicalName, size_t size)
    {
        return pGame != nullptr &&
            pGame->ResolveAnimationBlockName(requestedName, canonicalName, size);
    }

}

namespace SAMPAnim
{
    bool BlockExists(const char* lib)
    {
        char canonicalName[16]{};
        return CanonicalBlockName(lib, canonicalName, sizeof(canonicalName));
    }

    bool BlockLoaded(const char* lib)
    {
        char canonicalName[16]{};
        if (!CanonicalBlockName(lib, canonicalName, sizeof(canonicalName)))
            return false;

        GetBlockFn getBlock = GetBlockFunction();
        if (getBlock == nullptr)
        {
            // The 32-bit game exports may omit this helper. The guarded SCM
            // query checks the canonical block name without a null dereference.
            return pGame != nullptr && pGame->IsAnimationLoaded(canonicalName);
        }

        CAnimBlock* block = getBlock(canonicalName);
        return block != nullptr && block->bLoaded;
    }

    bool HasAnimation(const char* lib, const char* name)
    {
        if (name == nullptr || name[0] == '\0')
            return false;

        char canonicalName[16]{};
        if (!CanonicalBlockName(lib, canonicalName, sizeof(canonicalName)))
            return false;

        GetBlockFn getBlock = GetBlockFunction();
        GetHierarchyFn getHierarchy = GetHierarchyFunction();
        if (getBlock == nullptr || getHierarchy == nullptr)
            return true; // validated block is loaded; retain the 32-bit SCM path

        CAnimBlock* block = getBlock(canonicalName);
        if (block == nullptr || !block->bLoaded)
            return false;

        return getHierarchy(name, block) != nullptr;
    }

    void Request(const char* lib)
    {
        if (pGame != nullptr && BlockExists(lib))
            pGame->RequestAnimation(lib);
    }

    bool IsPreloadName(const char* name)
    {
        if (name == nullptr)
            return true;

        /* Servers preload a library with ApplyAnimation(id, lib, "null", ...). */
        return name[0] == '\0'
            || strcasecmp(name, "null") == 0
            || strcasecmp(name, "none") == 0;
    }

    bool ShouldLogMissingBlock(const char* lib)
    {
        static char logged[128][24];
        static int loggedCount = 0;

        if (lib == nullptr)
            return false;

        for (int i = 0; i < loggedCount; i++)
        {
            if (strcasecmp(logged[i], lib) == 0)
                return false;
        }

        if (loggedCount >= (int)(sizeof(logged) / sizeof(logged[0])))
            return false;

        CopyString(logged[loggedCount], sizeof(logged[0]), lib);
        loggedCount++;
        return true;
    }

    bool Apply(int pedId, CPedGTA* ped, const char* lib, const char* name, float delta,
        int opt1, int opt2, int opt3, int opt4, int time)
    {
        if (pedId < 0 || ped == nullptr || GamePool_Ped_GetAt(pedId) != ped ||
            ped->m_pRwClump == nullptr || lib == nullptr || name == nullptr)
            return false;

        char canonicalName[16]{};
        if (!CanonicalBlockName(lib, canonicalName, sizeof(canonicalName)))
        {
            if (ShouldLogMissingBlock(lib))
                LogAnim("animation block %s is not registered", lib);
            return false;
        }

        // Verify the resolved block and hierarchy first. The name passed to
        // SCM must be the exact spelling stored by CAnimManager: its lookup is
        // strcmp-based (for example, registered "ped" vs server-sent "PED").
        // Then let the engine create the normal ped task.
        GetBlockFn getBlock = GetBlockFunction();
        GetHierarchyFn getHierarchy = GetHierarchyFunction();
        if (getBlock == nullptr || getHierarchy == nullptr)
        {
            // 32-bit keeps the guarded SCM route when private queries are not
            // exported; the block was resolved through CGame above.
            ScriptCommand(&apply_animation, pedId, name, canonicalName, delta,
                opt1, opt2, opt3, opt4, time);
            LogAnim("animation dispatched through legacy SCM path: ped=%d %s->%s:%s",
                pedId, lib, canonicalName, name);
            return true;
        }

        CAnimBlock* block = getBlock(canonicalName);
        if (block == nullptr || !block->bLoaded)
            return false;

        if (getHierarchy(name, block) == nullptr)
        {
            LogAnim("animation %s is not in block %s (requested %s)", name, canonicalName, lib);
            return false;
        }

        ScriptCommand(&apply_animation, pedId, name, canonicalName, delta,
            opt1, opt2, opt3, opt4, time);
        LogAnim("animation dispatched to engine task: ped=%d %s->%s:%s",
            pedId, lib, canonicalName, name);
        return true;
    }

    void Defer(CPedGTA* ped, int pedPoolId, const char* lib, const char* name, float delta,
        int opt1, int opt2, int opt3, int opt4, int time)
    {
        if (ped == nullptr || lib == nullptr || name == nullptr)
            return;

        if (g_pendingCount >= kPendingCapacity)
        {
            /* overwrite the oldest request */
            memmove(&g_pending[0], &g_pending[1], sizeof(SPendingAnimation) * (kPendingCapacity - 1));
            g_pendingCount = kPendingCapacity - 1;
        }

        SPendingAnimation& pending = g_pending[g_pendingCount++];
        pending.ped = ped;
        pending.pedId = pedPoolId;
        CopyString(pending.lib, sizeof(pending.lib), lib);
        CopyString(pending.name, sizeof(pending.name), name);
        pending.delta = delta;
        pending.opt1 = opt1;
        pending.opt2 = opt2;
        pending.opt3 = opt3;
        pending.opt4 = opt4;
        pending.time = time;
        pending.framesLeft = kPendingFrames;
    }

    void StopAll(CPedGTA* ped)
    {
        if (ped == nullptr)
            return;

        StopNonPartialAnimsFn stopNonPartialAnims = GetStopNonPartialAnimsFunction();
        if (stopNonPartialAnims != nullptr)
            stopNonPartialAnims(ped);
    }

    void ProcessPending()
    {
        if (g_pendingCount == 0)
            return;

        int writeIndex = 0;
        for (int i = 0; i < g_pendingCount; i++)
        {
            SPendingAnimation& pending = g_pending[i];

            bool keep = true;

            if (GamePool_Ped_GetAt(pending.pedId) != pending.ped)
            {
                /* the ped is gone (disconnected player, respawn, ...) */
                keep = false;
            }
            else if (!BlockExists(pending.lib))
            {
                keep = false;
            }
            else if (BlockLoaded(pending.lib))
            {
                if (!HasAnimation(pending.lib, pending.name))
                {
                    /* the data landed, but it does not carry this animation */
                    LogAnim("animation %s is not in block %s (dropped)", pending.name, pending.lib);
                    keep = false;
                }
                else if (Apply(pending.pedId, pending.ped, pending.lib, pending.name, pending.delta,
                    pending.opt1, pending.opt2, pending.opt3, pending.opt4, pending.time))
                {
                    keep = false;
                }
                else
                {
                    keep = --pending.framesLeft > 0;
                }
            }
            else
            {
                --pending.framesLeft;

                /* ask again every half second: a request can be dropped by the
                   streaming channel when it is busy with something else */
                if ((pending.framesLeft % 30) == 0 && GamePool_Ped_GetAt(pending.pedId) == pending.ped)
                    Request(pending.lib);

                keep = pending.framesLeft > 0;
                if (!keep)
                    LogAnim("block %s never finished loading, dropped %s", pending.lib, pending.name);
            }

            if (keep && writeIndex != i)
                g_pending[writeIndex] = pending;

            if (keep)
                writeIndex++;
        }

        g_pendingCount = writeIndex;
    }
}
