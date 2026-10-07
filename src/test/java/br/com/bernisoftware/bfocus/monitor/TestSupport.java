package br.com.bernisoftware.bfocus.monitor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

final class TestSupport {
    private static final Map<String, Class<?>> TYPES = new HashMap<>();
    private static Path typesDir;
    private static URLClassLoader loader;

    private TestSupport() {
    }

    static Path projectDir() {
        String p = System.getProperty("bfocus.projectDir");
        return p != null ? Paths.get(p) : Paths.get("").toAbsolutePath();
    }

    static MonitorOptions.Builder options(FakeServer server) {
        return MonitorOptions.builder()
            .key("bf_mon_test")
            .release("1.4.2")
            .baseUrl(server.baseUrl() + "/")
            .autoCapture(false)
            .retryDelay(Duration.ofMillis(50))
            .batchInterval(Duration.ofMillis(100));
    }

    /**
     * Exceção LANÇADA de verdade cuja classe tem exatamente o nome que o caso pede ({@code ValueError},
     * {@code KeyError}, {@code E}...): compilada na hora, no pacote padrão.
     */
    static synchronized RuntimeException thrown(String typeName, String message) {
        try {
            Class<?> type = TYPES.get(typeName);
            if (type == null) {
                if (typesDir == null) {
                    typesDir = Files.createTempDirectory("bfocus-monitor-types");
                    loader = new URLClassLoader(new URL[] {typesDir.toUri().toURL()}, TestSupport.class.getClassLoader());
                }
                Path src = typesDir.resolve(typeName + ".java");
                Files.write(src, ("public class " + typeName + " extends RuntimeException {\n"
                    + "  public " + typeName + "(String m) { super(m); }\n}\n").getBytes(StandardCharsets.UTF_8));
                JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
                int rc = javac.run(null, null, null, "-d", typesDir.toString(), src.toString());
                if (rc != 0) throw new IllegalStateException("javac falhou para " + typeName);
                type = loader.loadClass(typeName);
                TYPES.put(typeName, type);
            }
            RuntimeException error = (RuntimeException) type.getConstructor(String.class).newInstance(message);
            try {
                throw error;
            } catch (RuntimeException e) {
                return e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Level level(String s) {
        if (s == null) return null;
        switch (s) {
            case "fatal": return Level.FATAL;
            case "error": return Level.ERROR;
            case "warning": return Level.WARNING;
            case "info": return Level.INFO;
            default: return null;
        }
    }
}
