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
import java.util.Objects;

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
        String language = "rus";
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
        TessAPI1.TessBaseAPISetPageSegMode(handle, TessPageSegMode.PSM_AUTO);
        TessAPI1.TessBaseAPISetImage(handle, buf, image.getWidth(), image.getHeight(), bytespp, bytespl);

        ETEXT_DESC monitor = new ETEXT_DESC();
        TessAPI1.TessBaseAPIRecognize(handle, monitor);
        TessResultIterator ri = TessAPI1.TessBaseAPIGetIterator(handle);
        TessPageIterator pi = TessAPI1.TessResultIteratorGetPageIterator(ri);
        TessAPI1.TessPageIteratorBegin(pi);
        System.out.println("Bounding boxes:\nchar(s) left top right bottom confidence font-attributes");
        int level = TessAPI1.TessPageIteratorLevel.RIL_WORD;
        int height = image.getHeight();

        StringBuilder jsonString = new StringBuilder();

        do {
            Pointer ptr = TessAPI1.TessResultIteratorGetUTF8Text(ri, level);
            String word = ptr.getString(0);
            TessAPI1.TessDeleteText(ptr);
            float confidence = TessAPI1.TessResultIteratorConfidence(ri, level);
            IntBuffer leftB = IntBuffer.allocate(1);
            IntBuffer topB = IntBuffer.allocate(1);
            IntBuffer rightB = IntBuffer.allocate(1);
            IntBuffer bottomB = IntBuffer.allocate(1);
            TessAPI1.TessPageIteratorBoundingBox(pi, level, leftB, topB, rightB, bottomB);
            int left = leftB.get();
            int top = topB.get();
            int right = rightB.get();
            int bottom = bottomB.get();
            //System.out.println(String.format("%s %d %d %d %d %f", word, left, top, right, bottom, confidence));

            //Building JSON
            jsonString.append(new JSONObject()
                    .put("word", word)
                    .put("left", left)
                    .put("top", top)
                    .put("right", right)
                    .put("bottom", bottom)
                    .put("confidence", confidence)
                    .toString());
            jsonString.append("\n");

        } while (TessAPI1.TessPageIteratorNext(pi, level) == 1);

        System.out.println(jsonString);



        //------------------End of block-------------

        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(datapath);
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setLanguage(language);
        String text = tesseract.doOCR(convertedFile);
        redirectAttributes.addFlashAttribute("file", file);
        redirectAttributes.addFlashAttribute("text", text);
        return new RedirectView("result");
    }

    @RequestMapping("/result")
    public String result() {
        return "result";
    }

    public static File convert(MultipartFile file) throws IOException {
        File convertedFile = new File(Objects.requireNonNull(file.getOriginalFilename()));
        //File tiff = PdfUtilities.convertPdf2Tiff(convertedFile);
        //tiff.createNewFile();

        if (convertedFile.createNewFile()) {
            FileOutputStream fos = new FileOutputStream(convertedFile);
            fos.write(file.getBytes());
            fos.close();
        }
        return convertedFile;
    }

}
