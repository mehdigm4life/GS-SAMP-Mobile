"""Guard custom in-game text gamma and the checkpoint radar color mapping."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
hooks = (root / "app/src/main/cpp/samp/game/hooks.cpp").read_text()
render_start = hooks.index("void Render2dStuff()")
render_end = hooks.index("/* =============================================================================== */", render_start)
render = hooks[render_start:render_end]

# Gamma correction stays active through native textdraws and the ImGui overlay
# (chat, 3D labels, player bubbles), then is restored after all text rendering.
gamma_on = render.index('CHook::CallFunction<void>("_Z12emu_GammaSeth", 1);')
gamma_off = render.index('CHook::CallFunction<void>("_Z12emu_GammaSeth", 0);')
textdraw = render.index("pTextDrawPool->Draw()")
ui = render.index("if (pUI) pUI->render();")
assert gamma_on < textdraw < ui < gamma_off

colors = (root / "app/src/main/cpp/samp/game/radarcolors.cpp").read_text()
red_start = colors.index("case 1005:")
red_end = colors.index("case 1006:", red_start)
assert "return 0xFF0000FF;" in colors[red_start:red_end]

game = (root / "app/src/main/cpp/samp/game/game.cpp").read_text()
checkpoint_start = game.index("void CGame::SetCheckpointInformation(")
checkpoint_end = game.index("void CGame::SetRaceCheckpointInformation(", checkpoint_start)
assert "1005" in game[checkpoint_start:checkpoint_end]
print("PASS: custom text renders under gamma correction and checkpoint radar color is full-strength red")
