package com.manara.backend.common.file;

import com.jayway.jsonpath.JsonPath;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What actually lands on disk when an upload is accepted.
 *
 * <p>A local penetration test (2026-09-10) found that the upload check read an image's header and
 * then stored the original bytes. A PNG signature and header with no pixel data at all was accepted
 * and served back byte for byte, and so was a valid PNG with a script appended after its end: nothing
 * past the header was ever looked at, so nothing past the header was ever refused.
 *
 * <p>Every accepted case here therefore reads the stored file back and decodes it, and every refused
 * case counts what is left in the directory. A refusal that leaves a temporary file behind is still a
 * write into a directory the web server hands out.
 *
 * <p>All images are generated in memory. The few malformed ones are built from a real encoder's output
 * with the damage applied on top, so each test says exactly which property of the file it is about.
 */
class UploadedImageReencodingTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@reencode.example";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final String SCRIPT = "<script>window.__audit=1</script>";

    /** The service only checks the role; nothing here needs the account to exist. */
    private static final User INSTRUCTOR = User.builder().role(Role.INSTRUCTOR).build();

    /** Created in a static initialiser for the reason given on {@code UploadAuthorizationTest}. */
    static final Path uploadDir = createIsolatedUploadDir();

    private static Path createIsolatedUploadDir() {
        try {
            return Files.createTempDirectory("manara-upload-reencode-test");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the test upload directory", e);
        }
    }

    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.uploads.dir", uploadDir::toString);
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FileUploadService fileUploadService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void startFromAnEmptyDirectory() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(candidate -> candidate.getEmail().endsWith(DOMAIN))
                .toList());
        emptyUploadDir();
    }

    @AfterAll
    static void removeTheDirectory() throws IOException {
        emptyUploadDir();
        Files.deleteIfExists(uploadDir);
    }

    // ------------------------------------------------------------ the finding

    @Test
    @DisplayName("The pentest's 33-byte PNG — signature and header, no pixels — is refused with a 400")
    void aHeaderWithNoPixelsIsRefused() throws Exception {
        byte[] headerOnly = Arrays.copyOf(png(gradient(16, 16, false)), 33);
        assertThat(headerOnly).hasSize(33);

        mockMvc.perform(multipart("/api/v1/uploads")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", headerOnly))
                        .with(csrf())
                        .with(signedIn(instructorAccount())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));

        assertThatThrownBy(() -> store(headerOnly, "cover.png", "image/png"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.notAnImage");
        assertThat(storedFiles()).as("nothing, not even a temporary file").isEmpty();
    }

    @Test
    @DisplayName("A valid PNG is stored, and what is stored decodes to the same pixels")
    void aValidPngIsStored() throws Exception {
        BufferedImage original = gradient(32, 24, true);

        String url = store(png(original), "cover.png", "image/png");

        assertThat(url).matches("^/uploads/[0-9a-f-]{36}\\.png$");
        assertSamePixels(decode(storedBytes(url)), original);
        assertThat(storedFiles()).as("exactly the stored file").hasSize(1);
    }

    @Test
    @DisplayName("A script appended after a valid PNG does not survive into the stored file")
    void bytesAfterTheImageDoNotSurvive() throws Exception {
        BufferedImage original = gradient(32, 24, false);
        byte[] polyglot = concat(png(original), SCRIPT.getBytes(StandardCharsets.US_ASCII));

        String url = store(polyglot, "cover.png", "image/png");
        byte[] stored = storedBytes(url);

        assertThat(stored).isNotEqualTo(polyglot);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("<script", "__audit");
        assertThat(pngChunkTypes(stored)).last().isEqualTo("IEND");
        assertSamePixels(decode(stored), original);
    }

    @Test
    @DisplayName("What is served is the re-encoded image, as image/png with nosniff")
    void whatIsServedIsTheReencodedImage() throws Exception {
        byte[] polyglot = concat(png(gradient(32, 24, false)), SCRIPT.getBytes(StandardCharsets.US_ASCII));

        String url = uploadOverHttp(polyglot, "cover.png", "image/png");
        MvcResult served = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        byte[] body = served.getResponse().getContentAsByteArray();
        assertThat(new String(body, StandardCharsets.ISO_8859_1)).doesNotContain("<script");
        assertThat(decode(body)).isNotNull();
    }

    // ------------------------------------------------------------ metadata

    @Test
    @DisplayName("Text chunks in a PNG are not carried over")
    void pngTextChunksAreDropped() throws Exception {
        byte[] withText = insertAfterPngHeader(png(gradient(16, 16, false)),
                pngChunk("tEXt", ("Comment\0" + SCRIPT).getBytes(StandardCharsets.ISO_8859_1)));

        byte[] stored = storedBytes(store(withText, "cover.png", "image/png"));

        assertThat(pngChunkTypes(stored)).isSubsetOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND");
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("__audit");
    }

    @Test
    @DisplayName("EXIF is stripped from a JPEG")
    void jpegExifIsStripped() throws Exception {
        byte[] photo = withExif(jpeg(gradient(32, 24, false)), 1, "MANARA-EXIF-MARKER");
        assertThat(jpegMarkersBeforeScan(photo)).as("the fixture really carries EXIF").contains(0xE1);

        String url = store(photo, "photo.jpg", "image/jpeg");
        byte[] stored = storedBytes(url);

        assertThat(url).endsWith(".jpg");
        // JFIF, quantisation and Huffman tables, frame header, scan — and no APPn beyond JFIF, no COM.
        assertThat(jpegMarkersBeforeScan(stored)).containsOnly(0xE0, 0xDB, 0xC0, 0xC4, 0xDA);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif", "MANARA-EXIF-MARKER");
    }

    /**
     * A phone stores the sensor's pixels as they came off it and records how to turn them in an EXIF
     * tag. Browsers honour that tag — so stripping it without applying it first would store every
     * portrait photo on its side.
     */
    @ParameterizedTest(name = "EXIF orientation {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void anExifOrientedPhotoIsStoredUpright(int orientation) throws Exception {
        BufferedImage upright = quadrants(48, 32);
        byte[] photo = withExif(jpeg(asTheCameraStoredIt(upright, orientation)), orientation, "phone");

        BufferedImage stored = decode(storedBytes(store(photo, "photo.jpg", "image/jpeg")));

        assertThat(stored.getWidth()).isEqualTo(48);
        assertThat(stored.getHeight()).isEqualTo(32);
        for (int[] centre : new int[][]{{12, 8}, {36, 8}, {12, 24}, {36, 24}}) {
            assertThat(closeTo(stored.getRGB(centre[0], centre[1]), upright.getRGB(centre[0], centre[1])))
                    .as("quadrant at %s,%s", centre[0], centre[1])
                    .isTrue();
        }
    }

    // ------------------------------------------------------------ resource bounds

    @ParameterizedTest(name = "{0} x {1}")
    @CsvSource({"20000, 20000", "12000, 10", "5000, 4000"})
    @DisplayName("Dimensions beyond the limits are refused from the header, before any decoding")
    void oversizeDimensionsAreRefusedFromTheHeader(int width, int height) throws Exception {
        // A header and nothing else: were this decoded it would fail as not-an-image, so being told
        // it is too large proves the refusal came from the declared size alone.
        byte[] header = pngHeaderOnly(width, height);

        assertThatThrownBy(() -> store(header, "cover.png", "image/png"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.imageTooLarge");
        assertThat(storedFiles()).isEmpty();
    }

    // ------------------------------------------------------------ animation

    @Test
    @DisplayName("An animated GIF is refused explicitly, not flattened by accident")
    void anAnimatedGifIsRefused() throws Exception {
        byte[] animated = animatedGif(2);

        assertThatThrownBy(() -> store(animated, "banner.gif", "image/gif"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.animated");
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("An animated PNG is refused the same way")
    void anAnimatedPngIsRefused() throws Exception {
        ByteBuffer actl = ByteBuffer.allocate(8).putInt(2).putInt(0);
        byte[] apng = insertAfterPngHeader(png(gradient(16, 16, false)), pngChunk("acTL", actl.array()));

        assertThatThrownBy(() -> store(apng, "banner.png", "image/png"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.animated");
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("A still GIF is accepted and stored as a PNG with the same pixels")
    void aStillGifIsStoredAsPng() throws Exception {
        BufferedImage original = solid(20, 12, 0xFF3366, BufferedImage.TYPE_BYTE_INDEXED);
        byte[] gif = encode(original, "gif");

        String url = store(gif, "banner.gif", "image/gif");

        assertThat(url).endsWith(".png");
        assertThat(storedBytes(url)).startsWith(PNG_SIGNATURE);
        assertSamePixels(decode(storedBytes(url)), decode(gif));
    }

    // ------------------------------------------------------------ format

    @Test
    @DisplayName("The stored extension follows what the bytes are, not what the client called them")
    void theExtensionFollowsTheContent() throws Exception {
        String jpegCalledPng = store(jpeg(gradient(16, 16, false)), "cover.png", "image/png");
        String pngCalledJpeg = store(png(gradient(16, 16, false)), "photo.jpg", "image/jpeg");

        assertThat(jpegCalledPng).matches("^/uploads/[0-9a-f-]{36}\\.jpg$");
        assertThat(storedBytes(jpegCalledPng)).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        assertThat(pngCalledJpeg).matches("^/uploads/[0-9a-f-]{36}\\.png$");
        assertThat(storedBytes(pngCalledJpeg)).startsWith(PNG_SIGNATURE);
        assertThat(storedFiles()).hasSize(2);
    }

    @Test
    @DisplayName("A JPEG uploaded as image/png is served as image/jpeg")
    void theServedTypeFollowsTheContent() throws Exception {
        String url = uploadOverHttp(jpeg(gradient(16, 16, false)), "cover.png", "image/png");

        assertThat(url).endsWith(".jpg");
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("A format ImageIO can read but the product does not accept is refused")
    void anUnapprovedFormatIsRefused() throws Exception {
        byte[] bmp = encode(gradient(8, 8, false), "bmp");

        assertThatThrownBy(() -> store(bmp, "cover.png", "image/png"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.notAnImage");
        assertThat(storedFiles()).isEmpty();
    }

    /**
     * WebP is on the upload allow-list, but the JDK has no WebP decoder and none is on the classpath,
     * so every WebP upload is refused as not-an-image. The first assertion is there so that the day a
     * plugin appears, this test says so rather than WebP silently changing behaviour.
     */
    @Test
    @DisplayName("WebP is refused, because nothing on the classpath can decode it")
    void webpIsRefused() throws Exception {
        assertThat(ImageIO.getImageReadersByMIMEType("image/webp").hasNext()).isFalse();
        byte[] webp = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22)
                .put("WEBP".getBytes(StandardCharsets.US_ASCII))
                .put("VP8L".getBytes(StandardCharsets.US_ASCII)).putInt(10).put(new byte[10])
                .array();

        assertThatThrownBy(() -> store(webp, "cover.webp", "image/webp"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.notAnImage");
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("A JPEG cut off part-way through its image data is refused")
    void aTruncatedJpegIsRefused() throws Exception {
        byte[] full = jpeg(noise(64, 64));
        byte[] truncated = Arrays.copyOf(full, full.length * 7 / 10);

        assertThatThrownBy(() -> store(truncated, "photo.jpg", "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.notAnImage");
        assertThat(storedFiles()).isEmpty();
    }

    /**
     * A CMYK file from a print workflow must never be stored as CMYK: not every browser renders one.
     *
     * <p>Whether it is converted or refused depends on the JDK's JPEG decoder, which has not always
     * read four-channel files. Both outcomes are acceptable; storing it unconverted, or failing with
     * anything but a 400, is not. That is the property asserted, so the test holds on either decoder.
     */
    @Test
    @DisplayName("A CMYK JPEG is converted to RGB, or refused — never stored as CMYK")
    void aCmykJpegIsNeverStoredAsCmyk() throws Exception {
        String url;
        try {
            url = store(cmykJpeg(16, 16), "print.jpg", "image/jpeg");
        } catch (BusinessException refused) {
            assertThat(refused).hasMessage("error.file.notAnImage");
            assertThat(storedFiles()).isEmpty();
            return;
        }

        BufferedImage stored = decode(storedBytes(url));
        assertThat(url).endsWith(".jpg");
        assertThat(stored.getColorModel().getColorSpace().getType()).isEqualTo(ColorSpace.TYPE_RGB);
        assertThat(stored.getColorModel().getNumComponents()).isEqualTo(3);
    }

    /**
     * The multipart limit already stops a larger request at the container, but this service is what
     * reads the bytes into memory, so it states its own bound rather than relying on configuration
     * elsewhere. The payload is a valid PNG padded past the limit, so only its size can refuse it.
     */
    @Test
    @DisplayName("A file over the size limit is refused before it is read as an image")
    void anOversizeFileIsRefused() throws Exception {
        byte[] padded = concat(png(gradient(8, 8, false)), new byte[5 * 1024 * 1024]);

        assertThatThrownBy(() -> store(padded, "cover.png", "image/png"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.tooLarge");
        assertThat(storedFiles()).isEmpty();
    }

    // ------------------------------------------------------------ helpers: storage

    private String store(byte[] bytes, String filename, String contentType) {
        return fileUploadService.storeFile(new MockMultipartFile("file", filename, contentType, bytes), INSTRUCTOR);
    }

    private String uploadOverHttp(byte[] bytes, String filename, String contentType) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/uploads")
                        .file(new MockMultipartFile("file", filename, contentType, bytes))
                        .with(csrf())
                        .with(signedIn(instructorAccount())))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.url");
    }

    /** Persisted, because a signed-in request re-reads the account; see {@code UploadAuthorizationTest}. */
    private User instructorAccount() {
        return userRepository.save(User.builder()
                .fullName("Reencode Tester")
                .email("instructor" + DOMAIN)
                .password("not-a-real-password-for-tests")
                .role(Role.INSTRUCTOR)
                .emailVerified(true)
                .build());
    }

    private static byte[] storedBytes(String url) throws IOException {
        return Files.readAllBytes(uploadDir.resolve(url.substring("/uploads/".length())));
    }

    /** Everything in the directory, hidden files included — a leftover temporary file counts. */
    private static List<Path> storedFiles() throws IOException {
        try (Stream<Path> entries = Files.list(uploadDir)) {
            return entries.toList();
        }
    }

    private static void emptyUploadDir() throws IOException {
        if (!Files.isDirectory(uploadDir)) {
            return;
        }
        for (Path entry : storedFiles()) {
            Files.deleteIfExists(entry);
        }
    }

    // ------------------------------------------------------------ helpers: images

    private static BufferedImage gradient(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = alpha ? x * 255 / Math.max(1, width - 1) : 0xFF;
                image.setRGB(x, y, a << 24 | (x * 7 & 0xFF) << 16 | (y * 11 & 0xFF) << 8 | ((x + y) * 5 & 0xFF));
            }
        }
        return image;
    }

    private static BufferedImage noise(int width, int height) {
        Random random = new Random(42);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    private static BufferedImage solid(int width, int height, int rgb, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /** Four flat quadrants in four different colours, so every one of the eight orientations is distinguishable. */
    private static BufferedImage quadrants(int width, int height) {
        int[] colours = {0xD02020, 0x20D020, 0x2020D0, 0xD0D020};
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, colours[(y < height / 2 ? 0 : 2) + (x < width / 2 ? 0 : 1)]);
            }
        }
        return image;
    }

    /**
     * The pixels a camera would have stored for an image that should be seen as {@code upright}.
     *
     * <p>Built from the EXIF definition of each orientation value — where the stored row 0 and column
     * 0 appear to the viewer — independently of how the service applies it.
     */
    private static BufferedImage asTheCameraStoredIt(BufferedImage upright, int orientation) {
        int width = upright.getWidth();
        int height = upright.getHeight();
        boolean swaps = orientation >= 5;
        int rawWidth = swaps ? height : width;
        int rawHeight = swaps ? width : height;
        BufferedImage raw = new BufferedImage(rawWidth, rawHeight, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] at = switch (orientation) {
                    case 1 -> new int[]{x, y};
                    case 2 -> new int[]{rawWidth - 1 - x, y};
                    case 3 -> new int[]{rawWidth - 1 - x, rawHeight - 1 - y};
                    case 4 -> new int[]{x, rawHeight - 1 - y};
                    case 5 -> new int[]{y, x};
                    case 6 -> new int[]{y, rawHeight - 1 - x};
                    case 7 -> new int[]{rawWidth - 1 - y, rawHeight - 1 - x};
                    default -> new int[]{rawWidth - 1 - y, x};
                };
                raw.setRGB(at[0], at[1], upright.getRGB(x, y));
            }
        }
        return raw;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, bytes)).as("a %s writer exists", format).isTrue();
        return bytes.toByteArray();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        return encode(image, "png");
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        return encode(image, "jpeg");
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static byte[] animatedGif(int frames) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int frame = 0; frame < frames; frame++) {
                BufferedImage image = solid(16, 16, frame % 2 == 0 ? 0xFF0000 : 0x0000FF,
                        BufferedImage.TYPE_BYTE_INDEXED);
                writer.writeToSequence(new IIOImage(image, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Four channels and no Adobe marker, which a JPEG decoder reads as CMYK. */
    private static byte[] cmykJpeg(int width, int height) throws IOException {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setPixel(x, y, new int[]{200, 10, 10, 30});
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void assertSamePixels(BufferedImage actual, BufferedImage expected) {
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                // A fully transparent pixel has no colour worth comparing.
                if ((want >>> 24) == 0 && (got >>> 24) == 0) {
                    continue;
                }
                assertThat(Integer.toHexString(got)).as("pixel %s,%s", x, y).isEqualTo(Integer.toHexString(want));
            }
        }
    }

    /** Within JPEG's rounding of a flat colour, well away from any edge. */
    private static boolean closeTo(int actual, int expected) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((actual >> shift & 0xFF) - (expected >> shift & 0xFF)) > 40) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ helpers: PNG and JPEG structure

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length)
                .putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue())
                .array();
    }

    /** A signature and an IHDR declaring the given size, and nothing after it. */
    private static byte[] pngHeaderOnly(int width, int height) {
        byte[] ihdr = ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                .array();
        return concat(PNG_SIGNATURE, pngChunk("IHDR", ihdr));
    }

    /** IHDR is always the first chunk and always 25 bytes, so it ends at offset 33. */
    private static byte[] insertAfterPngHeader(byte[] png, byte[] chunk) {
        return concat(Arrays.copyOf(png, 33), chunk, Arrays.copyOfRange(png, 33, png.length));
    }

    private static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int position = PNG_SIGNATURE.length;
        while (position + 8 <= png.length) {
            int length = ByteBuffer.wrap(png, position, 4).getInt();
            types.add(new String(png, position + 4, 4, StandardCharsets.US_ASCII));
            position += 12 + length;
        }
        return types;
    }

    /**
     * The file laid out the way a phone writes it: SOI, then an EXIF APP1 segment, then the image.
     * The encoder's own JFIF APP0 is removed, as phones do not write one.
     */
    private static byte[] withExif(byte[] jpeg, int orientation, String description) {
        int rest = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            rest = 4 + ((jpeg[4] & 0xFF) << 8 | jpeg[5] & 0xFF);
        }
        byte[] tiff = exifTiff(orientation, description);
        int length = 2 + 6 + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(tiff);
        out.write(jpeg, rest, jpeg.length - rest);
        return out.toByteArray();
    }

    /** A big-endian TIFF structure with one IFD: ImageDescription and Orientation. */
    private static byte[] exifTiff(int orientation, String description) {
        byte[] text = (description + "\0").getBytes(StandardCharsets.US_ASCII);
        int dataOffset = 8 + 2 + 2 * 12 + 4;
        return ByteBuffer.allocate(dataOffset + text.length)
                .put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8)
                .putShort((short) 2)
                .putShort((short) 0x010E).putShort((short) 2).putInt(text.length).putInt(dataOffset)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0)
                .put(text)
                .array();
    }

    /** Marker codes from just after SOI up to and including the first SOS. */
    private static List<Integer> jpegMarkersBeforeScan(byte[] jpeg) {
        List<Integer> markers = new ArrayList<>();
        int position = 2;
        while (position + 4 <= jpeg.length) {
            int marker = jpeg[position + 1] & 0xFF;
            markers.add(marker);
            if (marker == 0xDA) {
                break;
            }
            position += 2 + ((jpeg[position + 2] & 0xFF) << 8 | jpeg[position + 3] & 0xFF);
        }
        return markers;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
