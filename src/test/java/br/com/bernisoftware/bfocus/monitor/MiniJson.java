package br.com.bernisoftware.bfocus.monitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Leitor de JSON mínimo para os testes (o pacote não tem dependência; os testes também não). */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("lixo no fim do JSON em " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    /** Caminho com ponto ({@code breadcrumbs.0.category}); null se não existe. */
    static Object at(Object node, String dotted) {
        Object cur = node;
        for (String part : dotted.split("\\.")) {
            if (cur instanceof Map) {
                cur = obj(cur).get(part);
            } else if (cur instanceof List && part.matches("\\d+")) {
                int idx = Integer.parseInt(part);
                cur = idx < arr(cur).size() ? arr(cur).get(idx) : null;
            } else {
                return null;
            }
            if (cur == null) return null;
        }
        return cur;
    }

    /** Igualdade de valores JSON (números comparados pelo valor). */
    static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        if (a instanceof List && b instanceof List) {
            List<Object> x = arr(a);
            List<Object> y = arr(b);
            if (x.size() != y.size()) return false;
            for (int k = 0; k < x.size(); k++) if (!same(x.get(k), y.get(k))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> x = obj(a);
            Map<String, Object> y = obj(b);
            if (!x.keySet().equals(y.keySet())) return false;
            for (String k : x.keySet()) if (!same(x.get(k), y.get(k))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            i++; // :
            ws();
            m.put(k, value());
            ws();
            if (s.charAt(i++) == '}') return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (s.charAt(i++) == ']') return a;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: sb.append(e);
            }
        }
    }

    private Number number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        return Long.parseLong(n);
    }
}
