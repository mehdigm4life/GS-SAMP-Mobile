"""Guard server chat color spans and the checkpoint radar color path."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]

hooks = (root / "app/src/main/cpp/samp/game/hooks.cpp").read_text()
render_start = hooks.index("void Render2dStuff()")
render_end = hooks.index("/* =============================================================================== */", render_start)
render = hooks[render_start:render_end]

# Gamma correction is confined to GTA's native font pass. The custom UI owns
# server chat colors and must render after the global gamma flag is restored.
gamma_on = render.index('CHook::CallFunction<void>("_Z12emu_GammaSeth", 1);')
gamma_off = render.index('CHook::CallFunction<void>("_Z12emu_GammaSeth", 0);')
textdraw = render.index("pTextDrawPool->Draw()")
ui = render.index("if (pUI) pUI->render();")
assert gamma_on < gamma_off < textdraw < ui

renderer = (root / "app/src/main/cpp/samp/gui/imguirenderer.cpp").read_bytes().decode("latin-1")
assert "text_end - text_cur >= 8" in renderer
assert "processInlineHexColor(text_cur + 1, text_cur + 7, col)" in renderer
assert "text_cur += 8;" in renderer and "text_start = text_cur;" in renderer
assert "else return false;" in renderer  # reject malformed hex without consuming it as a color tag
assert "color.x = static_cast<float>((hexColor >> 16) & 0xFF) / 255.0f;" in renderer
assert "color.y = static_cast<float>((hexColor >> 8) & 0xFF) / 255.0f;" in renderer
assert "color.z = static_cast<float>(hexColor & 0xFF) / 255.0f;" in renderer

colors = (root / "app/src/main/cpp/samp/game/radarcolors.cpp").read_text()
red_start = colors.index("case 1005:")
red_end = colors.index("case 1006:", red_start)
assert "return 0xFF0000FF;" in colors[red_start:red_end]

game = (root / "app/src/main/cpp/samp/game/game.cpp").read_text()
assert "1005" in game[game.index("void CGame::SetCheckpointInformation("):game.index("void CGame::SetRaceCheckpointInformation(")]
assert "1005" in game[game.index("void CGame::SetRaceCheckpointInformation("):game.index("void CGame::MakeRaceCheckpoint(")]

# These legacy patches skip radar sprite drawing/color selection. Keep the
# native coordinate/entity blip and 3D-marker paths intact.
patches = (root / "app/src/main/cpp/samp/game/patches.cpp").read_text()
for address in ("0x52522C", "0x525E14", "0x52737C", "0x5273F4", "0x00442120", "0x0044217C"):
    assert address not in patches

print("PASS: custom chat color tags retain their spans; checkpoint IDs use full red with native radar draw paths")
