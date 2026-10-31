"""Reproduce ASan-5's write; test the production boundary hook with a worker stub."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/cpp/samp/game/hooks.cpp').read_text()
code=r'''
#include "app/src/main/cpp/samp/game/StreamingReadBuffer.h"
#include <cassert>
#include <cstring>
using int32=int;using uint32=std::uint32_t;using uint64_t=std::uint64_t;
enum class eChannelState { IDLE, READING, STARTED, ERR };
struct Channel { int modelIds[16]{};eChannelState LoadStatus=eChannelState::IDLE;
 bool IsIdle(){return LoadStatus==eChannelState::IDLE;} };
struct CStreaming {
 static inline unsigned char* ms_pStreamingBuffer[2]{};
 static inline int ms_streamingBufferSize=806;
 static inline Channel ms_channel[2];
};
void FLog(const char*,...){}
int syncs=0,reads=0;uint32 seenSectors=0,seenOffset=0;
void CdStreamSync(int){++syncs;}
bool Read(int,void* p,uint32 offset,uint32 sectors){
 ++reads;seenSectors=sectors;seenOffset=offset;
 memset(p,0x5a,size_t(sectors)*2048);return true;
}
void Allocate(int half) {
 auto* raw=static_cast<unsigned char*>(malloc(size_t(half)*4096+2048));assert(raw);
 auto* first=reinterpret_cast<unsigned char*>((uintptr_t(raw)+2048)&~uintptr_t(2047));
 reinterpret_cast<void**>(first)[-1]=raw;
 CStreaming::ms_pStreamingBuffer[0]=first;
 CStreaming::ms_pStreamingBuffer[1]=first+size_t(half)*2048;
 CStreaming::ms_streamingBufferSize=half;
}
void Free(){free(reinterpret_cast<void**>(CStreaming::ms_pStreamingBuffer[0])[-1]);}
void* FailAlloc(size_t){return nullptr;}
'''
a=source.index('static bool (*EngineCdStreamRead)');b=source.index('void(*CStreaming__Init2)();',a)
code+=source[a:b]
code+=r'''
int main(int argc,char**) {
 Allocate(806);EngineCdStreamRead=Read;
 if(argc>1){Read(0,CStreaming::ms_pStreamingBuffer[0],2,1614);return 0;}
 assert(CdStreamReadCapacity_hook(0,CStreaming::ms_pStreamingBuffer[0],2,1614));
 assert(reads==1&&syncs==2&&seenSectors==1614&&seenOffset==2);
 assert(CStreaming::ms_streamingBufferSize==807);
 assert(CStreaming::ms_pStreamingBuffer[1]-CStreaming::ms_pStreamingBuffer[0]==807*2048);
 assert(CStreaming::ms_pStreamingBuffer[0][1614*2048-1]==0x5a);
 // Each normal channel fits; fitting reads do not reallocate or synchronize.
 auto* base=CStreaming::ms_pStreamingBuffer[0];
 assert(CdStreamReadCapacity_hook(1,CStreaming::ms_pStreamingBuffer[1],9,807));
 assert(CStreaming::ms_pStreamingBuffer[0]==base&&syncs==2);
 // Never move memory with active or completed-but-unconsumed data.
 for(auto state:{eChannelState::READING,eChannelState::STARTED,eChannelState::ERR}) {
  CStreaming::ms_channel[1].LoadStatus=state;
  int before=reads;assert(!CdStreamReadCapacity_hook(0,base,2,1616));
  assert(reads==before&&CStreaming::ms_pStreamingBuffer[0]==base);
 }
 CStreaming::ms_channel[1].LoadStatus=eChannelState::IDLE;
 CStreaming::ms_channel[0].LoadStatus=eChannelState::STARTED;
 assert(!CdStreamReadCapacity_hook(0,base,2,1616));
 CStreaming::ms_channel[0].LoadStatus=eChannelState::ERR;
 assert(CdStreamReadCapacity_hook(0,base,2,1615)); // retry + odd count rounds to two equal halves
 assert(CStreaming::ms_streamingBufferSize==808&&seenSectors==1615);
 using namespace StreamingReadBuffer;
 auto& first=CStreaming::ms_pStreamingBuffer[0];auto& second=CStreaming::ms_pStreamingBuffer[1];
 auto& half=CStreaming::ms_streamingBufferSize;
 auto old=first;auto split=second;auto capacity=half;
 assert(Ensure(first,second,half,0,1620,true,FailAlloc)==Result::NoMemory);
 assert(first==old&&second==split&&half==capacity);
 assert(Ensure(first,second,half,0,UINT32_MAX,true)==Result::Invalid);
 assert(IsPlausibleImgSectorCount(0));
 assert(IsPlausibleImgSectorCount(0xffff));
 assert(!IsPlausibleImgSectorCount(0x10000));
 assert(!IsPlausibleImgSectorCount(1786015488));
 assert(Ensure(first,second,half,1,half+1,true)==Result::Busy);
 half++;assert(Ensure(first,second,half,0,1,true)==Result::Invalid);half--;
 half=INT32_MAX;assert(Ensure(first,second,half,0,1,true)==Result::Invalid);half=capacity;
 Free();
}
'''
code='#include <initializer_list>\n'+code
with tempfile.TemporaryDirectory() as d:
    cpp=Path(d)/'test.cpp';cpp.write_text(code);exe=str(Path(d)/'test')
    subprocess.run(['g++','-std=c++17','-fsanitize=address,undefined','-fno-pie','-no-pie',
                    '-I',str(root),str(cpp),'-o',exe],check=True)
    old=subprocess.run([exe,'old'],capture_output=True,text=True)
    assert old.returncode!=0 and 'heap-buffer-overflow' in old.stderr,old.stderr
    subprocess.run([exe],check=True)
print('PASS: ASan-5 write reproduced; full-size reads, worker sync, channel layout, busy/failed allocation guards')
