#!/usr/bin/env python3
"""Inspect the compiled cinema APK, not just its source manifest."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
if len(sys.argv) != 2:
    raise SystemExit('Usage: check-apk-manifest.py manifest.xml')
ns = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(Path(sys.argv[1])).getroot()
assert root.get('package') == 'fr.soustitresvoix.player.translation', 'Unexpected application identity'
assert root.get(ns + 'versionCode') == '8' and root.get(ns + 'versionName') == '0.5.1', 'Unexpected version'
app = root.find('application')
assert app is not None and app.get(ns + 'allowBackup') == 'false', 'Backup must remain disabled'
assert app.get(ns + 'usesCleartextTraffic') == 'false', 'Cleartext network traffic must remain disabled'
permissions = {node.get(ns + 'name', '') for node in root if node.tag.startswith('uses-permission')}
forbidden = {
    'android.permission.RECORD_AUDIO',
    'android.permission.CAMERA', 'android.permission.READ_CONTACTS',
    'android.permission.WRITE_CONTACTS', 'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VISUAL_USER_SELECTED',
}
assert not permissions.intersection(forbidden), f'Forbidden permissions: {permissions.intersection(forbidden)}'
assert 'android.permission.INTERNET' in permissions, 'Explicit translation requires Internet permission'
assert root.find("queries/intent/action[@" + ns + "name='android.intent.action.TTS_SERVICE']") is not None
assert not any('Fixture' in x.get(ns + 'name', '') for x in app), 'Test service leaked into production APK'
print('PASS compiled cinema APK: identity, backup disabled, TTS query, no test service')
print('PASS translation Internet permission, HTTPS only, no microphone, camera, contacts or broad storage permission')
print('Actual permissions:', ', '.join(sorted(permissions)) or '(none)')
print('NOTE offline speech engines are external applications.')
