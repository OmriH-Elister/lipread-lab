package com.nous.lipread;

import android.content.ContentResolver;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class SilentVideoProcessor {
    private SilentVideoProcessor() {}

    public static File createVideoOnlyCopy(ContentResolver resolver, Uri input, File output) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor descriptor = null;
        boolean started = false;
        try {
            descriptor = resolver.openFileDescriptor(input, "r");
            if (descriptor == null) throw new IOException("Cannot open selected video");
            extractor.setDataSource(descriptor.getFileDescriptor());

            int videoTrack = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = format;
                    break;
                }
            }
            if (videoTrack < 0 || videoFormat == null) throw new IOException("No video track found");

            if (output.exists() && !output.delete()) throw new IOException("Cannot replace temporary video");
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            applyRotation(resolver, input, muxer);
            int outputTrack = muxer.addTrack(videoFormat);
            muxer.start();
            started = true;

            extractor.selectTrack(videoTrack);
            int maxSize = videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1024 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maxSize, 1024 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outputTrack, buffer, info);
                extractor.advance();
            }
            return output;
        } finally {
            extractor.release();
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (RuntimeException ignored) {}
                }
                muxer.release();
            }
            if (descriptor != null) descriptor.close();
        }
    }

    private static void applyRotation(ContentResolver resolver, Uri input, MediaMuxer muxer) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(input, "r")) {
            if (descriptor == null) return;
            retriever.setDataSource(descriptor.getFileDescriptor());
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) muxer.setOrientationHint(Integer.parseInt(rotation));
        } catch (Exception ignored) {
            // Rotation metadata is optional.
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}
