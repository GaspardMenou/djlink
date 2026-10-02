#!/usr/bin/env python3
"""Build with the JDK and Python standard library. Dependencies stay in .tools/."""
import concurrent.futures
import hashlib
import os
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LIB = ROOT / '.tools/lib'
COORDS = [
    ('org.deepsymmetry', 'beat-link', '8.0.0'),
    ('org.deepsymmetry', 'electro', '0.1.4'),
    ('org.deepsymmetry', 'crate-digger', '0.2.1'),
    ('org.apache.commons', 'commons-math3', '3.6.1'),
    ('org.acplt.remotetea', 'remotetea-oncrpc', '1.1.4'),
    ('io.kaitai', 'kaitai-struct-runtime', '0.10'),
    ('io.github.willena', 'sqlite-jdbc', '3.49.0.0'),
    ('org.apiguardian', 'apiguardian-api', '1.1.2'),
    ('org.slf4j', 'slf4j-api', '1.7.36'),
    ('org.slf4j', 'slf4j-simple', '1.7.36'),
    ('com.google.code.gson', 'gson', '2.13.2'),
]


def fetch(group, artifact, version, classifier=''):
    name = f'{artifact}-{version}{classifier}.jar'
    path = LIB / name
    if path.exists():
        return path
    relative = f'{group.replace(".", "/")}/{artifact}/{version}/{name}'
    cached = ROOT / '.tools/m2' / relative
    if cached.exists():
        shutil.copyfile(cached, path)
        return path
    url = 'https://repo.maven.apache.org/maven2/' + relative
    print('Téléchargement :', name, flush=True)
    with urllib.request.urlopen(url + '.sha1', timeout=20) as response:
        expected = response.read().decode().strip().split()[0]
    with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=20) as response:
        size = int(response.headers['Content-Length'])

    def block(start):
        end = min(start + 262144, size) - 1
        for attempt in range(3):
            try:
                request = urllib.request.Request(url, headers={'Range': f'bytes={start}-{end}'})
                with urllib.request.urlopen(request, timeout=30) as response:
                    data = response.read()
                if len(data) != end - start + 1:
                    raise IOError('Réponse de téléchargement incomplète')
                return data
            except (OSError, IOError):
                if attempt == 2:
                    raise

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        data = b''.join(pool.map(block, range(0, size, 262144)))
    if hashlib.sha1(data).hexdigest() != expected:
        raise IOError(f'Empreinte invalide : {name}')
    path.write_bytes(data)
    return path


def build():
    LIB.mkdir(parents=True, exist_ok=True)
    dependencies = [fetch(*coordinate) for coordinate in COORDS]
    sources = fetch('org.deepsymmetry', 'beat-link', '8.0.0', '-sources')
    generated = ROOT / 'target/generated-sources'
    # Narrow compatibility fixes to the upstream EPL-2.0 sources, kept in the jar.
    patches = {
        'org/deepsymmetry/beatlink/VirtualCdj.java': (
            '            case CDJ_STATUS:',
            '            case DEVICE_REKORDBOX_LIGHTING_HELLO_BYTES:\n            case CDJ_STATUS:'),
        'org/deepsymmetry/beatlink/CdjStatus.java': (
            '        COLLECTION (4),',
            '        COLLECTION (4),\n        USB_2_SLOT (7),'),
        'org/deepsymmetry/beatlink/data/TimeFinder.java': (
            '                if (!lastPosition.precise) {',
            '                if (lastPosition == null || !lastPosition.precise) {'),
    }
    with zipfile.ZipFile(sources) as archive:
        for name, (before, after) in patches.items():
            source = archive.read(name).decode()
            if source.count(before) != 1:
                raise ValueError(f'Le correctif ne correspond plus à {name}')
            destination = generated / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(source.replace(before, after), encoding="utf-8")
    classes = ROOT / 'target/classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    java_files = list((ROOT / 'src/main/java').rglob('*.java')) + list(generated.rglob('*.java'))
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, dependencies)),
                    '-d', str(classes), *map(str, java_files)], check=True)
    shutil.copytree(ROOT / 'src/main/resources', classes, dirs_exist_ok=True)
    output = ROOT / 'target/djlink-1.0.0.jar'
    entries = {}
    for dependency in dependencies:
        with zipfile.ZipFile(dependency) as archive:
            for name in archive.namelist():
                if name.endswith('/') or name.endswith('module-info.class') or name.upper().endswith(('.SF', '.DSA', '.RSA')) or name == 'META-INF/MANIFEST.MF':
                    continue
                data = archive.read(name)
                if any(word in name.lower() for word in ("license", "notice", "copying")):
                    entries[f"META-INF/licenses/{dependency.stem}/{Path(name).name}"] = data
                if name.startswith('META-INF/services/') and name in entries:
                    data = entries[name] + b'\n' + data
                entries[name] = data
    for file in classes.rglob('*'):
        if file.is_file():
            entries[file.relative_to(classes).as_posix()] = file.read_bytes()
    for file in generated.rglob('*.java'):
        entries['META-INF/sources/' + file.relative_to(generated).as_posix()] = file.read_bytes()
    entries['META-INF/sources/beat-link-8.0.0-sources.jar'] = sources.read_bytes()
    for name in ('LICENSE', 'THIRD_PARTY.md'):
        entries['META-INF/' + name] = (ROOT / name).read_bytes()
    entries['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\nMain-Class: local.djlink.DjLink\n\n'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    print('Prêt :', output)


if __name__ == '__main__':
    build()
