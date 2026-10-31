"""Compile real Render/size functions and encoding, with a recording UI backend."""
from pathlib import Path
import subprocess
import tempfile
import sys
root = Path(__file__).resolve().parents[1]
path = 'app/src/main/cpp/samp/game/materialtextgenerator.cpp'
source = (subprocess.check_output(['git','show',sys.argv[1]+':'+path],cwd=root,text=True)
          if len(sys.argv)>1 else (root/path).read_text())
code = r'''
#include <string>
#include <cstring>
#include <cassert>
#include <climits>
#include "app/src/main/cpp/samp/vendor/encoding/encoding.h"
struct ImVec2 { float x=0,y=0; ImVec2(){} ImVec2(float a,float b):x(a),y(b){} };
struct ImColor {};
int begun=0,ended=0,rendered=0,submitted=0,measured=0,drawn=0;
std::string expected;
ImVec2 position;
namespace ImGui {
 void NewFrame(){++begun;} void EndFrame(){++ended;} void Render(){++rendered;}
 void* GetDrawData(){return nullptr;}
}
struct Renderer {
 void drawRect(const ImVec2&,const ImVec2&,const ImColor&,bool){}
 ImVec2 calculateTextSize(const std::string& s,int) {
   assert(s==expected); ++measured; return {10,6};
 }
 void drawText(const ImVec2& p,const ImColor&,const std::string& s,bool,int) {
   assert(s==expected); position=p; ++drawn;
 }
};
struct UI {
 Renderer r; Renderer* renderer(){return &r;}
 void renderDrawData(void*){++submitted;}
} ui;
UI* pUI=&ui;
struct MaterialTextGenerator {
 void Render(const char*,const ImVec2&,int,bool,const ImColor&,const ImColor&,int);
 void GetMaterialSize(int,int*,int*);
};
'''
code += source[source.index('void MaterialTextGenerator::Render('):source.index('const ImColor fix_color')]
code += source[source.index('void MaterialTextGenerator::GetMaterialSize('):]
code += r'''
int main() {
 MaterialTextGenerator g;
 // Short strings expose the stack lifetime; long strings expose the heap lifetime.
 for(const std::string s : {std::string("abc"),std::string(2047,'x'),std::string(""),std::string("\xc0\xe1")}) {
   expected=Encoding::cp2utf(s);
   for(int align : {0,1,2,-1,255}) {
     g.Render(s.c_str(),{100,50},24,false,{}, {},align);
     assert(position.x==(align==1?45:align==2?90:0));
     assert(position.y==(align==1?22:0));
   }
 }
 expected=""; g.Render(nullptr,{100,50},24,false,{}, {},0);
 assert(begun==21 && ended==21 && rendered==21 && submitted==21 && measured==21 && drawn==21);
 pUI=nullptr; g.Render("ignored",{},24,false,{}, {},0); assert(begun==21);
 const int sizes[14][2]={{32,32},{64,32},{64,64},{128,32},{128,64},{128,128},{256,32},{256,64},{256,128},{256,256},{512,64},{512,128},{512,256},{512,512}};
 for(int i=0;i<14;++i) {int x,y; g.GetMaterialSize((i+1)*10,&x,&y); assert(x==sizes[i][0]&&y==sizes[i][1]);}
 for(int i : {INT_MIN,-1,0,1,9,11,139,141,255,INT_MAX}) {
   int x,y; g.GetMaterialSize(i,&x,&y); assert(x==32&&y==32);
 }
}
'''
with tempfile.TemporaryDirectory() as d:
    cpp=Path(d)/'render.cpp'; cpp.write_text(code)
    exe=str(Path(d)/'test')
    subprocess.run(['g++','-std=c++17','-g','-O1','-fsanitize=address,undefined',
                    '-fsanitize-address-use-after-scope','-fno-pie','-no-pie','-funsigned-char',
                    '-include','cstring','-I',str(root),str(cpp),
                    str(root/'app/src/main/cpp/samp/vendor/encoding/encoding.cpp'),'-o',exe],check=True)
    subprocess.run([exe],check=True)
print('PASS: real material-text render lifetime, short/long/encoded text, all alignments and size bounds')
