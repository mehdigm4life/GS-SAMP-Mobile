"""Exercise production material callbacks/destructor with reference-counted RW stubs."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
game = root / 'app/src/main/cpp/samp/game'
util = (game / 'util.cpp').read_text(errors='replace')
obj = (game / 'object.cpp').read_text()
def function(text, signature):
    a = text.index(signature); b = text.index('{', a); level = 1; e = b + 1
    while level:
        level += (text[e] == '{') - (text[e] == '}'); e += 1
    return text[a:e]
code = r'''
#include <cassert>
#include <cstdint>
struct RwTexture { int refs; };
void RwTextureDestroy(RwTexture* t) { assert(t->refs > 0); --t->refs; }
struct RpMaterial { RwTexture* texture; };
struct Geometry { struct { RpMaterial** materials; int numMaterials; } matList; };
struct RpAtomic { struct { struct { int type; } object; } object; Geometry* geometry; };
struct Entity { int m_nModelIndex; };
Entity* engineEntity;
int destroy_object, destroyed = 0, unloaded = -1;
void ScriptCommand(int*, uint32_t) { delete engineEntity; engineEntity = nullptr; ++destroyed; }
struct CStreaming { static void RemoveModelIfNoRefs(int m) { unloaded=m; } };
struct CHook {
    template<class T> static T CallFunction(const char*, RpMaterial* m, RwTexture* t) {
        assert(t->refs > 0); ++t->refs;
        if (m->texture) RwTextureDestroy(m->texture);
        m->texture = t; return m;
    }
};
struct CObject {
    uintptr_t m_MaterialTexture[16]{};
    uintptr_t m_MaterialTextTexture[16]{};
    char* m_szMaterialText[16]{};
    Entity* m_pEntity = nullptr;
    uint32_t m_dwGTAId = 0;
    ~CObject();
};
'''
for sig in ('static void SetObjectMaterialTexture(', 'RpAtomic* ObjectMaterialCallBack(', 'RpAtomic* ObjectMaterialTextCallBack('):
    code += function(util, sig) + '\n'
code += function(obj, 'CObject::~CObject()')
code += r'''
int main() {
    RwTexture original{1}, replacement{1}, text{1};
    RpMaterial m{&original}; RpMaterial* list[]{&m};
    Geometry g{{list,1}}; RpAtomic a{{{1}}, &g};
    {
        CObject o;
        o.m_pEntity = engineEntity = new Entity{123};
        o.m_szMaterialText[0] = new char[2048];
        o.m_MaterialTexture[0] = reinterpret_cast<uintptr_t>(&replacement);
        o.m_MaterialTextTexture[0] = reinterpret_cast<uintptr_t>(&text);
        ObjectMaterialCallBack(&a, &o);
        assert(original.refs == 0 && replacement.refs == 2);
        ObjectMaterialCallBack(&a, &o); // repeated drawing must not leak refs
        assert(replacement.refs == 2);
        ObjectMaterialTextCallBack(&a, &o);
        assert(replacement.refs == 1 && text.refs == 2);
        RpAtomic camera{{{4}}, nullptr}; // frame enumeration is not atomic-only
        ObjectMaterialCallBack(&camera, &o);
        ObjectMaterialTextCallBack(nullptr, &o);
        ObjectMaterialCallBack(&a, nullptr);
    }
    assert(destroyed == 1 && unloaded == 123 && engineEntity == nullptr);
    assert(replacement.refs == 0 && text.refs == 1);
    // The geometry still owns its texture after the server object is gone.
    RwTextureDestroy(m.texture); assert(text.refs == 0);
    { CObject failedCreation; } // null entity destruction must be safe
}
'''
# The production constructor must initialise owned arrays before either early return.
constructor = function(obj, 'CObject::CObject(')
assert constructor.index('m_szMaterialText[i] = nullptr') < constructor.index('if(!m_dwGTAId) return;')
with tempfile.TemporaryDirectory() as d:
    cpp = Path(d)/'test.cpp'; cpp.write_text(code); exe = str(Path(d)/'test')
    subprocess.run(['g++','-std=c++17','-fsanitize=address,undefined','-fno-pie','-no-pie',str(cpp),'-o',exe],check=True)
    subprocess.run([exe],check=True)
print('PASS: material ownership, repeated replacement, non-atomic frames, object destruction')
