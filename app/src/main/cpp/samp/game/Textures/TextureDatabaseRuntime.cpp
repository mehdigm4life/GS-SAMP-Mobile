//
// Created by x1y2z on 11.01.2023.
//

#include "TextureDatabaseRuntime.h"
#include "../vendor/armhook/patch.h"

namespace
{
    // The engine ignores the format it is handed: Load() stores it at [db+0xa0]
    // and the format selection at 0x220700 rewrites it, so every database ends
    // up as DXT on the devices seen so far (the launcher asked for DF_PVR for
    // "player" and the engine still opened texdb/player/player.dxt.tmb).
    // A database whose .dxt index is missing therefore crashes in
    // TextureDatabaseRuntime::SortEntries (SIGSEGV 0x6f1 / 0x133f), so only a
    // fingerprint of the family the engine really opens is accepted here.
    const char *g_engineFamily = "dxt";

    // /storage/emulated/0/GTA/ is the root the launcher installs into; it is only
    // used when the engine's own StorageRootBuffer has not been filled yet.
    const char *g_texDbFallbackRoot = "/storage/emulated/0/GTA/";

    bool TexDbTmbExists(const char *dbName, const char *suffix)
    {
        char path[0x200];

        const char *roots[3];
        roots[0] = (g_pszStorage && g_pszStorage[0]) ? g_pszStorage : nullptr;
        roots[1] = g_texDbFallbackRoot;
        roots[2] = "";

        for (int i = 0; i < 3; ++i)
        {
            if (!roots[i])
                continue;

            snprintf(path, sizeof(path), "%stexdb/%s/%s.%s.tmb", roots[i], dbName, dbName, suffix);
            if (access(path, R_OK) == 0)
                return true;
        }

        return false;
    }
}

TextureDatabaseFormat TextureDatabaseRuntime::ResolveFormatForDevice(const char *withName, TextureDatabaseFormat preferredFormat)
{
    if (!withName || !withName[0])
        return preferredFormat;

    // Pass the family the engine resolves to anyway, so the request and the file
    // that gets opened can never disagree.
    if (TexDbTmbExists(withName, g_engineFamily))
    {
        FLog("texdb %s: opening .%s (installed on disk)", withName, g_engineFamily);
        return DF_DXT;
    }

    if (preferredFormat != DF_Default && preferredFormat != DF_ALL
        && TexDbTmbExists(withName, preferredFormat == DF_PVR ? "pvr"
                            : preferredFormat == DF_ETC ? "etc"
                            : preferredFormat == DF_UNC ? "unc"
                            : preferredFormat == DF_360 ? "360" : "ps3"))
    {
        FLog("texdb %s: no .dxt index, using the requested family instead", withName);
        return preferredFormat;
    }

    FLog("texdb %s: no .dxt index installed, keeping the default resolution", withName);
    return preferredFormat;
}

bool TextureDatabaseRuntime::IsTextureDatabaseInstalled(const char *withName)
{
    if (!withName || !withName[0])
        return false;

    if (TexDbTmbExists(withName, g_engineFamily))
        return true;

    // Say exactly what is on disk: the engine would open .dxt and crash in
    // SortEntries, so the caller skips this database instead of loading it.
    const char *families[] = { "pvr", "etc", "unc", "360", "ps3" };
    char found[0x80] = { 0 };
    for (int i = 0; i < 5; ++i)
    {
        if (TexDbTmbExists(withName, families[i]))
        {
            if (found[0])
                strncat(found, ",", sizeof(found) - strlen(found) - 1);
            strncat(found, families[i], sizeof(found) - strlen(found) - 1);
        }
    }

    FLog("texdb %s: no .%s index installed (on disk: %s) - engine load skipped",
         withName, g_engineFamily, found[0] ? found : "nothing");
    return false;
}

TextureDatabaseRuntime* TextureDatabaseRuntime::Load(const char *withName, bool fullyLoad, TextureDatabaseFormat forcedFormat)
{
    return CHook::CallFunction<TextureDatabaseRuntime*>(g_libGTASA + (VER_x32 ? 0x001EA864 + 1 : 0x28771C), withName, fullyLoad, forcedFormat);
}

void TextureDatabaseRuntime::Register(TextureDatabaseRuntime *toRegister) {
    CHook::CallFunction<void>(g_libGTASA + (VER_x32 ? 0x1E9B48 + 1 : 0x2865D8), toRegister);
//    if (std::find(registered.dataPtr, registered.dataPtr + registered.numEntries, toRegister) != registered.dataPtr + registered.numEntries) {
//        return; // ��� ���������������, �������
//    }
//
//    if (registered.numAlloced < registered.numEntries + 1) {
//        size_t newAlloc = ((3 * (registered.numEntries + 1)) >> 1) + 3;
//        TextureDatabaseRuntime** newData = static_cast<TextureDatabaseRuntime**>(malloc(sizeof(TextureDatabaseRuntime*) * newAlloc));
//
//        if (registered.dataPtr) {
//            std::memcpy(newData, registered.dataPtr, sizeof(TextureDatabaseRuntime*) * registered.numEntries);
//            free(registered.dataPtr);
//        }
//
//        registered.dataPtr = newData;
//        registered.numAlloced = newAlloc;
//    }
//
//    registered.dataPtr[registered.numEntries++] = toRegister;
}

void TextureDatabaseRuntime::Unregister(TextureDatabaseRuntime *toUnregister) {
    CHook::CallFunction<void>(g_libGTASA + (VER_x32 ? 0x1E9C00 + 1 : 0x2866A4), toUnregister);
}

RwTexture* TextureDatabaseRuntime::GetTexture(const char *name) {
    return ((RwTexture*(*)(const char*))(g_libGTASA + (VER_x32 ? 0x001E9C64 + 1 : 0x286718)))(name);
}

void TextureDatabaseRuntime::UpdateStreaming(float deltaTime, bool flush) {
    CHook::CallFunction<void>(g_libGTASA + (VER_x32 ? 0x1E9518 + 1 : 0x285DBC), deltaTime, flush);
}

TextureDatabaseRuntime* TextureDatabaseRuntime::GetDatabase(const char *dbName) {
    return CHook::CallFunction<TextureDatabaseRuntime*>(g_libGTASA + (VER_x32 ? 0x1EAC0C + 1 : 0x287AF4), dbName);
//    for (unsigned int i = 0; i < TextureDatabaseRuntime::loaded.numEntries; ++i) {
//        TextureDatabaseRuntime *currentDatabase = TextureDatabaseRuntime::loaded.dataPtr[i];
//        if (strcmp(currentDatabase->name, dbName) == 0) {
//            return currentDatabase;
//        }
//    }
//
//    return nullptr;
}

void TextureDatabaseRuntime::InjectHooks() {
//    CHook::Write(g_libGTASA + (VER_x32 ? 0x675FCC : 0x84A020), &detailTextures);
//    CHook::Write(g_libGTASA + (VER_x32 ? 0x676720 : 0x84AEB0), &storedTexels);
//    CHook::Write(g_libGTASA + (VER_x32 ? 0x679768 : 0x850EE8), &loaded);
//    CHook::Write(g_libGTASA + (VER_x32 ? 0x679E70 : 0x851CF8), &registered);
}

RwBool TextureAnnihilate(RwTexture *texture) {
    return CHook::CallFunction<RwBool>(g_libGTASA + (VER_x32 ? 0x1DB33C + 1 : 0x273A1C), texture);
}
