"""Verify case-insensitive SA-MP names resolve to the registered GTA IFP block spelling."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
game_cpp = (root / "app/src/main/cpp/samp/game/game.cpp").read_text()
game_h = (root / "app/src/main/cpp/samp/game/game.h").read_text()
anim_cpp = (root / "app/src/main/cpp/samp/game/AnimationSupport.cpp").read_text()

resolver = game_cpp[game_cpp.index("bool CGame::ResolveAnimationBlockName("):game_cpp.index("bool CGame::HasAnimationBlock(")]
loaded = game_cpp[game_cpp.index("bool CGame::IsAnimationLoaded("):game_cpp.index("// 0.3.7\nvoid CGame::RequestAnimation(")]
request = game_cpp[game_cpp.index("void CGame::RequestAnimation("):game_cpp.index("// 0.3.7\nfloat CGame::FindGroundZForCoord(")]
apply = anim_cpp[anim_cpp.index("bool Apply(int pedId,"):anim_cpp.index("\n    void Defer(", anim_cpp.index("bool Apply(int pedId,"))]

assert "fn(query)" in resolver
assert "query[i] >= 'A'" in resolver and "query[i] <= 'Z'" in resolver
assert "query[i] >= 'a'" in resolver and "query[i] <= 'z'" in resolver
assert "strncasecmp(candidate->szName, requestedName, sizeof(candidate->szName)) == 0" in resolver
assert "CAnimManager::ms_aAnimBlocks[i]" in resolver
assert "memcpy(canonicalName, block->szName, length)" in resolver
assert "ResolveAnimationBlockName(szAnimLib, canonicalName" in loaded
assert "ScriptCommand(&is_animation_loaded, canonicalName)" in loaded
assert "ResolveAnimationBlockName(szAnimLib, canonicalName" in request
assert "ScriptCommand(&request_animation, canonicalName)" in request
assert "CanonicalBlockName(lib, canonicalName" in apply
assert "getBlock(canonicalName)" in apply
assert "ScriptCommand(&apply_animation, pedId, name, canonicalName" in apply
assert "bool ResolveAnimationBlockName(const char* requestedName" in game_h
print("PASS: case-varied RPC block names resolve to the registered spelling before queries, streaming, and apply")
