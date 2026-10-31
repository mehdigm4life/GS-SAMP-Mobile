"""Exercise actual NewPlayer, RemovePlayer and destructor with a fake GTA pool."""
from pathlib import Path
import subprocess, tempfile, sys
root=Path(__file__).resolve().parents[1]
def load(name):
    path='app/src/main/cpp/samp/game/'+name
    if len(sys.argv)>1:
        return subprocess.check_output(['git','show',sys.argv[1]+':'+path],cwd=root,text=True)
    return (root/path).read_text()
game=load('game.cpp'); ped=load('playerped.cpp')
code=r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <climits>
#include <cstdarg>
#define PLAYER_PED_SLOTS 120
#define VER_x32 false
bool bUsedPlayerSlots[PLAYER_PED_SLOTS]{};
struct PAD_KEYS { int v; }; PAD_KEYS RemotePlayerKeys[PLAYER_PED_SLOTS]{};
struct Data { int m_nPlayerGroup=1; } data;
struct CPedGTA { int m_nModelIndex=100; Data* m_pPlayerData=&data; };
CPedGTA* pool[512]{};
int destroyed=0,engineDestroyed=0,unloaded=0,constructed=0;
bool failCreation=false;
void FLog(const char*,...){}
void SetPlayerPedPtrRecord(int slot,int){assert(slot>=0&&slot<PLAYER_PED_SLOTS);}
CPedGTA* GamePool_Ped_GetAt(unsigned id){return id<512?pool[id]:nullptr;}
bool IsValidGamePed(CPedGTA* p){return p!=nullptr;}
int disassociate_object, destroy_object_with_fade;
void ScriptCommand(int*,...){}
struct CStreaming { static void RemoveModelIfNoRefs(int id){assert(id==100);++unloaded;} };
void RemoveEnginePed(uintptr_t address) {
 auto* p=reinterpret_cast<CPedGTA*>(address);
 for(auto& entry:pool) if(entry==p) entry=nullptr;
 delete p; ++engineDestroyed;
}
uintptr_t g_libGTASA=reinterpret_cast<uintptr_t>(&RemoveEnginePed)-0x5CDC64;
struct CPlayerPed {
 CPedGTA* m_pPed=nullptr;
 uint8_t m_bytePlayerNumber;
 unsigned m_dwGTAId=0,m_dwParachuteObject=0;
 CPlayerPed(int slot,int,float,float,float,float):m_bytePlayerNumber(slot) {
   ++constructed; assert(slot>=2&&slot<PLAYER_PED_SLOTS);
   if(!failCreation) {m_dwGTAId=slot+1;m_pPed=new CPedGTA;pool[m_dwGTAId]=m_pPed;}
 }
 ~CPlayerPed();
 void FlushAttach(){} bool IsInVehicle(){return false;}
 void RemoveFromVehicleAndPutAt(float,float,float){}
};
struct CGame {
 CPlayerPed* NewPlayer(int,float,float,float,float,bool,bool);
 bool RemovePlayer(CPlayerPed*);
};
'''
code+=game[game.index('inline int FindFirstFreePlayerPedSlot()'):game.index('CGame::CGame()')]
code+=game[game.index('CPlayerPed* CGame::NewPlayer('):game.index('void CGame::DisableMarker(')]
code+=ped[ped.index('CPlayerPed::~CPlayerPed()'):ped.index('bool CPlayerPed::IsInVehicle()')]
code+=r'''
int main(){
 CGame g;
 auto* p=g.NewPlayer(0,0,0,0,0,false,false);
 assert(p&&p->m_bytePlayerNumber==2&&bUsedPlayerSlots[2]);
 assert(g.RemovePlayer(p)); assert(!bUsedPlayerSlots[2]);
 assert(!g.RemovePlayer(nullptr));
 // Thousands of stream-in/out and respawn-like cycles with slot reuse.
 for(int i=0;i<3000;++i){
   p=g.NewPlayer(0,0,0,0,0,false,false); assert(p&&p->m_bytePlayerNumber==2);
   assert(g.RemovePlayer(p));
 }
 memset(bUsedPlayerSlots,1,sizeof(bUsedPlayerSlots));
 const int before=constructed;
 assert(!g.NewPlayer(0,0,0,0,0,false,false)); assert(constructed==before);
 bUsedPlayerSlots[119]=false;
 p=g.NewPlayer(0,0,0,0,0,false,false); assert(p&&p->m_bytePlayerNumber==119);
 g.RemovePlayer(p); assert(!bUsedPlayerSlots[119]);
 memset(bUsedPlayerSlots,0,sizeof(bUsedPlayerSlots));
 failCreation=true; assert(!g.NewPlayer(0,0,0,0,0,false,false)); assert(!bUsedPlayerSlots[2]);
 failCreation=false;
 p=g.NewPlayer(0,0,0,0,0,false,false);
 p->m_pPed->m_pPlayerData=nullptr; g.RemovePlayer(p);
 // Engine already removed the ped: never dereference its stale pointer.
 p=g.NewPlayer(0,0,0,0,0,false,false);
 RemoveEnginePed(reinterpret_cast<uintptr_t>(p->m_pPed));
 int count=engineDestroyed, models=unloaded;
 g.RemovePlayer(p); assert(engineDestroyed==count&&unloaded==models);
 // Reused engine handle: wrapper must not delete a different ped.
 p=g.NewPlayer(0,0,0,0,0,false,false);
 auto id=p->m_dwGTAId;
 RemoveEnginePed(reinterpret_cast<uintptr_t>(p->m_pPed));
 pool[id]=new CPedGTA;
 count=engineDestroyed; g.RemovePlayer(p); assert(engineDestroyed==count);
 delete pool[id];pool[id]=nullptr;
 // A corrupted slot byte must not index either key or reservation arrays.
 p=g.NewPlayer(0,0,0,0,0,false,false);p->m_bytePlayerNumber=255;g.RemovePlayer(p);
}
'''
with tempfile.TemporaryDirectory() as d:
    cpp=Path(d)/'test.cpp';cpp.write_text(code);exe=str(Path(d)/'test')
    subprocess.run(['g++','-std=c++17','-g','-O1','-fsanitize=address,undefined',
        '-fno-pie','-no-pie',str(cpp),'-o',exe],check=True)
    subprocess.run([exe],check=True)
print('PASS: player removal, 3000 reuse cycles, exhausted slots, creation failure and stale engine handles')
