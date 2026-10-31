#pragma once
#include "../../game/game.h"
#include "../../game/util.h"

#include "../../main.h"
#include "../MicGlyph.h"
#include "../../vendor/imgui/imgui_internal.h"

#include "../../settings.h"
#include "util/CUtil.h"


extern CSettings* pSettings;

/*
    The TALK control.

    It never depended on a texture database entry: the icon comes from
    MicGlyph.h. Every session used to log

        Texture (samp) not found in database (voiceactive)
        Texture (samp) not found in database (icon_micro_on)

    and then fell back to drawing the word "MIC" with the chat font. Now the
    button is a drawn microphone with a caption, exactly the same glyph the
    voice window uses, so it looks identical on every device and cannot be
    missing while the texture set is incomplete.
*/
class VoiceButton : public Button
{
public:
	VoiceButton() : Button("TALK", UISettings::fontSize() / 2) {
		m_recording = false;
		/* 5:3 aspect ratio */
		/* The database this client installs has neither name, so both load
		   attempts return null. They are still attempted because a server pack
		   may ship the real artwork - but the drawn icon below is the default,
		   not a fallback. */
		m_texture_micro_on = LoadIcon("samp", "voiceactive", "icon_micro_on");
		m_texture_micro_off = LoadIcon("samp", "voicepassive", "icon_micro_off");
	}

	virtual void draw(ImGuiRenderer* renderer) override
	{
		const bool voiceEnabled = (pSettings != nullptr && pSettings->Get().bVoiceChatEnable);
		const bool active = recording() && voiceEnabled;

		const ImVec2 pos = absolutePosition();
		const ImVec2 end(pos.x + size().x, pos.y + size().y);
		const float width = size().x;
		const float height = size().y;
		if (width < 2.0f || height < 2.0f)
			return;

		if (countdown > 0 && recording() == 1) countdown--;
		if (countdown == 0 && recording() == 1) setRecording(0);

		const float rounding = ImMin(width, height) * 0.24f;

		/* A server pack with the real artwork still wins when it exists. */
		RwTexture* texture = recording() ? m_texture_micro_on : m_texture_micro_off;
		if (texture != nullptr && texture->raster != nullptr)
		{
			renderer->drawImage(pos, end, texture->raster);
			return;
		}

		/* background */
		const ImColor background = active
			? ImColor(0.78f, 0.13f, 0.13f, 0.94f)
			: (focused() ? ImColor(0.20f, 0.26f, 0.38f, 0.94f) : ImColor(0.11f, 0.11f, 0.11f, 0.86f));
		renderer->drawRoundRectFilled(pos, end, rounding, background);

		/* outline - the same accent the other buttons use */
		renderer->drawRoundRect(pos, end, rounding,
			active ? ImColor(1.0f, 0.42f, 0.42f, 0.95f) : ImColor(0x64, 0x95, 0xED), ImMax(1.0f, width * 0.045f));

		/* pulsing ring while the microphone is open */
		if (active)
		{
			const float pulse = 0.5f + 0.5f * ImSin((float)ImGui::GetTime() * 6.0f);
			const float expand = 2.0f * pulse;
			const ImVec2 pulseMin(pos.x - expand, pos.y - expand);
			const ImVec2 pulseMax(end.x + expand, end.y + expand);
			renderer->drawRoundRect(pulseMin, pulseMax,
				rounding, ImColor(1.0f, 0.35f, 0.35f, 0.35f + 0.35f * pulse), ImMax(1.0f, width * 0.03f));
		}

		/* the microphone itself, slightly above the middle so the caption fits */
		const float glyphHeight = ImMin(height * 0.54f, width * 0.62f);
		const ImVec2 glyphCenter(pos.x + width * 0.5f, pos.y + height * 0.40f);
		const ImU32 glyphColor = voiceEnabled
			? (active ? IM_COL32(255, 255, 255, 255) : IM_COL32(235, 240, 250, 255))
			: IM_COL32(150, 155, 165, 190);
		MicGlyph::Draw(renderer->drawList(), glyphCenter, glyphHeight, glyphColor, active ? 0.55f : 0.0f);

		/* caption */
		const std::string caption = active ? "LIVE" : "TALK";
		const float captionSize = ImMin(height * 0.16f, width * 0.30f);
		const ImVec2 captionBox = renderer->calculateTextSize(caption, captionSize);
		renderer->drawText(ImVec2(pos.x + (width - captionBox.x) * 0.5f, pos.y + height * 0.72f),
			active ? ImColor(1.0f, 1.0f, 1.0f) : ImColor(0.86f, 0.90f, 0.98f),
			caption, true, captionSize);
	}

	void touchPopEvent() override
	{
		countdown = 500;
		setRecording(0);
	}

	void touchPushEvent() override
	{
			//setRecording(recording() ^ 1);
		setRecording(1);
		countdown = 500;
	}

	void setRecording(bool recording) 
	{ 
		if (recording == 1) countdown = 200;
		m_recording = recording;
		this->setCaptionColor(m_recording ? ImColor(1.0f, 0.0f, 0.0f) : ImColor(1.0f, 1.0f, 1.0f));
	}

	bool recording() const { return m_recording; }

private:
	/** First name that exists in [database], or null when none does. */
	static RwTexture* LoadIcon(const char* database, const char* name, const char* fallback)
	{
		RwTexture* texture = (RwTexture*)CUtil::LoadTextureFromDB(database, name);
		if (texture == nullptr && fallback != nullptr)
			texture = (RwTexture*)CUtil::LoadTextureFromDB(database, fallback);
		return texture;
	}

	bool m_recording;
	RwTexture* m_texture_micro_on;
	RwTexture* m_texture_micro_off;
	//int countdown = 1000;
public:
	int countdown = 1000;
};
