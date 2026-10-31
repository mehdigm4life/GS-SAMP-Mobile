#pragma once
#include <atomic>

// Signal-handler-safe arbitration: no mutex, allocation, or non-lock-free atomics.
class CrashReportOwner {
public:
    enum class Result { Acquired, OtherThread, Reentered };
    Result TryAcquire(int tid) noexcept {
        int expected = 0;
        if (owner.compare_exchange_strong(expected, tid, std::memory_order_relaxed))
            return Result::Acquired;
        return expected == tid ? Result::Reentered : Result::OtherThread;
    }
private:
    static_assert(std::atomic<int>::is_always_lock_free, "Crash ownership must be lock-free");
    std::atomic<int> owner{0}; // Never reset: a fatal owner terminates the process.
};
