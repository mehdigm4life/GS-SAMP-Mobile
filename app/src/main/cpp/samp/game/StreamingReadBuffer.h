#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>

namespace StreamingReadBuffer {
constexpr std::size_t Sector = 2048;
enum class Result { Fits, Grew, Busy, Invalid, NoMemory };

// The engine owns ONE aligned allocation, split into two adjacent channels.
// Resize only an oversized channel-0 request while BOTH channels are idle
// (or channel 0 is retrying a failed read). No live model data is discarded.
// Layout remains compatible with CMemoryMgr::FreeAlign on both ARM ABIs.
inline Result Ensure(unsigned char*& first, unsigned char*& second,
                     int& halfSectors, unsigned channel, std::uint32_t sectors,
                     bool channelsSafe, void* (*allocate)(std::size_t) = std::malloc,
                     void (*release)(void*) = std::free)
{
    if (!first || !second || halfSectors <= 0 || channel > 1 || !sectors)
        return Result::Invalid;
    const auto base = reinterpret_cast<std::uintptr_t>(first);
    const auto split = reinterpret_cast<std::uintptr_t>(second);
    const std::size_t halfBytes = std::size_t(halfSectors) * Sector;
    if (split <= base || split - base != halfBytes) return Result::Invalid;
    const std::uint64_t available = std::uint64_t(halfSectors) * (channel == 0 ? 2 : 1);
    if (sectors <= available) return Result::Fits;
    if (channel != 0 || !channelsSafe) return Result::Busy;
    const std::uint64_t total = (std::uint64_t(sectors) + 1) & ~std::uint64_t(1);
    // The reader shifts a signed 32-bit sector count by 11 in the engine.
    if (total > std::uint64_t(std::numeric_limits<int>::max()) / Sector ||
        total * Sector > std::numeric_limits<std::size_t>::max() - Sector)
        return Result::Invalid;
    auto* raw = static_cast<unsigned char*>(allocate(std::size_t(total) * Sector + Sector));
    if (!raw) return Result::NoMemory;
    auto* aligned = reinterpret_cast<unsigned char*>(
        (reinterpret_cast<std::uintptr_t>(raw) + Sector) & ~(std::uintptr_t(Sector) - 1));
    reinterpret_cast<void**>(aligned)[-1] = raw;
    void* oldRaw = reinterpret_cast<void**>(first)[-1];
    first = aligned;
    halfSectors = int(total / 2);
    second = first + std::size_t(halfSectors) * Sector;
    release(oldRaw);
    return Result::Grew;
}
}
