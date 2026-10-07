package br.com.bernisoftware.bfocus.monitor;

import java.util.List;
import java.util.Map;

/** JSON do evento escrito à mão (zero dependência). Nulo e coleção vazia não saem. */
final class EventJson {
    private EventJson() {
    }

    static String serialize(MonitorEvent e) {
        StringBuilder sb = new StringBuilder(1024);
        Obj w = new Obj(sb);
        w.str("timestamp", e.getTimestamp());
        w.str("level", (e.getLevel() == null ? Level.ERROR : e.getLevel()).wire());
        w.str("release", e.getRelease());
        w.str("environment", e.getEnvironment());
        MonitorEvent.ExceptionInfo x = e.getException();
        if (x != null) {
            w.key("exception");
            Obj xo = new Obj(sb);
            xo.str("type", x.getType());
            xo.str("message", x.getMessage() == null ? "" : x.getMessage());
            xo.key("frames");
            sb.append('[');
            boolean first = true;
            if (x.getFrames() != null) {
                for (MonitorEvent.Frame f : x.getFrames()) {
                    if (f == null) continue;
                    if (!first) sb.append(',');
                    first = false;
                    Obj fo = new Obj(sb);
                    fo.str("file", f.getFile());
                    fo.str("function", f.getFunction());
                    fo.num("line", f.getLine());
                    fo.num("col", f.getCol());
                    fo.bool("inApp", f.isInApp());
                    fo.end();
                }
            }
            sb.append(']');
            xo.end();
        }
        w.str("transaction", e.getTransaction());
        w.str("url", e.getUrl());
        if (e.getUser() != null && notEmpty(e.getUser().getExternalId())) {
            w.key("user");
            Obj u = new Obj(sb);
            u.str("externalId", e.getUser().getExternalId());
            u.str("userHash", e.getUser().getUserHash());
            u.end();
        }
        if (e.getCustomer() != null && notEmpty(e.getCustomer().getExternalId())) {
            w.key("customer");
            Obj c = new Obj(sb);
            c.str("externalId", e.getCustomer().getExternalId());
            c.end();
        }
        if (e.getTags() != null && !e.getTags().isEmpty()) {
            w.key("tags");
            map(sb, e.getTags());
        }
        if (e.getBreadcrumbs() != null && !e.getBreadcrumbs().isEmpty()) {
            w.key("breadcrumbs");
            sb.append('[');
            boolean first = true;
            for (MonitorEvent.Breadcrumb b : e.getBreadcrumbs()) {
                if (b == null) continue;
                if (!first) sb.append(',');
                first = false;
                Obj bo = new Obj(sb);
                bo.str("timestamp", b.getTimestamp());
                bo.str("category", b.getCategory());
                bo.str("message", b.getMessage());
                bo.str("level", (b.getLevel() == null ? Level.INFO : b.getLevel()).wire());
                bo.end();
            }
            sb.append(']');
        }
        List<String> fp = e.getFingerprint();
        if (fp != null && !fp.isEmpty()) {
            w.key("fingerprint");
            sb.append('[');
            boolean first = true;
            for (String p : fp) {
                if (p == null) continue;
                if (!first) sb.append(',');
                first = false;
                quote(sb, p);
            }
            sb.append(']');
        }
        if (e.getContexts() != null && !e.getContexts().isEmpty()) {
            w.key("contexts");
            Obj co = new Obj(sb);
            for (Map.Entry<String, Map<String, String>> kv : e.getContexts().entrySet()) {
                if (kv.getKey() == null || kv.getValue() == null) continue;
                co.key(kv.getKey());
                map(sb, kv.getValue());
            }
            co.end();
        }
        if (e.getSdk() != null) {
            w.key("sdk");
            Obj s = new Obj(sb);
            s.str("name", e.getSdk().getName());
            s.str("version", e.getSdk().getVersion());
            s.end();
        }
        w.end();
        return sb.toString();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static void map(StringBuilder sb, Map<String, String> map) {
        Obj o = new Obj(sb);
        for (Map.Entry<String, String> kv : map.entrySet()) o.str(kv.getKey(), kv.getValue());
        o.end();
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
    }

    /** Escritor de objeto que só põe a vírgula quando o campo existe. */
    private static final class Obj {
        private final StringBuilder sb;
        private boolean any;

        Obj(StringBuilder sb) {
            this.sb = sb;
            sb.append('{');
        }

        void key(String name) {
            if (any) sb.append(',');
            any = true;
            quote(sb, name);
            sb.append(':');
        }

        void str(String name, String value) {
            if (name == null || value == null) return;
            key(name);
            quote(sb, value);
        }

        void num(String name, Integer value) {
            if (value == null) return;
            key(name);
            sb.append(value.intValue());
        }

        void bool(String name, boolean value) {
            key(name);
            sb.append(value ? "true" : "false");
        }

        void end() {
            sb.append('}');
        }
    }
}
