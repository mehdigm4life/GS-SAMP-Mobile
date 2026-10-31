"""Compile the production marshaller with a fake VM, under ASan/UBSan."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/cpp/samp/game/scripting.cpp').read_text()
start = source.index('int ScriptCommand(')
end = source.index('\nvoid InitScripting()', start)
code = r'''
#include <cstdint>
#include <cstdarg>
#include <cstring>
#include <cassert>
struct GAME_SCRIPT_THREAD { int32_t dwLocalVar[42]; } thread;
GAME_SCRIPT_THREAD* gst = &thread;
struct SCRIPT_COMMAND { uint16_t OpCode; char Params[16]; };
char ScriptBuf[0xFF];
void* pdwParamVars[18];
int calls = 0;
char lastScriptBuf[0xFF]{};
uint8_t ExecuteScriptBuf() {
    memcpy(lastScriptBuf, ScriptBuf, sizeof(lastScriptBuf));
    ++calls;
    gst->dwLocalVar[0] = 0x12345678;
    return 1;
}
'''
code += source[start:end]
code += r'''
int main() {
    SCRIPT_COMMAND ref{1, "v"};
    struct { uint32_t value; uint32_t canary; } guarded{3, 0xabcdef01};
    assert(ScriptCommand(&ref, &guarded.value) == 1);
    assert(guarded.value == 0x12345678 && guarded.canary == 0xabcdef01);
    // A standalone four-byte heap cell also detects overreads on input.
    auto* cell = new uint32_t(0);
    assert(ScriptCommand(&ref, cell) == 1);
    assert(*cell == 0x12345678);
    delete cell;
    float f = 0;
    assert(ScriptCommand(&ref, &f) == 1);
    uint32_t bits; memcpy(&bits, &f, 4); assert(bits == 0x12345678);
    const int before = calls;
    assert(ScriptCommand(&ref, (void*)nullptr) == 0);
    SCRIPT_COMMAND text{1, "s"};
    char oversized[300]; memset(oversized, 'a', 299); oversized[299] = 0;
    assert(ScriptCommand(&text, oversized) == 0);
    assert(ScriptCommand(&text, (char*)nullptr) == 0);
    assert(calls == before);

    // The production ApplyAnimation opcode layout and argument order used by
    // the fixed animation path: actor, name, library, delta, four flags, time.
    SCRIPT_COMMAND applyAnimation{0x0812, "issfiiiii"};
    const float delta = 4.0f;
    assert(ScriptCommand(&applyAnimation, 7, (char*)"handsup", (char*)"PED",
                         (double)delta, 1, 0, 0, 0, -1) == 1);
    assert((unsigned char)lastScriptBuf[0] == 0x12 && (unsigned char)lastScriptBuf[1] == 0x08);
    assert((unsigned char)lastScriptBuf[2] == 0x01 && lastScriptBuf[3] == 7);
    assert((unsigned char)lastScriptBuf[7] == 0x0e && lastScriptBuf[8] == 7);
    assert(memcmp(lastScriptBuf + 9, "handsup", 7) == 0);
    assert((unsigned char)lastScriptBuf[16] == 0x0e && lastScriptBuf[17] == 3);
    assert(memcmp(lastScriptBuf + 18, "PED", 3) == 0);
    assert((unsigned char)lastScriptBuf[21] == 0x06);
    float encodedDelta; memcpy(&encodedDelta, lastScriptBuf + 22, sizeof(encodedDelta));
    assert(encodedDelta == delta);
    assert(lastScriptBuf[26] == 1 && lastScriptBuf[31] == 1 &&
           lastScriptBuf[36] == 1 && lastScriptBuf[41] == 1 &&
           lastScriptBuf[46] == 1 && (unsigned char)lastScriptBuf[47] == 0xff);

    // A long string followed by a reference must not overrun ScriptBuf.
    SCRIPT_COMMAND combined{1, "sv"};
    char maximum[251]; memset(maximum, 'a', 250); maximum[250] = 0;
    assert(ScriptCommand(&combined, maximum, &guarded.value) == 0);
    assert(calls == before + 1);
}
'''
with tempfile.TemporaryDirectory() as d:
    cpp = Path(d) / 'test.cpp'
    cpp.write_text(code)
    exe = str(Path(d) / 'test')
    subprocess.run(['g++', '-std=c++17', '-fsanitize=address,undefined', '-fno-pie', '-no-pie', str(cpp), '-o', exe], check=True)
    subprocess.run([exe], check=True)
print('PASS: SCM references use four bytes; invalid parameters are rejected; ApplyAnimation opcode and argument order verified')
