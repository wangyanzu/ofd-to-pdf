#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVAC=javac
  JAR=jar
fi
# Copies only pinned runtime dependencies; iText is explicitly excluded in pom.xml.
# Offline by default. Set OFD_BUILD_ONLINE=1 only to resolve missing public dependencies.
if [ "${OFD_BUILD_ONLINE:-0}" = 1 ]; then
  mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=dist/lib
else
  mvn -o dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=dist/lib
fi
mkdir -p target/classes
"$JAVAC" --release 11 -encoding UTF-8 -cp 'dist/lib/*' -d target/classes src/main/java/org/local/ofd/Main.java src/main/java/org/ofdrw/converter/utils/CommonUtil.java src/main/java/org/ofdrw/converter/FontLoader.java
python3 - <<'PYMANIFEST'
from pathlib import Path
# jar handles continuation wrapping while reading this manifest.
p=Path('target/MANIFEST.MF')
classpath=' '.join('lib/'+x.name for x in sorted(Path('dist/lib').glob('*.jar')))
lines=['Manifest-Version: 1.0','Main-Class: org.local.ofd.Main']
line='Class-Path: '+classpath
while len(line)>70:
    lines.append(line[:70]); line=' '+line[70:]
lines.append(line)
p.write_text('\n'.join(lines)+'\n\n')
PYMANIFEST
"$JAR" --create --file dist/ofd-engine.jar --manifest target/MANIFEST.MF -C target/classes .
python3 - <<'PY'
from pathlib import Path
import hashlib,json
p=Path('dist')
manifest={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(p.rglob('*.jar'))}
(p/'checksums.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY

python3 package_notices.py
