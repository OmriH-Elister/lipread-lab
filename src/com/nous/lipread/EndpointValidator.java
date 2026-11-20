package com.nous.lipread;

import java.net.URI;
import java.net.URISyntaxException;

public final class EndpointValidator {
    private EndpointValidator() {}

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Endpoint is required");
        String value = raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Use a valid HTTPS endpoint");
            }
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Use a valid HTTPS endpoint", e);
        }
    }
}
