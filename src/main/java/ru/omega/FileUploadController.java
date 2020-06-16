package ru.omega;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
                                         RedirectAttributes redirectAttributes, Model model) throws IOException, TesseractException {

        byte[] bytes = file.getBytes();
        Path path = Paths.get(Objects.requireNonNull(file.getOriginalFilename()));
        Files.write(path, bytes);


        File convertedFile = convert(file);
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("tessdata");
        tesseract.setTessVariable("user_defined_dpi", "300");
        //tesseract.setLanguage("rus");
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
