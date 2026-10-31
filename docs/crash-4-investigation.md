# crash-4 / logcat-14 investigation

## Evidence, not a claim of a universal fix

The report contains three crashes, not a single proven root cause:

* `libGTASA+0x31bf44`: `OS_FileSetPosition`, reading `[x0+8]` with a null
  file handle; caller `ZIPFile::ReadExisting+0x38` (`0x326c54`). This is
  during loading, before spawn. The matching log session reports missing
  archive files. Do not return fake success from file APIs or suppress SIGSEGV.
* An unknown module PC: cannot resolve it using the two module bases logged
  by the old reporter. It may be another native module; no attribution yet.
* `libGTASA+0x26f7b8`: `RwFrameUpdateObjects+4`. Caller `0x6e4b28` is in
  `CSpecialFX::Update`. Disassembly traces the frame through the active entry
  in `C3dMarkers::m_aMarkerArray`, its atomic at +0x50, and parent at +8.
  The non-null invalid frame suggests corrupted/stale state, but does not
  identify the write that caused it. Disabling all effects is not a repair.

## Concrete memory error repaired

SCM locals are int32_t, but ScriptCommand used uintptr_t* for both reference
loads and write-back. On arm64 this reads/writes eight bytes, including when
passed `uint32_t*` (`m_dwRaceCheckpointHandle`) or `float*`
(`FindGroundZForCoord`). This can corrupt adjacent fields/stack storage.
References now copy exactly four bytes, including float bit patterns, using
memcpy. Reference encoding capacity and string length checks were also fixed.
The float opcode tag and engine opcode dispatch have NOT been changed.

`tests/test_script_marshalling.py` compiles the real marshaller with a fake VM
under ASan/UBSan and checks a four-byte heap cell, adjacent canary, float output,
null references/strings, long strings, and a reference at the buffer boundary.
This proves the width/bounds fix, NOT that it accounts for every device crash.

## Evidence to retain if a device failure persists

Fatal crash.txt now includes arm64 registers and /proc/self/maps captured at
failure. These resolve driver/plugin PCs and identify the marker/frame values
without unsafe pointer walking in the signal handler. Output stays in
/storage/emulated/0/GS-SAMP/crash.txt. No app data is uploaded automatically.

Still required: real-device loading, spawn and repeated respawn validation;
resolve the missing-file read and unknown-module crash before claiming final
stability. Existing >>/<< behavior and native game controls are unchanged.
