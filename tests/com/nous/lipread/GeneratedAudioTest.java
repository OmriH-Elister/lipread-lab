package com.nous.lipread;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class GeneratedAudioTest {
    public static void main(String[] args) throws Exception {
        detectsWaveHeader();
        rejectsNonWaveContent();
        rejectsShortContent();
        System.out.println("GeneratedAudioTest passed");
    }

    private static void detectsWaveHeader() throws Exception {
        File file = write("RIFF1234WAVEdata");
        try {
            if (!GeneratedAudio.isWave(file)) throw new AssertionError("Expected WAVE detection");
        } finally {
            file.delete();
        }
    }

    private static void rejectsNonWaveContent() throws Exception {
        File file = write("OggS123456789012");
        try {
            if (GeneratedAudio.isWave(file)) throw new AssertionError("Non-WAVE accepted");
        } finally {
            file.delete();
        }
    }

    private static void rejectsShortContent() throws Exception {
        File file = write("RIFF");
        try {
            if (GeneratedAudio.isWave(file)) throw new AssertionError("Short file accepted");
        } finally {
            file.delete();
        }
    }

    private static File write(String value) throws Exception {
        File file = File.createTempFile("lipread-audio", ".bin");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        }
        return file;
    }
}
