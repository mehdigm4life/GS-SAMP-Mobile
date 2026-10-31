#pragma once

/*
    Animation support.

    Everything the client needs to make a server animation actually reach the
    engine, in one place:

      * resolve the animation block of a library name (the table that comes from
        ANIMGRP.DAT) and read its "loaded" flag - the engine's own
        IS_ANIMATION_LOADED (script opcode 0x04EE) dereferences the result of
        GetAnimationBlock() without a null check, which is what crashed every
        session that preloaded an unknown block;
      * find the hierarchy of an animation *by name* inside a loaded block, so a
        name this build does not carry is skipped instead of leaving a task that
        never finishes (a ped with such a task stands frozen forever - the
        "character does not move" report);
      * play it with CAnimManager::BlendAnimation(), the same call the engine's
        own CTaskSimpleRunAnim uses, computing the flags exactly like
        CRunningScript::PlayAnimScriptCommand (script opcode 0x0812) does;
      * defer a request for a block that is still streaming instead of applying
        it against data that is not there yet.
*/

struct CPedGTA;

namespace SAMPAnim
{
    /* false for a library that is not in this install (e.g. "CRACK") */
    bool BlockExists(const char* lib);

    /* true when the block's IFP data is in memory and usable */
    bool BlockLoaded(const char* lib);

    /* true when [name] is one of the animations the loaded block carries */
    bool HasAnimation(const char* lib, const char* name);

    /* ask streaming for the block (no-op when the block does not exist) */
    void Request(const char* lib);

    /* "null" is how servers preload a library - it is never a real animation */
    bool IsPreloadName(const char* name);

    /*
        Applies [name] of [lib] on [ped]. Returns false when nothing was
        applied; the caller can then decide to fall back to the script command.
        [opt1..opt4] are loop / lockX / lockY / freeze, [time] the duration.
    */
    bool Apply(CPedGTA* ped, const char* lib, const char* name, float delta,
        int opt1, int opt2, int opt3, int opt4, int time);

    /* remembers a request for a block that is still loading (bounded, safe) */
    void Defer(CPedGTA* ped, int pedPoolId, const char* lib, const char* name, float delta,
        int opt1, int opt2, int opt3, int opt4, int time);

    /* stops every full-body animation of the ped (CLEAR_ANIMATIONS) */
    void StopAll(CPedGTA* ped);

    /* called once per frame; applies the deferred requests that became ready */
    void ProcessPending();

    /* true when [lib] was already reported as missing, so the log stays short */
    bool ShouldLogMissingBlock(const char* lib);
}
