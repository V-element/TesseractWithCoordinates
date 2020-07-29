package ru.omega;

import magick.*;

public class ImageMagick {
    static {
        //System.setProperty("java.library.path", "C:/temp/");
        //System.out.println("java.library.path is: " + System.getProperty("java.library.path"));
        System.loadLibrary("jmagick");
    }

   /* public static void main(String[] args) {

        try {
            ImageInfo info = new ImageInfo("C:\\temp\\img-20200629-wa0001.jpg");
            MagickImage image = new MagickImage(info);
            //image.autoOrientImage();
            //image.despeckleImage();
            image.enhanceImage();
            image.setGrayscale();
            image.setFilter(8);
            //image.setBackgroundColor(new PixelPacket(0,0,0,50));
            image.reduceNoiseImage(3);
            image.contrastImage(true);
            image.contrastImage(true);
            image.contrastImage(true);
            image.contrastImage(true);
            //image.modulateImage("0x130+30");
            //image.setFilter(2);
            //image.setNumberColors(2);
            //image.blurImage(150,300);
            //image.negateImage(100);
            //image.setNumberColors(10);
            //image.thresholdImage(40000);
            image.medianFilterImage(10);
            //image.solarizeImage(1000);
            image.setFileName("text.jpg");
            image.writeImage(info);
        } catch (MagickException e) {
            e.printStackTrace();
        }
    }*/
}
