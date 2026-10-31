"""Reproduce ASan's startup overlap, test the narrow helper and retained checks."""
from pathlib import Path
import os
import subprocess
import tempfile
import re
import struct

root = Path(__file__).resolve().parents[1]
# Audit every BL to strncpy in the complete engine function, with no optional
# disassembler dependency. This would have caught the one-call-only patch.
elf = (root / 'app/src/main/jniLibs/arm64-v8a/libGTASA.so').read_bytes()
assert elf[:6] == b'\x7fELF\x02\x01'
phoff = struct.unpack_from('<Q', elf, 32)[0]
entsize, count = struct.unpack_from('<HH', elf, 54)
def at(address, size):
    for i in range(count):
        kind, _, offset, vaddr, _, filesz, _, _ = struct.unpack_from('<IIQQQQQQ', elf, phoff+i*entsize)
        if kind == 1 and vaddr <= address and address+size <= vaddr+filesz:
            return elf[offset+address-vaddr:offset+address-vaddr+size]
    raise AssertionError('unmapped engine address')
returns = []
for pc in range(0x320860, 0x32147c, 4):
    word = struct.unpack('<I', at(pc, 4))[0]
    if word & 0xfc000000 != 0x94000000: continue
    imm = word & 0x03ffffff
    if imm & 0x02000000: imm -= 0x04000000
    if pc + imm*4 == 0x22f890: returns.append(pc+4)
header = (root / 'app/src/main/cpp/samp/util/OverlapSafeStrncpy.h').read_text()
listed = re.search(r'kCapabilityCopyReturns\[\] = \{(.*?)\}', header, re.S)[1]
assert returns == [int(x,16) for x in re.findall(r'0x[0-9a-f]+',listed)]
assert len(returns) == 12 and 0x320edc in returns and 0x321044 in returns
code = r'''
#include "app/src/main/cpp/samp/util/OverlapSafeStrncpy.h"
#include <cassert>
#include <cstdlib>
int delegated=0;
char* Original(char* d, const char* s, size_t n) { ++delegated; return strncpy(d,s,n); }
int main(int argc, char** argv) {
 if(argc > 1) {
   auto* p = new char[8]; memset(p, 'x', 8);
   if(argv[1][0]=='o') {
     char* (*volatile copy)(char*, const char*, size_t) = strncpy;
     copy(p,p,8); // old engine behavior must fail under ASan
   } else {
     CopyCapabilityToken(p,p,9); // genuine overflow must STILL fail
   }
   delete[] p; return 0;
 }
 for(auto offset : kCapabilityCopyReturns) {
   char token[8]={'t','o','k','e','n',0,'!','!'};
   assert(DispatchCapabilityCopy(offset,token,token,8,Original)==token);
   assert(memcmp(token,"token\0\0\0",8)==0);
   assert(!IsCapabilityCopyReturn(offset-1) && !IsCapabilityCopyReturn(offset+1));
 }
 // Exhaust overlapping placements against a snapshot of the source, so
 // the oracle never invokes undefined strncpy overlap itself.
 for(int pattern=0;pattern<2;++pattern)
 for(int source=0;source<32;++source)
 for(int destination=0;destination<32;++destination)
 for(int n=0;n<=32;++n) {
   char actual[64], expected[64], before[64];
   for(int i=0;i<64;++i) before[i]=(pattern && i%7==0)?0:char('a'+i%26);
   memcpy(actual,before,64); memcpy(expected,before,64);
   bool ended=false;
   for(int i=0;i<n;++i) {
     if(before[source+i]==0) ended=true;
     expected[destination+i]=ended?0:before[source+i];
   }
   CopyCapabilityToken(actual+destination,actual+source,n);
   assert(memcmp(actual,expected,64)==0);
 }
 assert(delegated==0);
 char external[8]{};
 DispatchCapabilityCopy(0x321048,external,"outside",8,Original);
 assert(delegated==1 && strcmp(external,"outside")==0);
 assert(CopyCapabilityToken(nullptr,nullptr,0)==nullptr);
 char b[16]; memset(b,'!',sizeof(b)); memcpy(b,"abc",4);
 assert(CopyCapabilityToken(b,b,8)==b);
 assert(memcmp(b,"abc\0\0\0\0\0",8)==0 && b[8]=='!');
 memcpy(b,"   abc xyz",11);
 CopyCapabilityToken(b,b+3,3); assert(memcmp(b,"abc",3)==0);
 memcpy(b,"abc",4); CopyCapabilityToken(b+1,b,4);
 assert(memcmp(b,"aabc\0",5)==0);
 const char src[3]={'x','y','z'};
 memset(b,'!',sizeof(b)); CopyCapabilityToken(b,src,3);
 assert(memcmp(b,"xyz!",4)==0); // no read beyond non-terminated source
 b[0]=0; CopyCapabilityToken(b,b,8);
 for(int i=0;i<8;++i) assert(b[i]==0);
}
'''
with tempfile.TemporaryDirectory() as d:
    cpp = Path(d)/'test.cpp'; cpp.write_text(code)
    exe = str(Path(d)/'test')
    subprocess.run(['g++','-std=c++17','-fsanitize=address,undefined',
                    '-fno-builtin-strncpy','-fno-pie','-no-pie','-I',str(root),str(cpp),'-o',exe],check=True)
    env = dict(os.environ, ASAN_OPTIONS='detect_leaks=0:halt_on_error=1')
    subprocess.run([exe],env=env,check=True)
    for mode, expected in [('old','strncpy-param-overlap'),('bounds','heap-buffer-overflow')]:
        result = subprocess.run([exe, mode],env=env,text=True,capture_output=True)
        assert result.returncode != 0 and expected in result.stderr, result.stderr
print('PASS: all 12 ELF copy sites covered; unrelated calls delegated; original overlap reproduced; in-place trim/padding fixed; genuine overflow still detected')
