#include "../../main.h"
#include "..//gui.h"
#include "button.h"

extern bool OpenButton;

Button::Button(const std::string& caption, float font_size)
{
	m_callback = nullptr;

	m_label = new Label(caption, ImColor(1.0f, 1.0f, 1.0f), false, font_size);
	this->addChild(m_label);

	m_color = UISettings::buttonColor();
	m_colorFocused = UISettings::buttonFocusedColor();
}

void Button::performLayout()
{
	float padding = UISettings::padding();

	m_label->performLayout();
	this->setSize(m_label->size() + ImVec2(padding * 2, padding / 2 * 2));

	m_label->setPosition((size() - m_label->size()) / 2);
}

void Button::draw(ImGuiRenderer* renderer)
{
	renderer->drawRect(absolutePosition(), absolutePosition() + size(),
		focused() ? m_colorFocused : m_color, true);

	//Outline
	renderer->drawRect(absolutePosition() + ImVec2(UISettings::outlineSize(), UISettings::outlineSize()),
		(absolutePosition() + size()) - ImVec2(UISettings::outlineSize(), UISettings::outlineSize()),
		ImColor(0.287f, 0.287f, 0.287f, 0.890f)/*ImColor(0xF5, 0x91, 0x32)*/, false, UISettings::outlineSize());

	Widget::draw(renderer);
}

void Button::touchPopEvent()
{
	if (m_callback) m_callback();
}


//============== Custom Button=========================//
CButton::CButton(const std::string& caption, float font_size)
{
	m_callback = nullptr;

	m_label = new Label(caption, ImColor(1.0f, 1.0f, 1.0f), false, font_size);
	this->addChild(m_label);

	m_color = UISettings::buttonColor();
	m_colorFocused = UISettings::buttonFocusedColor();
}

void CButton::performLayout()
{
	float padding = UISettings::padding();

	m_label->performLayout();
	this->setSize(m_label->size() + ImVec2(padding * 2, padding / 2 * 2));

	m_label->setPosition((size() - m_label->size()) / 2);
}

void CButton::draw(ImGuiRenderer* renderer)
{
	/* The bar is always shown (OpenButton starts true); "<<" only hides it. */
	renderer->drawRect(absolutePosition(), absolutePosition() + size(),
		focused() ? m_colorFocused : m_color, true);

	
	renderer->drawRect(absolutePosition() + ImVec2(UISettings::outlineSize(), UISettings::outlineSize()),
		(absolutePosition() + size()) - ImVec2(UISettings::outlineSize(), UISettings::outlineSize()),
		ImColor(0x64, 0x95, 0xED)/*ImColor(0, 0, 0, 200)*/, false, UISettings::outlineSize());

	Widget::draw(renderer);
}

void CButton::touchPopEvent()
{
	if (m_callback) m_callback();
}
//=======================================//

//======== >> Button ====================//
OButton::OButton(const std::string& caption, float font_size)
{
	m_callback = nullptr;

	m_label = new Label(caption, ImColor(1.0f, 1.0f, 1.0f), false, font_size);
	this->addChild(m_label);

	m_color = UISettings::buttonColor();
	m_colorFocused = UISettings::buttonFocusedColor();
}

void OButton::performLayout()
{
	float padding = UISettings::padding();

	m_label->performLayout();
	this->setSize(m_label->size() + ImVec2(padding * 2, padding / 2 * 2));

	m_label->setPosition((size() - m_label->size()) / 2);
	//m_label->setPosition((m_label->size()) / 2);

}

void OButton::draw(ImGuiRenderer* renderer)
{
	/*
		It used to move itself to (15,15) after every draw - which overwrote the
		position the layout gave it - and to (-150,-150) while the bar was open.
		The bar is open by default, so this control is only drawn as the "show
		the bar again" button.
	*/
	if (OpenButton == true)
		return;

	renderer->drawRect(absolutePosition(), absolutePosition() + size(),
		focused() ? m_colorFocused : m_color, true);


	renderer->drawRect(absolutePosition() + ImVec2(UISettings::outlineSize(), UISettings::outlineSize()),
		(absolutePosition() + size()) - ImVec2(UISettings::outlineSize(), UISettings::outlineSize()),
		ImColor(0x64, 0x95, 0xED)/*ImColor(0, 0, 0, 200)*/, false, UISettings::outlineSize());

	Widget::draw(renderer);
}

void OButton::touchPopEvent()
{
	if (m_callback) m_callback();
}
//======================================//