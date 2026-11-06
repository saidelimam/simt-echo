package app.simt.echo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.Inflater;
import org.junit.Test;

/**
 * W9-11: launcher icon + splash assert against the Cosmos freeze
 * ({@code docs/research/cosmos-echo-freeze.md} §2) — void {@code #120A14},
 * hull {@code #1D1220}, ember {@code #FF8C42}, rose {@code #FF7E8A} — and no
 * lime channel anywhere (freeze §1: Echo Lime retired, never shipped).
 *
 * <p>W10-07 moved the step mark from an XML vector to PNG mipmaps rendered
 * from {@code res/design/echo-step.svg} (see the comment in
 * {@code mipmap-anydpi-v26/ic_launcher.xml}); the field stays a void
 * vector. These plain-JVM assertions therefore pin the committed PNGs'
 * colour channels (mark present, lime absent) and the adaptive-icon wiring,
 * not XML hexes. Pixel-level theme rendering is covered by the shared
 * {@code EchoThemeHomeRenderTest} host gate.
 *
 * <p>PNGs are decoded with the {@link Png} helper below — unit tests compile
 * against android.jar, which has no AWT/ImageIO, so the decoder sticks to
 * {@code java.util.zip} and a hand-rolled unfilter.
 */
public class IconSplashTest {

    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    private static String res(String name) throws Exception {
        File dir = new File(System.getProperty("user.dir"));
        for (File d = dir; d != null; d = d.getParentFile()) {
            File moduleScoped = new File(d, "src/main/res/" + name);
            if (moduleScoped.isFile()) {
                return readAsset(moduleScoped);
            }
            File rootScoped = new File(d, "androidApp/src/main/res/" + name);
            if (rootScoped.isFile()) {
                return readAsset(rootScoped);
            }
        }
        throw new IllegalStateException("asset not found: " + name);
    }

    private static File mip(String density, String name) throws Exception {
        File dir = new File(System.getProperty("user.dir"));
        for (File d = dir; d != null; d = d.getParentFile()) {
            File moduleScoped = new File(d, "src/main/res/mipmap-" + density + "/" + name);
            if (moduleScoped.isFile()) {
                return moduleScoped;
            }
            File rootScoped = new File(d, "androidApp/src/main/res/mipmap-" + density + "/" + name);
            if (rootScoped.isFile()) {
                return rootScoped;
            }
        }
        throw new IllegalStateException("asset not found: mipmap-" + density + "/" + name);
    }

    /** File text with XML comments stripped: provenance notes may name retired hexes. */
    private static String readAsset(File file) throws Exception {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return text.replaceAll("(?s)<!--.*?-->", "");
    }

    private static void assertNoLime(String xml, String label) {
        String upper = xml.toUpperCase();
        assertFalse(label + " must not carry the retired lime accent", upper.contains("C6F432"));
        assertFalse(label + " must not carry the pre-Cosmos near-black", upper.contains("0B0D10"));
    }

    private static int distance(int rgb, int target) {
        return Math.abs(((rgb >> 16) & 255) - ((target >> 16) & 255))
                + Math.abs(((rgb >> 8) & 255) - ((target >> 8) & 255))
                + Math.abs((rgb & 255) - (target & 255));
    }

    /** The mark's ember ring and rose core must be present; lime must be absent. */
    private static void assertMarkChannels(Png img, String label) {
        long ember = 0;
        long rose = 0;
        long lime = 0;
        for (int i = 0; i < img.pixelCount(); i++) {
            int rgb = ((img.rgb(i * 3) & 255) << 16) | ((img.rgb(i * 3 + 1) & 255) << 8) | (img.rgb(i * 3 + 2) & 255);
            if (distance(rgb, 0xFF8C42) <= 48) {
                ember++;
            }
            if (distance(rgb, 0xFF7E8A) <= 48) {
                rose++;
            }
            if (distance(rgb, 0xC6F432) <= 48) {
                lime++;
            }
        }
        assertTrue(label + " must carry the ember step mark", ember > 0);
        assertTrue(label + " must carry the rose core", rose > 0);
        assertFalse(label + " must not carry the retired lime accent", lime > 0);
    }

    @Test
    public void foregroundIsHullWithEmberRoseMark() throws Exception {
        for (String density : DENSITIES) {
            String label = "launcher foreground " + density;
            Png foreground = new Png(mip(density, "ic_launcher_foreground.png"));
            assertNotNull(label + " must be a decodable PNG", foreground);
            assertMarkChannels(foreground, label);
            Png monochrome = new Png(mip(density, "ic_launcher_monochrome.png"));
            assertNotNull(label + ": themed-icon monochrome must be a decodable PNG", monochrome);
        }
    }

    @Test
    public void launcherBackgroundIsVoid() throws Exception {
        String xml = res("values/colors.xml").toUpperCase();
        assertTrue("splash/icon field is freeze void", xml.contains("120A14"));
        assertNoLime(xml, "launcher colors");
    }

    @Test
    public void splashThemeShowsVoidEdgeToEdge() throws Exception {
        String xml = res("values/themes.xml");
        assertTrue("window shows the launcher void", xml.contains("@color/echo_launcher_bg"));
        assertTrue("status bar extends the splash", xml.contains("android:statusBarColor"));
        assertTrue("navigation bar extends the splash", xml.contains("android:navigationBarColor"));
    }

