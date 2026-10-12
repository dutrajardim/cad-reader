package br.gov.mg.sids.reader;

import java.util.*;

/** JSON estrito para a API Discord, sem executar scripts nem depender de bibliotecas externas. */
final class BotJson {
    private final String input;
    private int pos;
    private BotJson(String input) {
        this.input = input;
    }
    static Object parse(String input) {
        BotJson p = new BotJson(input);
        Object value = p.value(0);
        p.space();
        if (p.pos != input.length())
            throw new IllegalArgumentException("JSON excedente");
        return value;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>)value : Collections.emptyMap();
    }
    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>)value : Collections.emptyList();
    }
    static String text(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value == null ? "" : value.toString();
    }
    static String write(Object value) {
        if (value == null)
            return "null";
        if (value instanceof String)
            return AppFiles.json((String)value);
        if (value instanceof Boolean || value instanceof Number)
            return value.toString();
        List<String> parts = new ArrayList<>();
        if (value instanceof Map) {
            ((Map<?,?>)value).forEach((k,v) -> parts.add(AppFiles.json(k.toString()) + ":" + write(v)));
            return "{" + String.join(",", parts) + "}";
        }
        for (Object item : (Iterable<?>)value)
            parts.add(write(item));
        return "[" + String.join(",", parts) + "]";
    }
    private void space() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos)))
            pos++;
    }
    private boolean take(char c) {
        space();
        if (pos < input.length() && input.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }
    private Object value(int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("JSON profundo");
        space();
        if (pos >= input.length())
            throw new IllegalArgumentException("JSON incompleto");
        char c = input.charAt(pos);
        if (c == '"')
            return string();
        if (take('{')) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (take('}'))
                return map;
            do {
                space();
                String key = string();
                if (!take(':'))
                    throw new IllegalArgumentException("JSON objeto");
                map.put(key, value(depth + 1));
            } while (take(','));
            if (!take('}'))
                throw new IllegalArgumentException("JSON objeto");
            return map;
        }
        if (take('[')) {
            List<Object> list = new ArrayList<>();
            if (take(']'))
                return list;
            do {
                list.add(value(depth + 1));
            } while (take(','));
            if (!take(']'))
                throw new IllegalArgumentException("JSON lista");
            return list;
        }
        for (String literal : new String[] {"true", "false", "null"})
            if (input.startsWith(literal, pos)) {
                pos += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        int start = pos;
        while (pos < input.length() && "-+0123456789.eE".indexOf(input.charAt(pos)) >= 0)
            pos++;
        if (start == pos)
            throw new IllegalArgumentException("JSON valor");
        String number = input.substring(start, pos);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("JSON número");
        return new java.math.BigDecimal(number);
    }
    private String string() {
        if (pos >= input.length() || input.charAt(pos++) != '"')
            throw new IllegalArgumentException("JSON texto");
        StringBuilder out = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"')
                return out.toString();
            if (c == '\\') {
                if (pos >= input.length())
                    break;
                c = input.charAt(pos++);
                switch (c) {
                case '"':
                case '\\':
                case '/':
                    out.append(c);
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    if (pos + 4 > input.length())
                        throw new IllegalArgumentException("JSON unicode");
                    out.append((char)Integer.parseInt(input.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("JSON escape");
                }
            } else {
                if (c < 32)
                    throw new IllegalArgumentException("JSON controle");
                out.append(c);
            }
        }
        throw new IllegalArgumentException("JSON texto incompleto");
    }
}
