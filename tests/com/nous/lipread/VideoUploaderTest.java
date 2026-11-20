package com.nous.lipread;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class VideoUploaderTest {
    public static void main(String[] args) throws Exception {
        uploadsMultipartAndParsesResponse();
        sendsOptionalBearerToken();
        reportsHttpErrors();
        System.out.println("VideoUploaderTest passed");
    }

    private static void uploadsMultipartAndParsesResponse() throws Exception {
        File sample = sampleFile();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", exchange -> {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (contentType == null || !contentType.startsWith("multipart/form-data; boundary=")) {
                throw new AssertionError("Missing multipart content type");
            }
            String payload = new String(body, StandardCharsets.ISO_8859_1);
            if (!payload.contains("name=\"video\"") || !payload.contains("test-video-bytes")) {
                throw new AssertionError("Missing uploaded video field");
            }
            byte[] response = "{\"transcript\":\"test phrase\",\"confidence\":0.5}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/transcribe";
            TranscriptionResponse result = VideoUploader.upload(endpoint, sample);
            assertEquals("test phrase", result.transcript);
        } finally {
            server.stop(0);
            sample.delete();
        }
    }

    private static void sendsOptionalBearerToken() throws Exception {
        File sample = sampleFile();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer secret-token".equals(authorization)) {
                throw new AssertionError("Missing bearer token");
            }
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"transcript\":\"authorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/transcribe";
            TranscriptionResponse result = VideoUploader.upload(endpoint, sample, " secret-token ");
            assertEquals("authorized", result.transcript);
        } finally {
            server.stop(0);
            sample.delete();
        }
    }

    private static void reportsHttpErrors() throws Exception {
        File sample = sampleFile();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcribe", exchange -> {
            byte[] response = "bad video".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/transcribe";
            try {
                VideoUploader.upload(endpoint, sample);
                throw new AssertionError("Expected HTTP error");
            } catch (java.io.IOException expected) {
                if (!expected.getMessage().contains("HTTP 422")) throw expected;
            }
        } finally {
            server.stop(0);
            sample.delete();
        }
    }

    private static File sampleFile() throws Exception {
        File file = File.createTempFile("lipread-uploader", ".mp4");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("test-video-bytes".getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
}
