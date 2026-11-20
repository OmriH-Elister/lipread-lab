package com.nous.lipread;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class VideoUploader {
    private VideoUploader() {}

    public static TranscriptionResponse upload(String endpoint, File video) throws IOException {
        return upload(endpoint, video, "");
    }

    public static TranscriptionResponse upload(String endpoint, File video, String bearerToken) throws IOException {
        String boundary = "LipReadLab" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(180_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(256 * 1024);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        String token = bearerToken == null ? "" : bearerToken.trim();
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);

        try {
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"video\"; filename=\"silent.mp4\"\r\n");
            out.writeBytes("Content-Type: video/mp4\r\n\r\n");
            InputStream in = new BufferedInputStream(new FileInputStream(video));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            in.close();
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
            out.close();

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readLimited(responseStream, 1_000_000);
            if (status < 200 || status >= 300) {
                throw new IOException("Server returned HTTP " + status + (body.isEmpty() ? "" : ": " + body));
            }
            try {
                return TranscriptionResponse.parse(body);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid server response", e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int total = 0;
        int count;
        while ((count = reader.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("Server response is too large");
            result.append(buffer, 0, count);
        }
        reader.close();
        return result.toString();
    }
}
