package ru.omega;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;


import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.List;
import java.util.Map;

public class ExtractBoxedText {
    public static void main(String[] args) throws IOException, DocumentException {
        changePdf();
        //manipulatePdf("C:/temp/eurotext-eng.pdf","C:/temp/eurotext-eng2.pdf");
        printDataFromPDF();
    }

    public static void printDataFromPDF() {

        try (PDDocument document = PDDocument.load(new File("C:/temp/eurotext-eng2.pdf")) ) {
            for (PDPage page : document.getDocumentCatalog().getPages()) {
                PdfBoxFinder boxFinder = new PdfBoxFinder(page);
                boxFinder.processPage(page);
                PDFTextStripperByArea stripperByArea = new PDFTextStripperByArea();
                for (Map.Entry<String, Rectangle2D> entry : boxFinder.getRegions().entrySet()) {
                    stripperByArea.addRegion(entry.getKey(), entry.getValue());
                }

                stripperByArea.extractRegions(page);
                List<String> names = stripperByArea.getRegions();
                names.sort(null);
                for (String name : names) {
                    System.out.printf("[%s] %s\n", name, stripperByArea.getTextForRegion(name));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void changePdf() {
        try (PDDocument doc = PDDocument.load(new File("C:/temp/eurotext-eng.pdf")))
        {
            for (int p = 0; p < doc.getNumberOfPages(); ++p)
            {
                //PDFont font1 = PDType1Font.HELVETICA;
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
                    // get real name
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
                    }
                    COSStream toUnicodeStream = new COSStream();
                    try (PrintWriter pw = new PrintWriter(toUnicodeStream.createOutputStream(COSName.FLATE_DECODE)))
                    {
                        // "9.10 Extraction of Text Content" in the PDF 32000 specification
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
                                "10 beginbfchar\n" + // number is count of entries
                                "<0001><0020>\n" + // space
                                "<0002><0041>\n" + // A
                                "<0003><0042>\n" + // B
                                "<0004><0044>\n" + // D
                                "<0013><0065>\n" + // e
                                "<0012><0064>\n" + // d
                                "<0017><0069>\n" + // i
                                "<001B><006E>\n" + // n
                                "<0015><0067>\n" + // g
                                "<1059><1059>\n" + // У
                                "endbfchar\n" +
                                "endcmap CMapName currentdict /CMap defineresource pop end end");
                    }
                    font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicodeStream);
                    String s = "";
                    //font.getCOSObject().setString(COSName.BASE_FONT, "Arial");
                    //font.getCOSObject().setItem(COSName.ENCODING, );
                }
            }
            doc.save("C:/temp/eurotext-eng2.pdf");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void manipulatePdf(String src, String dest) throws IOException, DocumentException {
        RandomAccessFile raf = new RandomAccessFile(src, "r");
        byte fontfile[] = new byte[(int)raf.length()];
        raf.readFully(fontfile);
        raf.close();
        // create a new stream for the font file
        PdfStream stream = new PdfStream(fontfile);
        stream.flateCompress();
        stream.put(PdfName.LENGTH1, new PdfNumber(fontfile.length));
        // create a reader object
        PdfReader reader = new PdfReader(dest);
        int n = reader.getXrefSize();
        PdfObject object;
        PdfDictionary font;
        PdfStamper stamper = new PdfStamper(reader, new FileOutputStream("C:/temp/eurotext-eng3.pdf"));
        PdfName fontname = new PdfName("GlyphLessFont");
        for (int i = 0; i < n; i++) {
            object = reader.getPdfObject(i);
            if (object == null || !object.isDictionary())
                continue;
            font = (PdfDictionary)object;
            if (PdfName.FONTDESCRIPTOR.equals(font.get(PdfName.TYPE))
                    && fontname.equals(font.get(PdfName.FONTNAME))) {
                PdfIndirectObject objref = stamper.getWriter().addToBody(stream);
                font.put(PdfName.BASEFONT, font.getAsName(new PdfName("Arial")));
                font.put(PdfName.FONTFILE, objref.getIndirectReference());
            }
        }
        stamper.close();
        reader.close();
    }


}

