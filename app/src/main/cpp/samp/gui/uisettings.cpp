#define IMGUI_DEFINE_MATH_OPERATORS
#include "../vendor/imgui/imgui.h"
#include "../vendor/imgui/imgui_internal.h"

#include <string>
#include "uisettings.h"
#include "../main.h"
#include "../settings.h"

extern CSettings* pSettings;

/* scaling */
ImVec2 UISettings::m_baseSize = ImVec2(640.0f, 480.0f);
ImVec2 UISettings::m_scaleFactor = ImVec2(1.0f, 1.0f);
ImVec2 UISettings::m_displaySize = ImVec2(640.0f, 480.0f);

/* font */
float UISettings::m_fontSize = 24.0f;
float UISettings::m_outlineSize = 2.0f;

/* ////////////////// pos & size ////////////////// */

/* widgets */
float UISettings::m_padding = 7.0f;

/* keyboard */
ImVec2 UISettings::m_keyboardPos = ImVec2(0.0f, 170.0f);
ImVec2 UISettings::m_keyboardSize = ImVec2(640.0f, 300.0f);
float UISettings::m_keyboardRowHeight = 52.0f;

/* splashscreen */
ImVec2 UISettings::m_splashscreenLogoPos;
ImVec2 UISettings::m_splashscreenLogoSize = ImVec2(640.0f, 480.0f);
ImVec2 UISettings::m_splashScreenPBarPos = ImVec2(100.0f, 460.0f);
ImVec2 UISettings::m_splashScreenPBarSize = ImVec2(440.0f, 10.0f);

/* chat */
int UISettings::m_chatDispMesssages = 9;
int UISettings::m_chatMaxMessages = 30;
ImVec2 UISettings::m_chatPos = ImVec2(8.0f, 6.0f);
ImVec2 UISettings::m_chatSize = ImVec2(400.0f, 0.0f);
ImVec2 UISettings::m_chatItemSize = ImVec2(400.0f, 13.0f);

/* spawn */
ImVec2 UISettings::m_spawnPos = ImVec2(200.0f, 420.0f);
ImVec2 UISettings::m_spawnSize = ImVec2(240.0f, 50.0f);

/* nametag */
ImVec2 UISettings::m_nametagBarSize = ImVec2(25.0f, 6.0f);

/* dialog */
ImVec2 UISettings::m_dialogButtonPanelSize = ImVec2(150.0f, 45.0f);
ImVec2 UISettings::m_dialogMinSize = ImVec2(150.0f, 120.0f);
ImVec2 UISettings::m_dialogMaxSize = ImVec2(620.0f, 400.0f);
float UISettings::m_dialogTitleHeight = 20.0f;
float UISettings::m_dialogListItemHeight = 30.0f;

/* buttonpanel */
ImVec2 UISettings::m_buttonPanelPos = ImVec2(6.0f, 118.0f);
ImVec2 UISettings::m_buttonPanelSize = ImVec2(360.0f, 44.0f);

/* voice button */
ImVec2 UISettings::m_buttonVoicePos = ImVec2(0.0f, 0.0f);   /* computed below */
ImVec2 UISettings::m_buttonVoiceSize = ImVec2(52.0f, 74.0f);

/* ////////////////// colors ////////////////// */

/* button */
ImColor UISettings::m_buttonColor = ImColor(0.11f, 0.11f, 0.11f, 0.80f);
ImColor UISettings::m_buttonFocusedColor = ImColor(0x64, 0x95, 0xED);

/* keyboard */
ImColor UISettings::m_keyboardBackgroundColor = ImColor(0, 0, 0, 150);

/* dialog */
ImColor UISettings::m_dialogBackgroundColor = ImColor(0, 0, 0, 200);
ImColor UISettings::m_dialogTitleBackgroundColor = ImColor(0, 0, 0, 200);

