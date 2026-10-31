"""Guard the verified animation path: validate first, then use GTA's SCM task opcode."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
cpp=(root/'app/src/main/cpp/samp/game/AnimationSupport.cpp').read_text()
h=(root/'app/src/main/cpp/samp/game/AnimationSupport.h').read_text()
ped=(root/'app/src/main/cpp/samp/game/playerped.cpp').read_text()
apply=cpp[cpp.index('bool Apply(int pedId,'):cpp.index('\n    void Defer(',cpp.index('bool Apply(int pedId,'))]
assert 'getBlock(lib)' in apply and 'block->bLoaded' in apply
assert 'getHierarchy(name, block)' in apply
assert 'ScriptCommand(&apply_animation, pedId, name, lib, delta' in apply
assert 'BlendAnimation' not in apply
assert 'GamePool_Ped_GetAt(pedId) != ped' in apply
assert 'SAMPAnim::Apply(m_dwGTAId, m_pPed, szAnimLib' in ped
assert 'Apply(pending.pedId, pending.ped' in cpp
assert 'engine task' in cpp
assert 'void ProcessPending()' in h
assert 'pGame->IsAnimationLoaded(lib)' in cpp
assert 'legacy SCM path' in apply
assert 'SAMPAnim::Apply(m_dwGTAId, m_pPed' in ped
print('PASS: validated animations and deferred requests dispatch through the GTA task opcode, not raw clump blending')
