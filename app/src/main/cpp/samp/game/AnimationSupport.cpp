/*
    Animation support - see AnimationSupport.h for what this is for.

    The functions themselves are the engine's; they are exported by libGTASA
    (GLOBAL DEFAULT in .dynsym), so they are resolved with dlsym() and only fall
    back to a fixed offset when the lookup fails. On the 32-bit build the offsets
    of these three are not known, so the fallback is disabled there and
    Apply() reports "not applied" - the caller then keeps using the script
    command, which is exactly the behaviour that build had before.
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

#include "Entity/CPedGTA.h"

#include "Animation/AnimBlock.h"
#include "Animation/AnimBlendAssociation.h"

extern CGame* pGame;

namespace
{
    typedef CAnimBlock* (*GetBlockFn)(const char*);
    typedef CAnimBlendHierarchy* (*GetHierarchyFn)(const char*, const CAnimBlock*);
    typedef CAnimBlendAssociation* (*BlendAnimationFn)(RpClump*, CAnimBlendHierarchy*, int32, float);
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

    BlendAnimationFn GetBlendAnimationFunction()
    {
        static BlendAnimationFn fn = (BlendAnimationFn)ResolveSymbol(
            "_ZN12CAnimManager14BlendAnimationEP7RpClumpP19CAnimBlendHierarchyif", 0x466D8C);
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

    int FlagsFor(int opt1, int opt2, int opt3, int opt4, int time)
    {
        /*
            The same computation CRunningScript::PlayAnimScriptCommand does for
            script opcode 0x0812 (it is visible in libGTASA at 0x3FF2F4..):
            a partial animation that loops unless the script asked for a single
            pass, plus the translation locks. Being partial is what keeps the
            player's own control task from dropping the animation again.
        */
        int32 flags = ANIMATION_PARTIAL;

        if (opt1)
        {
            flags |= ANIMATION_LOOPED;
        }
        else if (time >= 1 && !opt4)
        {
            flags |= ANIMATION_LOOPED;
        }

        if (!opt2) flags |= ANIMATION_TRANSLATE_Y;
        if (!opt3) flags |= ANIMATION_TRANSLATE_X;
        if (!opt1) flags |= ANIMATION_UNLOCK_LAST_FRAME;

        return flags;
    }
}

namespace SAMPAnim
{
    bool BlockExists(const char* lib)
    {
        if (lib == nullptr || lib[0] == '\0')
            return false;

        return pGame != nullptr && pGame->HasAnimationBlock(lib);
    }

    bool BlockLoaded(const char* lib)
    {
        if (!BlockExists(lib))
            return false;

        GetBlockFn getBlock = GetBlockFunction();
        if (getBlock == nullptr)
            return false;

        CAnimBlock* block = getBlock(lib);
        return block != nullptr && block->bLoaded;
    }

    bool HasAnimation(const char* lib, const char* name)
    {
        if (name == nullptr || name[0] == '\0')
            return false;

        GetBlockFn getBlock = GetBlockFunction();
        GetHierarchyFn getHierarchy = GetHierarchyFunction();
        if (getBlock == nullptr || getHierarchy == nullptr)
            return false;

        CAnimBlock* block = getBlock(lib);
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
        static char logged[8][24];
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

    bool Apply(CPedGTA* ped, const char* lib, const char* name, float delta,
        int opt1, int opt2, int opt3, int opt4, int time)
    {
        if (ped == nullptr || ped->m_pRwClump == nullptr)
            return false;

        if (lib == nullptr || name == nullptr)
            return false;

        GetBlockFn getBlock = GetBlockFunction();
        GetHierarchyFn getHierarchy = GetHierarchyFunction();
        BlendAnimationFn blendAnimation = GetBlendAnimationFunction();

        if (getBlock == nullptr || getHierarchy == nullptr || blendAnimation == nullptr)
            return false;

        CAnimBlock* block = getBlock(lib);
        if (block == nullptr || !block->bLoaded)
            return false;

        CAnimBlendHierarchy* hierarchy = getHierarchy(name, block);
        if (hierarchy == nullptr)
        {
            LogAnim("animation %s is not in block %s", name, lib);
            return false;
        }

        const int32 flags = FlagsFor(opt1, opt2, opt3, opt4, time);

        /*
            The blend rate. CRunningScript::PlayAnimScriptCommand forwards the
            script's "delta" argument here as well - it is the rate the pose
            blends in at, not the playback speed. 8.0f is the engine's default.
        */
        float blendRate = (delta > 0.01f) ? delta : 8.0f;
        if (blendRate < 0.1f) blendRate = 0.1f;
        if (blendRate > 20.0f) blendRate = 20.0f;

        CAnimBlendAssociation* association = blendAnimation(ped->m_pRwClump, hierarchy, flags, blendRate);
        if (association == nullptr)
        {
            LogAnim("blend failed for %s:%s", lib, name);
            return false;
        }

        /* A zero speed would hold the pose on its first frame. */
        if (association->m_fSpeed == 0.0f)
            association->m_fSpeed = 1.0f;

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
                else if (Apply(pending.ped, pending.lib, pending.name, pending.delta,
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
                keep = --pending.framesLeft > 0;
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
