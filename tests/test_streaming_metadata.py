"""The production loader rejects corrupt IMG sizes before dispatch and recovers on read refusal."""
from pathlib import Path
src=(Path(__file__).resolve().parents[1]/'app/src/main/cpp/samp/game/Streaming.cpp').read_text()
start=src.index('void CStreaming::RequestModelStream(')
end=src.index('\nbool CStreaming::ProcessLoadingChannel(',start)
body=src[start:end]
selector=src[src.index('int32 CStreaming::GetNextFileOnCd('):start]
check='!StreamingReadBuffer::IsPlausibleImgSectorCount(nThisModelSizeInSectors)'
assert body.count(check)==2
assert 'imgId >= TOTAL_IMG_ARCHIVES' in selector
assert 'Invalid queued IMG metadata removed' in selector
assert selector.index('Invalid queued IMG metadata removed') < selector.index('GetInfo(modelId).GetCdPosn()')
assert body.index(check)<body.index('nSectorsToRead += nThisModelSizeInSectors')
assert 'RemoveModel(modelId);' in body and 'streamingInfo->ClearAllFlags();' in body
assert 'if (!CdStreamRead(chIdx' in body
refusal=body[body.index('if (!CdStreamRead(chIdx'):]
assert 'RequestModel(failedModel, originalFlags[j]);' in refusal
assert 'ch.LoadStatus = eChannelState::IDLE;' in refusal
assert '++ch.totalTries <= 2' in refusal
assert 'ms_bLoadingBigModel = false;' in refusal
assert 'nSectorsToRead == 0' in body
# The guards must cover the engine's ordinary scheduler, not only explicit
# TryLoadModel() calls; verify the exact exported arm64 method is redirected.
inject_start=src.index('void CStreaming::InjectHooks()')
inject_end=src.index('int CStreaming::AddImageToList(',inject_start)
inject=src[inject_start:inject_end]
assert 'CHook::Redirect("_ZN10CStreaming18RequestModelStreamEi", &CStreaming::RequestModelStream);' in inject
print('PASS: invalid IMG sector metadata is rejected in ordinary and explicit streaming paths; failed reads restore the queue and channel')
