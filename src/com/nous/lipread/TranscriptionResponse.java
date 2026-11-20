package com.nous.lipread;

import java.util.HashSet;
import java.util.Set;

public final class TranscriptionResponse {
    public final String transcript;
    public final double confidence;

    private TranscriptionResponse(String transcript, double confidence) {
        this.transcript = transcript;
        this.confidence = confidence;
    }

    public static TranscriptionResponse parse(String json) {
        Parser parser = new Parser(json);
        return parser.parseResponse();
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        TranscriptionResponse parseResponse() {
            skipWhitespace();
            expect('{');
            Set<String> keys = new HashSet<>();
            String transcript = null;
            boolean hasTranscript = false;
            double confidence = -1.0;
            skipWhitespace();
            if (peek('}')) {
                position++;
            } else {
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    if (!keys.add(key)) fail("Duplicate top-level key");
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    if ("transcript".equals(key)) {
                        if (!peek('"')) fail("Transcript must be a string");
                        transcript = parseString();
                        hasTranscript = true;
                    } else if ("confidence".equals(key)) {
                        confidence = parseNumber();
                        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                            fail("Confidence must be finite and between 0 and 1");
                        }
                    } else {
                        skipValue();
                    }
                    skipWhitespace();
                    if (peek(',')) {
                        position++;
                        continue;
                    }
                    expect('}');
                    break;
                }
            }
            skipWhitespace();
            if (position != text.length()) fail("Trailing content after JSON object");
            if (!hasTranscript) fail("Server response has no transcript string");
            return new TranscriptionResponse(transcript, confidence);
        }

        private void skipValue() {
            skipWhitespace();
            if (position >= text.length()) fail("Missing JSON value");
            char c = text.charAt(position);
            if (c == '"') {
                parseString();
            } else if (c == '{') {
                skipObject();
            } else if (c == '[') {
                skipArray();
            } else if (c == 't') {
                expectLiteral("true");
            } else if (c == 'f') {
                expectLiteral("false");
            } else if (c == 'n') {
                expectLiteral("null");
            } else if (c == '-' || isDigit(c)) {
                parseNumber();
            } else {
                fail("Invalid JSON value");
            }
        }

        private void skipObject() {
            expect('{');
            Set<String> keys = new HashSet<>();
            skipWhitespace();
            if (peek('}')) {
                position++;
                return;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                if (!keys.add(key)) fail("Duplicate object key");
                skipWhitespace();
                expect(':');
                skipValue();
                skipWhitespace();
                if (peek(',')) {
                    position++;
                } else {
                    expect('}');
                    return;
                }
            }
        }

        private void skipArray() {
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                position++;
                return;
            }
            while (true) {
                skipValue();
                skipWhitespace();
                if (peek(',')) {
                    position++;
                } else {
                    expect(']');
                    return;
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return result.toString();
                if (c < 0x20) fail("Control character in JSON string");
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position >= text.length()) fail("Incomplete JSON escape");
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u': result.append(parseUnicodeEscape()); break;
                    default: fail("Invalid JSON escape");
                }
            }
            fail("Unterminated JSON string");
            return "";
        }

        private char parseUnicodeEscape() {
            if (position + 4 > text.length()) fail("Incomplete Unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(position++), 16);
                if (digit < 0) fail("Invalid Unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private double parseNumber() {
            int start = position;
            if (peek('-')) position++;
            if (peek('0')) {
                position++;
                if (position < text.length() && isDigit(text.charAt(position))) fail("Leading zero in JSON number");
            } else {
                requireDigits();
            }
            if (peek('.')) {
                position++;
                requireDigits();
            }
            if (peek('e') || peek('E')) {
                position++;
                if (peek('+') || peek('-')) position++;
                requireDigits();
            }
            try {
                return Double.parseDouble(text.substring(start, position));
            } catch (NumberFormatException e) {
                fail("Invalid JSON number");
                return 0;
            }
        }

        private void requireDigits() {
            int start = position;
            while (position < text.length() && isDigit(text.charAt(position))) position++;
            if (start == position) fail("Invalid JSON number");
        }

        private void expectLiteral(String literal) {
            if (!text.regionMatches(position, literal, 0, literal.length())) fail("Invalid JSON literal");
            position += literal.length();
        }

        private void expect(char expected) {
            if (position >= text.length() || text.charAt(position) != expected) {
                fail("Expected '" + expected + "'");
            }
            position++;
        }

        private boolean peek(char value) {
            return position < text.length() && text.charAt(position) == value;
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') position++;
                else break;
            }
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + " at position " + position);
        }
    }
}
