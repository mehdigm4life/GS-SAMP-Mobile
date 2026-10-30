//
// Created by x1y2z on 11.01.2023.
//

#include "TextureDatabaseRuntime.h"
#include "../vendor/armhook/patch.h"

namespace
{
    struct TexDbFamily
    {
        TextureDatabaseFormat format;
        const char *suffix;
    };

    // Same preference the launcher uses: PVRTC first (PowerVR and most Mali
    // parts), then S3TC/DXT, then ETC1 (mandatory in OpenGL ES 2.0), then the
    // console-only families. Every family is only accepted when its .tmb file
    // is really on disk.
    const TexDbFamily g_texDbFamilies[] = {
        { DF_PVR, "pvr" },
        { DF_DXT, "dxt" },
        { DF_ETC, "etc" },
        { DF_UNC, "unc" },
        { DF_360, "360" },
        { DF_PS3, "ps3" },
    };

    const int g_texDbFamilyCount = (int)(sizeof(g_texDbFamilies) / sizeof(g_texDbFamilies[0]));

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

    if (preferredFormat != DF_Default && preferredFormat != DF_ALL)
    {
        for (int i = 0; i < g_texDbFamilyCount; ++i)
        {
            if (g_texDbFamilies[i].format == preferredFormat && TexDbTmbExists(withName, g_texDbFamilies[i].suffix))
            {
                FLog("texdb %s: requested .%s is installed", withName, g_texDbFamilies[i].suffix);
                return preferredFormat;
            }
        }

        FLog("texdb %s: requested family is not installed, looking for an installed one", withName);
    }

    for (int i = 0; i < g_texDbFamilyCount; ++i)
    {
        if (TexDbTmbExists(withName, g_texDbFamilies[i].suffix))
        {
            FLog("texdb %s: opening .%s (installed on disk)", withName, g_texDbFamilies[i].suffix);
            return g_texDbFamilies[i].format;
        }
    }

    // Nothing installed for this database: keep the engine's own behaviour so the
    // real cause stays visible in the log instead of being masked here.
    FLog("texdb %s: no .tmb found for any family, keeping the default resolution", withName);
    return preferredFormat;
}

bool TextureDatabaseRuntime::IsTextureDatabaseInstalled(const char *withName)
{
    if (!withName || !withName[0])
        return false;

    for (int i = 0; i < g_texDbFamilyCount; ++i)
    {
        if (TexDbTmbExists(withName, g_texDbFamilies[i].suffix))
            return true;
    }

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
