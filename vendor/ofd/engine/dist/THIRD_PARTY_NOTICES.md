# Bundled dependency notices

The Java engine uses OFDRW 2.3.9 and Apache PDFBox 2.0.27. Dependency coordinates, SHA-256 hashes, and declared licenses are in `dependencies.json`; publisher license and notice files are retained under `licenses/`. No iText runtime jars are distributed or used.

Two OFDRW Apache-2.0 source classes are included with local changes: `CommonUtil.java` removes the unused iText color converter method; `FontLoader.java` removes the unused iText font loader methods and adds observable font substitution warnings. Their PDFBox/AWT conversion code remains from the fixed upstream version. Original source: https://github.com/ofdrw/ofdrw/tree/2.3.9/ofdrw-converter/src/main/java/org/ofdrw/converter

The unmodified converter dependency includes OFDRW's compatibility helpers under the com.itextpdf package; those helpers are OFDRW code and are not loaded by this engine. Its optional IText exporter is not invoked. The engine requires `ofd-engine.jar` before `lib/*` on a manually supplied classpath so the PDFBox-only shadow classes take precedence; `java -jar` configures this automatically.

Runtime dependencies are copied as published; upstream licenses apply. This bundle is not a complete JDK. The user supplies a compatible local Java runtime.
