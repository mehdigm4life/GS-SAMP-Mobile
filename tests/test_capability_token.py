"""Reproduce ASan's startup overlap, test the narrow helper and retained checks."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
code = r'''
#include "app/src/main/cpp/samp/util/OverlapSafeStrncpy.h"
#include <cassert>
#include <cstdlib>
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
print('PASS: original overlap reproduced; in-place trim/padding fixed; genuine overflow still detected')
