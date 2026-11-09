"""Compile the child lifecycle repair over the verified book JSON repair release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', type=Path, required=True)
parser.add_argument('--seed', type=Path, required=True)
parser.add_argument('--javac', default='javac')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
expected_base = '0dbf807563c9f7f50eee684c5345d67c4b24b9acee8131ea01ee9f01a364ed37'
if hashlib.sha256(args.base.read_bytes()).hexdigest() != expected_base:
    parser.error('The base must be the verified nightcore-2.16.1-foliafix-1.jar release')
build = root / 'target/child-lifecycle-repair'
classes = build / 'classes'
if classes.resolve().parent != build.resolve():
    raise RuntimeError('Unexpected compiler output directory')
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)
sources = [root / 'main/src/main/java/su/nightexpress/nightcore' / name
           for name in ['NightPlugin.java', 'NightCore.java', 'util/CommandUtil.java']]
classpath = [args.base.resolve(), args.seed / 'versions/26.2/shiroha-26.2.jar',
             *sorted((args.seed / 'libraries').rglob('*.jar'))]
arguments = ['--release', '21', '-g', '-encoding', 'UTF-8', '-proc:none', '-implicit:none',
             '-d', str(classes), '-cp', os.pathsep.join(map(str, classpath)), *map(str, sources)]
argfile = build / 'javac.args'
argfile.write_text('\n'.join('"' + arg.replace('\\', '/') + '"' for arg in arguments), encoding='utf-8')
compiled = subprocess.run([args.javac, '@' + str(argfile)], capture_output=True)
(build / 'build.log').write_bytes(compiled.stdout + compiled.stderr)
if compiled.returncode:
    print((compiled.stdout + compiled.stderr).decode('utf-8', errors='replace'))
    raise SystemExit(compiled.returncode)
replacements = {file.relative_to(classes).as_posix(): file.read_bytes() for file in classes.rglob('*.class')}
expected_classes = {'su/nightexpress/nightcore/NightPlugin.class', 'su/nightexpress/nightcore/NightCore.class',
                    'su/nightexpress/nightcore/util/CommandUtil.class'}
if set(replacements) != expected_classes:
    raise RuntimeError('Unexpected compiled classes')
destination = root / 'target/nightcore-2.16.1-foliafix-2.jar'
changes = []
with zipfile.ZipFile(args.base) as original, zipfile.ZipFile(destination, 'w') as repaired:
    for entry in original.infolist():
        before = original.read(entry.filename)
        after = replacements.get(entry.filename, before)
        if before != after:
            changes.append(entry.filename)
        repaired.writestr(entry, after)
if set(changes) != expected_classes:
    raise RuntimeError('Unexpected archive changes')
report = {'jar': str(destination), 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
          'bytes': destination.stat().st_size, 'changed_entries': changes, 'javac_exit': compiled.returncode}
(build / 'build.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
