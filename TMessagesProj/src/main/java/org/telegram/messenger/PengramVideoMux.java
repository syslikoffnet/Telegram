package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/**
 * Сборка и ремультиплексирование потоков без перекодирования:
 *  • {@link #mux(File, File, File)} — объединение раздельных видео MP4 и аудио M4A (YouTube HD, Reddit DASH);
 *  • {@link #remuxToMp4(File, File)} — перевод HLS MPEG-TS (.ts) / fMP4 в стандартный прогрессивный .mp4
 *    (сначала через системный MediaExtractor, а на прошивках без TS-экстрактора — встроенным
 *    парсером 188-байтных пакетов MPEG-TS H.264 Annex-B + ADTS AAC напрямую в MediaMuxer);
 *  • {@link #extractAudioToM4a(File, File)} — мгновенное извлечение звуковой дорожки в .m4a
 *    (прямое копирование AAC без перекодирования, либо транскод в AAC-LC 192 кбит/с для других кодеков).
 */
public final class PengramVideoMux {

    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final int TS_PACKET_SIZE = 188;

    private PengramVideoMux() {
    }

    public static void mux(File videoFile, File audioFile, File outputFile) throws Exception {
        mux(videoFile, audioFile, outputFile, null);
    }

    public static void mux(File videoFile, File audioFile, File outputFile, boolean[] cancel) throws Exception {
        final MediaExtractor videoExtractor = new MediaExtractor();
        final MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            final int srcVideoTrack = selectTrack(videoExtractor, "video/");
            final int srcAudioTrack = selectTrack(audioExtractor, "audio/");
            if (srcVideoTrack < 0) {
                throw new IllegalStateException("no video track");
            }
            if (srcAudioTrack < 0) {
                throw new IllegalStateException("no audio track");
            }

            videoExtractor.selectTrack(srcVideoTrack);
            audioExtractor.selectTrack(srcAudioTrack);

            final MediaFormat videoFormat = videoExtractor.getTrackFormat(srcVideoTrack);
            final MediaFormat audioFormat = audioExtractor.getTrackFormat(srcAudioTrack);

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
            final int dstVideo = muxer.addTrack(videoFormat);
            final int dstAudio = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            final int bufSize = Math.max(
                    maxInputSize(videoFormat),
                    Math.max(maxInputSize(audioFormat), COPY_BUFFER_BYTES));
            final ByteBuffer buffer = ByteBuffer.allocateDirect(bufSize);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            final long videoDurationUs = copySamples(videoExtractor, muxer, dstVideo, buffer, info, Long.MAX_VALUE, cancel);
            if (cancel != null && cancel[0]) {
                throw new java.util.concurrent.CancellationException("cancelled");
            }
            final long audioLimitUs = videoDurationUs > 0 ? videoDurationUs + 250_000L : Long.MAX_VALUE;
            copySamples(audioExtractor, muxer, dstAudio, buffer, info, audioLimitUs, cancel);
            if (cancel != null && cancel[0]) {
                throw new java.util.concurrent.CancellationException("cancelled");
            }
        } catch (Exception e) {
            outputFile.delete();
            throw e;
        } finally {
            try {
                videoExtractor.release();
            } catch (Throwable ignore) {
            }
            try {
                audioExtractor.release();
            } catch (Throwable ignore) {
            }
            if (muxer != null) {
                try {
                    if (started) {
                        muxer.stop();
                    }
                } catch (Throwable ignore) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Перепаковывает скачанный HLS-поток (.ts / fMP4) в стандартный прогрессивный .mp4 без перекодирования.
     */
    public static void remuxToMp4(File inputFile, File outputFile) throws Exception {
        remuxToMp4(inputFile, outputFile, null);
    }

    public static void remuxToMp4(File inputFile, File outputFile, boolean[] cancel) throws Exception {
        try {
            if (tryRemuxWithMediaExtractor(inputFile, outputFile, cancel)) {
                return;
            }
        } catch (java.util.concurrent.CancellationException ce) {
            outputFile.delete();
            throw ce;
        } catch (Throwable ignore) {
            outputFile.delete();
        }
        remuxMpegTsToMp4(inputFile, outputFile, false, cancel);
    }

    /**
     * Извлекает аудиодорожку из видео/контейнера в чистый .m4a (AAC-LC в MP4-контейнере).
     */
    public static void extractAudioToM4a(File mediaFile, File outputM4a) throws Exception {
        extractAudioToM4a(mediaFile, outputM4a, null);
    }

    public static void extractAudioToM4a(File mediaFile, File outputM4a, boolean[] cancel) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(mediaFile.getAbsolutePath());
            final int srcAudio = selectTrack(extractor, "audio/");
            if (srcAudio >= 0) {
                extractor.selectTrack(srcAudio);
                final MediaFormat fmt = extractor.getTrackFormat(srcAudio);
                final String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/mp4a")) {
                    muxer = new MediaMuxer(outputM4a.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    final int dstAudio = muxer.addTrack(fmt);
                    muxer.start();
                    started = true;
                    final ByteBuffer buf = ByteBuffer.allocateDirect(Math.max(maxInputSize(fmt), 256 * 1024));
                    final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    copySamples(extractor, muxer, dstAudio, buf, info, Long.MAX_VALUE, cancel);
                    if (cancel != null && cancel[0]) {
                        throw new java.util.concurrent.CancellationException("cancelled");
                    }
                    if (outputM4a.length() > 512) {
                        return;
                    }
                } else {
                    extractor.release();
                    transcodeAudioToM4a(mediaFile, outputM4a, cancel);
                    return;
                }
            }
        } catch (java.util.concurrent.CancellationException ce) {
            outputM4a.delete();
            throw ce;
        } catch (Throwable ignore) {
            outputM4a.delete();
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignore) {
            }
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Throwable ignore) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignore) {
                }
            }
        }
        remuxMpegTsToMp4(mediaFile, outputM4a, true, cancel);
    }

    private static boolean tryRemuxWithMediaExtractor(File inputFile, File outputFile, boolean[] cancel) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(inputFile.getAbsolutePath());
            final int srcVideo = selectTrack(extractor, "video/");
            final int srcAudio = selectTrack(extractor, "audio/");
            if (srcVideo < 0) {
                return false;
            }
            final MediaFormat vFmt = extractor.getTrackFormat(srcVideo);
            if (!vFmt.containsKey("csd-0")) {
                return false;
            }
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(vFmt.getInteger(MediaFormat.KEY_ROTATION));
            }
            final int dstVideo = muxer.addTrack(vFmt);
            int dstAudio = -1;
            MediaFormat aFmt = null;
            if (srcAudio >= 0) {
                aFmt = extractor.getTrackFormat(srcAudio);
                final String aMime = aFmt.getString(MediaFormat.KEY_MIME);
                if (aMime != null && aMime.startsWith("audio/mp4a")) {
                    dstAudio = muxer.addTrack(aFmt);
                }
            }
            muxer.start();
            started = true;

            final int bufSize = Math.max(maxInputSize(vFmt),
                    Math.max(aFmt != null ? maxInputSize(aFmt) : 0, COPY_BUFFER_BYTES));
            final ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            extractor.selectTrack(srcVideo);
            final long durUs = copySamples(extractor, muxer, dstVideo, buf, info, Long.MAX_VALUE, cancel);
            if (cancel != null && cancel[0]) {
                throw new java.util.concurrent.CancellationException("cancelled");
            }
            extractor.unselectTrack(srcVideo);
            if (dstAudio >= 0) {
                extractor.selectTrack(srcAudio);
                copySamples(extractor, muxer, dstAudio, buf, info, durUs > 0 ? durUs + 250_000L : Long.MAX_VALUE, cancel);
            }
            return outputFile.length() > 1024;
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignore) {
            }
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Throwable ignore) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Транскодирует произвольную звуковую дорожку (MP3/Opus/Vorbis) в AAC-LC 192 кбит/с .m4a. */
    private static void transcodeAudioToM4a(File input, File outputM4a, boolean[] cancel) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxStarted = false;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            final int track = selectTrack(extractor, "audio/");
            if (track < 0) {
                throw new IllegalStateException("no audio track");
            }
            extractor.selectTrack(track);
            final MediaFormat inFmt = extractor.getTrackFormat(track);
            final String inMime = inFmt.getString(MediaFormat.KEY_MIME);
            final int sampleRate = inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            final int channels = Math.max(1, Math.min(2, inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2));

            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inFmt, null, null, 0);
            decoder.start();

            final MediaFormat outFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            outFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outFmt.setInteger(MediaFormat.KEY_BIT_RATE, 192_000);
            outFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputM4a.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dstTrack = -1;
            boolean inDone = false;
            boolean decDone = false;
            boolean encDone = false;
            final MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            while (!encDone) {
                if (cancel != null && cancel[0]) {
                    throw new java.util.concurrent.CancellationException("cancelled");
                }
                if (!inDone) {
                    final int inIdx = decoder.dequeueInputBuffer(5_000);
                    if (inIdx >= 0) {
                        final ByteBuffer ib = decoder.getInputBuffer(inIdx);
                        if (ib != null) {
                            ib.clear();
                            final int sz = extractor.readSampleData(ib, 0);
                            if (sz < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else {
                                final long pts = Math.max(0L, extractor.getSampleTime());
                                decoder.queueInputBuffer(inIdx, 0, sz, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }
                if (!decDone) {
                    final int outIdx = decoder.dequeueOutputBuffer(decInfo, 5_000);
                    if (outIdx >= 0) {
                        final ByteBuffer pcm = decoder.getOutputBuffer(outIdx);
                        final boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (pcm != null && decInfo.size > 0) {
                            pcm.position(decInfo.offset);
                            pcm.limit(decInfo.offset + decInfo.size);
                            while (pcm.hasRemaining()) {
                                final int encIn = encoder.dequeueInputBuffer(10_000);
                                if (encIn < 0) break;
                                final ByteBuffer eb = encoder.getInputBuffer(encIn);
                                if (eb == null) break;
                                eb.clear();
                                final int chunk = Math.min(pcm.remaining(), eb.remaining());
                                final int savedLimit = pcm.limit();
                                pcm.limit(pcm.position() + chunk);
                                eb.put(pcm);
                                pcm.limit(savedLimit);
                                encoder.queueInputBuffer(encIn, 0, chunk, Math.max(0L, decInfo.presentationTimeUs), 0);
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false);
                        if (eos) {
                            final int encIn = encoder.dequeueInputBuffer(10_000);
                            if (encIn >= 0) {
                                encoder.queueInputBuffer(encIn, 0, 0, Math.max(0L, decInfo.presentationTimeUs),
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                            decDone = true;
                        }
                    }
                }
                while (true) {
                    final int encOut = encoder.dequeueOutputBuffer(encInfo, 5_000);
                    if (encOut == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxStarted) {
                            dstTrack = muxer.addTrack(encoder.getOutputFormat());
                            muxer.start();
                            muxStarted = true;
                        }
                    } else if (encOut >= 0) {
                        final ByteBuffer ob = encoder.getOutputBuffer(encOut);
                        if (ob != null && encInfo.size > 0 && muxStarted
                                && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ob.position(encInfo.offset);
                            ob.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(dstTrack, ob, encInfo);
                        }
                        final boolean eos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(encOut, false);
                        if (eos) {
                            encDone = true;
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            outputM4a.delete();
            throw e;
        } finally {
            try { extractor.release(); } catch (Throwable ignore) {}
            if (decoder != null) {
                try { decoder.stop(); } catch (Throwable ignore) {}
                try { decoder.release(); } catch (Throwable ignore) {}
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignore) {}
                try { encoder.release(); } catch (Throwable ignore) {}
            }
            if (muxer != null) {
                try { if (muxStarted) muxer.stop(); } catch (Throwable ignore) {}
                try { muxer.release(); } catch (Throwable ignore) {}
            }
        }
    }

    // --- Автономный демультиплексор MPEG-TS (188 байт, H.264 Annex-B + ADTS AAC) → MediaMuxer ---

    private static final int[] AAC_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private static final class TsSample {
        final boolean video;
        final byte[] data;
        final long ptsUs;
        final int flags;

        TsSample(boolean video, byte[] data, long ptsUs, int flags) {
            this.video = video;
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    private static void remuxMpegTsToMp4(File tsFile, File mp4Out, boolean audioOnly, boolean[] cancel) throws Exception {
        int pmtPid = -1;
        int videoPid = -1;
        int audioPid = -1;

        final ByteArrayOutputStream vPes = new ByteArrayOutputStream(65536);
        final ByteArrayOutputStream aPes = new ByteArrayOutputStream(16384);
        long vPesPtsUs = -1L;
        long aPesPtsUs = -1L;
        long basePtsUs = Long.MAX_VALUE;

        byte[] sps = null;
        byte[] pps = null;
        int aacProfile = 2;
        int aacFreqIdx = 4;
        int aacChannels = 2;
        boolean hasAudioConfig = false;

        final java.util.ArrayList<TsSample> samples = new java.util.ArrayList<>(2048);
        final byte[] pkt = new byte[TS_PACKET_SIZE];

        try (FileInputStream in = new FileInputStream(tsFile)) {
            while (readFullyOrEof(in, pkt)) {
                if ((pkt[0] & 0xFF) != 0x47) {
                    // Поиск синхробайта при сбое выравнивания
                    continue;
                }
                final boolean payloadStart = (pkt[1] & 0x40) != 0;
                final int pid = ((pkt[1] & 0x1F) << 8) | (pkt[2] & 0xFF);
                final int afc = (pkt[3] >> 4) & 0x03;
                if ((afc & 0x01) == 0) {
                    continue;
                }
                int pos = 4;
                if ((afc & 0x02) != 0) {
                    final int adaptLen = pkt[4] & 0xFF;
                    pos = 5 + adaptLen;
                }
                if (pos >= TS_PACKET_SIZE) {
                    continue;
                }
                if (pid == 0) {
                    int sec = pos + (payloadStart ? 1 + (pkt[pos] & 0xFF) : 0);
                    if (sec + 12 <= TS_PACKET_SIZE && (pkt[sec] & 0xFF) == 0x00) {
                        final int secLen = ((pkt[sec + 1] & 0x0F) << 8) | (pkt[sec + 2] & 0xFF);
                        final int end = Math.min(TS_PACKET_SIZE, sec + 3 + secLen - 4);
                        for (int p = sec + 8; p + 4 <= end; p += 4) {
                            final int progNum = ((pkt[p] & 0xFF) << 8) | (pkt[p + 1] & 0xFF);
                            if (progNum != 0) {
                                pmtPid = ((pkt[p + 2] & 0x1F) << 8) | (pkt[p + 3] & 0xFF);
                                break;
                            }
                        }
                    }
                } else if (pid == pmtPid) {
                    int sec = pos + (payloadStart ? 1 + (pkt[pos] & 0xFF) : 0);
                    if (sec + 12 <= TS_PACKET_SIZE && (pkt[sec] & 0xFF) == 0x02) {
                        final int secLen = ((pkt[sec + 1] & 0x0F) << 8) | (pkt[sec + 2] & 0xFF);
                        final int progInfoLen = ((pkt[sec + 10] & 0x0F) << 8) | (pkt[sec + 11] & 0xFF);
                        int p = sec + 12 + progInfoLen;
                        final int end = Math.min(TS_PACKET_SIZE, sec + 3 + secLen - 4);
                        while (p + 5 <= end) {
                            final int st = pkt[p] & 0xFF;
                            final int elemPid = ((pkt[p + 1] & 0x1F) << 8) | (pkt[p + 2] & 0xFF);
                            final int esInfoLen = ((pkt[p + 3] & 0x0F) << 8) | (pkt[p + 4] & 0xFF);
                            if (st == 0x1B && videoPid < 0) {
                                videoPid = elemPid;
                            } else if (st == 0x0F && audioPid < 0) {
                                audioPid = elemPid;
                            }
                            p += 5 + esInfoLen;
                        }
                    }
                } else if (!audioOnly && pid == videoPid) {
                    if (payloadStart) {
                        if (vPes.size() > 0 && vPesPtsUs >= 0) {
                            final byte[][] cfg = flushH264Pes(vPes.toByteArray(), vPesPtsUs, samples);
                            if (cfg[0] != null && sps == null) sps = cfg[0];
                            if (cfg[1] != null && pps == null) pps = cfg[1];
                            if (vPesPtsUs < basePtsUs) basePtsUs = vPesPtsUs;
                        }
                        vPes.reset();
                        final long[] hdr = parsePesHeader(pkt, pos);
                        if (hdr != null) {
                            vPesPtsUs = hdr[1] >= 0 ? (hdr[1] * 1_000_000L) / 90_000L : vPesPtsUs;
                            vPes.write(pkt, (int) hdr[0], TS_PACKET_SIZE - (int) hdr[0]);
                        }
                    } else if (vPesPtsUs >= 0) {
                        vPes.write(pkt, pos, TS_PACKET_SIZE - pos);
                    }
                } else if (pid == audioPid) {
                    if (payloadStart) {
                        if (aPes.size() > 0 && aPesPtsUs >= 0) {
                            final int[] cfg = flushAdtsPes(aPes.toByteArray(), aPesPtsUs, samples);
                            if (cfg != null && !hasAudioConfig) {
                                aacProfile = cfg[0];
                                aacFreqIdx = cfg[1];
                                aacChannels = cfg[2];
                                hasAudioConfig = true;
                            }
                            if (aPesPtsUs < basePtsUs) basePtsUs = aPesPtsUs;
                        }
                        aPes.reset();
                        final long[] hdr = parsePesHeader(pkt, pos);
                        if (hdr != null) {
                            aPesPtsUs = hdr[1] >= 0 ? (hdr[1] * 1_000_000L) / 90_000L : aPesPtsUs;
                            aPes.write(pkt, (int) hdr[0], TS_PACKET_SIZE - (int) hdr[0]);
                        }
                    } else if (aPesPtsUs >= 0) {
                        aPes.write(pkt, pos, TS_PACKET_SIZE - pos);
                    }
                }
            }
        }
        if (!audioOnly && vPes.size() > 0 && vPesPtsUs >= 0) {
            final byte[][] cfg = flushH264Pes(vPes.toByteArray(), vPesPtsUs, samples);
            if (cfg[0] != null && sps == null) sps = cfg[0];
            if (cfg[1] != null && pps == null) pps = cfg[1];
            if (vPesPtsUs < basePtsUs) basePtsUs = vPesPtsUs;
        }
        if (aPes.size() > 0 && aPesPtsUs >= 0) {
            final int[] cfg = flushAdtsPes(aPes.toByteArray(), aPesPtsUs, samples);
            if (cfg != null && !hasAudioConfig) {
                aacProfile = cfg[0];
                aacFreqIdx = cfg[1];
                aacChannels = cfg[2];
                hasAudioConfig = true;
            }
            if (aPesPtsUs < basePtsUs) basePtsUs = aPesPtsUs;
        }

        if (audioOnly) {
            if (!hasAudioConfig || samples.isEmpty()) {
                throw new IllegalStateException("no TS audio");
            }
        } else if (sps == null || pps == null || samples.isEmpty()) {
            throw new IllegalStateException("no TS H.264 stream");
        }
        if (basePtsUs == Long.MAX_VALUE) {
            basePtsUs = 0L;
        }

        MediaMuxer muxer = null;
        boolean started = false;
        try {
            muxer = new MediaMuxer(mp4Out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dstVideo = -1;
            int dstAudio = -1;
            if (!audioOnly) {
                final MediaFormat vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920);
                vFmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                vFmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
                dstVideo = muxer.addTrack(vFmt);
            }
            if (hasAudioConfig) {
                final int sr = (aacFreqIdx >= 0 && aacFreqIdx < AAC_SAMPLE_RATES.length)
                        ? AAC_SAMPLE_RATES[aacFreqIdx] : 44100;
                final MediaFormat aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                        sr, Math.max(1, aacChannels));
                final int asc = ((aacProfile & 0x1F) << 11) | ((aacFreqIdx & 0x0F) << 7) | ((aacChannels & 0x0F) << 3);
                final byte[] csd = new byte[]{(byte) ((asc >> 8) & 0xFF), (byte) (asc & 0xFF)};
                aFmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                dstAudio = muxer.addTrack(aFmt);
            }
            muxer.start();
            started = true;

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0, n = samples.size(); i < n; i++) {
                if (cancel != null && cancel[0]) {
                    throw new java.util.concurrent.CancellationException("cancelled");
                }
                final TsSample s = samples.get(i);
                final int track = s.video ? dstVideo : dstAudio;
                if (track < 0) continue;
                info.set(0, s.data.length, Math.max(0L, s.ptsUs - basePtsUs), s.flags);
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info);
            }
        } catch (Exception e) {
            mp4Out.delete();
            throw e;
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Throwable ignore) {}
                try { muxer.release(); } catch (Throwable ignore) {}
            }
        }
    }

    private static boolean readFullyOrEof(FileInputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            final int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static long[] parsePesHeader(byte[] pkt, int pos) {
        if (pos + 9 > TS_PACKET_SIZE) return null;
        if ((pkt[pos] & 0xFF) != 0x00 || (pkt[pos + 1] & 0xFF) != 0x00 || (pkt[pos + 2] & 0xFF) != 0x01) {
            return null;
        }
        final int flags2 = pkt[pos + 7] & 0xFF;
        final int hdrDataLen = pkt[pos + 8] & 0xFF;
        final int payloadPos = pos + 9 + hdrDataLen;
        if (payloadPos > TS_PACKET_SIZE) return null;
        long pts90k = -1L;
        if ((flags2 & 0x80) != 0 && pos + 14 <= TS_PACKET_SIZE) {
            pts90k = (((long) (pkt[pos + 9] & 0x0E)) << 29)
                    | (((long) (pkt[pos + 10] & 0xFF)) << 22)
                    | (((long) (pkt[pos + 11] & 0xFE)) << 14)
                    | (((long) (pkt[pos + 12] & 0xFF)) << 7)
                    | (((long) (pkt[pos + 13] & 0xFE)) >> 1);
        }
        return new long[]{payloadPos, pts90k};
    }

    private static byte[][] flushH264Pes(byte[] annexB, long ptsUs, java.util.ArrayList<TsSample> out) {
        byte[] sps = null;
        byte[] pps = null;
        boolean keyframe = false;
        boolean hasSlice = false;
        int nalStart = findStartCode(annexB, 0);
        while (nalStart >= 0) {
            final int scLen = (annexB[nalStart + 2] == 1) ? 3 : 4;
            final int body = nalStart + scLen;
            final int next = findStartCode(annexB, body);
            final int end = next >= 0 ? next : annexB.length;
            if (body < end) {
                final int nalType = annexB[body] & 0x1F;
                if (nalType == 7 && sps == null) {
                    sps = withStartCode(annexB, body, end);
                } else if (nalType == 8 && pps == null) {
                    pps = withStartCode(annexB, body, end);
                } else if (nalType == 5) {
                    keyframe = true;
                    hasSlice = true;
                } else if (nalType >= 1 && nalType <= 4) {
                    hasSlice = true;
                }
            }
            nalStart = next;
        }
        if (hasSlice) {
            out.add(new TsSample(true, annexB, ptsUs, keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0));
        }
        return new byte[][]{sps, pps};
    }

    private static byte[] withStartCode(byte[] src, int from, int to) {
        final int len = to - from;
        final byte[] out = new byte[4 + len];
        out[3] = 1;
        System.arraycopy(src, from, out, 4, len);
        return out;
    }

    private static int findStartCode(byte[] b, int from) {
        final int n = b.length;
        for (int i = from; i + 3 <= n; i++) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) return i;
                if (i + 4 <= n && b[i + 2] == 0 && b[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private static int[] flushAdtsPes(byte[] pes, long ptsUs, java.util.ArrayList<TsSample> out) {
        int[] cfg = null;
        int i = 0;
        int frameIdx = 0;
        int sr = 44100;
        while (i + 7 <= pes.length) {
            if ((pes[i] & 0xFF) != 0xFF || (pes[i + 1] & 0xF0) != 0xF0) {
                i++;
                continue;
            }
            final boolean noCrc = (pes[i + 1] & 0x01) != 0;
            final int hdrLen = noCrc ? 7 : 9;
            final int profile = ((pes[i + 2] >> 6) & 0x03) + 1;
            final int freqIdx = (pes[i + 2] >> 2) & 0x0F;
            final int chan = ((pes[i + 2] & 0x01) << 2) | ((pes[i + 3] >> 6) & 0x03);
            final int frameLen = ((pes[i + 3] & 0x03) << 11)
                    | ((pes[i + 4] & 0xFF) << 3)
                    | ((pes[i + 5] >> 5) & 0x07);
            if (frameLen <= hdrLen || i + frameLen > pes.length) {
                break;
            }
            if (cfg == null) {
                cfg = new int[]{profile, freqIdx, chan};
            }
            if (freqIdx >= 0 && freqIdx < AAC_SAMPLE_RATES.length) {
                sr = AAC_SAMPLE_RATES[freqIdx];
            }
            final byte[] rawAac = new byte[frameLen - hdrLen];
            System.arraycopy(pes, i + hdrLen, rawAac, 0, rawAac.length);
            final long framePts = ptsUs + (frameIdx * 1_024_000_000L) / sr;
            out.add(new TsSample(false, rawAac, framePts, 0));
            frameIdx++;
            i += frameLen;
        }
        return cfg;
    }

    private static long copySamples(MediaExtractor extractor, MediaMuxer muxer, int dstTrack,
                                    ByteBuffer buffer, MediaCodec.BufferInfo info, long maxTimeUs,
                                    boolean[] cancel) {
        long lastPts = 0;
        while (cancel == null || !cancel[0]) {
            info.offset = 0;
            info.size = extractor.readSampleData(buffer, 0);
            if (info.size < 0) {
                break;
            }
            final long pts = extractor.getSampleTime();
            if (pts < 0 || pts > maxTimeUs) {
                break;
            }
            info.presentationTimeUs = pts;
            info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(dstTrack, buffer, info);
            if (pts > lastPts) {
                lastPts = pts;
            }
            extractor.advance();
        }
        return lastPts;
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        final int count = extractor.getTrackCount();
        for (int i = 0; i < count; i++) {
            final MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    private static int maxInputSize(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            return format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        }
        return 0;
    }
}
