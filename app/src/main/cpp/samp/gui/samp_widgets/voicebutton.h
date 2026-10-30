#pragma once
#include "../../game/game.h"
#include "../../game/util.h"

#include "../../main.h"

#include "../../settings.h"
#include "util/CUtil.h"


extern CSettings* pSettings;

class VoiceButton : public Button
{
public:
	VoiceButton() : Button("TALK", UISettings::fontSize() / 2) {
		m_recording = false;
		/* 5:3 aspect ratio */
		// The database this client installs has neither name (the log says
		// "Texture (samp) not found in database (voiceactive)"), so both load
		// attempts return null. They used to be dereferenced on the first frame
		// after a server accepted the client, which is exactly the crash that
		// ended every session right after connecting.
		m_texture_micro_on = LoadIcon("samp", "voiceactive", "icon_micro_on");
		m_texture_micro_off = LoadIcon("samp", "voicepassive", "icon_micro_off");
	}

	virtual void draw(ImGuiRenderer* renderer) override
	{
		if (!pSettings || !pSettings->Get().bVoiceChatEnable) return;

		if (countdown > 0 && recording() == 1) countdown--;
		if (countdown == 0 && recording() == 1) setRecording(0);

		RwTexture* texture = recording() ? m_texture_micro_on : m_texture_micro_off;

		// A missing icon must never be a crash: without it the button is drawn
		// like every other button (background, outline and its TALK caption).
		if (texture != nullptr && texture->raster != nullptr)
			renderer->drawImage(absolutePosition(), absolutePosition() + size(), texture->raster);
		else
			Button::draw(renderer);

		//MyLog2("%f,%f",absolutePosition(), absolutePosition() + size());
		//MyLog2("countdown %d", countdown);
		//MyLog2("recording %d", recording());
		//MyLog2("press %d", press);
		
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