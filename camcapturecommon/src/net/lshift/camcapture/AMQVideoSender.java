package net.lshift.camcapture;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import java.awt.image.BufferedImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

import com.rabbitmq.client.AMQP.BasicProperties;

public class AMQVideoSender extends AMQPacketProducer {
    public AMQVideoSender(String host, String exchange, String routingKey)
        throws IOException
    {
	super(host, exchange, routingKey);
    }

    public void sendFrames(int targetFrameRate, java.util.Iterator frameProducer)
        throws IOException
    {
        final int protocolVersion = 2;
        
        if (targetFrameRate == 0) {
            targetFrameRate = 5;
        }

	BasicProperties prop =
	    new BasicProperties(AMQVideo.MIME_TYPE, null, null, new Integer(1),
				new Integer(0), null, null, null,
				null, null, null, null,
				null, null);

	resetStatistics();

        AMQVideoDecoder decoder = new AMQVideoDecoder();
        BufferedImage image = null;

	while (frameProducer.hasNext()) {
	    long frameProductionTime;

            while (((1000.0 * frameCount) /
                    ((frameProductionTime = System.currentTimeMillis())
		     - startTime)) > targetFrameRate)
	    {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {}
            }

            BufferedImage nextImage = (BufferedImage) frameProducer.next();
            if (nextImage == null) {
                continue;
            }

            if ((image == null) ||
                (image.getWidth() != nextImage.getWidth()) ||
                (image.getHeight() != nextImage.getHeight())) {
                image =  new BufferedImage(nextImage.getWidth(),
                                           nextImage.getHeight(),
                                           BufferedImage.TYPE_INT_RGB);
            }

            float compressionQuality;
            char frameKind;

            if ((frameCount % 15) == 0) {
                copyImage(nextImage, image);
                compressionQuality = 0.4F;
                frameKind = 'I';
            } else {
                subtractImages(nextImage, decoder.getCurrentImage(), image);
                compressionQuality = 0.3F;
                frameKind = 'P';
            }

	    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
	    DataOutputStream s = new DataOutputStream(byteStream);
	    s.write(protocolVersion);
	    s.writeLong(frameProductionTime);
            s.write((int) frameKind);
            writeCompressed(image, compressionQuality, s);
	    s.flush();
	    byteStream.flush();
            byte[] compressedFrame = byteStream.toByteArray();

	    publishPacket(prop, compressedFrame);

            decoder.handleFrame(compressedFrame);

	    reportStatistics("Video");
	}
    }

    public static void copyImage(BufferedImage sourceImage,
                                 BufferedImage destImage) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        int[] pixels = new int[width * height];
        sourceImage.getRGB(0, 0, width, height, pixels, 0, width);
        destImage.setRGB(0, 0, width, height, pixels, 0, width);
    }

    public static void subtractImages(BufferedImage newImage,
                                      BufferedImage oldImage,
                                      BufferedImage deltaImage)
    {
        int width = newImage.getWidth();
        int height = newImage.getHeight();
        int[] newLine = new int[width];
        int[] oldLine = new int[width];
        int[] deltaLine = new int[width];

        for (int i = 0; i < height; i++) {
            newImage.getRGB(0, i, width, 1, newLine, 0, width);
            oldImage.getRGB(0, i, width, 1, oldLine, 0, width);
            for (int j = 0; j < width; j++) {
                deltaLine[j] = AMQVideo.kernelEncode(oldLine[j], newLine[j]);
            }
            deltaImage.setRGB(0, i, width, 1, deltaLine, 0, width);
        }
    }

    public static void writeCompressed(BufferedImage image,
                                       float compressionQuality,
                                       java.io.OutputStream outStream)
        throws IOException
    {
        ImageWriter writer = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
        ImageOutputStream ios = ImageIO.createImageOutputStream(outStream);
        writer.setOutput(ios);
        ImageWriteParam iwparam = new JPEGImageWriteParam(java.util.Locale.getDefault());
        iwparam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        iwparam.setCompressionQuality(compressionQuality);
        writer.write(null, new IIOImage(image, null, null), iwparam);
        ios.flush();
        writer.dispose();
        ios.close();
    }
}