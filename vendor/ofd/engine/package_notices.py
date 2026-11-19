#!/usr/bin/env python3
"""Keep licenses/notices included by the pinned dependency publishers."""
from pathlib import Path
from zipfile import ZipFile
import json
import hashlib
import shutil
import xml.etree.ElementTree as ET

root=Path(__file__).parent
lib=root/'dist/lib'
notices=root/'dist/licenses'
notices.mkdir(exist_ok=True)
coordinates=[]
repository=Path.home()/'.m2/repository'
cache_index={}
for artifact in repository.rglob('*.jar'):
    if artifact.name in {x.name for x in lib.glob('*.jar')}: cache_index[artifact.name]=artifact
ns={'m':'http://maven.apache.org/POM/4.0.0'}
for jar in sorted(lib.glob('*.jar')):
    item={'jar':jar.name,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
    with ZipFile(jar) as z:
        for name in z.namelist():
            base=name.rsplit('/',1)[-1].upper()
            if name.startswith('META-INF/') and ('LICENSE' in base or base.startswith('NOTICE')) and not name.endswith('/'):
                folder=notices/jar.stem
                folder.mkdir(exist_ok=True)
                # Some jars include more than one legal file with identical basename.
                (folder/name.replace('/','_')).write_bytes(z.read(name))
            if name.startswith('META-INF/maven/') and name.endswith('/pom.properties'):
                props=dict(line.split('=',1) for line in z.read(name).decode().splitlines() if '=' in line and not line.startswith('#'))
                if props.get('artifactId') and jar.name.startswith(props['artifactId']+'-'):
                    item['coordinates']=':'.join(props.get(k,'') for k in ('groupId','artifactId','version'))
                    pom=Path.home()/'.m2/repository'/props['groupId'].replace('.','/')/props['artifactId']/props['version']/(props['artifactId']+'-'+props['version']+'.pom')
                    if pom.exists():
                        tree=ET.parse(pom)
                        item['declared_licenses']=[{'name':x.findtext('m:name',default='',namespaces=ns),'url':x.findtext('m:url',default='',namespaces=ns)} for x in tree.findall('./m:licenses/m:license',ns)]
    if 'coordinates' not in item and jar.name in cache_index:
        artifact=cache_index[jar.name]
        parts=artifact.relative_to(repository).parts
        item['coordinates']='.'.join(parts[:-3])+':'+parts[-3]+':'+parts[-2]
    if jar.name in cache_index:
        pom=cache_index[jar.name].with_suffix('.pom')
        if pom.exists():
            folder=notices/jar.stem; folder.mkdir(exist_ok=True)
            shutil.copy2(pom,folder/'publisher-pom.xml')
    coordinates.append(item)
(root/'dist/dependencies.json').write_text(json.dumps(coordinates,ensure_ascii=False,indent=2)+'\n')
(root/'dist/THIRD_PARTY_NOTICES.md').write_text('''# Bundled dependency notices

The Java engine uses OFDRW 2.3.9 and Apache PDFBox 2.0.27. Dependency coordinates, SHA-256 hashes, and declared licenses are in `dependencies.json`; publisher license and notice files are retained under `licenses/`. No iText runtime jars are distributed or used.

Two OFDRW Apache-2.0 source classes are included with local changes: `CommonUtil.java` removes the unused iText color converter method; `FontLoader.java` removes the unused iText font loader methods and adds observable font substitution warnings. Their PDFBox/AWT conversion code remains from the fixed upstream version. Original source: https://github.com/ofdrw/ofdrw/tree/2.3.9/ofdrw-converter/src/main/java/org/ofdrw/converter

The unmodified converter dependency includes OFDRW's compatibility helpers under the com.itextpdf package; those helpers are OFDRW code and are not loaded by this engine. Its optional IText exporter is not invoked. The engine requires `ofd-engine.jar` before `lib/*` on a manually supplied classpath so the PDFBox-only shadow classes take precedence; `java -jar` configures this automatically.

Runtime dependencies are copied as published; upstream licenses apply. This bundle is not a complete JDK. The user supplies a compatible local Java runtime.
''')

shutil.copy2(root/'LICENSE-OFDRW',notices/'LICENSE-OFDRW-Apache-2.0.txt')
