package com.manara.backend.common.file;

import com.manara.backend.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Decodes an uploaded image completely and writes a new file from its pixels alone.
 *
 * <p>The check this replaces read an image's width and height from its header and then stored the
 * original bytes. Nothing after the header was ever looked at, so nothing after it was ever refused:
 * a PNG signature and header with no pixel data was accepted, and so was a valid PNG with a script
 * appended after its end, both served back byte for byte. Decoding every pixel refuses the first.
 * Writing the output from the decoded raster, rather than copying the input, means nothing else the
 * uploader wrote — trailing bytes, EXIF, text chunks, ICC profiles, comments — reaches the disk,
 * whether or not anyone thought to look for it.
 *
 * <p>Every refusal is a {@link BusinessException}, answered 400. An {@link IOException} out of
 * {@link #reencode} means writing the output failed, which is the server's problem, not the upload's.
 */
@Slf4j
@Component
class UploadedImageReencoder {

    /** The format a stored file was written in, which its extension — and so its served type — follows. */
    enum StoredFormat {
        PNG("png", "png"),
        JPEG("jpeg", "jpg");

        private final String writerName;
        private final String extension;

        StoredFormat(String writerName, String extension) {
            this.writerName = writerName;
            this.extension = extension;
        }

        String extension() {
            return extension;
        }

        boolean canEncode(BufferedImage image) {
            return ImageIO.getImageWriters(ImageTypeSpecifier.createFromRenderedImage(image), writerName).hasNext();
        }
    }

    /**
     * Source formats accepted, by the name of the reader that actually decoded the bytes. The JDK also
     * reads BMP, TIFF and WBMP, which were accepted under a {@code .png} name before; they are not
     * formats this product uses. WebP is on the upload allow-list, but the JDK has no WebP reader and
     * none is on the classpath, so a WebP file finds no reader here and is refused as not an image.
     */
    private static final Set<String> APPROVED_SOURCE_FORMATS = Set.of("png", "jpeg", "gif");

    /** One generation of loss on a photo that is re-encoded; not visible at 0.9, and about the size a phone writes. */
    private static final float JPEG_QUALITY = 0.9f;

    /** How long an upload waits for decode memory before being told to retry, rather than holding its thread indefinitely. */
    private static final long DECODE_WAIT_SECONDS = 20;

    /** The copy made to turn or convert an image is always {@code TYPE_INT_RGB} or {@code TYPE_INT_ARGB}. */
    private static final int WORKING_COPY_BYTES_PER_PIXEL = 4;

    private static final long MIB = 1024 * 1024;
    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
    private static final int EXIF_ORIENTATION_TAG = 0x0112;

    private final UploadProperties properties;
    private final int decodeBudgetMib;

    /**
     * One permit per MiB of decoded raster, shared by every upload in flight. Parallel uploads queue
     * for it instead of exhausting the heap together. Fair, so a large image is not starved by a
     * stream of small ones.
     */
    private final Semaphore decodeBudget;

    UploadedImageReencoder(UploadProperties properties) {
        this.properties = properties;
        this.decodeBudgetMib = Math.clamp(properties.decodeMemoryBudget().toMegabytes(), 1, Integer.MAX_VALUE);
        this.decodeBudget = new Semaphore(decodeBudgetMib, true);
    }

    /**
     * Decodes {@code source} and writes it afresh to {@code destination}.
     *
     * @return the format written, from which the stored name's extension is taken
     * @throws BusinessException if the bytes are not an acceptable image
     * @throws IOException       if the output could not be written
     */
    StoredFormat reencode(byte[] source, Path destination) throws IOException {
        ImageReader reader = null;
        int reserved = 0;
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            StoredFormat target;
            BufferedImage output;
            try {
                reader = approvedReaderFor(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                // Metadata is ignored by the decoder. EXIF orientation and APNG's animation chunk are
                // read from the bytes directly, and asking the PNG reader for metadata would have it
                // inflate compressed text chunks: a decompression bomb of its own.
                reader.setInput(input, false, true);

                // Everything up to the decode reads headers only, so an image refused for its size
                // or its frames is refused before a single pixel is allocated.
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                checkDimensions(width, height);
                if (isAnimated(reader, format, source)) {
                    throw new BusinessException("error.file.animated");
                }

                int cost = decodeCostMib(reader, (long) width * height);
                reserve(cost);
                reserved = cost;
                BufferedImage decoded = decodeCompletely(reader);

                // A JPEG is re-encoded as a JPEG, anything else as a PNG. Re-encoding a screenshot or
                // a logo as JPEG would blur its edges and text; re-encoding a photo as PNG would
                // multiply its size several times over. Alpha always means PNG, since JPEG has none.
                target = "jpeg".equals(format) && !decoded.getColorModel().hasAlpha()
                        ? StoredFormat.JPEG
                        : StoredFormat.PNG;
                int orientation = "jpeg".equals(format) ? exifOrientation(source) : 1;
                output = prepareForWriting(decoded, orientation, target);
            } catch (IOException | RuntimeException ex) {
                if (ex instanceof BusinessException refusal) {
                    throw refusal;
                }
                // A truncated or malformed file, a colour model the decoder cannot handle, or a
                // decoder tripping over hostile input: all the same answer, and none of them a 500.
                log.debug("Refusing an upload that did not decode: {}", ex.toString());
                throw new BusinessException("error.file.notAnImage");
            }
            write(output, target, destination);
            return target;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (reserved > 0) {
                decodeBudget.release(reserved);
            }
        }
    }

    private static ImageReader approvedReaderFor(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        while (readers.hasNext()) {
            ImageReader candidate = readers.next();
            if (APPROVED_SOURCE_FORMATS.contains(candidate.getFormatName().toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            candidate.dispose();
        }
        throw new BusinessException("error.file.notAnImage");
    }

    /**
     * Width and height each, as well as their product: an image within the pixel limit can still be
     * absurdly thin, and decoders size their row buffers from the width.
     */
    private void checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new BusinessException("error.file.notAnImage");
        }
        if (width > properties.maxDimension() || height > properties.maxDimension()
                || (long) width * height > properties.maxPixels()) {
            throw new BusinessException("error.file.imageTooLarge");
        }
    }

    /**
     * Whether the file is an animation, which is refused rather than flattened.
     *
     * <p>Keeping only the first frame would store a picture the uploader never saw: an animation's
     * first frame is often blank, or only the region that changes. Covers and banners have no reason
     * to move, so the uploader is told to choose a still image instead of being silently given one.
     */
    private static boolean isAnimated(ImageReader reader, String format, byte[] source) throws IOException {
        return switch (format) {
            // Counting frames walks the file's block structure without decoding any of them.
            case "gif" -> reader.getNumImages(true) > 1;
            // The JDK's PNG reader does not know APNG, and would decode only the default image.
            case "png" -> pngDeclaresAnimation(source);
            default -> false;
        };
    }

    /**
     * The memory decoding this image will take, estimated from its header: the raster the reader
     * will allocate, plus one working copy in case it has to be turned or converted.
     */
    private int decodeCostMib(ImageReader reader, long pixels) throws IOException {
        ImageTypeSpecifier destination = reader.getImageTypes(0).next();
        int bytesPerPixel = (destination.getColorModel().getPixelSize() + 7) / 8;
        long mib = (pixels * (bytesPerPixel + WORKING_COPY_BYTES_PER_PIXEL) + MIB - 1) / MIB;
        // Capped at the whole budget, so an image within the limits can always be decoded eventually,
        // even if the budget is configured smaller than it needs.
        return Math.clamp(mib, 1, decodeBudgetMib);
    }

    private void reserve(int mib) {
        try {
            if (decodeBudget.tryAcquire(mib, DECODE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        throw new BusinessException("error.file.busy");
    }

    /**
     * Decodes every pixel, refusing a file the decoder had to guess at.
     *
     * <p>The JPEG decoder does not fail on a truncated file: it warns, fills the missing rows with grey
     * and returns an image. Accepting that would store a picture nobody sent, so a warning refuses
     * the file as an error would.
     */
    private static BufferedImage decodeCompletely(ImageReader reader) throws IOException {
        List<String> warnings = new ArrayList<>();
        reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
        BufferedImage image = reader.read(0);
        if (!warnings.isEmpty()) {
            throw new IIOException("decoder warnings: " + warnings);
        }
        return image;
    }

    /**
     * The decoded image, upright and in a pixel layout {@code target}'s writer can encode.
     *
     * <p>Most images need neither and are returned as decoded. The rest are drawn once into an 8-bit
     * sRGB copy, which is also what becomes of a CMYK JPEG or a 16-bit PNG: neither is worth storing
     * as such for display in a browser.
     */
    private static BufferedImage prepareForWriting(BufferedImage decoded, int orientation, StoredFormat target) {
        if (orientation == 1 && decoded.getType() != BufferedImage.TYPE_CUSTOM && target.canEncode(decoded)) {
            return decoded;
        }
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        boolean swapsSides = orientation >= 5;
        BufferedImage copy = new BufferedImage(
                swapsSides ? height : width,
                swapsSides ? width : height,
                decoded.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            // Src rather than the default SrcOver, so translucent pixels are copied, not blended.
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(decoded, orientationTransform(orientation, width, height), null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    /** Maps the stored pixels onto the upright image: 2–4 are mirrors and a half turn, 5–8 swap the sides. */
    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);       // mirrored horizontally
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height); // turned 180°
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);      // mirrored vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);            // transposed
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);      // turned 90° clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width); // transversed
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);       // turned 90° anticlockwise
            default -> new AffineTransform();
        };
    }

    private static void write(BufferedImage image, StoredFormat format, Path destination) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format.writerName).next();
        try (ImageOutputStream output = new FileImageOutputStream(destination.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == StoredFormat.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.setOutput(output);
            // No metadata is passed in: the writer builds its own from the pixel layout alone, so
            // the file holds the header, palette and pixels it needs and nothing the uploader wrote.
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * The EXIF orientation of a JPEG: 1, "as stored", when there is none or it cannot be read.
     *
     * <p>A phone stores the sensor's pixels as they came off it and records in this tag how to turn
     * them. Browsers honour the tag, so dropping EXIF without applying it first would store every
     * portrait photo on its side. A tag that cannot be read leaves the image as stored rather than
     * refusing a photo over its metadata.
     */
    static int exifOrientation(byte[] jpeg) {
        int position = 2; // past SOI
        while (position + 4 <= jpeg.length && (jpeg[position] & 0xFF) == 0xFF) {
            int marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xFF) { // a fill byte before the marker
                position++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) { // start of scan, or end of image: no EXIF follows
                return 1;
            }
            int length = (jpeg[position + 2] & 0xFF) << 8 | jpeg[position + 3] & 0xFF;
            int payload = position + 4;
            int segmentEnd = position + 2 + length;
            if (length < 2 || segmentEnd > jpeg.length) {
                return 1;
            }
            if (marker == 0xE1 && segmentEnd - payload > EXIF_HEADER.length
                    && Arrays.equals(jpeg, payload, payload + EXIF_HEADER.length, EXIF_HEADER, 0, EXIF_HEADER.length)) {
                int tiff = payload + EXIF_HEADER.length;
                return tiffOrientation(ByteBuffer.wrap(jpeg, tiff, segmentEnd - tiff).slice());
            }
            position = segmentEnd;
        }
        return 1;
    }

    /** The Orientation entry of a TIFF structure's first IFD, which is where EXIF keeps it. */
    private static int tiffOrientation(ByteBuffer tiff) {
        try {
            short byteOrder = tiff.getShort(0);
            if (byteOrder == 0x4949) { // "II"
                tiff.order(ByteOrder.LITTLE_ENDIAN);
            } else if (byteOrder != 0x4D4D) { // "MM"
                return 1;
            }
            if (tiff.getShort(2) != 42) {
                return 1;
            }
            int ifd = tiff.getInt(4);
            int entries = tiff.getShort(ifd) & 0xFFFF;
            for (int i = 0; i < entries; i++) {
                int entry = ifd + 2 + i * 12;
                // Tag, then type 3 (SHORT), whose single value sits at the start of the value field.
                if ((tiff.getShort(entry) & 0xFFFF) == EXIF_ORIENTATION_TAG && tiff.getShort(entry + 2) == 3) {
                    int value = tiff.getShort(entry + 8) & 0xFFFF;
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
        } catch (IndexOutOfBoundsException ex) {
            // A malformed IFD: the orientation is unknown, which is the same as none.
        }
        return 1;
    }

    /** Whether a PNG carries APNG's animation control chunk, which the specification places before the first IDAT. */
    private static boolean pngDeclaresAnimation(byte[] png) {
        int position = 8; // past the signature
        while (position + 8 <= png.length) {
            int length = ByteBuffer.wrap(png, position, 4).getInt();
            String type = new String(png, position + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("acTL")) {
                return true;
            }
            if (type.equals("IDAT") || length < 0 || length > png.length - position - 12) {
                return false;
            }
            position += 12 + length;
        }
        return false;
    }
}
