#!/usr/bin/env python3
"""Regression checks for packaged DSP integrity, not an NPU execution claim."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('hexagon_build', Path(__file__).with_name('prepare-hexagon.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackagedDspIntegrity(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.root = Path(self.work.name)
        self.directory = self.root / 'hexagon'
        self.directory.mkdir()
        header = bytearray(64)
        header[:7] = b'\x7fELF\x01\x01\x01'
        struct.pack_into('<HH', header, 16, 3, 164)
        for index, name in enumerate(module.NAMES):
            value = bytearray(header)
            value[40] = index
            (self.directory / name).write_bytes(value)
        self.manifest = {'version': 1, 'sourceRevision': module.SOURCE_REVISION,
                         'files': [module.verify_skel(self.directory / name) for name in module.NAMES]}
        self.write_manifest()

    def tearDown(self):
        self.work.cleanup()

    def write_manifest(self):
        (self.directory / 'manifest.json').write_text(json.dumps(self.manifest))

    def test_complete_architecture_set_and_hashes(self):
        self.assertEqual(module.verify_assets(self.root), self.manifest)

    def test_corrupt_library_is_rejected(self):
        with (self.directory / module.NAMES[0]).open('ab') as stream:
            stream.write(b'corruption')
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_cross_copied_architecture_is_rejected(self):
        (self.directory / module.NAMES[0]).write_bytes((self.directory / module.NAMES[1]).read_bytes())
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_arm64_elf_cannot_masquerade_as_dsp(self):
        file = self.directory / module.NAMES[0]
        header = bytearray(file.read_bytes())
        struct.pack_into('<H', header, 18, 183)
        file.write_bytes(header)
        with self.assertRaises(ValueError):
            module.verify_skel(file)

    def test_symlink_library_is_rejected(self):
        file = self.directory / module.NAMES[0]
        file.unlink()
        file.symlink_to(self.directory / module.NAMES[1])
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_missing_architecture_is_rejected(self):
        self.manifest['files'].pop()
        self.write_manifest()
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_wrong_source_revision_is_rejected(self):
        self.manifest['sourceRevision'] = '0' * 40
        self.write_manifest()
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_unexpected_asset_is_rejected(self):
        (self.directory / 'libcdsprpc.so').write_bytes(b'not a packaged driver')
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_boolean_version_is_rejected(self):
        self.manifest['version'] = True
        self.write_manifest()
        with self.assertRaises(ValueError):
            module.verify_assets(self.root)

    def test_changed_cmake_anchor_is_refused(self):
        file = self.root / 'CMakeLists.txt'
        file.write_text('# unexpected upstream configuration\n')
        patch = Path(__file__).resolve().parents[1] / 'app/src/main/cpp/prepare_hexagon_cmake.py'
        result = subprocess.run([sys.executable, str(patch), str(file)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(file.read_text(), '# unexpected upstream configuration\n')

    def test_cmake_adaptation_is_idempotent(self):
        file = self.root / 'CMakeLists.txt'
        fixture = Path(__file__).with_name('fixtures') / 'hexagon-cmake-8ca4060b.txt'
        original = fixture.read_text()
        prefix = original[:original.index('# Build HTP skels\n')]
        file.write_text(original)
        patch = Path(__file__).resolve().parents[1] / 'app/src/main/cpp/prepare_hexagon_cmake.py'
        subprocess.run([sys.executable, str(patch), str(file)], check=True)
        adapted = file.read_text()
        subprocess.run([sys.executable, str(patch), str(file)], check=True)
        self.assertEqual(file.read_text(), adapted)
        self.assertTrue(adapted.startswith(prefix))

    def test_previously_adapted_cmake_tampering_is_rejected(self):
        file = self.root / 'CMakeLists.txt'
        fixture = Path(__file__).with_name('fixtures') / 'hexagon-cmake-8ca4060b.txt'
        file.write_bytes(fixture.read_bytes())
        patch = Path(__file__).resolve().parents[1] / 'app/src/main/cpp/prepare_hexagon_cmake.py'
        subprocess.run([sys.executable, str(patch), str(file)], check=True)
        changed = file.read_text().replace('htp_iface_stub.c', 'unexpected_stub.c')
        file.write_text(changed)
        result = subprocess.run([sys.executable, str(patch), str(file)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(file.read_text(), changed)


if __name__ == '__main__':
    unittest.main()
