#pragma once

/*
    The microphone glyph used by every voice control in the game.

    The texture database this client installs has no microphone images at all
    ("Texture (samp) not found in database (voiceactive)" in every session), so
    the icon is drawn with primitives instead of being loaded. That makes it
    independent of the texture set, crisp at any resolution and impossible to
    crash on a missing raster.

    Everything here is a pure function of the draw list and the target rect, so
    it can be used from the widget renderer and from the ImGui voice window.
*/

#include "../vendor/imgui/imgui.h"

namespace MicGlyph {

/* Records the capsule + cradle + stand of a microphone inside [center]. */
inline void Draw(ImDrawList* drawList, const ImVec2& center, float height, ImU32 color, float slant = 0.0f)
{
	if (drawList == nullptr || height <= 0.0f)
		return;

	const float halfHeight = height * 0.5f;
	const float bodyWidth = height * 0.30f;           /* capsule diameter */
	const float bodyRadius = bodyWidth * 0.5f;
	const float bodyTop = center.y - halfHeight;
	const float bodyHeight = height * 0.60f;
	const float stroke = (height * 0.085f > 1.0f) ? height * 0.085f : 1.0f;

	/* capsule: a rounded rectangle with a full-radius cap */
	drawList->AddRectFilled(ImVec2(center.x - bodyRadius, bodyTop),
		ImVec2(center.x + bodyRadius, bodyTop + bodyHeight), color, bodyRadius);

	/* cradle: the half circle the capsule sits in */
	const float cradleRadius = bodyWidth * 0.95f;
	const float cradleY = bodyTop + bodyHeight * 0.86f;
	drawList->PathArcTo(ImVec2(center.x, cradleY), cradleRadius, 0.0f, IM_PI, 24);
	drawList->PathStroke(color, 0, stroke);

	/* stand and base */
	const float standTop = cradleY + cradleRadius;
	const float standBottom = center.y + halfHeight * 0.88f;
	drawList->AddLine(ImVec2(center.x, standTop), ImVec2(center.x, standBottom), color, stroke);
	drawList->AddLine(ImVec2(center.x - cradleRadius * 0.72f, standBottom),
		ImVec2(center.x + cradleRadius * 0.72f, standBottom), color, stroke);

	if (slant != 0.0f)
	{
		/* "muted" strike-through, drawn over the glyph */
		const ImVec2 from(center.x - halfHeight * 0.62f, center.y + slant * halfHeight);
		const ImVec2 to(center.x + halfHeight * 0.62f, center.y - slant * halfHeight);
		drawList->AddLine(from, to, color, stroke * 1.35f);
	}
}

} /* namespace MicGlyph */