    @Test
    public void adaptiveIconWiresBackgroundAndForeground() throws Exception {
        String xml = res("mipmap-anydpi-v26/ic_launcher.xml");
        assertTrue("void field background", xml.contains("@drawable/ic_launcher_background"));
        assertTrue("cosmos foreground", xml.contains("@mipmap/ic_launcher_foreground"));
        assertTrue("themed-icon monochrome", xml.contains("@mipmap/ic_launcher_monochrome"));
    }

    /**
     * Minimal 8-bit RGB/RGBA PNG decoder (bit depth 8, color types 2 and 6):
     * chunk walk + inflate + scanline unfilter. Anything else throws, so a
     * format change in the committed assets fails the gate loudly instead of
     * being silently skipped.
     */
    private static final class Png {
        private final int width;
        private final int height;
        private final byte[] pixels;

        Png(File file) throws Exception {
            byte[] data = Files.readAllBytes(file.toPath());
            byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1a, '\n'};
            for (int i = 0; i < sig.length; i++) {
                if (data[i] != sig[i]) {
                    throw new IllegalStateException("not a PNG: " + file.getName());
                }
            }
            int width = 0;
            int height = 0;
            int colorType = -1;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            int pos = 8;
            while (pos < data.length) {
                int len = ((data[pos] & 255) << 24) | ((data[pos + 1] & 255) << 16) | ((data[pos + 2] & 255) << 8) | (data[pos + 3] & 255);
                String type = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
                if (type.equals("IHDR")) {
                    width = ((data[pos + 8] & 255) << 24) | ((data[pos + 9] & 255) << 16) | ((data[pos + 10] & 255) << 8) | (data[pos + 11] & 255);
                    height = ((data[pos + 12] & 255) << 24) | ((data[pos + 13] & 255) << 16) | ((data[pos + 14] & 255) << 8) | (data[pos + 15] & 255);
                    int bitDepth = data[pos + 16] & 255;
                    colorType = data[pos + 17] & 255;
                    if (bitDepth != 8 || (colorType != 2 && colorType != 6)) {
                        throw new IllegalStateException(
                                "unsupported PNG (depth " + bitDepth + ", type " + colorType + "): " + file.getName());
                    }
                } else if (type.equals("IDAT")) {
                    idat.write(data, pos + 8, len);
                } else if (type.equals("IEND")) {
                    break;
                }
                pos += 12 + len;
            }
            int channels = colorType == 2 ? 3 : 4;
            int rowStride = width * channels + 1;
            byte[] id = idat.toByteArray();
            // The committed assets are emitted with a zlib wrapper (leading
            // 0x78), which the platform decoders tolerate although the spec
            // says raw deflate; detect it and inflate accordingly.
            Inflater inflater = new Inflater(id[0] != 0x78);
            inflater.setInput(id);
            byte[] raw = new byte[height * rowStride];
            int off = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(raw, off, raw.length - off);
                if (n == 0 && inflater.needsInput()) {
                    throw new IllegalStateException("truncated IDAT: " + file.getName());
                }
                off += n;
            }
            byte[] decoded = new byte[width * height * channels];
            for (int y = 0; y < height; y++) {
                int filter = raw[y * rowStride] & 255;
                for (int x = 0; x < width; x++) {
                    for (int c = 0; c < channels; c++) {
                        int idx = y * rowStride + x * channels + c;
                        int cur = raw[idx] & 255;
                        // Neighbours are the UNFILTERED values: the row above
                        // is already decoded, so all three come from the
                        // output buffer, never from `raw`.
                        int a = x > 0 ? decoded[(y * width + x - 1) * channels + c] & 255 : 0;
                        int b = y > 0 ? decoded[(y - 1) * width * channels + x * channels + c] & 255 : 0;
                        int d = (x > 0 && y > 0)
                                ? decoded[(y - 1) * width * channels + (x - 1) * channels + c] & 255
                                : 0;
                        switch (filter) {
                            case 0:
                                break;
                            case 1:
                                cur += a;
                                break;
                            case 2:
                                cur += b;
                                break;
                            case 3:
                                cur += (a + b) / 2;
                                break;
                            case 4:
                                cur += paeth(a, b, d);
                                break;
                            default:
                                throw new IllegalStateException("bad filter " + filter + " in " + file.getName());
                        }
                        decoded[(y * width + x) * channels + c] = (byte) (cur & 255);
                    }
                }
            }
            // Drop alpha so the layout is always 3 bytes/pixel. A 4-channel
            // PNG is RGBA per spec, so RGB starts at channel 1.
            pixels = new byte[width * height * 3];
            int rgbOffset = channels == 4 ? 1 : 0;
            for (int i = 0; i < width * height; i++) {
                pixels[i * 3] = decoded[i * channels + rgbOffset];
                pixels[i * 3 + 1] = decoded[i * channels + 1 + rgbOffset];
                pixels[i * 3 + 2] = decoded[i * channels + 2 + rgbOffset];
            }
            this.width = width;
            this.height = height;
        }

        private static int paeth(int a, int b, int c) {
            int p = a + b - c;
            int pa = Math.abs(p - a);
            int pb = Math.abs(p - b);
            int pc = Math.abs(p - c);
            if (pa <= pb && pa <= pc) {
                return a;
            }
            return pb <= pc ? b : c;
        }

        int pixelCount() {
            return width * height;
        }

        byte rgb(int index) {
            return pixels[index];
        }
    }
}
