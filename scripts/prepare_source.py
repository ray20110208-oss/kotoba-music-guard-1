#!/usr/bin/env python3
"""Materialize exactly the alpha5 archive plus tracked source overrides."""
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

root = Path(__file__).resolve().parent.parent
target = Path(sys.argv[1] if len(sys.argv) > 1 else 'work/project').resolve()
if target.exists():
    raise SystemExit(f'Refusing to overwrite existing directory: {target}')
parts = sorted(root.glob('Kotoba-Music-Guard-source-v0.2.5-alpha5.zip.part-*'))
if not parts:
    raise SystemExit('Missing alpha5 archive parts')
with tempfile.TemporaryFile() as archive:
    digest = hashlib.sha256()
    for part in parts:
        content = part.read_bytes()
        digest.update(content)
        archive.write(content)
    if digest.hexdigest() != 'df40c2a3b1f06c902168f996a10c900efb017c0bcc4bc8f35f4bdce69c1490d1':
        raise SystemExit('Source archive checksum mismatch')
    archive.seek(0)
    with zipfile.ZipFile(archive) as source:
        source.extractall(target)
shutil.copytree(root / 'source-overrides', target, dirs_exist_ok=True,
                ignore=shutil.ignore_patterns('node_modules', '.env', '.env.*'))
print(f'Prepared verified alpha5 + source-overrides: {target}')