/*
    One uniform scale for the whole interface.

    The layout is designed on a 640x480 canvas, but the scale used to be
    computed per axis (display / base). On a 1600x720 screen that gives 2.5 on
    X and 1.5 on Y: the font grew by height while the buttons beside it were
    laid out by width, so captions no longer fitted their buttons and the last
    entries of the button bar were pushed outside the panel - "the HUD buttons
    are not fully visible". Padding was never scaled at all, which made it
    worse on big screens.

    A single factor (the smaller of the two, so nothing is ever cropped) keeps
    every widget in proportion, and every rectangle is clamped inside the
    screen afterwards.
*/
void UISettings::Initialize(const ImVec2& display_size)
{
	if(!pSettings) return;

	const ImVec2 size = (display_size.x > 1.0f && display_size.y > 1.0f) ? display_size : m_baseSize;
	m_displaySize = size;

	float scale = ImMin(size.x / m_baseSize.x, size.y / m_baseSize.y);
	if (scale <= 0.01f) scale = 1.0f;
	m_scaleFactor = ImVec2(scale, scale);

	/* Chat keeps its own, slightly larger canvas: the panel is wider than the
	   640 design width on landscape phones and the message list should use it. */
	const float wideScale = ImMax(scale, ImMin(size.x / m_baseSize.x, scale * 1.35f));

	/* font */
	m_fontSize = 24.0f * scale;
	m_outlineSize = ImMax(1.0f, 2.0f * scale);
	m_padding = 7.0f * scale;

	/* chat line count */
	m_chatDispMesssages = pSettings->Get().iChatMaxMessages;

	/* keyboard: docked to the bottom edge, centred */
	m_keyboardRowHeight = 52.0f * scale;
	m_keyboardSize = ImVec2(ImMin(m_baseSize.x * wideScale, size.x), ImMin(m_baseSize.y * m_scaleFactor.y, size.y * 0.62f));
	m_keyboardPos = ImVec2((size.x - m_keyboardSize.x) * 0.5f, size.y - m_keyboardSize.y);

	/* splashscreen: covers the whole screen, progress bar at the bottom */
	m_splashscreenLogoSize = size;
	m_splashscreenLogoPos = ImVec2(0.0f, 0.0f);
	m_splashScreenPBarSize = ImVec2(size.x * 0.62f, ImMax(6.0f, 10.0f * scale));
	m_splashScreenPBarPos = ImVec2((size.x - m_splashScreenPBarSize.x) * 0.5f, size.y - 26.0f * scale);

	/* chat */
	m_chatItemSize = ImVec2(400.0f * wideScale, 13.0f * scale);
	m_chatPos = ImVec2(8.0f * scale, 6.0f * scale);
	m_chatSize = ImVec2(m_chatItemSize.x, m_chatItemSize.y * m_chatDispMesssages);

	/* spawn */
	m_spawnSize = ImVec2(240.0f * scale, 50.0f * scale);
	m_spawnPos = ImVec2((size.x - m_spawnSize.x) * 0.5f, size.y - m_spawnSize.y - 24.0f * scale);

	/* nametag */
	m_nametagBarSize = ImVec2(25.0f * scale, 6.0f * scale);

	/* dialog */
	m_dialogButtonPanelSize = ImVec2(150.0f * scale, 45.0f * scale);
	m_dialogMinSize = ImVec2(240.0f * scale, 120.0f * scale);
	m_dialogMaxSize = ImVec2(ImMin(560.0f * wideScale, size.x - 24.0f * scale), ImMin(400.0f * scale, size.y - 40.0f * scale));
	m_dialogTitleHeight = 20.0f * scale;
	m_dialogListItemHeight = 30.0f * scale;

	/* buttonpanel: one row above the chat area, never wider than the screen */
	m_buttonPanelSize = ImVec2(ImMin(360.0f * wideScale, size.x - 12.0f * scale), 44.0f * scale);
	m_buttonPanelPos = ImVec2(6.0f * scale, m_chatSize.y + 14.0f * scale);
	if (m_buttonPanelPos.y + m_buttonPanelSize.y > size.y * 0.55f)
		m_buttonPanelPos.y = ImMax(6.0f * scale, size.y * 0.55f - m_buttonPanelSize.y);

	/* voice button: right edge, above the on-screen pedals */
	m_buttonVoiceSize = ImVec2(52.0f * scale, 74.0f * scale);
	m_buttonVoicePos = ImVec2(size.x - m_buttonVoiceSize.x - 12.0f * scale, size.y * 0.32f);

	/* nothing may sit outside the screen */
	auto clampInside = [&size](ImVec2& pos, const ImVec2& widgetSize)
	{
		if (pos.x + widgetSize.x > size.x) pos.x = ImMax(0.0f, size.x - widgetSize.x);
		if (pos.y + widgetSize.y > size.y) pos.y = ImMax(0.0f, size.y - widgetSize.y);
		if (pos.x < 0.0f) pos.x = 0.0f;
		if (pos.y < 0.0f) pos.y = 0.0f;
	};

	clampInside(m_keyboardPos, m_keyboardSize);
	clampInside(m_chatPos, m_chatSize);
	clampInside(m_spawnPos, m_spawnSize);
	clampInside(m_buttonPanelPos, m_buttonPanelSize);
	clampInside(m_buttonVoicePos, m_buttonVoiceSize);
	clampInside(m_splashScreenPBarPos, m_splashScreenPBarSize);
}
