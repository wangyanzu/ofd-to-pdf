#!/usr/bin/env python3
"""Local OFD to PDF entrypoint. Standard-library-only; invokes bundled Java engine."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import zipfile

SKILL = Path(__file__).resolve().parents[1]
MAX_ENTRIES = 20000
MAX_BYTES = 1024 * 1024 * 1024
MAX_XML_BYTES = 64 * 1024 * 1024


def preflight(path):
    if not path.is_file() or path.suffix.lower() != '.ofd':
        raise ValueError('Input must be an existing .ofd file: ' + str(path))
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ENTRIES or sum(e.file_size for e in entries) > MAX_BYTES:
                raise ValueError('OFD archive exceeds supported size limits')
            names = set()
            for entry in entries:
                name = entry.filename
                posix = PurePosixPath(name)
                if ('\\' in name or posix.is_absolute() or '..' in posix.parts
                        or '\x00' in name or re.match(r'^[A-Za-z]:', name)):
                    raise ValueError('Unsafe archive path: ' + name)
                normalized = str(posix)
                if normalized in names:
                    raise ValueError('Duplicate archive path: ' + name)
                names.add(normalized)
                if entry.flag_bits & 1:
                    raise ValueError('Encrypted ZIP entries are unsupported')
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('Symlink in OFD archive is unsupported')
                if name.lower().endswith('.xml'):
                    if entry.file_size > MAX_XML_BYTES:
                        raise ValueError('OFD XML entry exceeds supported size limits')
                    content = archive.read(entry)
                    declarations = content.replace(b'\x00', b'').upper()
                    if b'<!DOCTYPE' in declarations or b'<!ENTITY' in declarations:
                        raise ValueError('XML DTD/entity declarations are unsupported')
            if 'OFD.xml' not in names:
                raise ValueError('OFD.xml is missing from archive root')
    except zipfile.BadZipFile as exc:
        raise ValueError('Invalid or damaged OFD ZIP archive') from exc


def java_runtime(explicit=None):
    candidates = []
    if explicit:
        candidates = [explicit]
    else:
        if os.environ.get('OFD_JAVA'):
            candidates.append(os.environ['OFD_JAVA'])
        if os.environ.get('JAVA_HOME'):
            candidates.append(str(Path(os.environ['JAVA_HOME']) / 'bin/java'))
        for home in ('/opt/homebrew/opt/openjdk@11', '/opt/homebrew/opt/openjdk',
                     '/usr/local/opt/openjdk@11', '/usr/local/opt/openjdk'):
            candidates.append(home + '/bin/java')
        if shutil.which('java'):
            candidates.append(shutil.which('java'))
        candidates.extend(str(p) for p in Path('/Library/Java/JavaVirtualMachines').glob('*/Contents/Home/bin/java'))
    for candidate in dict.fromkeys(candidates):
        try:
            result = subprocess.run([candidate, '-version'], capture_output=True, text=True, timeout=10)
            match = re.search(r'version\s+"(\d+)(?:\.(\d+))?', result.stderr + result.stdout)
            if result.returncode == 0 and match and int(match.group(1)) >= 11:
                return candidate
        except (OSError, subprocess.TimeoutExpired):
            pass
    raise ValueError('No working Java 11+ runtime found. Set OFD_JAVA to its java executable.')


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def convert(source, output, runtime, mode, dpi, timeout):
    preflight(source)
    if output.suffix.lower() != '.pdf':
        raise ValueError('Output must have a .pdf extension')
    report = output.with_suffix('.report.json')
    preview = output.with_suffix('.preview')
    log = output.with_suffix('.conversion.log')
    for target in (output, report, preview, log):
        if target.exists():
            raise ValueError('Refusing to overwrite existing output: ' + str(target))
    output.parent.mkdir(parents=True, exist_ok=True)
    engine = SKILL / 'engine/dist'
    if not (engine / 'ofd-engine.jar').is_file():
        raise ValueError('Bundled engine missing. Run: python3 ' + str(SKILL / 'engine/build.py'))
    original_digest = sha256(source)
    classpath = str(engine / 'ofd-engine.jar') + os.pathsep + str(engine / 'lib/*')
    command = [runtime, '-Djava.awt.headless=true', '-Xmx2g', '-cp', classpath,
               'org.local.ofd.Main', '--input', str(source), '--output', str(output),
               '--mode', mode, '--dpi', str(dpi), '--report', str(report),
               '--preview-dir', str(preview)]
    with log.open('x', encoding='utf-8') as stream:
        try:
            process = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            raise ValueError('Conversion timed out; inspect log: ' + str(log)) from exc
    if sha256(source) != original_digest:
        raise ValueError('Source OFD changed during conversion; result needs investigation')
    if process.returncode != 0:
        raise ValueError('Conversion failed; inspect log: ' + str(log))
    if not output.is_file() or not report.is_file():
        raise ValueError('Converter did not produce a PDF and report')
    with output.open('rb') as stream:
        if stream.read(5) != b'%PDF-':
            raise ValueError('Converter output is not a PDF')
    details = json.loads(report.read_text(encoding='utf-8'))
    return {'input': str(source), 'pdf': str(output), 'report': str(report),
            'previews': str(preview), 'log': str(log), 'source_sha256': original_digest,
            'conversion': details, 'visual_review_required': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', nargs='+', type=Path)
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument('--output', type=Path, help='PDF path (single input only)')
    destination.add_argument('--output-dir', type=Path, help='Default: ./output/ofd-to-pdf')
    parser.add_argument('--mode', choices=('auto', 'vector', 'image'), default='auto')
    parser.add_argument('--dpi', type=int, default=300)
    parser.add_argument('--java', help='Java 11+ executable')
    parser.add_argument('--timeout', type=int, default=300, help='Timeout per input in seconds')
    args = parser.parse_args()
    if args.output and len(args.inputs) != 1:
        parser.error('--output requires exactly one input')
    if not 72 <= args.dpi <= 600:
        parser.error('--dpi must be between 72 and 600')
    if args.timeout < 1:
        parser.error('--timeout must be positive')
    sources = [p.expanduser().resolve() for p in args.inputs]
    directory = (args.output_dir or Path.cwd() / 'output/ofd-to-pdf').expanduser().resolve()
    outputs = [args.output.expanduser().resolve()] if args.output else [directory / (p.stem + '.pdf') for p in sources]
    if len(set(outputs)) != len(outputs):
        parser.error('Inputs share the same output name; convert separately with --output')
    try:
        runtime = java_runtime(args.java)
    except ValueError as exc:
        print(json.dumps({'error': str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1
    failures = 0
    for source, output in zip(sources, outputs):
        try:
            result = convert(source, output, runtime, args.mode, args.dpi, args.timeout)
            print(json.dumps(result, ensure_ascii=False))
        except (ValueError, OSError, zipfile.LargeZipFile) as exc:
            failures += 1
            print(json.dumps({'input': str(source), 'error': str(exc)}, ensure_ascii=False), file=sys.stderr)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
