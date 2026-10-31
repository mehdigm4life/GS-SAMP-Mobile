"""Index loader/JNI/config locations, without treating decompilation as source truth."""
import re
from pathlib import Path
root = Path('analysis/jadx/sources')
patterns = re.compile(r'System\.load|loadLibrary|native\s+\w|https?://|\.so\b|\.apk\b|files\.json', re.I)
lines = ['# Decompiled loader/JNI index', '', 'Review candidates, not proven execution paths. URLs below omit queries/fragments.', '']
count = 0
for file in sorted(root.rglob('*.java')):
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
