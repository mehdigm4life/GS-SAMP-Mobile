"""Ensure hands-up diagnostics record server animation flags and clear targets."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
src = (root / "app/src/main/cpp/samp/net/scriptrpc.cpp").read_text()
start = src.index("void ScrApplyPlayerAnimation(")
end = src.index("void ScrClearPlayerAnimations(", start)
apply = src[start:end]
clear_end = src.index("void ScrSetPlayerHealth(", end)
clear = src[end:clear_end]

assert 'loop=%d lockX=%d lockY=%d freeze=%d time=%u' in apply
assert 'szAnimLib, szAnimName, fDelta, bLoop, bLockX, bLockY, bFreeze, dwTime' in apply
assert apply.index('FLog("ApplyAnimation:') < apply.index('pPlayerPed->ApplyAnimation(')
assert 'if (!bsData.Read(playerId))' in clear
assert 'FLog("ClearAnimation: player=%u", unsigned(playerId));' in clear
assert clear.index('bsData.Read(playerId)') < clear.index('FLog("ClearAnimation:')

special_start = src.index("void ScrSetPlayerSpecialAction(")
special_end = src.index("void ScrSetPlayerAttachedObject(", special_start)
special = src[special_start:special_end]
assert 'if (!bsData.Read(byteSpecialAction))' in special
assert 'FLog("SpecialAction RPC: %u", unsigned(byteSpecialAction));' in special
assert special.index('bsData.Read(byteSpecialAction)') < special.index('FLog("SpecialAction RPC:')

local_src = (root / "app/src/main/cpp/samp/net/localplayer.cpp").read_text()
get_action_start = local_src.index("uint8_t CLocalPlayer::GetSpecialAction()")
get_action_end = local_src.index("uint32_t CLocalPlayer::GetPlayerColorAsARGB()", get_action_start)
get_action = local_src[get_action_start:get_action_end]
assert 'FLog("Local special action sync: %u", unsigned(action));' in get_action
assert 'return logAndReturn(SPECIAL_ACTION_HANDSUP);' in get_action
print('PASS: diagnostics capture animation flags, targeted clears, and incoming/outgoing special-action transitions')
