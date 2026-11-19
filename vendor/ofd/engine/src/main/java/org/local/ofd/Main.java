package org.local.ofd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.reader.model.StampAnnotEntity;
import org.ofdrw.core.signatures.appearance.StampAnnot;
import org.ofdrw.converter.ImageMaker;
import org.ofdrw.converter.FontLoader;
import org.ofdrw.converter.font.TrueTypeFont;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.converter.export.PDFExporterPDFBox;
import org.ofdrw.core.basicType.ST_Box;
import org.json.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Local conversion only. The two renderers are correlated; comparison is not independent proof. */
public final class Main {
    private static final double PT_PER_MM = 72.0 / 25.4;
    private static final int CHECK_DPI = 96;
    private static final int MAX_PAGE_PIXELS = 50_000_000;
    private static final java.util.List<String> warnings = new ArrayList<>();
    private static final java.util.List<String> fatalLogs = new ArrayList<>();
    private static final Map<Integer,Integer> sourceTextCounts=new HashMap<>();

    private static void configureLogging() {
        LoggerContext c = (LoggerContext) LoggerFactory.getILoggerFactory();
        c.reset();
        AppenderBase<ILoggingEvent> a = new AppenderBase<ILoggingEvent>() {
            protected void append(ILoggingEvent e) {
                if (!e.getLevel().isGreaterOrEqual(Level.WARN)) return;
                String m = e.getLoggerName() + ": " + e.getFormattedMessage();
                if (e.getThrowableProxy() != null) m += " [" + e.getThrowableProxy().getMessage() + "]";
                if (!warnings.contains(m)) warnings.add(m);
                if (e.getLevel().isGreaterOrEqual(Level.ERROR) || m.matches("(?s).*(无法渲染|绘制异常|缺字|不存在字符|No glyph|notdef|cannot render).*")) fatalLogs.add(m);
            }
        };
        a.setContext(c); a.start();
        ch.qos.logback.classic.Logger root = c.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.WARN); root.addAppender(a);
    }

    public static void main(String[] argv) {
        JSONObject report = new JSONObject();
        Path reportPath = null, temporary = null, output = null;
        boolean published = false;
        try {
            configureLogging();
            Map<String,String> args = new HashMap<>();
            Set<String> allowed = new HashSet<>(Arrays.asList("--input","--output","--mode","--dpi","--report","--preview-dir"));
            if (argv.length % 2 != 0) throw new IllegalArgumentException("Arguments must be --name value pairs");
            for (int i=0; i<argv.length; i+=2) {
                if (!allowed.contains(argv[i]) || args.put(argv[i],argv[i+1]) != null) throw new IllegalArgumentException("Unknown or duplicate argument: " + argv[i]);
            }
            Path input = required(args,"--input"); output = required(args,"--output");
            if (args.containsKey("--report")) reportPath = required(args,"--report");
            Path preview = args.containsKey("--preview-dir") ? required(args,"--preview-dir") : null;
            String mode = args.getOrDefault("--mode","auto");
            if (!Arrays.asList("auto","vector","image").contains(mode)) throw new IllegalArgumentException("Invalid mode");
            int dpi = Integer.parseInt(args.getOrDefault("--dpi","300"));
            if (dpi<72 || dpi>600) throw new IllegalArgumentException("DPI must be 72..600");
            if (!Files.isRegularFile(input)) throw new IOException("Input is not a regular file");
            ensureAbsent(output);
            if (reportPath != null) { ensureAbsent(reportPath); if (reportPath.equals(output)||reportPath.equals(input)) throw new IOException("Report path conflicts with input/output"); }
            if (preview != null && Files.exists(preview)) throw new IOException("Preview directory already exists: " + preview);
            Files.createDirectories(output.getParent());
            if (preview != null) Files.createDirectories(preview);
            temporary = Files.createTempFile(output.getParent(),".ofd-convert-",".pdf");
            report.put("engine","OFDRW 2.3.9 / PDFBox 2.0.27").put("input",input.toString()).put("output",output.toString()).put("mode_requested",mode).put("dpi",dpi).put("preview_dpi",CHECK_DPI);
            report.put("signature_validation_migrated",false).put("visual_verification","renderer_comparison_only");
            warnings.add("Renderer comparison uses OFDRW for source rendering; independent visual review is required for full fidelity.");
            configureCjkFonts();
            int declaredStamps = declaredStampCount(input);
            try (OFDReader reader = new OFDReader(input)) {
                validateImages(reader);
                validateTextGlyphs(reader,input);
                report.put("source_text_validation","unicode_glyphs_checked_explicit_subset_indices_require_visual_review");
                int n = reader.getNumberOfPages();
                if (n<1 || n>1000) throw new IOException("Unsupported page count: " + n);
                int decodedStamps = 0;
                for (StampAnnotEntity s : reader.getStampAnnots()) {
                    decodedStamps += s.getStampAnnots().size();
                    if (s.getImageByte()==null || s.getImageByte().length==0) throw new IOException("Stamp image missing or unsupported");
                }
                if (decodedStamps != declaredStamps) throw new IOException("Stamp decoding incomplete: declared " + declaredStamps + ", decoded " + decodedStamps);
                report.put("stamp_annotation_count",decodedStamps).put("stamp_decode_verified",true).put("stamp_visual_verification","manual_review_required");
                JSONArray sizes = new JSONArray();
                for (int i=0;i<n;i++) { ST_Box b=reader.getPageInfo(i+1).getSize(); checkSize(b,dpi); sizes.put(new JSONObject().put("page",i+1).put("width_mm",b.getWidth()).put("height_mm",b.getHeight())); }
                report.put("page_count",n);
                String used = mode.equals("image") ? "image" : "vector";
                JSONArray result;
                if (used.equals("vector")) {
                    try {
                        try (PDFExporterPDFBox exporter = new PDFExporterPDFBox(input,temporary)) { exporter.export(); }
                        failForLogs();
                        verifyVectorText(temporary);
                        result = verify(reader,temporary,sizes,null);
                        if (hasSevereDifference(result)) throw new IOException("Vector rendering differs materially from source rendering");
                    } catch (Exception | LinkageError e) {
                        if (!mode.equals("auto")) throw e;
                        warnings.add("Vector conversion fallback: " + e.getMessage());
                        fatalLogs.clear();
                        used="image"; makeImagePdf(reader,temporary,dpi); failForLogs();
                    }
                } else { makeImagePdf(reader,temporary,dpi); failForLogs(); }
                result = verify(reader,temporary,sizes,preview,used.equals("image") ? dpi : CHECK_DPI);
                failForLogs();
                report.put("pages",result).put("mode_used",used);
                if (hasSevereDifference(result,used.equals("image"))) throw new IOException("Output differs materially from source rendering");
                if (used.equals("image")) warnings.add("Image PDF: text is not directly searchable or copyable; original OFD attachments are not embedded.");
                report.put("mode_used",used).put("pages",result).put("structural_verified",true).put("vector_text_validation",used.equals("vector")?"TextCode_character_counts_checked":"not_applicable_image_mode");
            }
            report.put("warnings",new JSONArray(warnings.toArray())).put("status","success");
            // Files.move without REPLACE_EXISTING preserves another concurrently created output.
            Files.move(temporary,output); temporary=null; published=true;
            if (reportPath!=null) writeReport(reportPath,report);
            System.out.println(report.toString());
        } catch (Exception | LinkageError e) {
            if (temporary!=null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            if (published) try { Files.deleteIfExists(output); } catch (IOException ignored) {}
            report.put("status","failed").put("error",e.getClass().getSimpleName()+": "+e.getMessage()).put("warnings",new JSONArray(warnings.toArray()));
            if (reportPath!=null && !Files.exists(reportPath)) try { writeReport(reportPath,report); } catch (IOException ignored) {}
            System.out.println(report.toString()); System.exit(1);
        }
    }
    private static Path required(Map<String,String> args,String key) { String value=args.get(key); if(value==null||value.isEmpty()) throw new IllegalArgumentException("Missing "+key); return Paths.get(value).toAbsolutePath().normalize(); }
    private static void ensureAbsent(Path p) throws IOException { if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing to overwrite: "+p); }
    private static void writeReport(Path p,JSONObject r) throws IOException { Files.createDirectories(p.getParent()); Files.write(p,r.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW); }
    private static void failForLogs() throws IOException { if(!fatalLogs.isEmpty()) throw new IOException("Renderer reported content loss: "+fatalLogs.get(0)); }
    private static void checkSize(ST_Box b,int dpi) throws IOException { double w=b.getWidth(),h=b.getHeight(); if(!Double.isFinite(w)||!Double.isFinite(h)||w<=0||h<=0||w>2000||h>2000||Math.round(w*dpi/25.4)*Math.round(h*dpi/25.4)>MAX_PAGE_PIXELS) throw new IOException("Page size exceeds safe rendering limits"); }
    private static ImageMaker maker(OFDReader reader,int dpi) { ImageMaker m=new ImageMaker(reader,dpi/25.4); m.config.setStampOpacity(1f); m.config.setClearStampBackground(false); return m; }
    private static BufferedImage white(BufferedImage source) { BufferedImage rgb=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB); Graphics2D g=rgb.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,rgb.getWidth(),rgb.getHeight()); g.drawImage(source,0,0,null); g.dispose(); return rgb; }
    private static void makeImagePdf(OFDReader reader,Path path,int dpi) throws IOException {
        ImageMaker m=maker(reader,dpi);
        try(PDDocument pdf=new PDDocument()) {
            for(int i=0;i<reader.getNumberOfPages();i++) {
                ST_Box box=reader.getPageInfo(i+1).getSize();
                PDPage page=new PDPage(new PDRectangle((float)(box.getWidth()*PT_PER_MM),(float)(box.getHeight()*PT_PER_MM))); pdf.addPage(page);
                BufferedImage img=white(m.makePage(i)); failForLogs();
                try(PDPageContentStream stream=new PDPageContentStream(pdf,page)) { stream.drawImage(LosslessFactory.createFromImage(pdf,img),0,0,page.getMediaBox().getWidth(),page.getMediaBox().getHeight()); }
                img.flush();
            }
            pdf.save(path.toFile());
        }
    }
    private static JSONArray verify(OFDReader reader,Path path,JSONArray sizes,Path preview) throws IOException {
        return verify(reader,path,sizes,preview,CHECK_DPI);
    }
    private static JSONArray verify(OFDReader reader,Path path,JSONArray sizes,Path preview,int sourceDpi) throws IOException {
        JSONArray pages=new JSONArray(); ImageMaker m=maker(reader,sourceDpi);
        try(PDDocument pdf=PDDocument.load(path.toFile())) {
            if(pdf.getNumberOfPages()!=reader.getNumberOfPages()) throw new IOException("Page count changed");
            PDFRenderer renderer=new PDFRenderer(pdf);
            for(int i=0;i<pdf.getNumberOfPages();i++) {
                JSONObject p=sizes.getJSONObject(i); PDRectangle box=pdf.getPage(i).getMediaBox();
                if(Math.abs(box.getWidth()-p.getDouble("width_mm")*PT_PER_MM)>.05 || Math.abs(box.getHeight()-p.getDouble("height_mm")*PT_PER_MM)>.05) throw new IOException("Page dimensions changed at page "+(i+1));
                BufferedImage source=white(m.makePage(i)), target=renderer.renderImageWithDPI(i,CHECK_DPI);
                if(sourceDpi!=CHECK_DPI) {
                    // Lossless image output must contain the exact source raster.
                    BufferedImage embedded=null;
                    org.apache.pdfbox.pdmodel.PDResources resources=pdf.getPage(i).getResources();
                    for(org.apache.pdfbox.cos.COSName name:resources.getXObjectNames()) {
                        org.apache.pdfbox.pdmodel.graphics.PDXObject object=resources.getXObject(name);
                        if(object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                            if(embedded!=null) throw new IOException("Unexpected multiple page images");
                            embedded=((org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)object).getImage();
                        }
                    }
                    if(embedded==null || embedded.getWidth()!=source.getWidth() || embedded.getHeight()!=source.getHeight()) throw new IOException("Embedded page raster size changed");
                    for(int row=0;row<source.getHeight();row++) for(int col=0;col<source.getWidth();col++)
                        if((source.getRGB(col,row)&0xffffff)!=(embedded.getRGB(col,row)&0xffffff)) throw new IOException("Embedded page raster pixels changed");
                    p.put("lossless_raster_verified",true);

                    BufferedImage sampled=new BufferedImage(target.getWidth(),target.getHeight(),BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=sampled.createGraphics();
                    graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source,0,0,sampled.getWidth(),sampled.getHeight(),null);
                    graphics.dispose(); source.flush(); source=sampled;
                }
                JSONObject diff=difference(source,target);
                JSONArray stampDiffs=stampDifferences(reader,i+1,source,target);
                p.put("stamp_regions",stampDiffs);
                p.put("pdf_width_pt",box.getWidth()).put("pdf_height_pt",box.getHeight()).put("difference",diff);
                if(preview!=null) { String suffix=String.format(Locale.ROOT,"-%04d.png",i+1); ImageIO.write(source,"PNG",preview.resolve("source"+suffix).toFile()); ImageIO.write(target,"PNG",preview.resolve("pdf"+suffix).toFile()); }
                source.flush(); target.flush(); pages.put(p);
            }
        }
        return pages;
    }
    private static JSONObject difference(BufferedImage source,BufferedImage target) {
        int w=source.getWidth(),h=source.getHeight();
        BufferedImage aligned=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); Graphics2D g=aligned.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,w,h); g.drawImage(target,0,0,w,h,null); g.dispose();
        // Compare local coverage rather than DPI-dependent glyph edge pixels.
        // Retain the unsmoothed metric for diagnostics.
        long rawSum=0;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int a=source.getRGB(x,y),b=aligned.getRGB(x,y);
            for(int shift=0;shift<24;shift+=8) rawSum+=Math.abs(((a>>shift)&255)-((b>>shift)&255));
        }
        float[] weights=new float[9]; Arrays.fill(weights,1f/9f);
        java.awt.image.ConvolveOp blur=new java.awt.image.ConvolveOp(
            new java.awt.image.Kernel(3,3,weights),java.awt.image.ConvolveOp.EDGE_NO_OP,null);
        BufferedImage smoothSource=blur.filter(source,null), smoothTarget=blur.filter(aligned,null);
        long sum=0, ink=0, changed=0;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int a=smoothSource.getRGB(x,y),b=smoothTarget.getRGB(x,y); int max=0;
            for(int shift=0;shift<24;shift+=8) { int delta=Math.abs(((a>>shift)&255)-((b>>shift)&255)); sum+=delta; max=Math.max(max,delta); }
            if((a&0xffffff)<0xf0f0f0 || (b&0xffffff)<0xf0f0f0) { ink++; if(max>64) changed++; }
        }
        aligned.flush(); smoothSource.flush(); smoothTarget.flush();
        return new JSONObject().put("raw_mean_absolute_channel_error",rawSum/(double)(w*h*3L)).put("comparison_filter","3x3_box").put("mean_absolute_channel_error",sum/(double)(w*h*3L)).put("ink_difference_fraction",ink==0?0.0:changed/(double)ink).put("source_pixels",w+"x"+h).put("pdf_pixels",target.getWidth()+"x"+target.getHeight());
    }
    private static boolean hasSevereDifference(JSONArray pages) { return hasSevereDifference(pages,false); }
    private static boolean hasSevereDifference(JSONArray pages,boolean exactRaster) {
        for(int i=0;i<pages.length();i++) {
            JSONObject d=pages.getJSONObject(i).getJSONObject("difference");
            if(d.getDouble("ink_difference_fraction")>.35 || d.getDouble("mean_absolute_channel_error")>8) return true;
            JSONArray stamps=pages.getJSONObject(i).getJSONArray("stamp_regions");
            for(int j=0;j<stamps.length();j++) {
                JSONObject stamp=stamps.getJSONObject(j), sd=stamp.getJSONObject("difference");
                if(stamp.getLong("source_ink_pixels")>20 && stamp.getLong("pdf_ink_pixels")<stamp.getLong("source_ink_pixels")*.1) return true;
                if(sd.getDouble("ink_difference_fraction")>.35 || (!exactRaster && sd.getDouble("mean_absolute_channel_error")>8)) return true;
            }
        } return false;
    }
    private static long inkPixels(BufferedImage image) { long n=0; for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) { int c=image.getRGB(x,y); if(((c>>16)&255)<220||((c>>8)&255)<220||(c&255)<220) n++; } return n; }
    private static JSONArray stampDifferences(OFDReader reader,int page,BufferedImage source,BufferedImage target) throws IOException {
        JSONArray result=new JSONArray();
        BufferedImage aligned=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=aligned.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,aligned.getWidth(),aligned.getHeight()); g.drawImage(target,0,0,aligned.getWidth(),aligned.getHeight(),null); g.dispose();
        String id=reader.getPageInfo(page).getId().toString();
        for(StampAnnotEntity entity:reader.getStampAnnots()) for(StampAnnot stamp:entity.getStampAnnots()) {
            if(!id.equals(stamp.getPageRef().toString())) continue;
            ST_Box b=stamp.getBoundary();
            if(b==null) throw new IOException("Stamp has no boundary");
            int x=Math.max(0,(int)Math.floor(b.getTopLeftX()*CHECK_DPI/25.4)), y=Math.max(0,(int)Math.floor(b.getTopLeftY()*CHECK_DPI/25.4));
            int right=Math.min(source.getWidth(),(int)Math.ceil((b.getTopLeftX()+b.getWidth())*CHECK_DPI/25.4));
            int bottom=Math.min(source.getHeight(),(int)Math.ceil((b.getTopLeftY()+b.getHeight())*CHECK_DPI/25.4));
            if(right<=x||bottom<=y) throw new IOException("Stamp is outside page bounds");
            BufferedImage src=source.getSubimage(x,y,right-x,bottom-y), dst=aligned.getSubimage(x,y,right-x,bottom-y);
            long srcInk=inkPixels(src),dstInk=inkPixels(dst);
            if(srcInk==0) throw new IOException("Source stamp region is blank; visible stamp requires manual investigation");
            result.put(new JSONObject().put("id",stamp.getID()).put("boundary_mm",b.toString()).put("source_ink_pixels",srcInk).put("pdf_ink_pixels",dstInk).put("difference",difference(src,dst)).put("verification","renderer_comparison_only"));
        }
        aligned.flush(); return result;
    }
    private static void verifyVectorText(Path pdfPath) throws IOException {
        Map<Integer,Integer> pdfCounts=new HashMap<>();
        try(PDDocument pdf=PDDocument.load(pdfPath.toFile())) {
            String extracted=new PDFTextStripper().getText(pdf);
            extracted.codePoints().filter(cp->!Character.isWhitespace(cp)&&!Character.isISOControl(cp)).forEach(cp->pdfCounts.merge(cp,1,Integer::sum));
        }
        for(Map.Entry<Integer,Integer> entry:sourceTextCounts.entrySet()) {
            if(pdfCounts.getOrDefault(entry.getKey(),0)<entry.getValue()) throw new IOException("Vector text verification: missing U+"+Integer.toHexString(entry.getKey()).toUpperCase(Locale.ROOT)+" from original TextCode; refusing possible content loss");
        }
    }
    private static void validateImages(OFDReader reader) throws IOException {
        for(org.ofdrw.core.basicStructure.res.CT_MultiMedia media:reader.getResMgt().getMultiMedias()) {
            if(media.getType()!=null && media.getType().toString().equalsIgnoreCase("Image")) {
                String id=media.getID().toString();
                BufferedImage image=reader.getResMgt().getImage(id);
                if(image==null) throw new IOException("Missing or unsupported image resource: "+id+" ("+media.getMediaFile()+")");
                if(image.getWidth()<1||image.getHeight()<1) throw new IOException("Empty image resource: "+id);
            }
        }
    }
    private static void configureCjkFonts() {
        FontLoader fonts=FontLoader.getInstance();
        String song="/System/Library/Fonts/Supplemental/Songti.ttc";
        String kai="/Library/Fonts/AdobeKaitiStd-Regular.otf";
        if(Files.isRegularFile(Paths.get(song))) { fonts.addSystemFontMapping("宋体",song); fonts.addSystemFontMapping("SimSun",song); }
        if(Files.isRegularFile(Paths.get(kai))) { fonts.addSystemFontMapping("楷体",kai); fonts.addSystemFontMapping("KaiTi",kai); }
    }
    private static void validateTextGlyphs(OFDReader reader,Path input) throws Exception {
        DocumentBuilderFactory f=secureFactory();
        java.util.Map<String,TrueTypeFont> fonts=new HashMap<>();
        try(ZipFile zip=new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(entry.isDirectory()||!entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
                org.w3c.dom.Document xml;
                try(InputStream in=zip.getInputStream(entry)) { xml=f.newDocumentBuilder().parse(in); }
                org.w3c.dom.NodeList texts=xml.getElementsByTagNameNS("*","TextObject");
                for(int i=0;i<texts.getLength();i++) {
                    org.w3c.dom.Element text=(org.w3c.dom.Element)texts.item(i);
                    String fontId=text.getAttribute("Font");
                    CT_Font declared=reader.getResMgt().getFont(fontId);
                    if(declared==null) throw new IOException("Text references unknown font "+fontId);
                    TrueTypeFont font=fonts.get(fontId);
                    if(font==null) { font=FontLoader.getInstance().loadFontSimilar(reader.getResourceLocator(),declared).getFont(); if(font==null) throw new IOException("Text font cannot be loaded: "+fontId); fonts.put(fontId,font); }
                    // Explicit CGTransform glyph indices can reference a subset font with no Unicode cmap.
                    if(text.getElementsByTagNameNS("*","CGTransform").getLength()>0 && declared.getFontFile()!=null) continue;
                    org.apache.fontbox.ttf.CmapLookup cmap=font.getUnicodeCmapLookup();
                    org.w3c.dom.NodeList codes=text.getElementsByTagNameNS("*","TextCode");
                    for(int j=0;j<codes.getLength();j++) {
                        String value=codes.item(j).getTextContent();
                        for(int offset=0;offset<value.length();) {
                            int cp=value.codePointAt(offset); offset+=Character.charCount(cp);
                            if(Character.isWhitespace(cp)||Character.isISOControl(cp)) continue;
                            sourceTextCounts.merge(cp,1,Integer::sum);
                            if(cmap==null||cmap.getGlyphId(cp)==0) throw new IOException("Missing glyph U+"+Integer.toHexString(cp).toUpperCase(Locale.ROOT)+" in font "+declared.getFontName()+"; refusing content loss");
                            org.ofdrw.converter.font.GlyphData glyph=font.getGlyph(cmap.getGlyphId(cp));
                            if(glyph==null||glyph.getPath()==null||glyph.getPath().getBounds2D().isEmpty()) throw new IOException("Empty glyph U+"+Integer.toHexString(cp).toUpperCase(Locale.ROOT)+" in font "+declared.getFontName()+"; refusing content loss");
                        }
                    }
                }
            }
        }
    }
    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true); f.setFeature("http://xml.org/sax/features/external-general-entities",false); f.setFeature("http://xml.org/sax/features/external-parameter-entities",false); f.setXIncludeAware(false); f.setExpandEntityReferences(false); return f;
    }
    private static int declaredStampCount(Path input) throws Exception {
        DocumentBuilderFactory f=secureFactory();
        int count=0;
        try(ZipFile zip=new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) { ZipEntry e=entries.nextElement(); if(!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) try(InputStream in=zip.getInputStream(e)) { org.w3c.dom.Document xml=f.newDocumentBuilder().parse(in); if(e.getName().equalsIgnoreCase("OFD.xml") && xml.getElementsByTagNameNS("*","DocBody").getLength()!=1) throw new IOException("Multiple OFD documents are unsupported; refusing to omit any document"); count+=xml.getElementsByTagNameNS("*","StampAnnot").getLength(); } }
        }
        return count;
    }
}
