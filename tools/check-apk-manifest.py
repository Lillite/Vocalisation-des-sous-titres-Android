#!/usr/bin/env python3
"""Check the compiled manifest, including the exact parallel-test identity."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
if len(sys.argv) != 2:
    raise SystemExit('Usage: check-apk-manifest.py manifest.xml')
ns = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(Path(sys.argv[1])).getroot()
assert root.get('package') == 'fr.soustitresvoix.player.cinema', 'Unexpected application identity'
app = root.find('application')
assert app is not None and app.get(ns + 'allowBackup') == 'false', 'Backup must remain disabled'
permissions = {node.get(ns + 'name', '') for node in root if node.tag.startswith('uses-permission')}
forbidden = {'android.permission.INTERNET', 'android.permission.RECORD_AUDIO', 'android.permission.CAMERA',
    'android.permission.READ_CONTACTS', 'android.permission.WRITE_CONTACTS', 'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_MEDIA_VIDEO', 'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VISUAL_USER_SELECTED'}
assert not permissions.intersection(forbidden), f'Forbidden permissions: {permissions.intersection(forbidden)}'
assert root.find("queries/intent/action[@" + ns + "name='android.intent.action.TTS_SERVICE']") is not None
assert not any('TestTtsService' in s.get(ns + 'name', '') for s in app.findall('service')), 'Synthetic test engine must not ship'
print('PASS compiled APK: expected cinema package, no cloud backup, TTS service visible')
print('PASS no Internet, microphone, camera, contacts or broad media/storage access')
print('PASS synthetic test TTS service excluded from the user APK')
print('Actual permissions:', ', '.join(sorted(permissions)) or '(none)')
