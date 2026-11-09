"""Compile the repair from source and preserve every unrelated production entry."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
parser = argparse.ArgumentParser()
parser.add_argument('--base', type=Path, required=True)
parser.add_argument('--seed', type=Path, required=True)
parser.add_argument('--javac', default='javac')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
assert hashlib.sha256(args.base.read_bytes()).hexdigest() == '4fbefc9a7f79b32d827fce542553f6143b03b33cb68801eae46ac101cd3562fe', 'wrong production baseline'
output = root / 'target/book-json-repair/classes'
assert output.resolve().parent == (root / 'target/book-json-repair').resolve()
if output.exists():
    shutil.rmtree(output)
output.mkdir(parents=True)
names = {
    'main': ['util/ItemNbt.java', 'util/ItemTag.java'],
    'utils': ['util/Version.java', 'util/nbt/DataFixerUtil.java', 'util/nbt/NbtUtil.java',
              'util/nbt/NbtSerializer.java', 'util/nbt/LegacyItemFormat.java'],
}
sources = [root / module / 'src/main/java/su/nightexpress/nightcore' / name
           for module, files in names.items() for name in files]
classpath = [args.base.resolve(), args.seed / 'versions/26.2/shiroha-26.2.jar', *sorted((args.seed / 'libraries').rglob('*.jar'))]
arguments = ['--release', '21', '-g', '-encoding', 'UTF-8', '-proc:none', '-implicit:none',
             '-d', str(output), '-cp', os.pathsep.join(map(str, classpath))] + list(map(str, sources))
argfile = root / 'target/book-json-repair/javac.args'
argfile.write_text('\n'.join('"' + a.replace('\\', '/') + '"' for a in arguments), encoding='utf-8')
result = subprocess.run([args.javac, '@' + str(argfile)], capture_output=True)
(root / 'target/book-json-repair/build.log').write_bytes(result.stdout + result.stderr)
if result.returncode:
    print((result.stdout + result.stderr).decode('utf-8', errors='replace'))
    raise SystemExit(result.returncode)
destination = root / 'target/nightcore-2.16.1-foliafix-1.jar'
destination.parent.mkdir(exist_ok=True)
compiled = {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*.class')}
changes = []
with zipfile.ZipFile(args.base) as original, zipfile.ZipFile(destination, 'w') as rebuilt:
    for entry in original.infolist():
        before = original.read(entry.filename)
        after = compiled.pop(entry.filename, before)
        if before != after:
            changes.append(entry.filename)
        rebuilt.writestr(entry, after)
    for name, data in compiled.items():
        rebuilt.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        changes.append(name)
report = {'jar': str(destination), 'size': destination.stat().st_size,
          'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(), 'changed_entries': sorted(changes),
          'javac_exit': result.returncode, 'packaging': 'source-compiled repair, unchanged production entries retained'}
(root / 'target/book-json-repair/build.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(report, indent=2, ensure_ascii=False))
