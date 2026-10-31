from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
code = r'''
#include "app/src/main/cpp/samp/diagnostics/CrashReportOwner.h"
#include <cassert>
#include <thread>
#include <vector>
int main() {
    CrashReportOwner owner;
    std::atomic<int> winners{0};
    std::vector<std::thread> threads;
    for (int i=1; i<=32; ++i) threads.emplace_back([&,i] {
        auto r = owner.TryAcquire(i);
        if (r == CrashReportOwner::Result::Acquired) {
            ++winners;
            assert(owner.TryAcquire(i) == CrashReportOwner::Result::Reentered);
        } else assert(r == CrashReportOwner::Result::OtherThread);
    });
    for (auto& t : threads) t.join();
    assert(winners == 1);
}
'''
with tempfile.TemporaryDirectory() as d:
    p = Path(d)/'test.cpp'; p.write_text(code); exe=str(Path(d)/'test')
    subprocess.run(['g++','-std=c++17','-pthread','-I',str(root),str(p),'-o',exe],check=True)
    subprocess.run([exe],check=True)
print('PASS: one fatal reporter across 32 concurrent threads; reentry detected')
