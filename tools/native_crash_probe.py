"""Symbolicate only against an exact .text match; decode ARM64 without executing it."""
import argparse
import hashlib
import re
from pathlib import Path
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

class Image:
    def __init__(self, path):
        self.file = open(path, 'rb')
        self.elf = ELFFile(self.file)
        self.symbols = {}
        for name in ('.dynsym', '.symtab'):
            table = self.elf.get_section_by_name(name)
            if table:
                for s in table.iter_symbols():
                    if s['st_shndx'] != 'SHN_UNDEF' and s['st_info']['type'] == 'STT_FUNC' and s['st_size']:
                        self.symbols[s.name] = (s['st_value'], s['st_size'])
    def text_hash(self):
        return hashlib.sha256(self.elf.get_section_by_name('.text').data()).hexdigest()
    def data(self, address, size):
        for seg in self.elf.iter_segments():
            if seg['p_type'] == 'PT_LOAD' and seg['p_vaddr'] <= address < seg['p_vaddr']+seg['p_filesz']:
                self.file.seek(seg['p_offset']+address-seg['p_vaddr'])
                return self.file.read(min(size, seg['p_filesz']-(address-seg['p_vaddr'])))
        return b''
    def containing(self, address):
        return [(n,a,z) for n,(a,z) in self.symbols.items() if a <= address < a+z]
    def dump(self, name, start=None, length=None):
        addr,size = self.symbols[name]
        start = addr if start is None else start
        length = min(size, 1400) if length is None else length
        lines = [f'{name}: start={addr:#x} size={size:#x}']
        for i in Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm(self.data(start,length),start):
            lines.append(f'{i.address:x}: {i.mnemonic} {i.op_str}')
        return '\n'.join(lines)

def notice(text):
    print('::notice::'+text.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))

def main():
    p=argparse.ArgumentParser()
    p.add_argument('mode', choices=['crash','reference'])
    p.add_argument('library',type=Path)
    p.add_argument('--shipped',type=Path)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args(); img=Image(args.library); chunks=[]
    if args.mode=='crash':
        shipped=Image(args.shipped)
        if img.text_hash()!=shipped.text_hash(): raise SystemExit('Refusing symbolication: unstripped .text differs from shipped build-91')
        notice('Verified exact .text SHA256 against shipped build-91: '+img.text_hash())
        for target in (0x1e81b0,0x1edfe4,0x1e9fd8):
            matches=img.containing(target)
            if not matches: chunks.append(f'{target:#x}: no containing function'); continue
            name,addr,size=matches[0]
            start=max(addr,target-64)
            chunks.append(f'CRASH-7 {target:#x} => '+img.dump(name,start,min(160,addr+size-start)))
    else:
        names=[n for n in img.symbols if re.search(r'RLEDecompress|OS_FileRead|[Gg][Pp][Uu].*[Tt]exture|[Tt]exture.*[Gg][Pp][Uu]',n)]
        chunks.append('Candidate symbols:\n'+'\n'.join(f'{n} {img.symbols[n]}' for n in names))
        for n in names[:8]: chunks.append(img.dump(n))
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text('\n\n'.join(chunks))
    for chunk in chunks[:9]: notice(chunk[:3500])
if __name__=='__main__': main()
