import sys
import unittest
import zipfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from analyze_reference_apk import validate_entries, MAX_ENTRY

class ArchiveGuards(unittest.TestCase):
    def test_safe(self):
        validate_entries([zipfile.ZipInfo('lib/arm64-v8a/libGTASA.so')])
    def test_paths(self):
        for name in ('../escape.so', '/escape.so', 'lib/../../escape', 'lib\\escape'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_entries([zipfile.ZipInfo(name)])
    def test_duplicate(self):
        with self.assertRaises(ValueError): validate_entries([zipfile.ZipInfo('a'), zipfile.ZipInfo('a')])
    def test_large(self):
        item=zipfile.ZipInfo('a'); item.file_size=MAX_ENTRY+1
        with self.assertRaises(ValueError): validate_entries([item])
    def test_symlink(self):
        item=zipfile.ZipInfo('a'); item.external_attr=(0o120777 << 16)
        with self.assertRaises(ValueError): validate_entries([item])

if __name__ == '__main__': unittest.main()
