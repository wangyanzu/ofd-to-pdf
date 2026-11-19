# OFD 转 PDF Java 引擎

需要可执行的 JDK 11+。固定 OFDRW 2.3.9、PDFBox 2.0.27。不会上传文件；无需 Maven 或 ~/.m2 即可运行已经打包的 dist。

```sh
python3 engine/build.py --java-home /opt/homebrew/opt/openjdk@11
/opt/homebrew/opt/openjdk@11/bin/java -Djava.awt.headless=true -jar engine/dist/ofd-engine.jar \
  --input /absolute/input.ofd --output /absolute/output.pdf \
  --mode auto --dpi 300 --report /absolute/report.json \
  --preview-dir /absolute/new-preview-directory
```

--report 与 --preview-dir 可选。其他模式 vector 和 image 可直接选择。DPI 范围 72–600。构建默认 Maven 离线；缺公开依赖时可加 --online，转换始终离线。build.py 自检 java-home，macOS 优先使用可执行的 ARM Homebrew JDK 11。Windows 使用 python 构建入口需 sh 和 Maven；预打包 Java 引擎本身可跨平台运行。

CLI stdout 是 JSON，exit 0 成功，exit 1 失败。输出文件与报告均禁止覆盖；临时 PDF 校验后才移动到最终路径，失败不发布最终 PDF。预览目录必须不存在。source-0001.png 与 pdf-0001.png 等是所有页的 96 DPI 对照预览；image 模式实际按 --dpi 高清无损 PNG 封装，页序和毫米尺寸不变。

## 校验和兼容边界

- 校验单文档、页数、页面尺寸、原始文本 Unicode 字形是否存在、图像资源可解析、声明印章与成功解码章数一致；无法解码签章或缺字时拒绝转换。
- 将源文本 TextCode 字符数与矢量 PDF 提取文字比较，防止矢量导出悄悄缺中文字。嵌入子集字体通过显式 CGTransform 指定字形时需视觉复核，不能仅用 Unicode 验证。
- 对所有页做源渲染与 PDF 渲染差异指标；对每个 StampAnnot 区域单独比较，源章空白直接失败，章丢失或明显差异禁止当作成功的矢量结果。auto 可回退 image；image 也须通过比较。
- 宋体/楷体未嵌入时，macOS 可用 Songti.ttc / AdobeKaitiStd-Regular.otf 映射；明确记录字体替代，不能承诺和原字体完全一致。其他平台依据本地字体；无法绘制字符则安全拒绝。
- PDFBox 矢量路径对于部分系统 TTC 字体仍可能缺字，TextCode 检查会发现并回退图片 PDF。图片 PDF 保留可见内容，但文字不可直接搜索/复制，原 OFD 附件不会内嵌。
- 原始 sign-scale.ofd 使用的特殊 U+24E7 在本机宋体映射中缺失，应当失败，并保留原文件。特殊字体/字形支持是后续改进方向。
- 源和 PDF 对照均依赖 OFDRW；相关渲染器的共模缺陷仍可能漏检。结构校验、逐字检测与图像差异不等于独立视觉证明。报告标注 renderer_comparison_only、manual_review_required，技能必须查看预览核对内容、章位置和外观。
- 原 OFD 数字签名不会被迁移为有效 PDF 数字签名；只保留可见印章。未进行原签名真实性验签。

两个 Apache-2.0 OFDRW 工具源码做本地覆盖，移除原库中公共方法签名对 iText 的隐藏依赖，增加可观察字体替代警告。Class-Path 必须先 engine.jar 再 lib/*；java -jar 自动处理。没有打包 iText runtime jars。依赖坐标、hash、原 publisher pom、许可证和 NOTICE 在 dist 内保留。
