package ru.omega;

import com.github.jaiimageio.plugins.tiff.BaselineTIFFTagSet;
import com.github.jaiimageio.plugins.tiff.TIFFDirectory;
import com.github.jaiimageio.plugins.tiff.TIFFField;
import com.github.jaiimageio.plugins.tiff.TIFFTag;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.ochafik.lang.jnaerator.runtime.NativeSize;
import net.sourceforge.lept4j.Leptonica1;
import net.sourceforge.lept4j.Pix;
import net.sourceforge.tess4j.ITessAPI.*;
import net.sourceforge.tess4j.TessAPI1;
import net.sourceforge.tess4j.util.ImageHelper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.PointerByReference;

public class ExtractBoxedText {

    private static String filePathImage;
    private static final String fileNameTxt = "temp";
    private static String outputPath;
    private static String filePathPDF;
    private static StringBuilder cmapString;
    private static final HashMap<String, Double> symbolConfidenceHashMap = new HashMap<>();
    private static String tessDataPath;

    public static void main(String[] args) {
        if (args.length > 0) {

            try {

                String extension = FilenameUtils.getExtension(args[0]);
                outputPath = args[0].substring(0, args[0].lastIndexOf("\\")+1);
                if (extension.equals("pdf")) {
                    //Converting PDF to Tiff for Tesseract
                    filePathPDF = args[0];
                    convertPdfToTiff();
                } else {
                    filePathImage = args[0];
                }

                //Extracting tessdata to temp directory
                extractTessdata();

                //Converting file (tiff, jpg, png, etc.) to searchable PDF with Tesseract
                convertFileToSearchablePDF();

                //Creating symbol's confidence Map
                getSymbolsConfidenceMap();

                //Removing errors with xref
                uncompressXRef(filePathPDF,filePathPDF.replace(".pdf","_searchable.pdf"));

                //Adding CMap to PDF for unicode
                addCMapToPdf();

                //Creating and writing data to XML
                createAndWriteDataToXml();

                new File(filePathPDF).deleteOnExit();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void extractTessdata() throws IOException, URISyntaxException {

        final URL url = ExtractBoxedText.class.getResource("/tessdata");
        String s = url.getPath();
        final Path workingDirPath = Files.createTempDirectory("tessdata");
        final File tempDirectory = workingDirPath.toFile();
        tessDataPath = tempDirectory.toString() + "\\tessdata\\";

        if (s.contains("jar!")) {
            // we have a jar file
            // format: file:/location...jar!...path-in-the-jar
            // we only want to have location
            int excl = s.lastIndexOf("!");
            s = s.substring(0, excl);
            s = s.substring("file:/".length());

            try (JarFile jf = new JarFile(s)){
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    String name = je.getName();
                    if (name.contains("tessdata")) {
                        if (je.isDirectory()) {
                            // directory found

                            Path dir = workingDirPath.resolve(name);
                            Files.createDirectory(dir);
                        } else {
                            Path file = workingDirPath.resolve(name);
                            try (InputStream is = jf.getInputStream(je)) {
                                Files.copy(is, file, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
            }
        } else {
            // debug mode: no jar
            if (url != null) {
                try {
                    final File apps = new File(url.toURI());

                    for (File app : apps.listFiles()) {
                        FileUtils.copyToDirectory(app, new File(tessDataPath));//tempDirectory);
                    }
                } catch (URISyntaxException e) {

                }
            }
        }
        makeVolatile(tempDirectory);
    }

    private static void makeVolatile(File folder) {
        folder.deleteOnExit();
        try {
            for (File f : folder.listFiles()) {
                if (f.isDirectory())
                    makeVolatile(f);
                else
                    f.deleteOnExit();
            }
        } catch (NullPointerException e) {

        }
    }

    private static void uncompressXRef(String src, String dest) throws IOException, DocumentException {
        PdfReader reader = new PdfReader(src);
        PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(dest));
        stamper.close();
        reader.close();
        new File(filePathPDF).deleteOnExit();
        filePathPDF = dest;
    }

    private static void convertPdfToTiff() throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        File tiff = new File(outputPath + "image.tif");
        try (ImageOutputStream output = ImageIO.createImageOutputStream(tiff);
             PDDocument doc = PDDocument.load(new File(filePathPDF))) {

            ImageIO.scanForPlugins();
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            BufferedImage[] images = new BufferedImage[pageCount];
            for (int i = 0; i < pageCount; ++i) {
                images[i] = renderer.renderImageWithDPI(i, 400, ImageType.RGB);
            }

            writer.setOutput(output);

            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_DISABLED);

            writer.prepareWriteSequence(null);

            for (BufferedImage imageBuf : images) {
                writer.writeToSequence(new IIOImage(imageBuf, null, createMetadata(writer, params)), params);
            }

            writer.endWriteSequence();
        }
        writer.dispose();

        tiff.deleteOnExit();
        filePathImage = tiff.getPath();

    }

    private static IIOMetadata createMetadata(ImageWriter writer, ImageWriteParam writerParams) throws
            IIOInvalidTreeException
    {
        // Get default metadata from writer
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_GRAY);
        IIOMetadata meta = writer.getDefaultImageMetadata(type, writerParams);

        // Convert default metadata to TIFF metadata
        TIFFDirectory dir = TIFFDirectory.createFromMetadata(meta);

        // Get {X,Y} resolution tags
        BaselineTIFFTagSet base = BaselineTIFFTagSet.getInstance();
        TIFFTag tagXRes = base.getTag(BaselineTIFFTagSet.TAG_X_RESOLUTION);
        TIFFTag tagYRes = base.getTag(BaselineTIFFTagSet.TAG_Y_RESOLUTION);

        // Create {X,Y} resolution fields
        TIFFField fieldXRes = new TIFFField(tagXRes, TIFFTag.TIFF_RATIONAL, 1, new long[][] { {400, 1 } });
        TIFFField fieldYRes = new TIFFField(tagYRes, TIFFTag.TIFF_RATIONAL, 1, new long[][] { {400, 1 } });

        // Add {X,Y} resolution fields to TIFFDirectory
        dir.addTIFFField(fieldXRes);
        dir.addTIFFField(fieldYRes);

        // Add unit field to TIFFDirectory (change to RESOLUTION_UNIT_CENTIMETER if necessary)
        dir.addTIFFField(new TIFFField(base.getTag(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT), BaselineTIFFTagSet.RESOLUTION_UNIT_INCH));

        // Return TIFF metadata so it can be picked up by the IIOImage
        return dir.getAsMetadata();
    }

    //TODO not used
    private static void changeFilesProperties() throws IOException {

        TessBaseAPI handle = TessAPI1.TessBaseAPICreate();
        String datapath = tessDataPath;
        String language = "osd";
        String image = filePathImage;
        int set_only_init_params = TessAPI1.FALSE;
        int oem = TessOcrEngineMode.OEM_DEFAULT;
        PointerByReference configs = null;
        int configs_size = 0;

        String[] params = {"load_system_dawg",
                "load_freq_dawg",
                "user_defined_dpi",
                "language_model_penalty_non_freq_dict_word",
                "language_model_penalty_non_dict_word",
                "textord_tabfind_find_tables",
                "textord_tablefind_recognize_tables"};
        String[] vals = {"F","F","400","1","1","T","T"};
        PointerByReference vars_vec = new PointerByReference();
        vars_vec.setPointer(new StringArray(params));
        PointerByReference vars_values = new PointerByReference();
        vars_values.setPointer(new StringArray(vals));
        NativeSize vars_vec_size = new NativeSize(params.length);

        int rc = TessAPI1.TessBaseAPIInit4(handle, datapath, language,
                oem, configs, configs_size, vars_vec, vars_values, vars_vec_size, set_only_init_params);

        if (rc != 0) {
            TessAPI1.TessBaseAPIDelete(handle);
            System.out.println("Could not initialize tesseract.");
            return;
        }

        String outputbase = outputPath + fileNameTxt;

        /////

        BufferedImage imagebuf = ImageIO.read(new FileInputStream(new File(image)));

        /*ByteBuffer buf = ImageIOHelper.convertImageData(imagebuf);

        int bpp = imagebuf.getColorModel().getPixelSize();
        int bytespp = bpp / 8;
        int bytespl = (int) Math.ceil(imagebuf.getWidth() * bytespp);*/

        TessAPI1.TessBaseAPISetPageSegMode(handle, TessPageSegMode.PSM_SINGLE_BLOCK);

        Pix pix = Leptonica1.pixRead(image);
        TessAPI1.TessBaseAPISetImage2(handle, pix);
        //TessAPI1.TessBaseAPISetImage(handle, buf, imagebuf.getWidth(), imagebuf.getHeight(), bytespp, bytespl);
        TessAPI1.TessBaseAPISetSourceResolution(handle, 400);
        TessAPI1.TessBaseAPIGetAvailableLanguagesAsVector(handle);

        //Detecting and changing rotation of image
        IntBuffer orientDegB = IntBuffer.allocate(1);
        FloatBuffer orientConfB = FloatBuffer.allocate(1);
        PointerByReference scriptNameB = new PointerByReference();
        FloatBuffer scriptConfB = FloatBuffer.allocate(1);
        TessAPI1.TessBaseAPIDetectOrientationScript(handle, orientDegB, orientConfB, scriptNameB, scriptConfB);
        float angle = orientDegB.get();

        if (angle != 0 && orientConfB.get() > 10) {
            imagebuf = ImageHelper.rotateImage(imagebuf, angle); // deskew image
            /*RescaleOp op = new RescaleOp(1.0f, 35, null);
            op.filter(imagebuf,imagebuf);*/
            ImageIO.write(imagebuf, "png", new File(image));
        }

        TessAPI1.TessBaseAPIDelete(handle);
        filePathPDF = outputbase + ".pdf";

    }

    private static void convertFileToSearchablePDF() {

        TessBaseAPI handle = TessAPI1.TessBaseAPICreate();
        String datapath = tessDataPath;
        String language = "rus+eng";
        String image = filePathImage;
        int set_only_init_params = TessAPI1.FALSE;
        int oem = TessOcrEngineMode.OEM_DEFAULT;
        PointerByReference configs = null;
        int configs_size = 0;

        String[] params = {"load_system_dawg",
                "load_freq_dawg",
                "user_defined_dpi",
                "textord_tabfind_find_tables",
                "textord_tablefind_recognize_tables",
                "tessedit_use_primary_params_model",
                "textord_debug_tabfind",
                "tessedit_char_blacklist"};

        String[] vals = {"F","F","400","T","T","T","T","|`\\"};
        PointerByReference vars_vec = new PointerByReference();
        vars_vec.setPointer(new StringArray(params));
        PointerByReference vars_values = new PointerByReference();
        vars_values.setPointer(new StringArray(vals));
        NativeSize vars_vec_size = new NativeSize(params.length);

        int rc = TessAPI1.TessBaseAPIInit4(handle, datapath, language,
                oem, configs, configs_size, vars_vec, vars_values, vars_vec_size, set_only_init_params);

        if (rc != 0) {
            TessAPI1.TessBaseAPIDelete(handle);
            System.out.println("Could not initialize tesseract.");
            return;
        }

        String outputbase = outputPath + fileNameTxt;

        TessAPI1.TessBaseAPISetPageSegMode(handle, TessPageSegMode.PSM_AUTO_OSD);
        Pix pix = Leptonica1.pixRead(image);
        TessAPI1.TessBaseAPISetImage2(handle, pix);

        TessAPI1.TessBaseAPISetSourceResolution(handle, 400);
        TessAPI1.TessBaseAPIGetAvailableLanguagesAsVector(handle);

        TessResultRenderer renderer = TessAPI1.TessHOcrRendererCreate(outputbase);

        String dataPath = TessAPI1.TessBaseAPIGetDatapath(handle);
        TessAPI1.TessResultRendererBeginDocument(renderer, outputbase + ".pdf");
        TessAPI1.TessResultRendererInsert(renderer, TessAPI1.TessPDFRendererCreate(outputbase, dataPath, TessAPI1.FALSE));
        TessAPI1.TessBaseAPIProcessPages(handle, image, null, 0, renderer);
        TessAPI1.TessResultRendererEndDocument(renderer);
        TessAPI1.TessDeleteResultRenderer(renderer);
        TessAPI1.TessBaseAPIDelete(handle);
        new File(outputbase + ".hocr").deleteOnExit();
        filePathPDF = outputbase + ".pdf";
    }

    private static void getSymbolsConfidenceMap() {

        HashMap<Object, ArrayList<Double>> entryHashMap = new HashMap<>();

        TessBaseAPI handle = TessAPI1.TessBaseAPICreate();
        String datapath = tessDataPath;
        String language = "rus+eng";
        String image = filePathImage;
        int set_only_init_params = TessAPI1.FALSE;
        int oem = TessOcrEngineMode.OEM_DEFAULT;
        PointerByReference configs = null;
        int configs_size = 0;

        String[] params = {"load_system_dawg",
                "load_freq_dawg",
                "user_defined_dpi",
                "language_model_penalty_non_freq_dict_word",
                "language_model_penalty_non_dict_word",
                "textord_tabfind_find_tables",
                "textord_tablefind_recognize_tables"};
        String[] vals = {"F","F","400","1","1","T","T"};
        PointerByReference vars_vec = new PointerByReference();
        vars_vec.setPointer(new StringArray(params));
        PointerByReference vars_values = new PointerByReference();
        vars_values.setPointer(new StringArray(vals));
        NativeSize vars_vec_size = new NativeSize(params.length);

        TessAPI1.TessBaseAPIInit4(handle, datapath, language,
                oem, configs, configs_size, vars_vec, vars_values, vars_vec_size, set_only_init_params);

        TessAPI1.TessBaseAPISetPageSegMode(handle, TessPageSegMode.PSM_SINGLE_BLOCK);
        Pix pix = Leptonica1.pixRead(image);
        TessAPI1.TessBaseAPISetImage2(handle, pix);
        TessAPI1.TessBaseAPISetSourceResolution(handle, 400);

        ETEXT_DESC monitor = new ETEXT_DESC();
        TessAPI1.TessBaseAPIRecognize(handle, monitor);
        TessResultIterator ri = TessAPI1.TessBaseAPIGetIterator(handle);
        TessPageIterator pi = TessAPI1.TessResultIteratorGetPageIterator(ri);
        TessAPI1.TessPageIteratorBegin(pi);
        int level = TessPageIteratorLevel.RIL_SYMBOL;

        do {
            Pointer ptr = TessAPI1.TessResultIteratorGetUTF8Text(ri, level);
            String word = ptr.getString(0);
            TessAPI1.TessDeleteText(ptr);
            double confidence = TessAPI1.TessResultIteratorConfidence(ri, level);
            ArrayList<Double> confidences = entryHashMap.getOrDefault(word, new ArrayList<>());
            confidences.add(confidence);
            entryHashMap.put(word,confidences);
        } while (TessAPI1.TessPageIteratorNext(pi, level) == 1);

        for (Map.Entry entry: entryHashMap.entrySet()) {
            double sumConfidence = 0;
            int count = 0;
            for (Double f: (ArrayList<Double>) entry.getValue()) {
                sumConfidence += f;
                count++;
            }
            symbolConfidenceHashMap.put((String) entry.getKey(), count == 0 ? 0 : (sumConfidence / count));
        }
        TessAPI1.TessBaseAPIDelete(handle);
    }

    private static void addCMapToPdf() throws IOException {
        //try (PDDocument doc = PDDocument.load(new File(filePath)))
        try (PDDocument doc = PDDocument.load(new File(filePathPDF)))
        {
            for (int p = 0; p < doc.getNumberOfPages(); ++p)
            {
                PDPage page = doc.getPage(p);
                PDResources res = page.getResources();
                for (COSName fontName : res.getFontNames())
                {
                    PDFont font = res.getFont(fontName);
                    COSBase encoding = font.getCOSObject().getDictionaryObject(COSName.ENCODING);
                    if (!COSName.IDENTITY_H.equals(encoding))
                    {
                        continue;
                    }
                    /*// get real name
                    String fname = font.getName();
                    int plus = fname.indexOf('+');
                    if (plus != -1)
                    {
                        fname = fname.substring(plus + 1);
                    }

                    if (font.getCOSObject().containsKey(COSName.TO_UNICODE))
                    {
                        //continue;
                    }
                    System.out.println("File eurotext-eng, page " + (p + 1) + ", " + fontName.getName() + ", " + font.getName());
                    if (!fname.startsWith("Calibri-Bold"))
                    {
                        //continue;
                    }*/
                    COSStream toUnicodeStream = new COSStream();
                    try (PrintWriter pw = new PrintWriter(toUnicodeStream.createOutputStream(COSName.FLATE_DECODE)))
                    {
                        cmapString = new StringBuilder("/CIDInit /ProcSet findresource begin\n" +
                                "12 dict begin\n" +
                                "begincmap\n" +
                                "/CIDSystemInfo\n" +
                                "<< /Registry (Adobe)\n" +
                                "/Ordering (UCS) /Supplement 0 >> def\n" +
                                "/CMapName /Adobe-Identity-UCS def\n" +
                                "/CMapType 2 def\n" +
                                "1 begincodespacerange\n" +
                                "<0000> <FFFF>\n" +
                                "endcodespacerange\n");

                        for (int i = 32; i <= 191; i++) {
                            addUnicodeSymbolToCMap(i);
                        }

                        for (int i = 1025; i <= 1105; i++) {
                            addUnicodeSymbolToCMap(i);
                        }

                        for (int i = 8200; i <= 8500; i++) {
                            addUnicodeSymbolToCMap(i);
                        }

                        cmapString.append("endcmap CMapName currentdict /CMap defineresource pop end end");
                        pw.println(cmapString.toString());

                        /*// "9.10 Extraction of Text Content" in the PDF 32000 specification
                        pw.println("/CIDInit /ProcSet findresource begin\n" +
                                "12 dict begin\n" +
                                "begincmap\n" +
                                "/CIDSystemInfo\n" +
                                "<< /Registry (Adobe)\n" +
                                "/Ordering (UCS) /Supplement 0 >> def\n" +
                                "/CMapName /Adobe-Identity-UCS def\n" +
                                "/CMapType 2 def\n" +
                                "1 begincodespacerange\n" +
                                "<0000> <FFFF>\n" +
                                "endcodespacerange\n" +
                                "1 beginbfchar\n" + // number is count of entries
                                "<0020><0020> 0032 \n" + // Space
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0021><0021> 0033 \n" + // !
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0022><0022> 0034 \n" + // "
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0024><0024> 0036\n" + // $
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0028><0028> 0040\n" + // (
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0029><0029> 0041\n" + // )
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<002c><002c> 0044\n" + // ,
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<002d><002d> 0045\n" + // -
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<002e><002e> 0046\n" + // .
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<002f><002f> 0047\n" + // /
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0030><0030> 0048\n" + // 0
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0031><0031> 0049\n" + // 1
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0032><0032> 0050\n" + // 2
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0033><0033> 0051\n" + // 3
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0034><0034> 0052\n" + // 4
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0035><0035> 0053\n" + // 5
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0036><0036> 0054\n" + // 6
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0037><0037> 0055\n" + // 7
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0038><0038> 0056\n" + // 8
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0039><0039> 0057\n" + // 9
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<003a><003a> 0058\n" + // :
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<003b><003b> 0059\n" + // ;
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0041><0041> 0065\n" + // A
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0042><0042> 0066\n" + // B
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0043><0043> 0067\n" + // C
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0045><0045> 0069\n" + // E
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0047><0047> 0071\n" + // G
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0048><0048> 0072\n" + // H
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<004b><004b> 0075\n" + // K
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<004c><004c> 0076\n" + // L
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<004f><004f> 0079\n" + // O
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0053><0053> 0083\n" + // S
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0056><0056> 0086\n" + // V
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0058><0058> 0088\n" + // X
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<005b><005b> 0091\n" + // [
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<005d><005d> 0093\n" + // ]
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0061><0061> 0097\n" + // a
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0062><0062> 0098\n" + // b
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0063><0063> 0099\n" + // c
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0064><0064> 0100\n" + // d
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0065><0065> 0101\n" + // e
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0069><0069> 0105\n" + // i
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<006d><006d> 0109\n" + // m
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<006e><006e> 0110\n" + // n
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<006f><006f> 0111\n" + // o
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0072><0072> 0114\n" + // r
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0076><0076> 0118\n" + // v
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0078><0078> 0120\n" + // x
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0079><0079> 0121\n" + // y
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<007c><007c> 0124\n" + // |
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<00ab><00ab> 0171\n" + // <<
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<00bb><00bb> 0187\n" + // >>
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0410><0410> 1040\n" + // А русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0411><0411> 1041\n" + // Б русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0412><0412> 1042\n" + // В русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0413><0413> 1043\n" + // Г русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0414><0414> 1044\n" + // Д русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0415><0415> 1045\n" + // Е русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0416><0416> 1046\n" + // Ж русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0417><0417> 1047\n" + // З русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0418><0418> 1048\n" + // И русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0419><0419> 1049\n" + // Й русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<041a><041a> 1050\n" + // К русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<041b><041b> 1051\n" + // Л русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<041c><041c> 1052\n" + // М русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<041d><041d> 1053\n" + // Н русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<041e><041e> 1054\n" + // О русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<041f><041f> 1055\n" + // П русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0420><0420> 1056\n" + // Р русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0421><0421> 1057\n" + // С русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0422><0422> 1058\n" + // Т русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0423><0423> 1059\n" + // У русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0424><0424> 1060\n" + // Ф русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0425><0425> 1061\n" + // Х русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0426><0426> 1062\n" + // Ц русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0427><0427> 1063\n" + // Ч русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0428><0428> 1064\n" + // Ш русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0429><0429> 1065\n" + // Щ русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<042a><042a> 1066\n" + // Ъ русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<042b><042b> 1067\n" + // Ы русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<042c><042c> 1068\n" + // Ь русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<042d><042d> 1069\n" + // Э русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<042e><042e> 1070\n" + // Ю русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<042f><042f> 1071\n" + // Я русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0430><0430> 1072\n" + // а русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0431><0431> 1073\n" + // б русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0432><0432> 1074\n" + // в русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0433><0433> 1075\n" + // г русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0434><0434> 1076\n" + // д русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0435><0435> 1077\n" + // е русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0436><0436> 1078\n" + // ж русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0437><0437> 1079\n" + // з русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0438><0438> 1080\n" + // и русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0439><0439> 1081\n" + // й русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<043a><043a> 1082\n" + // к русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<043b><043b> 1083\n" + // л русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<043c><043c> 1084\n" + // м русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<043d><043d> 1085\n" + // н русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<043e><043e> 1086\n" + // о русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<043f><043f> 1087\n" + // п русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0440><0440> 1088\n" + // р русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0441><0441> 1089\n" + // с русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0442><0442> 1090\n" + // т русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0443><0443> 1091\n" + // у русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0444><0444> 1092\n" + // ф русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0445><0445> 1093\n" + // х русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0446><0446> 1094\n" + // ц русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0447><0447> 1095\n" + // ч русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0448><0448> 1096\n" + // ш русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<0449><0449> 1097\n" + // щ русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<044a><044a> 1098\n" + // ъ русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<044b><044b> 1099\n" + // ы русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<044c><044c> 1100\n" + // ь русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<044d><044d> 1101\n" + // э русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<044e><044e> 1102\n" + // ю русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<044f><044f> 1103\n" + // я русская
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<2014><2014> 8212\n" + // —
                                "endbfchar\n" +
                                "1 beginbfchar\n" +
                                "<2116><2116> 8470\n" + // №
                                "endbfchar\n" +
                                "endcmap CMapName currentdict /CMap defineresource pop end end");*/
                    }

                    font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicodeStream);
                }
            }

            doc.save(filePathPDF);
        }
    }

    private static void addUnicodeSymbolToCMap(int i) {
        String hexCode = Integer.toHexString(0x10000 | i).substring(1);
        String unicode;
        if (i>9999) {
            unicode = String.format("%05d", i);
        } else {
            unicode = String.format("%04d", i);
        }
        cmapString.append("1 beginbfchar\n");
        cmapString.append("<").append(hexCode).append("><").append(hexCode).append("> ").append(unicode).append(" \n");
        cmapString.append("endbfchar\n");
    }

    private static void createAndWriteDataToXml() throws IOException {
        XmlParser xmlParser = new XmlParser(filePathPDF);
        xmlParser.setSymbolConfidenceHashMap(symbolConfidenceHashMap);
        xmlParser.writeDataFromPdf();
    }

}

