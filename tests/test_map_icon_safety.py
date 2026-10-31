"""Exercise production map-icon methods/RPCs, including every byte-sized slot."""
from pathlib import Path
import subprocess
import tempfile
import sys

root = Path(__file__).resolve().parents[1]
net = (root / 'app/src/main/cpp/samp/net/netgame.cpp').read_text()
rpc = (root / 'app/src/main/cpp/samp/net/scriptrpc.cpp').read_text()
if len(sys.argv) == 2:
    net = subprocess.check_output(['git', 'show', sys.argv[1]+':app/src/main/cpp/samp/net/netgame.cpp'], cwd=root, text=True)
    rpc = subprocess.check_output(['git', 'show', sys.argv[1]+':app/src/main/cpp/samp/net/scriptrpc.cpp'], cwd=root, text=True)
code = r'''
#include <cstdint>
#include <cstring>
#include <cassert>
#define MAX_MAP_ICONS 100
int created = 0, removed = 0;
uint8_t lastType = 0;
struct Game {
 uint32_t CreateRadarMarkerIcon(uint8_t type, float,float,float,uint32_t,uint8_t) {
   ++created; lastType=type; return 123;
 }
} game;
Game* pGame=&game;
int disable_marker;
void ScriptCommand(int*, uint32_t) { ++removed; }
struct CNetGame {
 uint32_t m_dwMapIcon[MAX_MAP_ICONS]{};
 uintptr_t poolsCanary=0x12345678;
 void SetMapIcon(uint8_t,float,float,float,uint8_t,uint32_t,uint8_t);
 void DisableMapIcon(uint8_t);
} net;
CNetGame* pNetGame=&net;
struct CVector { float x,y,z; };
struct RPCParameters { unsigned char* input; unsigned int numberOfBitsOfData; };
namespace RakNet {
struct BitStream {
 unsigned char* data; unsigned int size, offset=0;
 BitStream(unsigned char* p, unsigned int n, bool):data(p),size(n) {}
 template<class T> bool Read(T& value) {
   if(sizeof(T)>size-offset) return false;
   memcpy(&value,data+offset,sizeof(T)); offset+=sizeof(T); return true;
 }
};
}
'''
code += net[net.index('void CNetGame::SetMapIcon('):net.index('void CNetGame::ResetVehiclePool()')]
code += rpc[rpc.index('void ScrSetPlayerMapIcon('):rpc.index('void ScrShowNameTag(')]
code += r'''
int main() {
 for(unsigned int id=100;id<256;++id) {
   net.SetMapIcon(id,0,0,0,0,0,0); net.DisableMapIcon(id);
 }
 assert(created==0 && removed==0 && net.poolsCanary==0x12345678);
 for(unsigned int id=0;id<100;++id) {
   net.SetMapIcon(id,0,0,0,0,0,0); assert(net.m_dwMapIcon[id]==123);
   net.DisableMapIcon(id); assert(net.m_dwMapIcon[id]==0);
 }
 assert(created==100 && removed==100);
 created=removed=0;
 unsigned char payload[19]{};
 RPCParameters params{payload,0};
 for(unsigned int bits=0;bits<152;++bits) {
   params.numberOfBitsOfData=bits; ScrSetPlayerMapIcon(&params);
 }
 for(unsigned int bits=0;bits<8;++bits) {
   params.numberOfBitsOfData=bits; ScrRemovePlayerMapIcon(&params);
 }
 ScrSetPlayerMapIcon(nullptr); ScrRemovePlayerMapIcon(nullptr);
 RPCParameters empty{nullptr,152};
 ScrSetPlayerMapIcon(&empty); ScrRemovePlayerMapIcon(&empty);
 assert(created==0 && removed==0);
 params.numberOfBitsOfData=152;
 for(unsigned int id=100;id<256;++id) {
   payload[0]=id; ScrSetPlayerMapIcon(&params); ScrRemovePlayerMapIcon(&params);
 }
 assert(created==0 && removed==0);
 for(auto id:{1,2,4,56,99}) {
   payload[0]=id; payload[13]=56; ScrSetPlayerMapIcon(&params);
   assert(net.m_dwMapIcon[id]==123 && lastType==52);
   ScrRemovePlayerMapIcon(&params); assert(net.m_dwMapIcon[id]==0);
 }
 assert(created==5 && removed==5 && net.poolsCanary==0x12345678);
 pNetGame=nullptr; ScrSetPlayerMapIcon(&params); ScrRemovePlayerMapIcon(&params);
}
'''
code = '#include <initializer_list>\n' + code
with tempfile.TemporaryDirectory() as d:
    cpp = Path(d) / 'test.cpp'
    cpp.write_text(code)
    exe = str(Path(d) / 'test')
    subprocess.run(['g++', '-std=c++17', '-fsanitize=address,undefined',
                    '-fno-sanitize-recover=all', '-fno-pie', '-no-pie', str(cpp), '-o', exe], check=True)
    subprocess.run([exe], check=True)
print('PASS: map-icon bounds, truncated RPCs, preserved slot IDs, adjacent pool-table canary')
