package com.nous.lipread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class GeneratedAudio {
    private GeneratedAudio() {}

    public static boolean isWave(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 12) return false;
        byte[] header = new byte[12];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) return false;
                offset += count;
            }
        }
        return header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
    }
}
