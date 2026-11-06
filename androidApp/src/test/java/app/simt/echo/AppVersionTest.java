package app.simt.echo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Version-discipline gate (docs/release.md): versionName is
 * MAJOR.MINOR.PATCH, versionCode = MAJOR * 10000 + MINOR * 100 + PATCH, and
 * the single source of truth is {@code app-version} in
 * {@code gradle/libs.versions.toml}. These plain-JVM assertions pin the
 * generated {@code BuildConfig} to the catalog value, so a re-hardcoded
 * version in a build file fails here instead of drifting silently.
 */
public class AppVersionTest {

    @Test
    public void versionNameIsSemver() {
        assertTrue(
                "versionName must be MAJOR.MINOR.PATCH, got: " + BuildConfig.VERSION_NAME,
                Pattern.matches("\\d+\\.\\d+\\.\\d+", BuildConfig.VERSION_NAME));
    }

    @Test
    public void versionCodeFollowsTheReleaseFormula() {
        String[] p = BuildConfig.VERSION_NAME.split("\\.");
        int expected =
                Integer.parseInt(p[0]) * 10000
                        + Integer.parseInt(p[1]) * 100
                        + Integer.parseInt(p[2]);
        assertEquals(
                "versionCode must be MAJOR*10000+MINOR*100+PATCH of " + BuildConfig.VERSION_NAME,
                expected,
                BuildConfig.VERSION_CODE);
    }

    @Test
    public void catalogIsTheSingleSourceOfTruth() throws Exception {
        String toml = readCatalog();
        assertEquals(
                "app-version in libs.versions.toml must equal BuildConfig.VERSION_NAME",
                BuildConfig.VERSION_NAME,
                catalogAppVersion(toml));
    }

    private static String catalogAppVersion(String toml) {
        for (String line : toml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("app-version")) {
                int eq = trimmed.indexOf('=');
                return trimmed.substring(eq + 1).trim().replace("\"", "");
            }
        }
        throw new IllegalStateException("app-version not found in libs.versions.toml");
    }

    private static String readCatalog() throws Exception {
        for (File d = new File(System.getProperty("user.dir")); d != null; d = d.getParentFile()) {
            File f = new File(d, "gradle/libs.versions.toml");
            if (f.isFile()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("gradle/libs.versions.toml not found");
    }
}
