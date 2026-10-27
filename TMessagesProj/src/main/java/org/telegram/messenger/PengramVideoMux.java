package org.telegram.messenger;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Склейка скачанных дорожек (видео без звука + звуковая дорожка) в один mp4
 * копированием семплов без перекодирования (MediaExtractor + MediaMuxer).
 * Нужна для YouTube-клиентов, отдающих только adaptive-форматы.
 */
public final class PengramVideoMux {

    private static final long MAX_PTS_GAP_US = 10_000_000L;

    private PengramVideoMux() {
    }

    /** Бросает Exception при ошибке; out удаляется неудачным. */
    public static void mux(File videoFile, File audioFile, File outFile, boolean[] cancel) throws Exception {
        final MediaExtractor videoExtractor = new MediaExtractor();
        final MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            videoExtractor.setDataSource(videoFile.getPath());
            audioExtractor.setDataSource(audioFile.getPath());

            final int videoTrack = selectTrack(videoExtractor, true);
            final int audioTrack = selectTrack(audioExtractor, false);
            if (videoTrack < 0) {
                throw new Exception("no video track");
            }
            if (audioTrack < 0) {
                throw new Exception("no audio track");
            }
            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);

            final MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
            final MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);

            muxer = new MediaMuxer(outFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final int outVideo = muxer.addTrack(videoFormat);
            final int outAudio = muxer.addTrack(audioFormat);
            muxer.start();

            final long videoDurationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;

            copySamples(videoExtractor, muxer, outVideo, videoTrack, cancel);
            if (cancel != null && cancel[0]) {
                throw new java.util.concurrent.CancellationException("cancelled");
            }
            // Звук может быть длиннее — обрезаем по факту (PTS не обязаны идти с нуля).
            copySamples(audioExtractor, muxer, outAudio, audioTrack, cancel);

            muxer.stop();
            muxer.release();
            muxer = null;
            if (outFile.length() <= 0) {
                throw new Exception("empty output");
            }
        } catch (Throwable t) {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignore) {
                }
            }
            if (outFile.exists()) {
                outFile.delete();
            }
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new Exception(t);
        } finally {
            try {
                videoExtractor.release();
            } catch (Throwable ignore) {
            }
            try {
                audioExtractor.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private static int selectTrack(MediaExtractor extractor, boolean video) {
        final int count = extractor.getTrackCount();
        for (int i = 0; i < count; i++) {
            final MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                continue;
            }
            if (video && mime.startsWith("video/")) {
                return i;
            }
            if (!video && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static void copySamples(MediaExtractor extractor, MediaMuxer muxer, int outTrack, int extractorTrack,
                                    boolean[] cancel) throws Exception {
        final MediaFormat format = extractor.getTrackFormat(extractorTrack);
        final int maxInputSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1024 * 1024;
        final ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maxInputSize, 256 * 1024));
        final android.media.MediaCodec.BufferInfo sampleInfo = new android.media.MediaCodec.BufferInfo();

        final long maxOutputPtsUs = format.containsKey(MediaFormat.KEY_DURATION)
                ? Math.min(format.getLong(MediaFormat.KEY_DURATION) * 2, 60L * 60_000_000L)
                : 60L * 60_000_000L;

        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long lastWrittenPtsUs = 0;
        while (true) {
            if (cancel != null && cancel[0]) {
                throw new java.util.concurrent.CancellationException("cancelled");
            }
            final int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                break;
            }
            final long ptsUs = extractor.getSampleTime();
            if (ptsUs > maxOutputPtsUs) {
                break;
            }
            if (ptsUs >= 0 && ptsUs - lastWrittenPtsUs > MAX_PTS_GAP_US && lastWrittenPtsUs > 0) {
                // Скачанный поток с гигантским разрывом — не тащим «хвост».
                break;
            }
            buffer.position(0);
            buffer.limit(sampleSize);
            sampleInfo.set(0, sampleSize, Math.max(ptsUs, 0), extractor.getSampleFlags());
            muxer.writeSampleData(outTrack, buffer, sampleInfo);
            if (ptsUs >= 0) {
                lastWrittenPtsUs = ptsUs;
            }
            extractor.advance();
        }
    }
}
