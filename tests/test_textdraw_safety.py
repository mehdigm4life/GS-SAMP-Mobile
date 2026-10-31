"""Host regression test of the actual texture-slot and lifecycle functions."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'app/src/main/cpp/samp/game'

def function(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

util = (GAME / 'util.cpp').read_text(errors='replace')
td = (GAME / 'textdraw.cpp').read_text(errors='replace')
code = '''
#include <cassert>
#include <cstdint>
#include <cstring>
struct RwTexture { int refs; };
int releases = 0;
void RwTextureDestroy(RwTexture* t) { assert(t->refs > 0); --t->refs; ++releases; }
bool bTextDrawTextureSlotState[200];
uintptr_t TextDrawTexture[200];
struct CTextDraw {
    struct { int iTextureSlot; } m_TextDrawData;
    ~CTextDraw();
};
'''
for signature in ('void ResetTextDrawTextures()', 'int GetFreeTextDrawTextureSlot()', 'void DestroyTextDrawTexture(int index)'):
    code += function(util, signature) + '\n'
code += function(td, 'CTextDraw::~CTextDraw()')
code += '''
int main() {
    ResetTextDrawTextures();
    for (int i=0; i<200; ++i) assert(GetFreeTextDrawTextureSlot() == i);
    assert(GetFreeTextDrawTextureSlot() == -1);
    DestroyTextDrawTexture(-1); DestroyTextDrawTexture(200);
    RwTexture texture{2};
    TextDrawTexture[0] = TextDrawTexture[1] = reinterpret_cast<uintptr_t>(&texture);
    { CTextDraw a{{1}}; }
    assert(texture.refs == 1 && releases == 1 && TextDrawTexture[1] == 0);
    assert(GetFreeTextDrawTextureSlot() == 1);
    { CTextDraw a{{0}}; }
    assert(texture.refs == 0 && releases == 2);
    DestroyTextDrawTexture(0);
    assert(releases == 2);
    { CTextDraw a{{-1}}; }
}
'''
with tempfile.TemporaryDirectory() as d:
    source = Path(d) / 'test.cpp'
    source.write_text(code)
    exe = str(Path(d) / 'test')
    subprocess.run(['g++', '-std=c++17', '-fsanitize=address,undefined', str(source), '-o', exe], check=True)
    subprocess.run([exe], check=True)
print('PASS: exhausted/invalid slots, shared references, slot zero and single destruction')
