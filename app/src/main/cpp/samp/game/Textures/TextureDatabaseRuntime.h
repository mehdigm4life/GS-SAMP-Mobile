//
// Created by x1y2z on 11.01.2023.
//

#pragma once

#include <cstdint>
#include <queue>
#include <list>
#include "game/RW/RenderWare.h"
#include "OSFile.h"

#include "TextureDatabase.h"


class TextureDatabaseRuntime : public TextureDatabase {
public:
    TDBArray<uint32>   priorityStreamingQueue;
    TDBArray<uint32>   renderedStreamingQueue;
    TDBArray<uint32>   unrenderedStreamingQueue;
    TDBArray<uint32>   deletionQueue;
    OSFile             streamFile;
    TDBArray<uint32>   fullDataOffsets;
    TDBArray<uint32>   hashOffsets;
    uint32             numHashes;

public:
    static inline uint32 storedTexels{};
    static inline TDBArray<TextureDatabaseRuntime*> loaded;
    static inline TDBArray<TextureDatabaseRuntime*> registered;
    static inline TDBArray<RwTexture*> detailTextures;

public:
    static void InjectHooks();

    static TextureDatabaseRuntime* Load(const char *withName, bool fullyLoad, TextureDatabaseFormat forcedFormat);

    // Picks the texture family the engine must open for "withName".
    //
    // TextureDatabaseRuntime::Load() does not honour the format it is given: it
    // stores it at [db+0xa0] and the selection at 0x220700 rewrites it, so every
    // database ends up as DXT (the launcher passed DF_PVR for "player" and the
    // engine still opened texdb/player/player.dxt.tmb). When that index is
    // missing, Load() dereferences the null database in SortEntries and the app
    // dies with SIGSEGV right after the loading screen.
    //
    // The files on disk are the only ground truth: the .dxt index is requested
    // when it exists, otherwise the format the caller asked for is kept.
    static TextureDatabaseFormat ResolveFormatForDevice(const char *withName, TextureDatabaseFormat preferredFormat);

    // True when the .dxt index of "withName" is on disk, i.e. when the engine can
    // open this database without crashing. Callers must ask this before Load():
    // a skipped database loses its textures, a loaded one without its index
    // kills the process.
    static bool IsTextureDatabaseInstalled(const char *withName);
    static TextureDatabaseRuntime* GetDatabase(const char *dbName);
    static void UpdateStreaming(float deltaTime, bool flush);

    static void Register(TextureDatabaseRuntime *thiz);
    static void Unregister(TextureDatabaseRuntime *toUnregister);

    static RwTexture *GetTexture(const char *name);
};
VALIDATE_SIZE(TextureDatabaseRuntime, (VER_x32 ? 0xC8 : 0x118));
VALIDATE_SIZE(TDBArray<TextureDatabaseRuntime*>, (VER_x32 ? 0xC : 0x10));
