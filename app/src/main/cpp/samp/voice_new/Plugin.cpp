#include "../main.h"
#include "../gui/gui.h"

#include "Plugin.h"

#include "include/util/Timer.h"

#include "Record.h"
#include "Playback.h"
#include "Network.h"
//#include "BlackList.h"
#include "PluginConfig.h"
#include "MicroIcon.h"
#include "SpeakerList.h"
#include "../gui/MicGlyph.h"
//#include "PluginMenu.h"
#include "GlobalStream.h"
#include "StreamAtPoint.h"
#include "StreamAtVehicle.h"
#include "StreamAtPlayer.h"
#include "StreamAtObject.h"

#include "../audiostream.h"
extern CAudioStream* pAudioStream;

extern UI* pUI;

/*
    The microphone control of the voice window.

    It used to be a plain text button - the word MIC in the default font -
    because the icon textures it wants ("voiceactive" / "voicepassive") are not
    in the texture database this client installs. The control is now drawn:
    rounded background, accent outline and the shared MicGlyph, so it looks the
    same as the in-game TALK button and never depends on a texture.
*/
static bool MicIconButton(const char* id, const ImVec2& size, bool active, bool dimmed)
{
    if (size.x < 2.0f || size.y < 2.0f)
        return false;

    ImGui::PushID(id);

    const ImVec2 pos = ImGui::GetCursorScreenPos();
    const bool pressed = ImGui::InvisibleButton("##mic", size);
    const bool hovered = ImGui::IsItemHovered();
    const bool held = ImGui::IsItemActive();

    ImDrawList* drawList = ImGui::GetWindowDrawList();
    const float rounding = (size.x < size.y ? size.x : size.y) * 0.24f;

    ImU32 background;
    if (dimmed)          background = IM_COL32(40, 40, 40, 170);
    else if (active)     background = IM_COL32(199, 33, 33, 240);
    else if (held || hovered) background = IM_COL32(51, 66, 97, 240);
    else                 background = IM_COL32(28, 28, 28, 210);

    drawList->AddRectFilled(pos, ImVec2(pos.x + size.x, pos.y + size.y), background, rounding);
    drawList->AddRect(pos, ImVec2(pos.x + size.x, pos.y + size.y),
        active ? IM_COL32(255, 107, 107, 255) : IM_COL32(100, 149, 237, 255),
        rounding, 15, (size.x < size.y ? size.x : size.y) * 0.045f);

    MicGlyph::Draw(drawList,
        ImVec2(pos.x + size.x * 0.5f, pos.y + size.y * 0.46f),
        (size.y * 0.58f < size.x * 0.62f ? size.y * 0.58f : size.x * 0.62f),
        dimmed ? IM_COL32(165, 165, 165, 200) : IM_COL32(240, 245, 255, 255),
        active ? 0.55f : 0.0f);

    ImGui::PopID();
    return pressed;
}

bool Plugin::OnPluginLoad() noexcept
{
    if(!Render::Init())
    {
        LogVoice("[sv:err:plugin] : failed to init render module");
        return false;
    }

    Render::AddDeviceInitCallback(Plugin::OnDeviceInit);
    Render::AddRenderCallback(Plugin::OnRender);
    Render::AddDeviceFreeCallback(Plugin::OnDeviceFree);

    return true;
}

bool Plugin::OnSampLoad() noexcept
{
    if(!Samp::Init())
    {
        LogVoice("[sv:err:plugin] : failed to init samp");
        Render::Free();
        return false;
    }

    Samp::AddLoadCallback(Plugin::OnInitGame);
    Samp::AddExitCallback(Plugin::OnExitGame);

    if(!Network::Init())
    {
        LogVoice("[sv:err:plugin] : failed to init network");
        Render::Free();
        Samp::Free();
        return false;
    }

    Network::AddConnectCallback(Plugin::ConnectHandler);
    Network::AddSvConnectCallback(Plugin::PluginConnectHandler);
    Network::AddSvInitCallback(Plugin::PluginInitHandler);
    Network::AddDisconnectCallback(Plugin::DisconnectHandler);

    if(!Playback::Init())
    {
        LogVoice("[sv:err:plugin] : failed to init playback");
        Render::Free();
        Samp::Free();
        Network::Free();
        return false;
    }

    return true;
}

