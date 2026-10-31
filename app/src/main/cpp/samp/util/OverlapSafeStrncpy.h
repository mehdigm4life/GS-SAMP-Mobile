#pragma once
#include <cstddef>
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
