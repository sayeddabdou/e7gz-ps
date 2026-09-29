package ps.e7gz.common.codes;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds every {@code ARRAY[...]} literal in a {@code pg_get_constraintdef(...)} text and works out which column it is
 * compared with and how. A small tokenizer, not one big regex: anything it does not recognise is either an error
 * ({@link MalformedConstraintException}) or an {@link ArrayUse} with a {@code null} column, which the parity tests
 * treat as a failure - never as "no values".
 *
 * <p>Recognised shapes (the ones PostgreSQL 16 prints):
 * <pre>
 *   col = ANY (ARRAY['A'::text, 'B'::text])
 *   (col)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying])::text[])
 *   (col)::text &lt;&gt; ALL ((ARRAY[...])::text[])
 *   col &lt;@ ARRAY['A'::text, ...]
 *   col = ARRAY['A'::text]
 *   col = ANY (ARRAY[1, 2])            -- numeric list
 * </pre>
 */
final class CheckConstraintParser {

    private CheckConstraintParser() {
    }

    /**
     * One array literal and what it is compared with.
     *
     * @param column   the compared column, or {@code null} if the left-hand side is not a plain (optionally cast) column
     * @param operator {@code "= ANY"}, {@code "<> ALL"}, {@code "<@"}, {@code "="}, ... or {@code null} if unknown
     * @param values   the literal elements in order, duplicates kept
     * @param numeric  true if the elements are numbers rather than string literals
     */
    record ArrayUse(String column, String operator, List<String> values, boolean numeric) {
    }

    static final class MalformedConstraintException extends RuntimeException {
        MalformedConstraintException(String message, String definition) {
            super(message + " in: " + definition);
        }
    }

    /**
     * One {@code column <op> 'literal'} comparison, e.g. {@code (state = 'IN_PROGRESS'::text)} or
     * {@code ((status)::text <> 'CONVERTED'::text)}. Literals inside {@code ARRAY[...]} are not included.
     */
    record LiteralUse(String column, String operator, String value) {
    }

    /** Every {@code column <op> 'literal'} comparison whose left-hand side is a plain (optionally cast) column. */
    static List<LiteralUse> literalComparisons(String definition) {
        List<Token> tokens = tokenize(definition);
        List<LiteralUse> uses = new ArrayList<>();
        for (int i = 2; i < tokens.size(); i++) {
            if (tokens.get(i).kind == Kind.STRING && tokens.get(i - 1).kind == Kind.OP) {
                String column = column(tokens, i - 2);
                if (column != null) {
                    uses.add(new LiteralUse(column, tokens.get(i - 1).text, tokens.get(i).text));
                }
            }
        }
        return uses;
    }

