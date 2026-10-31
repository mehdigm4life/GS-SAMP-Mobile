#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

// strncpy's bounded read and zero-padding semantics, with defined overlap.
// Used only for the engine's in-place capability-token trim, not as a global
// sanitizer suppression or replacement for arbitrary string calls.
inline char* CopyCapabilityToken(char* destination, const char* source, std::size_t count)
{
    if (count == 0) return destination;
    const std::size_t length = strnlen(source, count);
    if (length != 0) memmove(destination, source, length);
    if (length < count) memset(destination + length, 0, count - length);
    return destination;
}

// Every strncpy call in the shipped ARM64 nvGetSystemCapabilities, audited
// from its complete instruction range. Not a blanket engine/libc exemption.
inline constexpr std::uintptr_t kCapabilityCopyReturns[] = {
    0x320924, 0x320940, 0x32095c, 0x320b40, 0x320c90, 0x320cf0,
    0x320d50, 0x320e38, 0x320edc, 0x320fa0, 0x321044, 0x32119c
};
inline bool IsCapabilityCopyReturn(std::uintptr_t offset)
{
    for (auto allowed : kCapabilityCopyReturns)
        if (offset == allowed) return true;
    return false;
}
using EngineStringCopy = char* (*)(char*, const char*, std::size_t);
inline char* DispatchCapabilityCopy(std::uintptr_t offset, char* destination,
                                   const char* source, std::size_t count,
                                   EngineStringCopy original)
{
    if (IsCapabilityCopyReturn(offset))
        return CopyCapabilityToken(destination, source, count);
    return original(destination, source, count);
}
