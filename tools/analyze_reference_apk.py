#!/usr/bin/env python3
"""Static-only APK/ELF inventory. Never load or execute APK native libraries."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path, PurePosixPath

MAX_ENTRY = 256 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024
FOCUS = ('RLEDecompress', 'CEntity6Render', 'RenderOneNonRoad', 'RpMaterialSetTexture',
         'RwFrameGetLTM', 'RwFrameUpdateObjects', 'GetAnimationBlock', 'BlendAnimation',
         'OS_FileRead', 'OS_FileSetPosition', 'ReadExisting', 'ProcessCommands',
         'AssignShader', 'C3dMarkers', 'JNI_OnLoad', 'setGpuTextureFormat')


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for data in iter(lambda: f.read(1024 * 1024), b''):
            h.update(data)
    return h.hexdigest()


def validate_entries(entries):
    total = 0
    seen = set()
    for entry in entries:
        name = PurePosixPath(entry.filename)
        if name.is_absolute() or '..' in name.parts or '\\' in entry.filename:
            raise ValueError('Unsafe archive path: ' + entry.filename)
        if entry.filename in seen:
            raise ValueError('Duplicate archive path: ' + entry.filename)
        seen.add(entry.filename)
        total += entry.file_size
        if entry.file_size > MAX_ENTRY or total > MAX_TOTAL:
            raise ValueError('Archive exceeds inspection size budget')
        if (entry.external_attr >> 16) & 0o170000 == 0o120000:
            raise ValueError('Archive symlinks are not accepted')


def elf_info(path, output):
    from elftools.elf.elffile import ELFFile
    from capstone import Cs, CS_ARCH_ARM, CS_ARCH_ARM64, CS_MODE_ARM, CS_MODE_THUMB
    result = {'sha256': sha(path), 'bytes': path.stat().st_size}
    with path.open('rb') as f:
        elf = ELFFile(f)
        result.update(bits=elf.elfclass, machine=elf['e_machine'], endian='little' if elf.little_endian else 'big')
        text = elf.get_section_by_name('.text')
        result['text_sha256'] = hashlib.sha256(text.data()).hexdigest() if text else None
        result['segments'] = [{k: s[k] for k in ('p_type', 'p_vaddr', 'p_offset', 'p_filesz', 'p_memsz', 'p_flags')}
                              for s in elf.iter_segments()]
        dynamic = elf.get_section_by_name('.dynamic')
        result['needed'] = []
        if dynamic:
            for tag in dynamic.iter_tags():
                if tag.entry.d_tag == 'DT_NEEDED': result['needed'].append(tag.needed)
                if tag.entry.d_tag == 'DT_SONAME': result['soname'] = tag.soname
        symbols = elf.get_section_by_name('.dynsym')
        rows = []
        if symbols:
            for s in symbols.iter_symbols():
                rows.append({'name': s.name, 'address': s['st_value'], 'size': s['st_size'],
                             'kind': s['st_info']['type'], 'undefined': s['st_shndx'] == 'SHN_UNDEF'})
        result['exports'] = sorted({s['name'] for s in rows if not s['undefined'] and s['name']})
        result['imports'] = sorted({s['name'] for s in rows if s['undefined'] and s['name']})
        result['has_symtab'] = elf.get_section_by_name('.symtab') is not None
        result['has_debug_info'] = elf.get_section_by_name('.debug_info') is not None
        for sec in elf.iter_sections():
            if sec['sh_type'] == 'SHT_NOTE':
                for note in sec.iter_notes():
                    if note['n_type'] == 'NT_GNU_BUILD_ID': result['build_id'] = note['n_desc']
        focus = [s for s in rows if not s['undefined'] and s['kind'] == 'STT_FUNC'
                 and any(token in s['name'] for token in FOCUS)]
        result['focus_symbols'] = focus
        lines = []
        for symbol in focus:
            addr = symbol['address']
            thumb = elf['e_machine'] == 'EM_ARM' and addr & 1
            start = addr & ~1 if thumb else addr
            size = min(symbol['size'], 2048)  # bounded excerpts, not complete function analysis
            for seg in elf.iter_segments():
                if seg['p_type'] == 'PT_LOAD' and seg['p_vaddr'] <= start < seg['p_vaddr'] + seg['p_filesz']:
                    size = min(size, seg['p_vaddr'] + seg['p_filesz'] - start)
                    f.seek(seg['p_offset'] + start - seg['p_vaddr'])
                    code = f.read(size)
                    break
            else: continue
            if elf['e_machine'] not in ('EM_ARM', 'EM_AARCH64'): continue
            decoder = Cs(CS_ARCH_ARM64 if elf.elfclass == 64 else CS_ARCH_ARM,
                         CS_MODE_THUMB if thumb else CS_MODE_ARM)
            lines.append('\n' + symbol['name'] + ' @ ' + hex(start) + ' (bounded excerpt)')
            lines += [f'{i.address:08x}: {i.mnemonic} {i.op_str}' for i in decoder.disasm(code, start)]
    output.with_suffix('.symbols.json').write_text(json.dumps(rows, indent=2))
    output.with_suffix('.asm.txt').write_text('\n'.join(lines))
    return result


def analyze(apk, project, out):
    out.mkdir(parents=True, exist_ok=True)
    extracted = out / 'native'
    extracted.mkdir(exist_ok=True)
    report = {'apk_sha256': sha(apk), 'apk_bytes': apk.stat().st_size, 'entries': [],
              'reference': {}, 'project': {}, 'comparisons': [], 'errors': []}
    with zipfile.ZipFile(apk) as z:
        validate_entries(z.infolist())
        for entry in z.infolist():
            report['entries'].append({'path': entry.filename, 'bytes': entry.file_size})
            if entry.filename.endswith('.so'):
                target = extracted / entry.filename
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(entry) as src, target.open('wb') as dst:
                    while data := src.read(1024 * 1024): dst.write(data)
                key = entry.filename
                try: report['reference'][key] = elf_info(target, target)
                except Exception as e: report['errors'].append(f'{key}: {type(e).__name__}: {e}')
    project_out = out / 'project-native'
    project_out.mkdir(exist_ok=True)
    for path in sorted((project / 'app/src/main/jniLibs').glob('*/*.so')):
        key = '/'.join(path.parts[-2:])
        target = project_out / key
        target.parent.mkdir(parents=True, exist_ok=True)
        try: report['project'][key] = elf_info(path, target)
        except Exception as e: report['errors'].append(f'project {key}: {type(e).__name__}: {e}')
    for name, ref in report['reference'].items():
        key = '/'.join(PurePosixPath(name).parts[-2:])
        local = report['project'].get(key)
        if not local: continue
        report['comparisons'].append({'library': key, 'identical_sha256': ref['sha256'] == local['sha256'],
                                      'same_machine': ref['machine'] == local['machine'],
                                      'identical_text': bool(ref['text_sha256']) and ref['text_sha256'] == local['text_sha256'],
                                      'focus_addresses_match': {x['name']: x['address'] == y['address'] for x in ref['focus_symbols'] for y in local['focus_symbols'] if x['name'] == y['name']},
                                      'reference_only_exports': sorted(set(ref['exports']) - set(local['exports'])),
                                      'project_only_exports': sorted(set(local['exports']) - set(ref['exports']))})
    (out / 'inventory.json').write_text(json.dumps(report, indent=2))
    lines = ['# Reference APK static analysis', '', f'APK SHA-256: `{report["apk_sha256"]}`', '',
             '## Packaged native libraries', '', '| APK path | Architecture | Size | SHA-256 |',
             '|---|---|---:|---|']
    for name, info in report['reference'].items():
        lines.append(f'| `{name}` | {info["machine"]}, {info["bits"]}-bit | {info["bytes"]} | `{info["sha256"]}` |')
    if not report['reference']: lines.append('\nNo parseable packaged .so libraries found. This does not prove the runtime has no native code.')
    lines += ['', '## Comparison with committed project libraries', '']
    for c in report['comparisons']:
        lines.append(f'- `{c["library"]}`: identical bytes={c["identical_sha256"]}; same machine={c["same_machine"]}; '
                     f'exports only in reference={len(c["reference_only_exports"])}; only in project={len(c["project_only_exports"])}.')
    if not report['comparisons']: lines.append('No matching ABI/basename pairs. Direct engine comparison is not yet possible.')
    nested = [e['path'] for e in report['entries'] if e['path'].lower().endswith(('.apk', '.zip', '.obb', '.xapk'))]
    lines += ['', '## Embedded packages (not recursively unpacked)', *['- `' + n + '`' for n in nested], '',
              '## Limits',
              '- Static inspection only; no APK or extracted library is executed.',
              '- A launcher may download the actual game. Packaged ABIs are not proof of runtime support on every phone.',
              '- JNI/engine symbols and assembly excerpts identify comparison targets, not verified ABI compatibility.',
              '- Decompiled code is approximate. Resources/native code may be protected or downloaded later.',
              '- No claim of full coverage, crash reproduction, or 100% stability.',
              '- Do not transplant third-party binaries/code without compatibility evidence and permission.', '', '## Inspection errors',
              *['- '+e for e in report['errors']]]
    (out / 'REPORT.md').write_text('\n'.join(lines)+'\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('apk', type=Path)
    p.add_argument('--project', type=Path, default=Path('.'))
    p.add_argument('--out', type=Path, default=Path('analysis'))
    args = p.parse_args()
    analyze(args.apk, args.project, args.out)