void Plugin::OnInitGame() noexcept
{
    // ~ none
}

void Plugin::OnExitGame() noexcept
{
    Network::Free();

    Plugin::streamTable.clear();

    Record::Free();
    Playback::Free();
}

int MicType = 1; //1. classbutton 2. imgui
void Plugin::MainLoop()
{
    if(!Samp::IsLoaded()) return;

    while(const auto controlPacket = Network::ReceiveControlPacket())
    {
        Plugin::ControlPacketHandler(*&*controlPacket);
    }

    while(const auto voicePacket = Network::ReceiveVoicePacket())
    {
        const auto& voicePacketRef = *voicePacket;

        const auto iter = Plugin::streamTable.find(voicePacketRef->stream);
        if(iter == Plugin::streamTable.end()) continue;

        iter->second->Push(*&voicePacketRef);
    }
    
    for(const auto& stream : Plugin::streamTable)
        stream.second->Tick();

    Playback::Tick();
    Record::Tick();

    int defaultKey = 0x42;

    
    //
    if(MicroIcon::IsShowed())
    {
        if (MicType == 1)
        {
            if (!Plugin::muteStatus)
            {
                //button voice
                VoiceButton* vbutton = pUI->voicebutton();
                if (vbutton->recording() && Plugin::recordStatus == 0)
                {
                    if (pAudioStream)
                    {
                        pAudioStream->Stop(true);
                    }
                    if (PluginConfig::GetMicroEnable())
                    {
                        LogVoice("[sv:dbg:plugin] : activation key(%hhu) pressed", defaultKey);

                        if (!Plugin::recordBusy)
                        {
                            Plugin::recordStatus = true;
                            Record::StartRecording();
                        }

                        SV::PressKeyPacket pressKeyPacket{};

                        pressKeyPacket.keyId = defaultKey;

                        if (!Network::SendControlPacket(SV::ControlPacketType::pressKey, &pressKeyPacket, sizeof(pressKeyPacket)))
                            LogVoice("[sv:err:main:HookWndProc] : failed to send PressKey packet");
                    }
                }
                if (!vbutton->recording() && Plugin::recordStatus == 1)
                {
                    if (PluginConfig::GetMicroEnable())
                    {
                        LogVoice("[sv:dbg:plugin] : activation key(%hhu) released", defaultKey);

                        if (!Plugin::recordBusy)
                        {
                            Plugin::recordStatus = false;
                        }

                        SV::ReleaseKeyPacket releaseKeyPacket{};

                        releaseKeyPacket.keyId = defaultKey;

                        if (!Network::SendControlPacket(SV::ControlPacketType::releaseKey, &releaseKeyPacket, sizeof(releaseKeyPacket)))
                            LogVoice("[sv:err:main:HookWndProc] : failed to send ReleaseKey packet");
                    }
                }
            }
            else
            {

            }
        }
        if (MicType == 2)
        {
            ImGuiIO& io = ImGui::GetIO();

            ImGui::PushStyleColor(ImGuiCol_Button, (ImVec4)ImColor(0x00, 0x00, 0x00, 0x00).Value);
            ImGui::PushStyleColor(ImGuiCol_ButtonHovered, (ImVec4)ImColor(0x00, 0x00, 0x00, 0x00).Value);
            ImGui::PushStyleColor(ImGuiCol_ButtonActive, (ImVec4)ImColor(0x00, 0x00, 0x00, 0x00).Value);

            ImGuiStyle style;
            style.FrameBorderSize = ImGui::GetStyle().FrameBorderSize;
            ImGui::GetStyle().FrameBorderSize = 0.0f;

            ImGui::Begin("VoiceButton", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoSavedSettings);

            ImVec2 vecButSize = ImVec2(MicroIcon::kBaseIconSize * 4 + 3.0f, MicroIcon::kBaseIconSize * 4);//*7 and *3 //absolutePosition()

            if (!Plugin::muteStatus)
            {
                if (Plugin::MicPress > 1) {
                    Plugin::MicPress--;
                }
                // tActiveIcon is never loaded by MicroIcon::Init(), and the
                // database does not carry "voice_off" either, so both pointers
                // can be null - they used to be dereferenced every frame.
                RwTexture* micTexture = Plugin::recordStatus ? MicroIcon::tActiveIcon : MicroIcon::tPassiveIcon;
                if (micTexture == nullptr || micTexture->raster == nullptr)
                    micTexture = Plugin::recordStatus ? MicroIcon::tPassiveIcon : MicroIcon::tActiveIcon;
                if (micTexture == nullptr || micTexture->raster == nullptr)
                    micTexture = MicroIcon::tMutedIcon;

                // Without an icon the same control is still offered as a plain
                // button, so voice chat stays usable instead of disappearing.
                const bool pressed = (micTexture != nullptr && micTexture->raster != nullptr)
                    ? ImGui::ImageButton((ImTextureID)micTexture->raster, vecButSize)
                    : MicIconButton("talk", vecButSize, Plugin::recordStatus, !PluginConfig::GetMicroEnable());

                if (pressed)
                {
                    if (PluginConfig::GetMicroEnable())
                    {
                        if (pAudioStream)
                        {
                            pAudioStream->Stop(true);
                        }
                        if (!Plugin::recordStatus)
                        {
                            LogVoice("[sv:dbg:plugin] : activation key(%hhu) pressed", defaultKey);

                            if (!Plugin::recordBusy)
                            {
                                Plugin::recordStatus = true;
                                Record::StartRecording();
                                if (Plugin::MicPress == 0) {
                                    Plugin::MicPress = 30;
                                }
                            }

                            SV::PressKeyPacket pressKeyPacket{};

                            pressKeyPacket.keyId = defaultKey;

                            if (!Network::SendControlPacket(SV::ControlPacketType::pressKey, &pressKeyPacket, sizeof(pressKeyPacket)))
                                LogVoice("[sv:err:main:HookWndProc] : failed to send PressKey packet");
                        }
                        else
                        {
                            LogVoice("[sv:dbg:plugin] : activation key(%hhu) released", defaultKey);

                            if (!Plugin::recordBusy)
                            {
                                Plugin::recordStatus = false;
                            }

                            SV::ReleaseKeyPacket releaseKeyPacket{};

                            releaseKeyPacket.keyId = defaultKey;

                            if (!Network::SendControlPacket(SV::ControlPacketType::releaseKey, &releaseKeyPacket, sizeof(releaseKeyPacket)))
                                LogVoice("[sv:err:main:HookWndProc] : failed to send ReleaseKey packet");
                        }
                    }
                }
                if (Plugin::MicPress == 1)
                {
                    LogVoice("[sv:dbg:plugin] : activation key(%hhu) released", defaultKey);

                    if (!Plugin::recordBusy)
                    {
                        Plugin::recordStatus = false;
                        Plugin::MicPress = 0;
                    }

                    SV::ReleaseKeyPacket releaseKeyPacket{};

                    releaseKeyPacket.keyId = defaultKey;

                    if (!Network::SendControlPacket(SV::ControlPacketType::releaseKey, &releaseKeyPacket, sizeof(releaseKeyPacket)))
                        LogVoice("[sv:err:main:HookWndProc] : failed to send ReleaseKey packet");
                }
            }
            else
            {
                RwTexture* mutedTexture = MicroIcon::tMutedIcon;
                const bool mutePressed = (mutedTexture != nullptr && mutedTexture->raster != nullptr)
                    ? ImGui::ImageButton((ImTextureID)mutedTexture->raster, vecButSize)
                    : MicIconButton("mute", vecButSize, false, true);

                if (mutePressed)
                {
                    // ~ none
                }
            }

            ImGui::SetWindowSize(ImVec2(-1, -1));

            //MyLog2("io.DisplaySize.x %f x io.DisplaySize.y %f", io.DisplaySize.x, io.DisplaySize.y);
            ImGui::SetWindowPos(ImVec2(io.DisplaySize.x / 1.3/*1.09*/ - vecButSize.x, io.DisplaySize.y / 1.5/*1.75*//*2.15*/ - vecButSize.y * 3));
            //ImGui::SetWindowPos(ImVec2(1520.0f, 430.0f));
            ImGui::End();

            ImGui::PopStyleColor(3);
            ImGui::GetStyle().FrameBorderSize = style.FrameBorderSize;
        }
    }

    uint8_t frameBuffer[Network::kMaxVoiceDataSize];
    if(const auto frameSize = Record::GetFrame(frameBuffer, sizeof(frameBuffer)))
    {
        if(!Network::SendVoicePacket(frameBuffer, frameSize))
            LogVoice("[sv:err:plugin] : failed to send voice packet");

        if(!Plugin::recordStatus)
        {
            Record::StopRecording();
            Network::EndSequence();
        }
    }
}

