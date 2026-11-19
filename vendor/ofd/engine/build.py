#!/usr/bin/env python3
"""Rebuild the pinned Java engine and runtime dependency bundle."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--java-home', type=Path)
parser.add_argument('--online', action='store_true', help='Allow Maven public dependency resolution; default is offline')
args = parser.parse_args()
candidates = [args.java_home, Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None,
              Path('/opt/homebrew/opt/openjdk@11'), Path('/opt/homebrew/opt/openjdk')]
java_home = None
for home in candidates:
    if home is None:
        continue
    if not all((home/'bin'/name).is_file() for name in ('java','javac','jar')):
        if home == args.java_home:
            parser.error('--java-home must contain bin/java, bin/javac, and bin/jar')
        continue
    try:
        subprocess.run([str(home/'bin/java'), '-version'], check=True, capture_output=True)
        java_home = home
        break
    except (OSError, subprocess.CalledProcessError):
        if home == args.java_home:
            parser.error('The requested Java runtime cannot execute on this machine')
if java_home is None and not all(shutil.which(x) for x in ('java','javac','jar')):
    parser.error('A runnable JDK 11 or newer and Maven are required')
env = os.environ.copy()
if java_home:
    env['JAVA_HOME'] = str(java_home)
    env['PATH'] = str(java_home/'bin') + os.pathsep + env.get('PATH','')
if args.online:
    env['OFD_BUILD_ONLINE'] = '1'
subprocess.run(['sh', str(Path(__file__).with_name('build.sh'))], check=True, env=env)
