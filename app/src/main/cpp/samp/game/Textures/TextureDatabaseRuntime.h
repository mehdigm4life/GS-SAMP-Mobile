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
    // The engine resolves DF_Default from its own GL capability flags
    // (has_s3tc / has_pvrtc / has_atitc in batexdb), and that probe does not
    // agree with the files the installer downloaded on every driver: on the
    // PowerVR Rogue GE8320 it reports DXT even though the GL_EXTENSIONS string
    // has no S3TC at all, the engine then opens texdb/mobile/mobile.dxt.tmb,
    // fails, and dereferences the null database inside
    // TextureDatabaseRuntime::SortEntries (SIGSEGV at 0x6f1).
    //
    // The files on disk are the only ground truth, so the family is chosen from
    // what the installer actually verified: the requested family when its .tmb
    // exists, otherwise the first family that does exist.
    static TextureDatabaseFormat ResolveFormatForDevice(const char *withName, TextureDatabaseFormat preferredFormat);

    // True when at least one texture family of "withName" is on disk, i.e. when
    // the engine can open texdb/<db>/<db>.<family>.tmb. Loading a database whose
    // index is missing makes TextureDatabaseRuntime::SortEntries dereference a
    // null database, so callers must ask this before calling Load.
    static bool IsTextureDatabaseInstalled(const char *withName);
    static TextureDatabaseRuntime* GetDatabase(const char *dbName);
    static void UpdateStreaming(float deltaTime, bool flush);

    static void Register(TextureDatabaseRuntime *thiz);
    static void Unregister(TextureDatabaseRuntime *toUnregister);

    static RwTexture *GetTexture(const char *name);
};
VALIDATE_SIZE(TextureDatabaseRuntime, (VER_x32 ? 0xC8 : 0x118));
VALIDATE_SIZE(TDBArray<TextureDatabaseRuntime*>, (VER_x32 ? 0xC : 0x10));