void Plugin::ConnectHandler(const std::string& serverIp, const uint16_t serverPort)
{
    // ~ none
}

void Plugin::PluginConnectHandler(SV::ConnectPacket& connectStruct)
{
    connectStruct.signature = SV::kSignature;
    connectStruct.version = SV::kVersion;
    connectStruct.micro = Record::HasMicro();
}

bool Plugin::PluginInitHandler(const SV::PluginInitPacket& initPacket)
{
    Plugin::muteStatus = initPacket.mute;

    if(!Record::Init(initPacket.bitrate))
    {
        LogVoice("[sv:inf:plugin:packet:init] : failed init record");
    }

    return true;
}

void Plugin::ControlPacketHandler(const ControlPacket& controlPacket)
{
    switch(controlPacket.packet)
    {
        case SV::ControlPacketType::muteEnable:
        {
            if(controlPacket.length != 0) break;

            LogVoice("[sv:dbg:plugin:muteenable]");

            Plugin::muteStatus = true;
            Plugin::recordStatus = false;
            Plugin::recordBusy = false;
        } 
        break;
        case SV::ControlPacketType::muteDisable:
        {
            if(controlPacket.length != 0) break;

            LogVoice("[sv:dbg:plugin:mutedisable]");

            Plugin::muteStatus = false;
        } 
        break;
        case SV::ControlPacketType::startRecord:
        {
            if(controlPacket.length != 0) break;

            LogVoice("[sv:dbg:plugin:startrecord]");

            if(Plugin::muteStatus) break;

            Plugin::recordBusy = true;
            Plugin::recordStatus = true;

            Record::StartRecording();
        } 
        break;
        case SV::ControlPacketType::stopRecord:
        {
            if(controlPacket.length != 0) break;

            LogVoice("[sv:dbg:plugin:stoprecord]");

            if(Plugin::muteStatus) break;

            Plugin::recordStatus = false;
            Plugin::recordBusy = false;
        } 
        break;
        case SV::ControlPacketType::addKey:
        {
            const auto& stData = *reinterpret_cast<const SV::AddKeyPacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:addkey] : keyid(0x%hhx)", stData.keyId);
            LogVoice("[dbg:keyfilter] : adding key (0x%hhx)", stData.keyId); // xd fake

            //KeyFilter::AddKey(stData.keyId);
        } 
        break;
        case SV::ControlPacketType::removeKey:
        {
            const auto& stData = *reinterpret_cast<const SV::RemoveKeyPacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:removekey] : keyid(0x%hhx)", stData.keyId);
            LogVoice("[dbg:keyfilter] : removing key (0x%hhx)", stData.keyId); // xd fake

            //KeyFilter::RemoveKey(stData.keyId);
        } 
        break;
        case SV::ControlPacketType::removeAllKeys:
        {
            if(controlPacket.length) break;

            LogVoice("[sv:dbg:plugin:removeallkeys]");
            LogVoice("[dbg:keyfilter] : removing all keys"); // xd fake

            //KeyFilter::RemoveAllKeys();
        } 
        break;
        case SV::ControlPacketType::createGStream:
        {
            const auto& stData = *reinterpret_cast<const SV::CreateGStreamPacket*>(controlPacket.data);
            if(controlPacket.length < sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:creategstream] : stream(%p), color(0x%x), name(%s)",
                stData.stream, stData.color, stData.color ? stData.name : "");

            const auto& streamPtr = Plugin::streamTable[stData.stream] =
                MakeGlobalStream(stData.color, stData.name);

            streamPtr->AddPlayCallback(SpeakerList::OnSpeakerPlay);
            streamPtr->AddStopCallback(SpeakerList::OnSpeakerStop);
        } 
        break;
        case SV::ControlPacketType::createLPStream:
        {
            const auto& stData = *reinterpret_cast<const SV::CreateLPStreamPacket*>(controlPacket.data);
            if(controlPacket.length < sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:createlpstream] : "
                "stream(%p), dist(%.2f), pos(%.2f;%.2f;%.2f), color(0x%x), name(%s)",
                stData.stream, stData.distance, stData.position.x, stData.position.y, stData.position.z,
                stData.color, stData.color ? stData.name : "");

            const auto& streamPtr = Plugin::streamTable[stData.stream] =
                MakeStreamAtPoint(stData.color, stData.name, stData.distance, stData.position);

            streamPtr->AddPlayCallback(SpeakerList::OnSpeakerPlay);
            streamPtr->AddStopCallback(SpeakerList::OnSpeakerStop);
        } 
        break;
        case SV::ControlPacketType::createLStreamAtVehicle:
        {
            const auto& stData = *reinterpret_cast<const SV::CreateLStreamAtPacket*>(controlPacket.data);
            if(controlPacket.length < sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:createlstreamatvehicle] : "
                "stream(%p), dist(%.2f), vehicle(%hu), color(0x%x), name(%s)",
                stData.stream, stData.distance, stData.target,
                stData.color, stData.color ? stData.name : "");

            const auto& streamPtr = Plugin::streamTable[stData.stream] =
                MakeStreamAtVehicle(stData.color, stData.name, stData.distance, stData.target);

            streamPtr->AddPlayCallback(SpeakerList::OnSpeakerPlay);
            streamPtr->AddStopCallback(SpeakerList::OnSpeakerStop);
        } 
        break;
        case SV::ControlPacketType::createLStreamAtPlayer:
        {
            const auto& stData = *reinterpret_cast<const SV::CreateLStreamAtPacket*>(controlPacket.data);
            if(controlPacket.length < sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:createlstreamatplayer] : "
                "stream(%p), dist(%.2f), player(%hu), color(0x%x), name(%s)",
                stData.stream, stData.distance, stData.target,
                stData.color, stData.color ? stData.name : "");

            const auto& streamPtr = Plugin::streamTable[stData.stream] =
                MakeStreamAtPlayer(stData.color, stData.name, stData.distance, stData.target);

            streamPtr->AddPlayCallback(SpeakerList::OnSpeakerPlay);
            streamPtr->AddStopCallback(SpeakerList::OnSpeakerStop);
        } 
        break;
        case SV::ControlPacketType::createLStreamAtObject:
        {
            const auto& stData = *reinterpret_cast<const SV::CreateLStreamAtPacket*>(controlPacket.data);
            if(controlPacket.length < sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:createlstreamatobject] : "
                "stream(%p), dist(%.2f), object(%hu), color(0x%x), name(%s)",
                stData.stream, stData.distance, stData.target,
                stData.color, stData.color ? stData.name : "");

            const auto& streamPtr = Plugin::streamTable[stData.stream] =
                MakeStreamAtObject(stData.color, stData.name, stData.distance, stData.target);

            streamPtr->AddPlayCallback(SpeakerList::OnSpeakerPlay);
            streamPtr->AddStopCallback(SpeakerList::OnSpeakerStop);
        } 
        break;
        case SV::ControlPacketType::updateLStreamDistance:
        {
            const auto& stData = *reinterpret_cast<const SV::UpdateLStreamDistancePacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:updatelpstreamdistance] : stream(%p), dist(%.2f)",
                stData.stream, stData.distance);

            const auto iter = Plugin::streamTable.find(stData.stream);
            if(iter == Plugin::streamTable.end()) break;

            static_cast<LocalStream*>(iter->second.get())->SetDistance(stData.distance);
        } 
        break;
        case SV::ControlPacketType::updateLPStreamPosition:
        {
            const auto& stData = *reinterpret_cast<const SV::UpdateLPStreamPositionPacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:updatelpstreamcoords] : stream(%p), pos(%.2f;%.2f;%.2f)",
                stData.stream, stData.position.x, stData.position.y, stData.position.z);

            const auto iter = Plugin::streamTable.find(stData.stream);
            if(iter == Plugin::streamTable.end()) break;

            static_cast<StreamAtPoint*>(iter->second.get())->SetPosition(stData.position);
        } 
        break;
        case SV::ControlPacketType::deleteStream:
        {
            const auto& stData = *reinterpret_cast<const SV::DeleteStreamPacket*>(controlPacket.data);
            if (controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:deletestream] : stream(%p)", stData.stream);

            Plugin::streamTable.erase(stData.stream);
        } 
        break;
        case SV::ControlPacketType::setStreamParameter:
        {
            const auto& stData = *reinterpret_cast<const SV::SetStreamParameterPacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:streamsetparameter] : stream(%p), parameter(%hhu), value(%.2f)",
                stData.stream, stData.parameter, stData.value);

            const auto iter = Plugin::streamTable.find(stData.stream);
            if(iter == Plugin::streamTable.end()) break;

            iter->second->SetParameter(stData.parameter, stData.value);
        } 
        break;
        case SV::ControlPacketType::slideStreamParameter:
        {
            const auto& stData = *reinterpret_cast<const SV::SlideStreamParameterPacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:streamslideparameter] : "
                "stream(%p), parameter(%hhu), startvalue(%.2f), endvalue(%.2f), time(%u)",
                stData.stream, stData.parameter, stData.startvalue, stData.endvalue, stData.time);

            const auto iter = Plugin::streamTable.find(stData.stream);
            if(iter == Plugin::streamTable.end()) break;

            iter->second->SlideParameter(stData.parameter, stData.startvalue, stData.endvalue, stData.time);
        } 
        break;
        case SV::ControlPacketType::createEffect:
        {
            const auto& stData = *reinterpret_cast<const SV::CreateEffectPacket*>(controlPacket.data);
            if(controlPacket.length < sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:effectcreate] : "
                "stream(%p), effect(%p), number(%hhu), priority(%d)",
                stData.stream, stData.effect, stData.number, stData.priority);

            const auto iter = Plugin::streamTable.find(stData.stream);
            if(iter == Plugin::streamTable.end()) break;

            iter->second->EffectCreate(stData.effect, stData.number, stData.priority,
                stData.params, controlPacket.length - sizeof(stData));
        } 
        break;
        case SV::ControlPacketType::deleteEffect:
        {
            const auto& stData = *reinterpret_cast<const SV::DeleteEffectPacket*>(controlPacket.data);
            if(controlPacket.length != sizeof(stData)) break;

            LogVoice("[sv:dbg:plugin:effectdelete] : stream(%p), effect(%p)",
                stData.stream, stData.effect);

            const auto iter = Plugin::streamTable.find(stData.stream);
            if(iter == Plugin::streamTable.end()) break;

            iter->second->EffectDelete(stData.effect);
        } 
        break;
    }
}

void Plugin::DisconnectHandler()
{
    Plugin::streamTable.clear();

    Plugin::muteStatus = false;
    Plugin::recordStatus = false;
    Plugin::recordBusy = false;

    Record::Free();
}

void Plugin::OnDeviceInit()
{
    FLog("Plugin::OnDeviceInit()");
    SpeakerList::Init();
	MicroIcon::Init();
}

void Plugin::OnRender()
{
    Timer::Tick();
    Plugin::MainLoop();
    SpeakerList::Render();
}

void Plugin::OnDeviceFree()
{
    SpeakerList::Free();
    MicroIcon::Free();
}

bool Plugin::muteStatus { false };
bool Plugin::recordStatus { false };
bool Plugin::recordBusy { false };
int Plugin::MicRecord{ 0 };
int Plugin::MicPress{ 0 };

std::map<uint32_t, StreamPtr> Plugin::streamTable;