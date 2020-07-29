package ru.omega;

import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import technology.tabula.*;
import technology.tabula.detectors.NurminenDetectionAlgorithm;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.ExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class XmlParser {

    private String filePathPDF;
    private String outputPath;
    private HashMap<String, Double> symbolConfidenceHashMap = new HashMap<>();
    private String currentDocType;
    private String docType;
    private ArrayList<Integer> pageNumbers = new ArrayList<>();

    public void setSymbolConfidenceHashMap(HashMap<String, Double> symbolConfidenceHashMap) {
        this.symbolConfidenceHashMap = symbolConfidenceHashMap;
    }


    public XmlParser(String filePathPDF) {
        this.filePathPDF = filePathPDF;
        this.outputPath = filePathPDF.substring(0, filePathPDF.lastIndexOf("\\")+1);
    }

    protected void writeDataFromPdf() throws IOException {
        try (PDDocument doc = PDDocument.load(new File(filePathPDF));
             PDDocument docWithTwoLayers = PDDocument.load(new File(filePathPDF.replace("_searchable","")))) {
            PDDocument currentDocument = new PDDocument();

            Splitter splitter = new Splitter();
            List<PDDocument> Pages = splitter.split(doc);
            Iterator<PDDocument> iterator = Pages.listIterator();
            PDFTextStripper pdfTextStripper = new PDFTextStripper();
            int pageCount = 0;
            StringBuilder textForParse = new StringBuilder();
            Integer currentPage = 0;
            while (iterator.hasNext() || !textForParse.toString().isEmpty()) {
                currentPage++;
                if (iterator.hasNext()) {
                    String nextLine = pdfTextStripper.getText(iterator.next());

                    if (isNewDocument(nextLine.toLowerCase())) {
                        if (pageCount != 0 && !textForParse.toString().isEmpty()) {
                            createNewXml(textForParse.toString(), pageCount, getOutputFileName("xml"));
                            currentDocument.save(getOutputFileName("pdf"));
                            currentDocument.close();
                        }
                        currentDocType = docType;
                        pageCount = 1;
                        pageNumbers.clear();
                        pageNumbers.add(currentPage);
                        currentDocument.close();
                        currentDocument = new PDDocument();
                        currentDocument.addPage(docWithTwoLayers.getPage(currentPage-1));

                        textForParse = new StringBuilder(nextLine);
                    } else {
                        textForParse.append("\n\r");
                        textForParse.append(nextLine);
                        pageCount++;
                        pageNumbers.add(currentPage);
                        currentDocument.addPage(docWithTwoLayers.getPage(currentPage-1));
                        currentDocType = docType;
                    }
                } else {
                    currentDocType = docType;
                    createNewXml(textForParse.toString(), pageCount, getOutputFileName("xml"));
                    currentDocument.save(getOutputFileName("pdf"));
                    currentDocument.close();
                    textForParse = new StringBuilder();
                }
            }
        }
    }

    //TODO
    private boolean isNewDocument(String textForParse) {

        //docType = "";
        if ((textForParse.contains("универсальны") || textForParse.contains("ередаточны")) && textForParse.contains("документ") && textForParse.contains("статус")) {
            //Новый УПД
            docType = "UPD";
            return true;
        } else if  ((textForParse.contains("универсальны") || textForParse.contains("ередаточны")) && textForParse.contains("документ")
                    && !textForParse.contains("статус")
                    && !(textForParse.contains("счет-фактура") || textForParse.contains("счет фактура"))) {
            //Существующий УПД
            docType = "UPD";
            return false;
        } else if (!(textForParse.contains("универсальны") || textForParse.contains("передаточны") || textForParse.contains("статус"))
                    && (textForParse.contains("счет-фактура") || textForParse.contains("счет фактура"))) {
            if (textForParse.contains("исправление")) {
                //Новая Счет-фактура
                docType = "Invoice";
                return true;
            } else {
                //Существующая Счет-фактура
                docType = "Invoice";
                return false;
            }

        } else if ((textForParse.contains("товарная") && textForParse.contains("накладная")) || textForParse.contains("торг-12")) {
            //Новая Накладная
            if (textForParse.contains("торг-12")) {
                docType = "Nakladnaya";
                return true;
            } else {
                docType = "Nakladnaya";
                return false;
            }
        }  else if (textForParse.contains("счет на оплату")) {
            //Новый Счет на оплату
            docType = "Account";
            return true;
        }  else if (textForParse.contains("акт №") || textForParse.contains("акт N") || textForParse.contains("акт выполненных")) {
            //Новый Акт
            docType = "Akt";
            return true;
        } else {
            docType = "Account";
            return false;
        }
    }

    private void createNewXml(String textForParse, int pageCount, String xmlName) {

        try {
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            //root elements
            Document doc = docBuilder.newDocument();

            //Parsing text from PDF to XML
            parseTextToXml(textForParse, pageCount, doc);

            //write the content into xml file
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(xmlName));

            transformer.transform(source, result);

        } catch (IOException | ParserConfigurationException | TransformerException pce) {
            pce.printStackTrace();
        }

    }

    private void parseTextToXml(String textForParsing, int pageCount, Document XmlDocument) throws IOException {

        switch (currentDocType) {
            case "Account":
                //System.out.println("Account!");
                parseAccountDocument(textForParsing, pageCount, XmlDocument);
                break;
            case "UPD":
                //System.out.println("UPD!");
                parseUpdDocument(textForParsing, pageCount, XmlDocument);
                break;
            case "Invoice":
                //System.out.println("Invoice!");
                parseInvoiceDocument(textForParsing, pageCount, XmlDocument);
                break;
            case "Nakladnaya":
                //System.out.println("Nakladnaya!");
                parseNakladnayaDocument(textForParsing, pageCount, XmlDocument);
                break;
            case "Akt":
                //System.out.println("Akt!");
                parseAktDocument(textForParsing, pageCount, XmlDocument);
                break;
        }

    }

    private void parseAccountDocument(String textForParsing, int pageCount, Document XmlDocument) throws IOException {

        double sum = 0;
        double taxSum = 0;
        boolean taxIncluded = true;

        Element mainElement = XmlDocument.createElement("form:Documents");
        mainElement.setAttribute("xmlns:form", "http://www.abbyy.com/FlexiCapture/Schemas/Export/FormData.xsd");
        mainElement.setAttribute("xmlns:addData", "http://www.abbyy.com/FlexiCapture/Schemas/Export/AdditionalFormData.xsd");
        mainElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");

        XmlDocument.appendChild(mainElement);

        Element rootElement = XmlDocument.createElement("_" + docType);
        rootElement.setAttribute("xmlns:_" + docType, "http://www.abbyy.com/FlexiCapture/Schemas/Export/" + docType + ".xsd");
        rootElement.setAttribute("addData:DocumentState", "Корректен");
        rootElement.setAttribute("addData:ImagePath", filePathPDF.substring(filePathPDF.lastIndexOf("\\")+1));
        mainElement.appendChild(rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Счет", "СчетНаОплату");
        appendChildToXML(XmlDocument, rootElement, "_TemplateName", docType);
        appendChildToXML(XmlDocument, rootElement, "_TemplateVersion", "");
        appendChildToXML(XmlDocument, rootElement, "_DocType", "СчетНаОплату");

        for (String line: textForParsing.split("\r\n")) {
            if (line.contains("Счет на оплату №")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DocNum", "Счет на оплату №", "от", true);
                if (line.contains("от")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DocDate", "от", "г.",true);
                }
            }

            if (line.contains("Поставщик")) {
                appendChildToXML(line, XmlDocument, rootElement, "_IssCompany", "Поставщик", ",",true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssINN", "ИНН", ",",true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssKPP", "КПП", ",",true);
                }
            }

            if (line.contains("Покупатель")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DesCompany", "Покупатель", ",", true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesINN", "ИНН", ",", true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesKPP", "КПП", ",", true);
                }
            }

            if (line.contains("Итого") || line.contains("Всего к оплате") || line.contains("В том числе НДС")) {
                double sum1 = Double.parseDouble(line.replaceAll("[^0-9,\\.]", "").replace(",", "."));

                if (line.contains("Итого")) {
                    sum = sum1;
                }

                if (line.contains("Всего к оплате")) {
                    if (Double.compare(sum, sum1) != 0) {
                        taxIncluded = false;
                        sum = sum1;
                    }
                }
                if (line.contains("В том числе НДС")) {
                    taxSum = sum1;
                }
            }
        }


        //Getting tabled data from PDF if exists
        //TODO
        getTabledDataFromAccountDocument(XmlDocument, rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Sum", parseStringToDoubleString(String.valueOf(sum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxSum", parseStringToDoubleString(String.valueOf(taxSum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxIncluded", Boolean.toString(taxIncluded));
        appendChildToXML(XmlDocument, rootElement, "_DocCur", "руб.");
        appendChildToXML(XmlDocument, rootElement, "_Pages", String.valueOf(pageCount));
        //TODO
        appendChildToXML(XmlDocument, rootElement, "_FileName", "inputFileName");
    }

    private void getTabledDataFromAccountDocument(Document XmlDocument, Element rootElement) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(new File(filePathPDF))) {

            NurminenDetectionAlgorithm detectionAlgorithm = new NurminenDetectionAlgorithm();

            ExtractionAlgorithm algExtractor;

            SpreadsheetExtractionAlgorithm extractor=new SpreadsheetExtractionAlgorithm();

            ObjectExtractor extractor1 = new ObjectExtractor(pdfDoc);
            PageIterator pages = extractor1.extract(pageNumbers);
            List<Table> tables=new ArrayList<Table>();
            while (pages.hasNext()) {
                Page page = pages.next();
                if (extractor.isTabular(page)) {
                    algExtractor=new SpreadsheetExtractionAlgorithm();
                }
                else
                    algExtractor=new BasicExtractionAlgorithm();

                List<Rectangle> tablesOnPage = detectionAlgorithm.detect(page);


                for (Rectangle guessRect : tablesOnPage) {
                    Page guess = page.getArea(guessRect);
                    tables.addAll((List<Table>) algExtractor.extract(guess));
                }

            }

            boolean foundTable = false;
            String[][] dataArray = null;

            for(Table table: tables) {
                List<List<RectangularTextContainer>> rows = table.getRows();

                for(int i=0, ia = 0; i<rows.size(); i++, ia++) {

                    List<RectangularTextContainer> cells = rows.get(i);

                    for(int j=0; j<cells.size(); j++) {
                        String cellString = cells.get(j).getText();

                        if (!foundTable) {
                            if ((cellString.equals("N") || cellString.equals("No") || cellString.equals("№"))) {
                                foundTable = true;
                                dataArray = new String[rows.size()-i][cells.size()];
                                ia = 0;
                                dataArray[ia][j] = cellString;
                            } else {
                                continue;
                            }
                        } else {
                            dataArray[ia][j] = cellString;
                        }
                    }
                }
            }

            if (foundTable) {
                Element element = XmlDocument.createElement("_Table1");
                rootElement.appendChild(element);

                //TODO
                /*<_Code />
                <_TaxRate />
                <_TaxSum xsi:nil="true" />
                <_TaxFree />*/
                Double tableSum = 0.0;
                for (int i = 1; i < dataArray.length; i++) {
                    for (int j = 0; j < dataArray[i].length; j++) {
                        String headText = dataArray[0][j];
                        String cellText;
                        if (j > 0 && dataArray[0][j-1].isEmpty()) {
                            cellText = dataArray[i][j-1] + " " + dataArray[i][j];
                        } else {
                            cellText = dataArray[i][j];
                        }
                        if (headText.equals("N") || headText.equals("No") || headText.equals("№")) {
                            appendChildToXML(XmlDocument, element, "_Position",cellText);
                        } else if (headText.toLowerCase().contains("товары")) {
                            appendChildToXML(XmlDocument, element, "_Descript",cellText);
                        } else if (headText.toLowerCase().contains("ед")) {
                            appendChildToXML(XmlDocument, element, "_Unit",cellText);
                        } else if (headText.toLowerCase().contains("кол")) {
                            appendChildToXML(XmlDocument, element, "_Qty",parseStringToDoubleString(cellText,3));
                        } else if (headText.toLowerCase().contains("цена")) {
                            appendChildToXML(XmlDocument, element, "_Price",parseStringToDoubleString(cellText,2),cellText);
                        } else if (headText.toLowerCase().contains("сумма")) {
                            appendChildToXML(XmlDocument, element, "_SumWithTax",parseStringToDoubleString(cellText,2));
                            tableSum += Double.parseDouble(cellText.replaceAll("[^0-9,\\.]","").replace(",","."));
                        }
                    }
                }

                appendChildToXML(XmlDocument, rootElement, "_SumTable1",parseStringToDoubleString(tableSum.toString(),2),tableSum.toString().replace(".",","));
            }
        }
    }

    private void parseUpdDocument(String textForParsing, int pageCount, Document XmlDocument) throws IOException {

        double sum = 0;
        double taxSum = 0;
        boolean taxIncluded = false;

        Element mainElement = XmlDocument.createElement("form:Documents");
        mainElement.setAttribute("xmlns:form", "http://www.abbyy.com/FlexiCapture/Schemas/Export/FormData.xsd");
        mainElement.setAttribute("xmlns:addData", "http://www.abbyy.com/FlexiCapture/Schemas/Export/AdditionalFormData.xsd");
        mainElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");

        XmlDocument.appendChild(mainElement);

        Element rootElement = XmlDocument.createElement("_" + docType);
        rootElement.setAttribute("xmlns:_" + docType, "http://www.abbyy.com/FlexiCapture/Schemas/Export/" + docType + ".xsd");
        rootElement.setAttribute("addData:DocumentState", "Корректен");
        rootElement.setAttribute("addData:ImagePath", filePathPDF.substring(filePathPDF.lastIndexOf("\\")+1));
        mainElement.appendChild(rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Универсальный_передаточный_документ", "УПД");
        appendChildToXML(XmlDocument, rootElement, "_TemplateName", docType);
        appendChildToXML(XmlDocument, rootElement, "_TemplateVersion", "");
        appendChildToXML(XmlDocument, rootElement, "_DocType", "УПД");

        for (String line: textForParsing.split("\r\n")) {
            if (line.contains("Счет-фактура №")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DocNum", "Счет-фактура №", "от", true);
                if (line.contains("от")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DocDate", "от", "г.",true);
                }
            }

            if (line.contains("Поставщик")) {
                appendChildToXML(line, XmlDocument, rootElement, "_IssCompany", "Поставщик", ",",true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssINN", "ИНН", ",",true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssKPP", "КПП", ",",true);
                }
            }

            if (line.contains("Покупатель")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DesCompany", "Покупатель", ",", true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesINN", "ИНН", ",", true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesKPP", "КПП", ",", true);
                }
            }

            //TODO
            /*if (line.contains("Итого") || line.contains("Всего к оплате") || line.contains("В том числе НДС")) {
                double sum1 = Double.parseDouble(line.replaceAll("[^0-9,\\.]", "").replace(",", "."));

                if (line.contains("Итого")) {
                    sum = sum1;
                }

                if (line.contains("Всего к оплате")) {
                    if (Double.compare(sum, sum1) == 0) {
                        taxIncluded = true;
                    } else {
                        sum = sum1;
                    }
                }
                if (line.contains("В том числе НДС")) {
                    taxSum = sum1;
                }
            }*/
        }


        //Getting tabled data from PDF if exists
        //TODO
        getTabledDataFromUpdDocument(XmlDocument, rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Sum", parseStringToDoubleString(String.valueOf(sum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxSum", parseStringToDoubleString(String.valueOf(taxSum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxIncluded", Boolean.toString(taxIncluded));
        appendChildToXML(XmlDocument, rootElement, "_DocCur", "руб.");
        appendChildToXML(XmlDocument, rootElement, "_Pages", String.valueOf(pageCount));
        //TODO
        appendChildToXML(XmlDocument, rootElement, "_FileName", "inputFileName");
    }

    private void getTabledDataFromUpdDocument(Document XmlDocument, Element rootElement) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(new File(filePathPDF))) {

            NurminenDetectionAlgorithm detectionAlgorithm = new NurminenDetectionAlgorithm();

            ExtractionAlgorithm algExtractor;

            SpreadsheetExtractionAlgorithm extractor=new SpreadsheetExtractionAlgorithm();

            ObjectExtractor extractor1 = new ObjectExtractor(pdfDoc);
            PageIterator pages = extractor1.extract(pageNumbers);
            List<Table> tables=new ArrayList<Table>();
            while (pages.hasNext()) {
                Page page = pages.next();
                if (extractor.isTabular(page)) {
                    algExtractor=new SpreadsheetExtractionAlgorithm();
                }
                else
                    algExtractor=new BasicExtractionAlgorithm();

                List<Rectangle> tablesOnPage = detectionAlgorithm.detect(page);


                for (Rectangle guessRect : tablesOnPage) {
                    Page guess = page.getArea(guessRect);
                    tables.addAll((List<Table>) algExtractor.extract(guess));
                }

            }

            boolean foundTable = false;
            String[][] dataArray = null;

            for(Table table: tables) {
                List<List<RectangularTextContainer>> rows = table.getRows();

                for(int i=0, ia = 0; i<rows.size(); i++, ia++) {

                    List<RectangularTextContainer> cells = rows.get(i);

                    for(int j=0; j<cells.size(); j++) {
                        String cellString = cells.get(j).getText();

                        if (!foundTable) {
                            if ((cellString.equals("N") || cellString.equals("No") || cellString.equals("№"))) {
                                foundTable = true;
                                dataArray = new String[rows.size()-i][cells.size()];
                                ia = 0;
                                dataArray[ia][j] = cellString;
                                //break;
                            } else {
                                continue;
                            }
                        } else {
                            dataArray[ia][j] = cellString;
                        }
                    }
                }
                //break;
            }

            if (foundTable) {
                Element element = XmlDocument.createElement("_Table1");
                rootElement.appendChild(element);

                //TODO
                /*<_Code />
                <_TaxRate />
                <_TaxSum xsi:nil="true" />
                <_TaxFree />*/
                Double tableSum = 0.0;
                for (int i = 1; i < dataArray.length; i++) {
                    for (int j = 0; j < dataArray[i].length; j++) {
                        String headText = dataArray[0][j];
                        String cellText;
                        if (j > 0 && dataArray[0][j-1].isEmpty()) {
                            cellText = dataArray[i][j-1] + " " + dataArray[i][j];
                        } else {
                            cellText = dataArray[i][j];
                        }
                        if (headText.equals("N") || headText.equals("No") || headText.equals("№")) {
                            appendChildToXML(XmlDocument, element, "_Position",cellText);
                        } else if (headText.toLowerCase().contains("товары")) {
                            appendChildToXML(XmlDocument, element, "_Descript",cellText);
                        } else if (headText.toLowerCase().contains("ед")) {
                            appendChildToXML(XmlDocument, element, "_Unit",cellText);
                        } else if (headText.toLowerCase().contains("кол")) {
                            appendChildToXML(XmlDocument, element, "_Qty",parseStringToDoubleString(cellText,3));
                        } else if (headText.toLowerCase().contains("цена")) {
                            appendChildToXML(XmlDocument, element, "_Price",parseStringToDoubleString(cellText,2),cellText);
                        } else if (headText.toLowerCase().contains("сумма")) {
                            appendChildToXML(XmlDocument, element, "_SumWithTax",parseStringToDoubleString(cellText,2));
                            tableSum += Double.parseDouble(cellText.replaceAll("[^0-9,\\.]","").replace(",","."));
                        }
                    }
                }

                appendChildToXML(XmlDocument, rootElement, "_SumTable1",parseStringToDoubleString(tableSum.toString(),2),tableSum.toString().replace(".",","));
            }
        }
    }

    private void parseInvoiceDocument(String textForParsing, int pageCount, Document XmlDocument) throws IOException {

        double sum = 0;
        double taxSum = 0;
        boolean taxIncluded = false;

        Element mainElement = XmlDocument.createElement("form:Documents");
        mainElement.setAttribute("xmlns:form", "http://www.abbyy.com/FlexiCapture/Schemas/Export/FormData.xsd");
        mainElement.setAttribute("xmlns:addData", "http://www.abbyy.com/FlexiCapture/Schemas/Export/AdditionalFormData.xsd");
        mainElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");

        XmlDocument.appendChild(mainElement);

        Element rootElement = XmlDocument.createElement("_" + docType);
        rootElement.setAttribute("xmlns:_" + docType, "http://www.abbyy.com/FlexiCapture/Schemas/Export/" + docType + ".xsd");
        rootElement.setAttribute("addData:DocumentState", "Корректен");
        rootElement.setAttribute("addData:ImagePath", filePathPDF.substring(filePathPDF.lastIndexOf("\\")+1));
        mainElement.appendChild(rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Счет-Фактура", "СчетФактура");
        appendChildToXML(XmlDocument, rootElement, "_TemplateName", docType);
        appendChildToXML(XmlDocument, rootElement, "_TemplateVersion", "");
        appendChildToXML(XmlDocument, rootElement, "_DocType", "СчетФактура");

        for (String line: textForParsing.split("\r\n")) {
            if (line.contains("Счет-фактура")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DocNum", "Счет-фактура №", "от", true);
                if (line.contains("от")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DocDate", "от", "г.",true);
                }
            }

            if (line.contains("Продавец:")) {
                appendChildToXML(line, XmlDocument, rootElement, "_IssCompany", "Продавец:", ",",true);
            }

            if (line.contains("ИНН/КПП продавца")) {
                //appendChildToXML(line, XmlDocument, rootElement, "_IssCompany", "Поставщик", ",",true);
                String InnKpp = line.substring(18).trim();

                appendChildToXML(XmlDocument, rootElement, "_IssINNKPP", InnKpp);
                appendChildToXML(XmlDocument, rootElement, "_IssINN", InnKpp.split("/")[0]);
                appendChildToXML(XmlDocument, rootElement, "_IssKPP", InnKpp.split("/")[1]);

            }


            if (line.contains("Грузоотправитель и его адрес:")) {
                appendChildToXML(line, XmlDocument, rootElement, "_ShipperCompany", "Грузоотправитель и его адрес:", ",", true);
            }

            if (line.contains("Грузополучатель и его адрес:")) {
                appendChildToXML(line, XmlDocument, rootElement, "_ConsegneeCompany", "Грузополучатель и его адрес:", ",", true);
            }

            if (line.contains("Покупатель:")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DesCompany", "Покупатель:", ",",true);
            }

            if (line.contains("ИНН/КПП покупателя")) {
                String InnKpp = line.substring(18).trim();

                appendChildToXML(XmlDocument, rootElement, "_DesINNKPP", InnKpp);
                appendChildToXML(XmlDocument, rootElement, "_DesINN", InnKpp.split("/")[0]);
                appendChildToXML(XmlDocument, rootElement, "_DesKPP", InnKpp.split("/")[1]);
            }

            //TODO
            /*if (line.contains("Итого") || line.contains("Всего к оплате") || line.contains("В том числе НДС")) {
                double sum1 = Double.parseDouble(line.replaceAll("[^0-9,\\.]", "").replace(",", "."));

                if (line.contains("Итого")) {
                    sum = sum1;
                }

                if (line.contains("Всего к оплате")) {
                    if (Double.compare(sum, sum1) == 0) {
                        taxIncluded = true;
                    } else {
                        sum = sum1;
                    }
                }
                if (line.contains("В том числе НДС")) {
                    taxSum = sum1;
                }
            }*/
        }

        //Getting tabled data from PDF if exists
        //TODO
        getTabledDataFromInvoiceDocument(XmlDocument, rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Sum", parseStringToDoubleString(String.valueOf(sum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxSum", parseStringToDoubleString(String.valueOf(taxSum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxIncluded", Boolean.toString(taxIncluded));
        appendChildToXML(XmlDocument, rootElement, "_DocCur", "руб.");
        appendChildToXML(XmlDocument, rootElement, "_Pages", String.valueOf(pageCount));
        //TODO
        appendChildToXML(XmlDocument, rootElement, "_FileName", "inputFileName");
    }

    private void getTabledDataFromInvoiceDocument(Document XmlDocument, Element rootElement) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(new File(filePathPDF))) {

            NurminenDetectionAlgorithm detectionAlgorithm = new NurminenDetectionAlgorithm();

            ExtractionAlgorithm algExtractor;

            SpreadsheetExtractionAlgorithm extractor=new SpreadsheetExtractionAlgorithm();

            ObjectExtractor extractor1 = new ObjectExtractor(pdfDoc);
            PageIterator pages = extractor1.extract(pageNumbers);
            List<Table> tables=new ArrayList<Table>();
            while (pages.hasNext()) {
                Page page = pages.next();
                if (extractor.isTabular(page)) {
                    algExtractor=new SpreadsheetExtractionAlgorithm();
                }
                else
                    algExtractor=new BasicExtractionAlgorithm();

                List<Rectangle> tablesOnPage = detectionAlgorithm.detect(page);


                for (Rectangle guessRect : tablesOnPage) {
                    Page guess = page.getArea(guessRect);
                    tables.addAll((List<Table>) algExtractor.extract(guess));
                }

            }

            boolean foundTable = false;
            String[][] dataArray = null;

            for(Table table: tables) {
                List<List<RectangularTextContainer>> rows = table.getRows();

                for(int i=0, ia = 0; i<rows.size(); i++, ia++) {

                    List<RectangularTextContainer> cells = rows.get(i);

                    for(int j=0; j<cells.size(); j++) {
                        String cellString = cells.get(j).getText();

                        if (!foundTable) {
                            if ((cellString.equals("N") || cellString.equals("No") || cellString.equals("№"))) {
                                foundTable = true;
                                dataArray = new String[rows.size()-i][cells.size()];
                                ia = 0;
                                dataArray[ia][j] = cellString;
                                //break;
                            } else {
                                continue;
                            }
                        } else {
                            dataArray[ia][j] = cellString;
                        }
                    }
                }
                //break;
            }

            if (foundTable) {
                Element element = XmlDocument.createElement("_Table1");
                rootElement.appendChild(element);

                //TODO
                /*<_Code />
                <_TaxRate />
                <_TaxSum xsi:nil="true" />
                <_TaxFree />*/
                Double tableSum = 0.0;
                for (int i = 1; i < dataArray.length; i++) {
                    for (int j = 0; j < dataArray[i].length; j++) {
                        String headText = dataArray[0][j];
                        String cellText;
                        if (j > 0 && dataArray[0][j-1].isEmpty()) {
                            cellText = dataArray[i][j-1] + " " + dataArray[i][j];
                        } else {
                            cellText = dataArray[i][j];
                        }
                        if (headText.equals("N") || headText.equals("No") || headText.equals("№")) {
                            appendChildToXML(XmlDocument, element, "_Position",cellText);
                        } else if (headText.toLowerCase().contains("товары")) {
                            appendChildToXML(XmlDocument, element, "_Descript",cellText);
                        } else if (headText.toLowerCase().contains("ед")) {
                            appendChildToXML(XmlDocument, element, "_Unit",cellText);
                        } else if (headText.toLowerCase().contains("кол")) {
                            appendChildToXML(XmlDocument, element, "_Qty",parseStringToDoubleString(cellText,3));
                        } else if (headText.toLowerCase().contains("цена")) {
                            appendChildToXML(XmlDocument, element, "_Price",parseStringToDoubleString(cellText,2),cellText);
                        } else if (headText.toLowerCase().contains("сумма")) {
                            appendChildToXML(XmlDocument, element, "_SumWithTax",parseStringToDoubleString(cellText,2));
                            tableSum += Double.parseDouble(cellText.replaceAll("[^0-9,\\.]","").replace(",","."));
                        }
                    }
                }

                appendChildToXML(XmlDocument, rootElement, "_SumTable1",parseStringToDoubleString(tableSum.toString(),2),tableSum.toString().replace(".",","));
            }
        }
    }

    private void parseAktDocument(String textForParsing, int pageCount, Document XmlDocument) throws IOException {

        double sum = 0;
        double taxSum = 0;
        boolean taxIncluded = true;

        Element mainElement = XmlDocument.createElement("form:Documents");
        mainElement.setAttribute("xmlns:form", "http://www.abbyy.com/FlexiCapture/Schemas/Export/FormData.xsd");
        mainElement.setAttribute("xmlns:addData", "http://www.abbyy.com/FlexiCapture/Schemas/Export/AdditionalFormData.xsd");
        mainElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");

        XmlDocument.appendChild(mainElement);

        Element rootElement = XmlDocument.createElement("_" + docType);
        rootElement.setAttribute("xmlns:_" + docType, "http://www.abbyy.com/FlexiCapture/Schemas/Export/" + docType + ".xsd");
        rootElement.setAttribute("addData:DocumentState", "Корректен");
        rootElement.setAttribute("addData:ImagePath", filePathPDF.substring(filePathPDF.lastIndexOf("\\")+1));
        mainElement.appendChild(rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Акт_выполненных_работ", "АктВыполненныхРабот");
        //appendChildToXML(XmlDocument, rootElement, "_TemplateName", "documentType");
        //appendChildToXML(XmlDocument, rootElement, "_TemplateVersion", "");
        appendChildToXML(XmlDocument, rootElement, "_DocType", "АктВыполненныхРабот");

        for (String line: textForParsing.split("\r\n")) {
            if (line.contains("Акт №")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DocNum", "Акт №", "от", true);
                if (line.contains("от")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DocDate", "от", "г.",true);
                }
            }

            if (line.contains("Исполнитель:")) {
                appendChildToXML(line, XmlDocument, rootElement, "_IssCompany", "Исполнитель:", ",",true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssINN", "ИНН", ",",true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssKPP", "КПП", ",",true);
                }
            }

            if (line.contains("Заказчик:")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DesCompany", "Заказчик:", ",", true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesINN", "ИНН", ",", true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesKPP", "КПП", ",", true);
                }
            }

            if (line.contains("Итого") || line.contains("В том числе НДС")) {
                double sum1 = Double.parseDouble(line.replaceAll("[^0-9,\\.]", "").replace(",", "."));

                if (line.contains("Итого")) {
                    sum = sum1;
                }

                if (line.contains("Всего к оплате")) {
                    if (Double.compare(sum, sum1) != 0) {
                        taxIncluded = false;
                        sum = sum1;
                    }
                } else {
                    taxIncluded = true;
                }

                if (line.contains("В том числе НДС")) {
                    taxSum = sum1;
                }
            }
        }


        //Getting tabled data from PDF if exists
        //TODO
        getTabledDataFromAktDocument(XmlDocument, rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Sum", parseStringToDoubleString(String.valueOf(sum),2));
        appendChildToXML(XmlDocument, rootElement, "_Sum_Calc", parseStringToDoubleString(String.valueOf(sum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxSum", parseStringToDoubleString(String.valueOf(taxSum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxIncluded", Boolean.toString(taxIncluded));
        appendChildToXML(XmlDocument, rootElement, "_DocCur", "руб.");
        appendChildToXML(XmlDocument, rootElement, "_Pages", String.valueOf(pageCount));
        //TODO
        appendChildToXML(XmlDocument, rootElement, "_FileName", "inputFileName");
    }

    private void getTabledDataFromAktDocument(Document XmlDocument, Element rootElement) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(new File(filePathPDF))) {

            NurminenDetectionAlgorithm detectionAlgorithm = new NurminenDetectionAlgorithm();

            ExtractionAlgorithm algExtractor;

            SpreadsheetExtractionAlgorithm extractor=new SpreadsheetExtractionAlgorithm();

            ObjectExtractor extractor1 = new ObjectExtractor(pdfDoc);
            PageIterator pages = extractor1.extract(pageNumbers);
            List<Table> tables=new ArrayList<Table>();
            while (pages.hasNext()) {
                Page page = pages.next();
                if (extractor.isTabular(page)) {
                    algExtractor=new SpreadsheetExtractionAlgorithm();
                }
                else
                    algExtractor=new BasicExtractionAlgorithm();

                List<Rectangle> tablesOnPage = detectionAlgorithm.detect(page);


                for (Rectangle guessRect : tablesOnPage) {
                    Page guess = page.getArea(guessRect);
                    tables.addAll((List<Table>) algExtractor.extract(guess));
                }

            }

            boolean foundTable = false;
            String[][] dataArray = null;

            for(Table table: tables) {
                List<List<RectangularTextContainer>> rows = table.getRows();

                for(int i=0, ia = 0; i<rows.size(); i++, ia++) {

                    List<RectangularTextContainer> cells = rows.get(i);

                    for(int j=0; j<cells.size(); j++) {
                        String cellString = cells.get(j).getText();

                        if (!foundTable) {
                            if ((cellString.equals("N") || cellString.equals("No") || cellString.equals("№"))) {
                                foundTable = true;
                                dataArray = new String[rows.size()-i][cells.size()];
                                ia = 0;
                                dataArray[ia][j] = cellString;
                                //break;
                            } else {
                                continue;
                            }
                        } else {
                            dataArray[ia][j] = cellString;
                        }
                    }
                }
                //break;
            }

            if (foundTable) {
                Element element = XmlDocument.createElement("_Table1");
                rootElement.appendChild(element);

                //TODO
                /*<_Code />
                <_TaxRate />
                <_TaxSum xsi:nil="true" />
                <_TaxFree />*/
                Double tableSum = 0.0;
                for (int i = 1; i < dataArray.length; i++) {
                    for (int j = 0; j < dataArray[i].length; j++) {
                        String headText = dataArray[0][j];
                        String cellText;
                        if (j > 0 && dataArray[0][j-1].isEmpty()) {
                            cellText = dataArray[i][j-1] + " " + dataArray[i][j];
                        } else {
                            cellText = dataArray[i][j];
                        }
                        if (headText.equals("N") || headText.equals("No") || headText.equals("№")) {
                            appendChildToXML(XmlDocument, element, "_Position",cellText);
                        } else if (headText.toLowerCase().contains("наименование")) {
                            appendChildToXML(XmlDocument, element, "_Descript",cellText);
                        } else if (headText.toLowerCase().contains("ед")) {
                            appendChildToXML(XmlDocument, element, "_Unit",cellText);
                        } else if (headText.toLowerCase().contains("кол")) {
                            appendChildToXML(XmlDocument, element, "_Qty",parseStringToDoubleString(cellText,3));
                        } else if (headText.toLowerCase().contains("цена")) {
                            appendChildToXML(XmlDocument, element, "_Price",parseStringToDoubleString(cellText,2),cellText);
                        } else if (headText.toLowerCase().contains("сумма")) {
                            appendChildToXML(XmlDocument, element, "_SumWithTax",parseStringToDoubleString(cellText,2));
                            tableSum += Double.parseDouble(cellText.replaceAll("[^0-9,\\.]","").replace(",","."));
                        }
                    }
                }

                appendChildToXML(XmlDocument, rootElement, "_SumTable1",parseStringToDoubleString(tableSum.toString(),2),tableSum.toString().replace(".",","));
            }
        }
    }

    private void parseNakladnayaDocument(String textForParsing, int pageCount, Document XmlDocument) throws IOException {

        double sum = 0;
        double taxSum = 0;
        boolean taxIncluded = false;

        Element mainElement = XmlDocument.createElement("form:Documents");
        mainElement.setAttribute("xmlns:form", "http://www.abbyy.com/FlexiCapture/Schemas/Export/FormData.xsd");
        mainElement.setAttribute("xmlns:addData", "http://www.abbyy.com/FlexiCapture/Schemas/Export/AdditionalFormData.xsd");
        mainElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");

        XmlDocument.appendChild(mainElement);

        Element rootElement = XmlDocument.createElement("_" + docType);
        rootElement.setAttribute("xmlns:_" + docType, "http://www.abbyy.com/FlexiCapture/Schemas/Export/" + docType + ".xsd");
        rootElement.setAttribute("addData:DocumentState", "Корректен");
        rootElement.setAttribute("addData:ImagePath", filePathPDF.substring(filePathPDF.lastIndexOf("\\")+1));
        mainElement.appendChild(rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Универсальный_передаточный_документ", "УПД");
        appendChildToXML(XmlDocument, rootElement, "_TemplateName", "documentType");
        appendChildToXML(XmlDocument, rootElement, "_TemplateVersion", "");
        appendChildToXML(XmlDocument, rootElement, "_DocType", "УПД");

        for (String line : textForParsing.split("\r\n")) {
            if (line.contains("Счет на оплату №")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DocNum", "Счет на оплату №", "от", true);
                if (line.contains("от")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DocDate", "от", "г.", true);
                }
            }

            if (line.contains("Поставщик")) {
                appendChildToXML(line, XmlDocument, rootElement, "_IssCompany", "Поставщик", ",", true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssINN", "ИНН", ",", true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_IssKPP", "КПП", ",", true);
                }
            }

            if (line.contains("Покупатель")) {
                appendChildToXML(line, XmlDocument, rootElement, "_DesCompany", "Покупатель", ",", true);

                if (line.contains("ИНН")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesINN", "ИНН", ",", true);
                }
                if (line.contains("КПП")) {
                    appendChildToXML(line, XmlDocument, rootElement, "_DesKPP", "КПП", ",", true);
                }
            }

            if (line.contains("Итого") || line.contains("Всего к оплате") || line.contains("В том числе НДС")) {
                double sum1;

                try {
                    sum1 = Double.parseDouble(line.replaceAll("[^0-9,\\.]", "").replace(",", "."));
                } catch (NumberFormatException e) {
                    sum1 = 0;
                }

                if (line.contains("Итого")) {
                    sum = sum1;
                }

                if (line.contains("Всего к оплате")) {
                    if (Double.compare(sum, sum1) == 0) {
                        taxIncluded = true;
                    } else {
                        sum = sum1;
                    }
                }
                if (line.contains("В том числе НДС")) {
                    taxSum = sum1;
                }
            }

        }


        //Getting tabled data from PDF if exists
        //TODO
        getTabledDataFromNakladnayaDocument(XmlDocument, rootElement);

        appendChildToXML(XmlDocument, rootElement, "_Sum", parseStringToDoubleString(String.valueOf(sum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxSum", parseStringToDoubleString(String.valueOf(taxSum),2));
        appendChildToXML(XmlDocument, rootElement, "_TaxIncluded", Boolean.toString(taxIncluded));
        appendChildToXML(XmlDocument, rootElement, "_DocCur", "руб.");
        appendChildToXML(XmlDocument, rootElement, "_Pages", String.valueOf(pageCount));
        //TODO
        appendChildToXML(XmlDocument, rootElement, "_FileName", "inputFileName");
    }

    private void getTabledDataFromNakladnayaDocument(Document XmlDocument, Element rootElement) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(new File(filePathPDF))) {

            NurminenDetectionAlgorithm detectionAlgorithm = new NurminenDetectionAlgorithm();

            ExtractionAlgorithm algExtractor;

            SpreadsheetExtractionAlgorithm extractor=new SpreadsheetExtractionAlgorithm();

            ObjectExtractor extractor1 = new ObjectExtractor(pdfDoc);
            PageIterator pages = extractor1.extract(pageNumbers);
            List<Table> tables=new ArrayList<Table>();
            while (pages.hasNext()) {
                Page page = pages.next();
                if (extractor.isTabular(page)) {
                    algExtractor=new SpreadsheetExtractionAlgorithm();
                }
                else
                    algExtractor=new BasicExtractionAlgorithm();

                List<Rectangle> tablesOnPage = detectionAlgorithm.detect(page);


                for (Rectangle guessRect : tablesOnPage) {
                    Page guess = page.getArea(guessRect);
                    tables.addAll((List<Table>) algExtractor.extract(guess));
                }

            }

            boolean foundTable = false;
            String[][] dataArray = null;

            for(Table table: tables) {
                List<List<RectangularTextContainer>> rows = table.getRows();

                for(int i=0, ia = 0; i<rows.size(); i++, ia++) {

                    List<RectangularTextContainer> cells = rows.get(i);

                    for(int j=0; j<cells.size(); j++) {
                        String cellString = cells.get(j).getText();

                        if (!foundTable) {
                            if ((cellString.equals("N") || cellString.equals("No") || cellString.equals("№"))) {
                                foundTable = true;
                                dataArray = new String[rows.size()-i][cells.size()];
                                ia = 0;
                                dataArray[ia][j] = cellString;
                                //break;
                            } else {
                                continue;
                            }
                        } else {
                            dataArray[ia][j] = cellString;
                        }
                    }
                }
                //break;
            }

            if (foundTable) {
                Element element = XmlDocument.createElement("_Table1");
                rootElement.appendChild(element);

                //TODO
                /*<_Code />
                <_TaxRate />
                <_TaxSum xsi:nil="true" />
                <_TaxFree />*/
                Double tableSum = 0.0;
                for (int i = 1; i < dataArray.length; i++) {
                    for (int j = 0; j < dataArray[i].length; j++) {
                        String headText = dataArray[0][j];
                        String cellText;
                        if (j > 0 && dataArray[0][j-1].isEmpty()) {
                            cellText = dataArray[i][j-1] + " " + dataArray[i][j];
                        } else {
                            cellText = dataArray[i][j];
                        }
                        if (headText.equals("N") || headText.equals("No") || headText.equals("№")) {
                            appendChildToXML(XmlDocument, element, "_Position",cellText);
                        } else if (headText.toLowerCase().contains("товары")) {
                            appendChildToXML(XmlDocument, element, "_Descript",cellText);
                        } else if (headText.toLowerCase().contains("ед")) {
                            appendChildToXML(XmlDocument, element, "_Unit",cellText);
                        } else if (headText.toLowerCase().contains("кол")) {
                            appendChildToXML(XmlDocument, element, "_Qty",parseStringToDoubleString(cellText,3));
                        } else if (headText.toLowerCase().contains("цена")) {
                            appendChildToXML(XmlDocument, element, "_Price",parseStringToDoubleString(cellText,2),cellText);
                        } else if (headText.toLowerCase().contains("сумма")) {
                            appendChildToXML(XmlDocument, element, "_SumWithTax",parseStringToDoubleString(cellText,2));
                            tableSum += Double.parseDouble(cellText.replaceAll("[^0-9,\\.]","").replace(",","."));
                        }
                    }
                }

                appendChildToXML(XmlDocument, rootElement, "_SumTable1",parseStringToDoubleString(tableSum.toString(),2),tableSum.toString().replace(".",","));
            }
        }
    }

    private void appendChildToXML(String line, Document XmlDocument, Element rootElement, String elementNameXml, String elementName, String nextString, boolean useFromIndex) {
        Element element = XmlDocument.createElement(elementNameXml);
        String stringToAppend = "";
        StringBuilder confidenceString = new StringBuilder();

        if (!elementName.equals("")) {
            int fromIndex = useFromIndex ? line.indexOf(elementName) + elementName.length() : line.length();
            int endIndex = line.indexOf(nextString, fromIndex);
            String tempString = "";
            if (endIndex > fromIndex){
                tempString = line.substring(fromIndex, endIndex).trim();
            }
            if (!elementNameXml.equals("_DocDate") || tempString.isEmpty()) {
                stringToAppend = tempString;
            } else {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("ru"));
                LocalDate dateTime = LocalDate.parse(tempString, formatter);
                stringToAppend = dateTime.toString();
            }

        } else {
            stringToAppend = line;
        }

        element.appendChild(XmlDocument.createTextNode(stringToAppend));
        for (char ch: stringToAppend.toCharArray()) {
            confidenceString.append(ch == 32 || symbolConfidenceHashMap.get(String.valueOf(ch)) > 90 ? "0" : "1");
        }
        element.setAttribute("addData:SuspiciousSymbols", confidenceString.toString());
        rootElement.appendChild(element);
    }

    private void appendChildToXML(Document XmlDocument, Element rootElement, String name, String value) {

        Element element = XmlDocument.createElement(name);
        element.appendChild(XmlDocument.createTextNode(value));
        element.setAttribute("addData:SuspiciousSymbols", getSuspiciousSymbols(value));

        rootElement.appendChild(element);

    }

    private void appendChildToXML(Document XmlDocument, Element rootElement, String name, String value, String recognizedValue) {

        Element element = XmlDocument.createElement(name);
        element.appendChild(XmlDocument.createTextNode(value));
        element.setAttribute("addData:SuspiciousSymbols", getSuspiciousSymbols(value));
        element.setAttribute("addData:RecognizedValue", recognizedValue);

        rootElement.appendChild(element);

    }

    private String getSuspiciousSymbols(String value) {
        return value.length() > 0 ? String.format("%0" + value.length() + "d", 0) : "";
    }

    private String parseStringToDoubleString(String value, int symbolsAfterDot) {
        return String.format("%." + symbolsAfterDot + "f",Double.parseDouble(value.replace(",",".").replaceAll("[^0-9,\\.]",""))).replace(",",".");
    }

    private String getOutputFileName(String extension) {
        return outputPath + currentDocType + "_p" + pageNumbers.get(0) + "." + extension;
    }

}
