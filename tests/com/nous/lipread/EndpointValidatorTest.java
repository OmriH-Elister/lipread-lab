package com.nous.lipread;

public final class EndpointValidatorTest {
    public static void main(String[] args) {
        acceptsHttpsEndpoint();
        rejectsHttpEndpoint();
        rejectsMissingScheme();
        normalizesTrailingSlash();
        System.out.println("EndpointValidatorTest passed");
    }

    private static void acceptsHttpsEndpoint() {
        assertEquals("https://example.com/transcribe", EndpointValidator.normalize(" https://example.com/transcribe "));
    }

    private static void rejectsHttpEndpoint() {
        assertThrows(() -> EndpointValidator.normalize("http://example.com/transcribe"));
    }

    private static void rejectsMissingScheme() {
        assertThrows(() -> EndpointValidator.normalize("example.com/transcribe"));
    }

    private static void normalizesTrailingSlash() {
        assertEquals("https://example.com/transcribe", EndpointValidator.normalize("https://example.com/transcribe/"));
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    private static void assertThrows(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
