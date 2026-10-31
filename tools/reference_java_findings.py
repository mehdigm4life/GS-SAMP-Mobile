"""Index loader/JNI/config locations, without treating decompilation as source truth."""
import re
from pathlib import Path
root = Path('analysis/jadx/sources')
patterns = re.compile(r'System\.load|loadLibrary|native\s+\w|https?://|\.so\b|\.apk\b|files\.json|setGpuTextureFormat|GL_EXT|GL_OES', re.I)
lines = ['# Decompiled loader/JNI index', '', 'Review candidates, not proven execution paths. URLs below omit queries/fragments.', '']
count = 0
for file in sorted(root.rglob('*.java'), key=lambda p: (not str(p.relative_to(root)).startswith('com/samp/'), str(p))):
    for number, line in enumerate(file.read_text(errors='replace').splitlines(), 1):
        if patterns.search(line):
            # Keep artifact output bounded and avoid propagating URL credentials/tokens.
            line = re.sub(r'(https?://[^\s"\?\#]+)[\?\#][^\s"]*', r'\1?<redacted>', line)
            lines.append(f'{file.relative_to(root)}:{number}: {line.strip()[:400]}')
            count += 1
            if count >= 1500: break
    if count >= 1500: break
Path('analysis/JAVA-INDEX.txt').write_text('\n'.join(lines)+'\n')
print(f'Indexed {count} candidate loader/JNI/config lines; see JAVA-INDEX.txt')
# A compact evidence excerpt is accessible via the Checks API when artifact
# downloads are blocked. Do not print the entire third-party decompilation.
selected = []
for line in lines:
    if any(token in line.lower() for token in ('com/samp/', 'com/nvidia/', 'gtasa', 'libsamp', 'scand', 'monetloader', 'system.loadlibrary', 'files.json')):
        selected.append(line)
        if len(selected) == 100: break
excerpt = '\n'.join(selected) or 'No matching loader evidence in the bounded index; inspect artifacts.'
for start in range(0, min(len(excerpt), 12000), 3000):
    part = 'Loader/JNI excerpt (partial):\n' + excerpt[start:start+3000]
    print('::notice::'+part.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
log = Path('analysis/jadx.log')
if log.exists():
    tail = '\n'.join(log.read_text(errors='replace').splitlines()[-25:])
    print('::notice::'+('JADX log tail:\n'+tail).replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
# GPU selector context is a focused compatibility comparison, not a port.
for file in (root/'com/samp/mobile/launcher/util/Util.java', root/'com/samp/mobile/game/SAMP.java'):
    if not file.exists(): continue
    source = file.read_text(errors='replace').splitlines()
    keep = set()
    for i, line in enumerate(source):
        if any(k in line for k in ('getStoredGpuType', 'setGpuTextureFormat(', 'getGpuType', 'gl_ext_texture_compression', 'gl_img_texture_compression')):
            keep.update(range(max(0, i-4), min(len(source), i+13)))
    text = '\n'.join(f'{i+1}: {source[i]}' for i in sorted(keep))[:2800]
    print('::notice::'+(str(file.relative_to(root))+' GPU context (partial):\n'+text).replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
for file in Path('analysis/native').glob('lib/*/libsamp.asm.txt'):
    source = file.read_text(errors='replace').splitlines()
    for i,line in enumerate(source):
        if 'setGpuTextureFormat' in line:
            text = str(file)+'\n'+'\n'.join(source[i:i+28])
            print('::notice::'+text.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
            break
