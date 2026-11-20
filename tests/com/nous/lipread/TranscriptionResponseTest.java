package com.nous.lipread;

public final class TranscriptionResponseTest {
    public static void main(String[] args) {
        parsesTranscriptAndConfidence();
        parsesEscapedText();
        rejectsMissingTranscript();
        rejectsTrailingGarbage();
        rejectsDuplicateKeys();
        rejectsNonStringTranscript();
        rejectsOutOfRangeConfidence();
        rejectsNonFiniteConfidence();
        acceptsNestedUnknownValues();
        System.out.println("TranscriptionResponseTest passed");
    }

    private static void parsesTranscriptAndConfidence() {
        TranscriptionResponse r = TranscriptionResponse.parse("{\"transcript\":\"hello world\",\"confidence\":0.72}");
        assertEquals("hello world", r.transcript);
        assertNear(0.72, r.confidence);
    }

    private static void parsesEscapedText() {
        TranscriptionResponse r = TranscriptionResponse.parse("{\"transcript\":\"she said \\\"hello\\\"\\nagain\"}");
        assertEquals("she said \"hello\"\nagain", r.transcript);
        assertNear(-1.0, r.confidence);
    }

    private static void rejectsMissingTranscript() {
        assertRejects("{\"error\":\"failed\"}");
    }

    private static void rejectsTrailingGarbage() {
        assertRejects("{\"transcript\":\"hello\"} trailing");
    }

    private static void rejectsDuplicateKeys() {
        assertRejects("{\"transcript\":\"first\",\"transcript\":\"second\"}");
        assertRejects("{\"transcript\":\"hello\",\"confidence\":0.2,\"confidence\":0.3}");
    }

    private static void rejectsNonStringTranscript() {
        assertRejects("{\"transcript\":null}");
        assertRejects("{\"transcript\":42}");
    }

    private static void rejectsOutOfRangeConfidence() {
        assertRejects("{\"transcript\":\"hello\",\"confidence\":-0.01}");
        assertRejects("{\"transcript\":\"hello\",\"confidence\":1.01}");
    }

    private static void rejectsNonFiniteConfidence() {
        assertRejects("{\"transcript\":\"hello\",\"confidence\":1e9999}");
        assertRejects("{\"transcript\":\"hello\",\"confidence\":NaN}");
    }

    private static void acceptsNestedUnknownValues() {
        TranscriptionResponse r = TranscriptionResponse.parse(
                "{\"metadata\":{\"items\":[true,null,{\"x\":1}]},\"transcript\":\"hello\"}");
        assertEquals("hello", r.transcript);
    }

    private static void assertRejects(String json) {
        try {
            TranscriptionResponse.parse(json);
            throw new AssertionError("Expected parse failure for " + json);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    private static void assertNear(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.0001) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
}
