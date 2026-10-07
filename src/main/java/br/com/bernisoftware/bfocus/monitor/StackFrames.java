package br.com.bernisoftware.bfocus.monitor;

import java.util.ArrayList;
import java.util.List;

/** Frames do contrato a partir do rastro do Java (que vem de DENTRO para fora). */
final class StackFrames {
    static final int MAX_FRAMES = 60;
    static final String OWN_PREFIX = "br.com.bernisoftware.bfocus.monitor.";
    private static final String[] LIBRARY_PREFIXES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "scala.",
        "org.springframework.", "org.apache.catalina.", "org.apache.tomcat.", "org.apache.coyote.",
        "org.eclipse.jetty.", "io.undertow.", "jakarta.", "org.junit.", "org.hibernate.",
    };

    private StackFrames() {
    }

    /** Frame cru, na ordem do runtime. */
    static final class Raw {
        final String className;
        final String function;
        final String file;
        final int line;

        Raw(String className, String function, String file, int line) {
            this.className = className;
            this.function = function;
            this.file = file;
            this.line = line;
        }
    }

    static List<MonitorEvent.Frame> fromThrowable(Throwable t, List<String> inAppPrefixes) {
        List<Raw> raw = new ArrayList<>();
        try {
            for (StackTraceElement el : t.getStackTrace()) {
                if (el == null) continue;
                String cls = el.getClassName();
                String simple = cls.substring(cls.lastIndexOf('.') + 1);
                raw.add(new Raw(cls, simple + "." + el.getMethodName(), fileOf(cls, el.getFileName()), el.getLineNumber()));
            }
        } catch (RuntimeException ignored) {
            // rastro ilegível: segue sem frames
        }
        return convert(raw, inAppPrefixes);
    }

    /** {@code com.acme.Pedido$1} + {@code Pedido.java} vira {@code com/acme/Pedido.java}. */
    static String fileOf(String className, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        int dot = className.lastIndexOf('.');
        return dot < 0 ? fileName : className.substring(0, dot).replace('.', '/') + "/" + fileName;
    }

    /** De dentro para fora (como o Java dá) para de fora para dentro, com {@code inApp}. */
    static List<MonitorEvent.Frame> convert(List<Raw> innerToOuter, List<String> inAppPrefixes) {
        int take = Math.min(innerToOuter.size(), MAX_FRAMES); // corta pelos mais EXTERNOS
        List<MonitorEvent.Frame> out = new ArrayList<>(take);
        for (int i = take - 1; i >= 0; i--) {
            Raw r = innerToOuter.get(i);
            MonitorEvent.Frame f = new MonitorEvent.Frame();
            f.setFile(r.file);
            f.setFunction(r.function);
            f.setLine(r.line > 0 ? r.line : null);
            f.setInApp(isInApp(r.className, inAppPrefixes));
            out.add(f);
        }
        return out;
    }

    static boolean isInApp(String className, List<String> inAppPrefixes) {
        if (className == null || className.isEmpty()) return false;
        if (className.startsWith(OWN_PREFIX)) return false;
        for (String p : inAppPrefixes) if (className.startsWith(p)) return true;
        for (String p : LIBRARY_PREFIXES) if (className.startsWith(p)) return false;
        return true;
    }
}
