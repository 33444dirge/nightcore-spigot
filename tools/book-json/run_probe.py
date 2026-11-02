"""Run the book regression in a copy of an existing disposable test server."""
import argparse
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--seed', type=Path, required=True)
parser.add_argument('--plugin', type=Path, required=True)
parser.add_argument('--combatpets', type=Path, required=True)
parser.add_argument('--with-combatpets', action='store_true')
parser.add_argument('--java', default='java')
parser.add_argument('--javac', default='javac')
parser.add_argument('--port', type=int, default=25971)
args = parser.parse_args()
seed = args.seed.resolve()
plugin = args.plugin.resolve()
pet_plugin = args.combatpets.resolve()
output = ROOT / 'target/book-json-test'
runtime = output / 'runtime'
if seed == runtime.resolve() or seed in runtime.resolve().parents or runtime.resolve() in seed.parents:
    parser.error('The seed must be separate from the test output directory')
for file in [seed / 'server.jar', seed / 'eula.txt', seed / 'versions/26.2/shiroha-26.2.jar', plugin, pet_plugin]:
    if not file.is_file():
        parser.error('Required file is missing: ' + str(file))
runtime.mkdir(parents=True, exist_ok=True)
for folder in ['libraries', 'versions', 'world']:
    if not (runtime / folder).exists():
        shutil.copytree(seed / folder, runtime / folder)
for file in ['server.jar', 'eula.txt']:
    if not (runtime / file).exists():
        shutil.copy2(seed / file, runtime / file)
(runtime / 'server.properties').write_text(
    f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nlevel-name=world\n'
    'view-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=2\n'
    'enable-query=false\nenable-rcon=false\n', encoding='utf-8')
plugins = runtime / 'plugins'
plugins.mkdir(exist_ok=True)
shutil.copy2(plugin, plugins / 'nightcore.jar')
nightcore = plugins / 'nightcore'
if not nightcore.exists():
    nightcore.mkdir()
    (nightcore / 'engine.yml').write_text('Plugin:\n  Language: en\nDatabase:\n  Type: SQLITE\n', encoding='utf-8')
if args.with_combatpets:
    shutil.copy2(pet_plugin, plugins / 'CombatPets.jar')
    pet_config = plugins / 'CombatPets'
    if not pet_config.exists():
        for file in (seed / 'plugins/CombatPets').rglob('*.yml'):
            relative = file.relative_to(seed / 'plugins/CombatPets')
            if relative.as_posix() == 'engine.yml':
                continue
            destination = pet_config / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, destination)
        pet_config.mkdir(exist_ok=True)
        (pet_config / 'engine.yml').write_text('Database:\n  Type: SQLITE\n', encoding='utf-8')
elif (plugins / 'CombatPets.jar').exists():
    parser.error('Use a fresh output directory when changing CombatPets test mode')
classes = output / 'probe-classes'
classes.mkdir(exist_ok=True)
classpath = [seed / 'versions/26.2/shiroha-26.2.jar', plugin, pet_plugin,
             *sorted((seed / 'libraries').rglob('*.jar'))]
arguments = ['-encoding', 'UTF-8', '-proc:none', '-d', str(classes), '-cp',
             os.pathsep.join(map(str, classpath)), str(Path(__file__).with_name('BookVersionProbe.java'))]
argfile = output / 'javac.args'
argfile.write_text('\n'.join('"' + a.replace('\\', '/') + '"' for a in arguments), encoding='utf-8')
compilation = subprocess.run([args.javac, '@' + str(argfile)], capture_output=True)
(output / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
if compilation.returncode:
    print((compilation.stdout + compilation.stderr).decode('utf-8', errors='replace'))
    raise SystemExit(compilation.returncode)
with zipfile.ZipFile(plugins / 'BookVersionProbe.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in classes.rglob('*.class'):
        archive.write(file, file.relative_to(classes).as_posix())
    archive.writestr('plugin.yml', 'name: BookVersionProbe\nversion: 1\nmain: audit.BookVersionProbe\n'
                     'api-version: \'1.21\'\nfolia-supported: true\ndepend: [nightcore]\nsoftdepend: [CombatPets]\n')
results = plugins / 'BookVersionProbe/results.json'
if results.exists():
    results.unlink()  # A previous run must never make a failed startup appear green.
with (runtime / 'console.log').open('wb') as log:
    process = subprocess.Popen([args.java, '-Xms256M', '-Xmx1536M', '-XX:ActiveProcessorCount=4',
                                '--enable-native-access=ALL-UNNAMED', '-jar', 'server.jar', '--nogui'],
                               cwd=runtime, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    try:
        code = process.wait(timeout=180)
    except subprocess.TimeoutExpired:
        process.stdin.write(b'stop\n')
        process.stdin.flush()
        process.wait(timeout=60)
        raise SystemExit('Probe timed out; requested normal stop')
print('server exit', code)
if not results.exists():
    print((runtime / 'console.log').read_text(encoding='utf-8', errors='replace')[-10000:])
    raise SystemExit('Probe did not produce fresh results')
report = json.loads(results.read_text(encoding='utf-8'))
print(json.dumps(report, ensure_ascii=False, indent=2))
expected = 22 if args.with_combatpets else 20
raise SystemExit(0 if code == 0 and report['failed'] == 0 and report['total'] == expected else 1)