    static List<ArrayUse> parse(String definition) {
        List<Token> tokens = tokenize(definition);
        List<ArrayUse> uses = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).is(Kind.IDENT, "ARRAY") && i + 1 < tokens.size() && tokens.get(i + 1).is(Kind.PUNCT, "[")) {
                uses.add(arrayUse(tokens, i, definition));
            }
        }
        return uses;
    }

    private static ArrayUse arrayUse(List<Token> tokens, int arrayAt, String definition) {
        // Elements: literal [:: type words] (, literal [:: type words])* ]
        if (tokens.subList(arrayAt, tokens.size()).stream().noneMatch(t -> t.is(Kind.PUNCT, "]"))) {
            throw new MalformedConstraintException("ARRAY[ without a closing ]", definition);
        }
        List<String> values = new ArrayList<>();
        Boolean numeric = null;
        int i = arrayAt + 2;
        if (!tokens.get(i).is(Kind.PUNCT, "]")) {
            while (true) {
                Token element = tokens.get(i);
                boolean isNumber = element.kind == Kind.NUMBER;
                if (element.kind != Kind.STRING && !isNumber) {
                    throw new MalformedConstraintException("Unexpected ARRAY element '" + element.text + "'", definition);
                }
                if (numeric != null && numeric != isNumber) {
                    throw new MalformedConstraintException("ARRAY mixes strings and numbers", definition);
                }
                numeric = isNumber;
                values.add(element.text);
                i++;
                if (tokens.get(i).kind == Kind.CAST) {
                    i++;
                    while (tokens.get(i).kind == Kind.IDENT) {
                        i++;
                    }
                }
                if (tokens.get(i).is(Kind.PUNCT, "]")) {
                    break;
                }
                if (!tokens.get(i).is(Kind.PUNCT, ",")) {
                    throw new MalformedConstraintException("Unexpected '" + tokens.get(i).text + "' in ARRAY", definition);
                }
                i++;
            }
        }

        // Left-hand side: walk back over "(" to the operator, then over the column expression.
        int j = arrayAt - 1;
        while (j >= 0 && tokens.get(j).is(Kind.PUNCT, "(")) {
            j--;
        }
        String operator = null;
        int lhsEnd = -1;
        if (j >= 1 && tokens.get(j).kind == Kind.IDENT
                && (tokens.get(j).text.equals("ANY") || tokens.get(j).text.equals("ALL"))
                && tokens.get(j - 1).kind == Kind.OP) {
            operator = tokens.get(j - 1).text + " " + tokens.get(j).text;
            lhsEnd = j - 2;
        } else if (j >= 0 && tokens.get(j).kind == Kind.OP) {
            operator = tokens.get(j).text;
            lhsEnd = j - 1;
        }
        return new ArrayUse(lhsEnd >= 0 ? column(tokens, lhsEnd) : null, operator, List.copyOf(values),
                Boolean.TRUE.equals(numeric));
    }

    /** {@code col} or {@code (col)::type words} ending at {@code end}; otherwise {@code null}. */
    private static String column(List<Token> tokens, int end) {
        if (tokens.get(end).kind != Kind.IDENT) {
            return null;
        }
        int start = end;
        while (start - 1 >= 0 && tokens.get(start - 1).kind == Kind.IDENT) {
            start--;
        }
        if (start - 1 >= 0 && tokens.get(start - 1).kind == Kind.CAST) {
            int c = start - 2;
            if (c >= 2 && tokens.get(c).is(Kind.PUNCT, ")") && tokens.get(c - 1).kind == Kind.IDENT
                    && tokens.get(c - 2).is(Kind.PUNCT, "(")) {
                return tokens.get(c - 1).text;
            }
            return null;
        }
        return start == end ? tokens.get(end).text : null;
    }

    private enum Kind { IDENT, STRING, NUMBER, CAST, OP, PUNCT }

    private record Token(Kind kind, String text) {
        boolean is(Kind k, String t) {
            return kind == k && text.equals(t);
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                StringBuilder value = new StringBuilder();
                i++;
                while (true) {
                    if (i >= s.length()) {
                        throw new MalformedConstraintException("Unterminated string literal", s);
                    }
                    if (s.charAt(i) == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                            value.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    value.append(s.charAt(i++));
                }
                tokens.add(new Token(Kind.STRING, value.toString()));
            } else if (c == '"') {
                int close = s.indexOf('"', i + 1);
                if (close < 0) {
                    throw new MalformedConstraintException("Unterminated quoted identifier", s);
                }
                tokens.add(new Token(Kind.IDENT, s.substring(i + 1, close)));
                i = close + 1;
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, s.substring(start, i)));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.IDENT, s.substring(start, i)));
            } else if (s.startsWith("::", i)) {
                tokens.add(new Token(Kind.CAST, "::"));
                i += 2;
            } else if ("()[],".indexOf(c) >= 0) {
                tokens.add(new Token(Kind.PUNCT, String.valueOf(c)));
                i++;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("<@", "@>", "<>", "<=", ">=", "!=", "||").contains(two)) {
                    tokens.add(new Token(Kind.OP, two));
                    i += 2;
                } else {
                    tokens.add(new Token(Kind.OP, String.valueOf(c)));
                    i++;
                }
            }
        }
        return tokens;
    }
}
