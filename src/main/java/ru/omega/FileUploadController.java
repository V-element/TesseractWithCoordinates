package ru.omega;

import com.sun.jna.Pointer;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITessAPI.*;
import net.sourceforge.tess4j.TessAPI1;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.ImageIOHelper;
import net.sourceforge.tess4j.util.PdfUtilities;
import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Controller
public class FileUploadController {

    @GetMapping("/")
    public String index() {
        return "upload";
    }

    @PostMapping(value = "/upload")
    public RedirectView singleFileUpload(@RequestParam("file") MultipartFile file,
                                         RedirectAttributes redirectAttributes, Model model) throws IOException, TesseractException, JSONException {

        byte[] bytes = file.getBytes();
        Path path = Paths.get(Objects.requireNonNull(file.getOriginalFilename()));
        Files.write(path, bytes);


        File convertedFile = convert(file);

        //------------------------Working with coordinates block----------------------
        String datapath = "tessdata";
        String language = "rus+eng";//"eng";
        TessBaseAPI handle = TessAPI1.TessBaseAPICreate();


        File tiff = null;
        if (FilenameUtils.getExtension(convertedFile.getName()).equals("pdf")) {
            tiff = PdfUtilities.convertPdf2Tiff(convertedFile);
        } else {
            tiff = convertedFile;
        }

        BufferedImage image = ImageIO.read(new FileInputStream(tiff));

        ByteBuffer buf = ImageIOHelper.convertImageData(image);

        int bpp = image.getColorModel().getPixelSize();
        int bytespp = bpp / 8;
        int bytespl = (int) Math.ceil(image.getWidth() * bpp / 8.0);

        TessAPI1.TessBaseAPIInit3(handle, datapath, language);
        TessAPI1.TessBaseAPISetPageSegMode(handle, TessPageSegMode.PSM_SINGLE_BLOCK);
        TessAPI1.TessBaseAPISetImage(handle, buf, image.getWidth(), image.getHeight(), bytespp, bytespl);
        TessAPI1.TessBaseAPISetSourceResolution(handle, 400);


        ETEXT_DESC monitor = new ETEXT_DESC();
        TessAPI1.TessBaseAPIRecognize(handle, monitor);
        TessResultIterator ri = TessAPI1.TessBaseAPIGetIterator(handle);
        TessPageIterator pi = TessAPI1.TessResultIteratorGetPageIterator(ri);
        TessAPI1.TessPageIteratorBegin(pi);
        int level = TessPageIteratorLevel.RIL_WORD;
        int height = image.getHeight();

        StringBuilder jsonString = new StringBuilder();
        JSONArray jsonArray = new JSONArray();

        do {
            Pointer ptr = TessAPI1.TessResultIteratorGetUTF8Text(ri, level);
            String word = ptr.getString(0);
            TessAPI1.TessDeleteText(ptr);
            float confidence = TessAPI1.TessResultIteratorConfidence(ri, level);
            IntBuffer leftB = IntBuffer.allocate(1);
            IntBuffer topB = IntBuffer.allocate(1);
            IntBuffer rightB = IntBuffer.allocate(1);
            IntBuffer bottomB = IntBuffer.allocate(1);
            IntBuffer pointSizeB = IntBuffer.allocate(1);
            TessAPI1.TessPageIteratorBoundingBox(pi, level, leftB, topB, rightB, bottomB);
            int left = leftB.get();
            int top = topB.get();
            int right = rightB.get();
            int bottom = bottomB.get();
            int pointSize = pointSizeB.get();
            //System.out.println(String.format("%s %d %d %d %d %f", word, left, top, right, bottom, confidence));

            //Building JSON
            //jsonArray.put(word);
            jsonArray.put(new JSONObject()
                    .put("word", word.replace("<", "&lt;").replace(">","&gt;"))
                    .put("left", left)
                    .put("top", top)
                    .put("right", right)
                    .put("bottom", bottom));

            /*jsonString.append(new JSONObject()
                    .put("word", word)
                    .put("left", left)
                    .put("top", top)
                    .put("right", right)
                    .put("bottom", bottom)
                    .put("confidence", confidence)
                    .toString());
            jsonString.append("\n");*/

        } while (TessAPI1.TessPageIteratorNext(pi, level) == 1);

        List<JSONObject> list = new ArrayList<>();
        for(int i = 0; i < jsonArray.length(); i++) {
            list.add(jsonArray.getJSONObject(i));
        }

        list = sortList(list);

        StringBuilder finalString = new StringBuilder();

        int currentBottom = 0;
        for(int i = 0; i < jsonArray.length(); i++) {
            JSONObject CurrentJsonObject = jsonArray.getJSONObject(i);
            int heightBetweenLines = Math.toIntExact(Math.round((CurrentJsonObject.getInt("bottom") - CurrentJsonObject.getInt("top")) * 0.27));

            if (currentBottom != 0 && CurrentJsonObject.getInt("bottom") <= currentBottom+heightBetweenLines && CurrentJsonObject.getInt("bottom") >= currentBottom-heightBetweenLines) {
                CurrentJsonObject.put("bottom", currentBottom);
            } else {
                currentBottom = CurrentJsonObject.getInt("bottom");
            }
        }

        list = sortList(list);

        currentBottom = 0;
        int lastCoordinate = 0;
        for (JSONObject CurrentJsonObject: list) {
            if (CurrentJsonObject.getString("word").trim().equals(" ")) {
                continue;
            }

            int spaceWidth = Math.toIntExact(Math.round((CurrentJsonObject.getInt("bottom") - CurrentJsonObject.getInt("top")) * 0.27 / 0.33));
            if (currentBottom != 0 && CurrentJsonObject.getInt("bottom") == currentBottom) {
                int numberOfSpaces = (CurrentJsonObject.getInt("left") - lastCoordinate) / (spaceWidth == 0 ? 1 : spaceWidth);
                if (numberOfSpaces > 0) {
                    finalString.append(String.join("", Collections.nCopies(numberOfSpaces, "&nbsp")));
                } else {
                    finalString.append("&nbsp");
                }
            } else {
                currentBottom = CurrentJsonObject.getInt("bottom");
                finalString.append("<br />");
                finalString.append(String.join("", Collections.nCopies(CurrentJsonObject.getInt("left") / (spaceWidth == 0 ? 1 : spaceWidth), "&nbsp")));
            }
            finalString.append(CurrentJsonObject.getString("word"));
            lastCoordinate = CurrentJsonObject.getInt("right");
        }

        /*Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(datapath);
        tesseract.setTessVariable("user_defined_dpi", "400");
        tesseract.setLanguage(language);
        String text = tesseract.doOCR(convertedFile);
        redirectAttributes.addFlashAttribute("file", file);
        redirectAttributes.addFlashAttribute("text", text);*/
        redirectAttributes.addFlashAttribute("jsonString", finalString);
        redirectAttributes.addFlashAttribute("defaultString", TessAPI1.TessBaseAPIGetHOCRText(handle, 1).getString(0));
        return new RedirectView("result");
    }

    @RequestMapping("/result")
    public String result() {
        return "result";
    }

    public static File convert(MultipartFile file) throws IOException {
        File convertedFile = new File(Objects.requireNonNull(file.getOriginalFilename()));

        if (convertedFile.createNewFile()) {
            FileOutputStream fos = new FileOutputStream(convertedFile);
            fos.write(file.getBytes());
            fos.close();
        }
        return convertedFile;
    }

    public List<JSONObject> sortList(List<JSONObject> list) {
        list.sort((object1, object2) -> {
            int compare = 0;
            try {
                compare = Integer.compare(object1.getInt("bottom"), object2.getInt("bottom"));
                if (compare == 0) {
                    compare = Integer.compare(object1.getInt("left"), object2.getInt("left"));
                }
                return compare;
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return compare;
        });
        return list;
    }
}
