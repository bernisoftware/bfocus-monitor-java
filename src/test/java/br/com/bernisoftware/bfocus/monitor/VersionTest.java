package br.com.bernisoftware.bfocus.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A versão existe no pom.xml E em {@link BfocusMonitor#VERSION}; vai no header de toda requisição. */
class VersionTest {
    @Test
    void versionMatchesPom() throws IOException {
        String pom = Files.readString(TestSupport.projectDir().resolve("pom.xml"));
        Matcher m = Pattern.compile("<artifactId>bfocus-monitor</artifactId>\\s*(?:<!--.*?-->\\s*)?<version>([^<]+)</version>", Pattern.DOTALL).matcher(pom);
        assertTrue(m.find(), "pom.xml sem a versão do bfocus-monitor");
        assertEquals(BfocusMonitor.VERSION, m.group(1).trim());
        assertEquals("bfocus-monitor-java/" + BfocusMonitor.VERSION, Transport.clientHeader());
    }

    @Test
    void versionMatchesReleaseJson() throws IOException {
        Path release = TestSupport.projectDir().resolve("../release.json").normalize();
        if (!Files.exists(release)) return; // só no monorepo
        Object version = MiniJson.obj(MiniJson.parse(Files.readString(release))).get("version");
        assertEquals(BfocusMonitor.VERSION, version);
    }
}
