# crash-6 / logcat-16

## Observations

crash-6 includes the seven old records from crash-5, then two new records.
The newest fatal contexts interleave line-for-line: the engine rendering thread
and GPU thread both wrote to the same crash.txt. Multiple records are not proof
of multiple independent root causes.

* GPU PC repeats at `libGLESv2_mtk.so` file offset 0x65e98.
* New PC `libsamp+0x1a2ca0`, LR `libGTASA+0x4f57d4`.
* A workflow-only commit (41d386b) rebuilt the unchanged native sources to
  resolve offsets BEFORE editing them. Run 41196320089 reports:
  `CEntity_Render_hook+0x80` for 0x1a2ca0.
  `CRenderer::RenderOneNonRoad+0xf4` is the engine caller.
* Older `libsamp+0x270904` resolves to
  `ReliabilityLayer::CreateInternalPacketFromBitStream+0xa4`; the caller at
  0x26fa98 is `HandleSocketReceiveFromConnectedPlayer+0x210`.
  This wider damage is not proof of a driver bug or one specific bad texture.

## Verified defects repaired

Review of streamed object lifetimes found:

1. CObject destructor accesses m_pEntity after destroy_object invalidates it.
   ASan reproduces a heap-use-after-free with the old destructor and a fake
   engine that releases the entity. Cache the model ID before destroying it.
2. Text storage allocated with new[] was released with delete. Use delete[].
3. Early constructor exits preceded material-array initialisation. Initialise
   those resources first and reject failed engine objects in the pool.
4. Material callbacks overwrote material->texture without acquiring its owned
   reference. Use engine RpMaterialSetTexture (export verified in libGTASA),
   preserving reference counts when objects/textures are replaced or destroyed.
5. Frame callbacks assumed every RwObject was an atomic; reject other types
   before reading geometry, and check optional geometry/material pointers.
6. Bound server object IDs before indexing the object pool.

Object-owned texture references are now released in destruction; geometry has
its own acquired references, so it does not retain a dangling pointer.

## Tests and reporting

* test_object_material_lifetime.py: extracts actual callbacks/destructor, uses
  fake RW/engine dependencies under ASan/UBSan, covers texture replacement,
  shared ownership, repeated draw, non-atomic objects and failed construction.
* test_crash_report_owner.py: 32 concurrent threads, exactly one report owner;
  owner reentry detected. Lock-free owner arbitration runs before any fatal
  report writes. Other fatal threads wait for process termination rather than
  corrupting the report or killing its writer mid-write. Reentry exits safely.
* Fatal records include TID. All diagnostics remain in GS-SAMP/crash.txt.
* Preserve exact unstripped native binaries as CI artifacts for future reports.

These tests verify defects, not a full reproduction of the device's spawn
crash. GPU/network corruption origin is not conclusively established. Do not
claim a final fix without device spawn/respawn testing. No UI toggles, native
control widgets, animation logic, cache downloader or RLE changes here.
