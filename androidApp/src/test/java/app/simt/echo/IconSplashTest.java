package app.simt.echo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * W9-11: launcher icon + splash assert against the Cosmos freeze
 * ({@code docs/research/cosmos-echo-freeze.md} §2) — void {@code #120A14},
 * hull {@code #1D1220}, ember {@code #FF8C42}, rose {@code #FF7E8A} — and no
 * lime channel anywhere (freeze §1: Echo Lime retired, never shipped).
 *
 * <p>Plain-JVM file assertions: they pin the committed asset hexes, not
 * pixels. Icon/splash pixels render in both schemes via the shared
 * {@code EchoThemeHomeRenderTest} host gate.
 */
public class IconSplashTest {

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

    @Test
    public void foregroundIsHullWithEmberRoseMark() throws Exception {
        String xml = res("drawable/ic_launcher_foreground.xml").toUpperCase();
        assertTrue("hull disc", xml.contains("1D1220"));
        assertTrue("ember ring", xml.contains("FF8C42"));
        assertTrue("rose core", xml.contains("FF7E8A"));
        assertNoLime(xml, "icon foreground");
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
        assertTrue("void background", xml.contains("@color/echo_launcher_bg"));
        assertTrue("cosmos foreground", xml.contains("@drawable/ic_launcher_foreground"));
    }
}
