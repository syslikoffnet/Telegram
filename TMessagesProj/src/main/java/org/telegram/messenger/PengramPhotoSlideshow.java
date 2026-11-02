package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import org.telegram.messenger.video.InputSurface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Аппаратная склейка серии фотографий (карусель TikTok и др.) в полноценное MP4-видео:
 * H.264 через {@link MediaCodec} + {@link InputSurface} (OpenGL ES 2.0), мягкий размытый
 * фон по краям кадра, плавный кроссфейд между слайдами и наложение оригинальной звуковой
 * дорожки (прямое копирование AAC либо перекодирование MP3/Opus → AAC-LC).
 *
 * Память расходуется константно: одновременно в ОЗУ находятся максимум два слайда,
 * поэтому даже карусель из 35 фотографий не вызывает OutOfMemoryError.
 */
public final class PengramPhotoSlideshow {

    private static final int FPS = 30;
    private static final int TRANSITION_FRAMES = 10; // ~0.33 сек кроссфейд

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexA;\n" +
            "uniform sampler2D uTexB;\n" +
            "uniform float uMix;\n" +
            "uniform float uZoomA;\n" +
            "uniform float uZoomB;\n" +
            "void main() {\n" +
            "    vec2 uvA = (vTexCoord - 0.5) / uZoomA + 0.5;\n" +
            "    vec2 uvB = (vTexCoord - 0.5) / uZoomB + 0.5;\n" +
            "    vec4 colA = texture2D(uTexA, clamp(uvA, 0.0, 1.0));\n" +
            "    vec4 colB = texture2D(uTexB, clamp(uvB, 0.0, 1.0));\n" +
            "    gl_FragColor = mix(colA, colB, uMix);\n" +
            "}\n";

    private PengramPhotoSlideshow() {
    }

    public static void build(List<File> photos, @Nullable File audioFile, File outFile,
                             int secondsPerSlide, boolean[] cancel,
                             @Nullable PengramLinkResolver.Progress progress) throws Exception {
        if (photos == null || photos.isEmpty()) {
            throw new Exception("no photos");
        }
        final int slideSec = Math.max(1, Math.min(6, secondsPerSlide));
        final int[] dims = chooseDimensions(photos.get(0));
        final File tempVideo = new File(outFile.getParentFile(), outFile.getName() + ".vtmp.mp4");
        try {
            boolean encoded = false;
            try {
                encodeSlidesToVideo(photos, tempVideo, dims[0], dims[1], 6_000_000, slideSec, cancel, progress);
                encoded = true;
            } catch (CancellationException ce) {
                throw ce;
            } catch (Throwable firstErr) {
                tempVideo.delete();
                // Бюджетные кодеки иногда не тянут 1080p Surface-профиль — падаем на 720p.
                final int fbW = align16((dims[0] * 2) / 3);
                final int fbH = align16((dims[1] * 2) / 3);
                encodeSlidesToVideo(photos, tempVideo, fbW, fbH, 3_500_000, slideSec, cancel, progress);
                encoded = true;
            }
            if (!encoded || tempVideo.length() <= 0) {
                throw new Exception("slideshow empty");
            }
            checkCancelled(cancel);

            final long totalDurationUs = (long) photos.size() * slideSec * 1_000_000L;
            if (audioFile != null && audioFile.exists() && audioFile.length() > 1024L) {
                try {
                    attachAudioToVideo(tempVideo, audioFile, outFile, totalDurationUs, cancel);
                    if (outFile.exists() && outFile.length() > 10_000L) {
                        tempVideo.delete();
                        return;
                    }
                } catch (CancellationException ce) {
                    outFile.delete();
                    throw ce;
                } catch (Throwable audioErr) {
                    FileLog.e(new Exception("PengramPhotoSlideshow audio mux fallback: " + audioErr, audioErr));
                    outFile.delete();
                }
            }

            moveOrCopy(tempVideo, outFile);
            if (outFile.length() <= 0) {
                throw new Exception("slideshow output empty");
            }
        } catch (Throwable t) {
            tempVideo.delete();
            if (outFile.exists()) {
                outFile.delete();
            }
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new Exception(t);
        }
    }

    private static int align16(int v) {
        return Math.max(256, (v / 16) * 16);
    }

    private static int[] chooseDimensions(File firstPhoto) {
        try {
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(firstPhoto.getAbsolutePath(), opts);
            int w = opts.outWidth;
            int h = opts.outHeight;
            final int rot = readExifRotation(firstPhoto);
            if (rot == 90 || rot == 270) {
                final int tmp = w;
                w = h;
                h = tmp;
            }
            if (w > 0 && h > 0) {
                final float ratio = (float) w / (float) h;
                if (ratio > 1.18f) {
                    return new int[]{1920, 1080};
                }
                if (ratio > 0.85f) {
                    return new int[]{1080, 1080};
                }
            }
        } catch (Throwable ignore) {
        }
        return new int[]{1080, 1920};
    }

    private static void encodeSlidesToVideo(List<File> photos, File outFile, int width, int height,
                                            int bitRate, int slideSec, boolean[] cancel,
                                            @Nullable PengramLinkResolver.Progress progress) throws Exception {
        MediaCodec encoder = null;
        InputSurface inputSurface = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int program = 0;
        int texA = 0;
        int texB = 0;
        Bitmap currBmp = null;
        Bitmap nextBmp = null;

        try {
            final MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new InputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int trackIndex = -1;

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            GLES20.glUseProgram(program);
            final int aPos = GLES20.glGetAttribLocation(program, "aPosition");
            final int aUv = GLES20.glGetAttribLocation(program, "aTexCoord");
            final int uTexA = GLES20.glGetUniformLocation(program, "uTexA");
            final int uTexB = GLES20.glGetUniformLocation(program, "uTexB");
            final int uMix = GLES20.glGetUniformLocation(program, "uMix");
            final int uZoomA = GLES20.glGetUniformLocation(program, "uZoomA");
            final int uZoomB = GLES20.glGetUniformLocation(program, "uZoomB");

            final float[] quad = {
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f,
            };
            final FloatBuffer vb = ByteBuffer.allocateDirect(quad.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vb.put(quad).position(0);

            final int[] texIds = new int[2];
            GLES20.glGenTextures(2, texIds, 0);
            texA = texIds[0];
            texB = texIds[1];
            initTexture(texA);
            initTexture(texB);

            currBmp = composeSlideBitmap(photos.get(0), width, height);
            uploadBitmap(texA, currBmp);
            if (photos.size() > 1) {
                nextBmp = composeSlideBitmap(photos.get(1), width, height);
                uploadBitmap(texB, nextBmp);
            } else {
                uploadBitmap(texB, currBmp);
            }

            final int framesPerSlide = slideSec * FPS;
            final int totalFrames = photos.size() * framesPerSlide;
            int globalFrame = 0;

            GLES20.glViewport(0, 0, width, height);

            for (int slide = 0; slide < photos.size(); slide++) {
                final boolean hasNext = slide + 1 < photos.size();
                for (int f = 0; f < framesPerSlide; f++) {
                    checkCancelled(cancel);

                    float mix = 0f;
                    if (hasNext && f >= framesPerSlide - TRANSITION_FRAMES) {
                        final float raw = (float) (f - (framesPerSlide - TRANSITION_FRAMES) + 1) / TRANSITION_FRAMES;
                        mix = raw * raw * (3f - 2f * raw);
                    }
                    final float progressA = (float) f / Math.max(1, framesPerSlide);
                    final float zoomA = 1.0f + 0.028f * progressA;
                    final float zoomB = 1.0f + 0.006f * mix;

                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glUseProgram(program);

                    vb.position(0);
                    GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb);
                    GLES20.glEnableVertexAttribArray(aPos);
                    vb.position(2);
                    GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vb);
                    GLES20.glEnableVertexAttribArray(aUv);

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
                    GLES20.glUniform1i(uTexA, 0);

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
                    GLES20.glUniform1i(uTexB, 1);

                    GLES20.glUniform1f(uMix, mix);
                    GLES20.glUniform1f(uZoomA, zoomA);
                    GLES20.glUniform1f(uZoomB, zoomB);

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    final long ptsNs = (long) globalFrame * 1_000_000_000L / FPS;
                    inputSurface.setPresentationTime(ptsNs);
                    inputSurface.swapBuffers();

                    final int[] state = drainVideoEncoder(encoder, muxer, bufferInfo, trackIndex, muxerStarted, false);
                    trackIndex = state[0];
                    muxerStarted = state[1] == 1;

                    globalFrame++;
                    if (progress != null && (globalFrame % 6 == 0 || globalFrame == totalFrames)) {
                        progress.onBytes(globalFrame, totalFrames);
                    }
                }

                if (hasNext) {
                    if (currBmp != null && currBmp != nextBmp) {
                        currBmp.recycle();
                    }
                    currBmp = nextBmp;
                    nextBmp = null;
                    final int swap = texA;
                    texA = texB;
                    texB = swap;
                    if (slide + 2 < photos.size()) {
                        nextBmp = composeSlideBitmap(photos.get(slide + 2), width, height);
                        uploadBitmap(texB, nextBmp);
                    } else if (currBmp != null) {
                        uploadBitmap(texB, currBmp);
                    }
                }
            }

            final int[] finalState = drainVideoEncoder(encoder, muxer, bufferInfo, trackIndex, muxerStarted, true);
            muxerStarted = finalState[1] == 1;

            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
            muxer = null;
        } finally {
            if (currBmp != null && !currBmp.isRecycled()) {
                currBmp.recycle();
            }
            if (nextBmp != null && nextBmp != currBmp && !nextBmp.isRecycled()) {
                nextBmp.recycle();
            }
            try {
                if (texA != 0 || texB != 0) {
                    GLES20.glDeleteTextures(2, new int[]{texA, texB}, 0);
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                }
            } catch (Throwable ignore) {
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable ignore) {
                }
                try {
                    encoder.release();
                } catch (Throwable ignore) {
                }
            }
            if (inputSurface != null) {
                try {
                    inputSurface.release();
                } catch (Throwable ignore) {
                }
            }
            if (muxer != null) {
                try {
                    if (muxerStarted) {
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

    private static int[] drainVideoEncoder(MediaCodec encoder, MediaMuxer muxer,
                                           MediaCodec.BufferInfo info, int trackIndex,
                                           boolean muxerStarted, boolean endOfStream) {
        if (endOfStream) {
            encoder.signalEndOfInputStream();
        }
        while (true) {
            final int status = encoder.dequeueOutputBuffer(info, endOfStream ? 10_000 : 0);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (status >= 0) {
                final ByteBuffer data = encoder.getOutputBuffer(status);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && muxerStarted && data != null) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, data, info);
                }
                encoder.releaseOutputBuffer(status, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
        return new int[]{trackIndex, muxerStarted ? 1 : 0};
    }

    /**
     * Прикрепляет аудиодорожку к уже готовому видео. Если звук уже в AAC (m4a) —
     * копирует семплы напрямую (при нехватке длины зацикливает под слайд-шоу).
     * Если звук в MP3 / Opus / Vorbis — декодирует в PCM и кодирует в AAC-LC 128 kbps.
     */
    private static void attachAudioToVideo(File videoFile, File audioFile, File outFile,
                                           long targetDurationUs, boolean[] cancel) throws Exception {
        final MediaExtractor audioProbe = new MediaExtractor();
        String audioMime = null;
        int probeTrack = -1;
        try {
            audioProbe.setDataSource(audioFile.getAbsolutePath());
            for (int i = 0; i < audioProbe.getTrackCount(); i++) {
                final MediaFormat fmt = audioProbe.getTrackFormat(i);
                final String m = fmt.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    probeTrack = i;
                    audioMime = m;
                    break;
                }
            }
        } finally {
            try {
                audioProbe.release();
            } catch (Throwable ignore) {
            }
        }
        if (probeTrack < 0 || audioMime == null) {
            throw new Exception("no audio stream");
        }
        if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(audioMime)) {
            muxVideoAndAacLoop(videoFile, audioFile, outFile, targetDurationUs, cancel);
        } else {
            muxVideoAndTranscodedAudio(videoFile, audioFile, outFile, targetDurationUs, cancel);
        }
    }

    private static void muxVideoAndAacLoop(File videoFile, File audioFile, File outFile,
                                           long targetDurationUs, boolean[] cancel) throws Exception {
        final MediaExtractor vEx = new MediaExtractor();
        final MediaExtractor aEx = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            vEx.setDataSource(videoFile.getAbsolutePath());
            aEx.setDataSource(audioFile.getAbsolutePath());
            final int vTrack = findTrack(vEx, "video/");
            final int aTrack = findTrack(aEx, "audio/");
            if (vTrack < 0 || aTrack < 0) {
                throw new Exception("missing track");
            }
            vEx.selectTrack(vTrack);
            aEx.selectTrack(aTrack);

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final int outV = muxer.addTrack(vEx.getTrackFormat(vTrack));
            final int outA = muxer.addTrack(aEx.getTrackFormat(aTrack));
            muxer.start();

            copyStream(vEx, muxer, outV, targetDurationUs + 500_000L, false, cancel);
            copyStream(aEx, muxer, outA, targetDurationUs, true, cancel);

            muxer.stop();
            muxer.release();
            muxer = null;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignore) {
                }
            }
            try {
                vEx.release();
            } catch (Throwable ignore) {
            }
            try {
                aEx.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void copyStream(MediaExtractor ex, MediaMuxer muxer, int outTrack,
                                   long maxDurationUs, boolean loopIfShort, boolean[] cancel) throws Exception {
        final ByteBuffer buf = ByteBuffer.allocateDirect(512 * 1024);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long offsetUs = 0;
        long lastSampleDurationUs = 23_219L;
        int loops = 0;
        while (offsetUs < maxDurationUs && loops < 20) {
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long prevPts = -1;
            long maxPtsInPass = 0;
            boolean wroteInPass = false;
            while (true) {
                checkCancelled(cancel);
                final int size = ex.readSampleData(buf, 0);
                if (size < 0) {
                    break;
                }
                final long samplePts = Math.max(0, ex.getSampleTime());
                if (prevPts >= 0 && samplePts > prevPts) {
                    lastSampleDurationUs = Math.min(200_000L, samplePts - prevPts);
                }
                prevPts = samplePts;
                final long outPts = offsetUs + samplePts;
                if (outPts >= maxDurationUs) {
                    return;
                }
                buf.position(0);
                buf.limit(size);
                info.set(0, size, outPts, ex.getSampleFlags());
                muxer.writeSampleData(outTrack, buf, info);
                wroteInPass = true;
                maxPtsInPass = Math.max(maxPtsInPass, samplePts);
                ex.advance();
            }
            if (!loopIfShort || !wroteInPass) {
                break;
            }
            offsetUs += maxPtsInPass + lastSampleDurationUs;
            loops++;
        }
    }

    private static void muxVideoAndTranscodedAudio(File videoFile, File audioFile, File outFile,
                                                   long targetDurationUs, boolean[] cancel) throws Exception {
        final MediaExtractor vEx = new MediaExtractor();
        final MediaExtractor aEx = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            vEx.setDataSource(videoFile.getAbsolutePath());
            aEx.setDataSource(audioFile.getAbsolutePath());
            final int vTrack = findTrack(vEx, "video/");
            final int aTrack = findTrack(aEx, "audio/");
            if (vTrack < 0 || aTrack < 0) {
                throw new Exception("missing track");
            }
            vEx.selectTrack(vTrack);
            aEx.selectTrack(aTrack);

            final MediaFormat inAudioFmt = aEx.getTrackFormat(aTrack);
            final String inMime = inAudioFmt.getString(MediaFormat.KEY_MIME);
            final int sampleRate = inAudioFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inAudioFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            final int channels = inAudioFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, Math.min(2, inAudioFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) : 2;

            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inAudioFmt, null, null, 0);
            decoder.start();

            final MediaFormat outAudioFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            outAudioFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outAudioFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            outAudioFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(outAudioFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final int outV = muxer.addTrack(vEx.getTrackFormat(vTrack));
            int outA = -1;

            final MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            long pcmBytesSubmitted = 0;
            final int bytesPerFrame = Math.max(2, channels * 2);
            int loops = 0;

            while (!encoderDone) {
                checkCancelled(cancel);

                if (!extractorDone) {
                    final int inIdx = decoder.dequeueInputBuffer(2_000);
                    if (inIdx >= 0) {
                        final ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sz = inBuf != null ? aEx.readSampleData(inBuf, 0) : -1;
                        if (sz < 0 && loops < 15) {
                            final long producedUs = (pcmBytesSubmitted / bytesPerFrame) * 1_000_000L / sampleRate;
                            if (producedUs + 200_000L < targetDurationUs) {
                                aEx.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                                loops++;
                                sz = inBuf != null ? aEx.readSampleData(inBuf, 0) : -1;
                            }
                        }
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, Math.max(0, aEx.getSampleTime()), 0);
                            aEx.advance();
                        }
                    }
                }

                if (!decoderDone) {
                    final int decStatus = decoder.dequeueOutputBuffer(decInfo, 2_000);
                    if (decStatus >= 0) {
                        final ByteBuffer pcm = decoder.getOutputBuffer(decStatus);
                        final boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (decInfo.size > 0 && pcm != null) {
                            pcm.position(decInfo.offset);
                            pcm.limit(decInfo.offset + decInfo.size);
                            while (pcm.hasRemaining()) {
                                checkCancelled(cancel);
                                final long currentPtsUs = (pcmBytesSubmitted / bytesPerFrame) * 1_000_000L / sampleRate;
                                if (currentPtsUs >= targetDurationUs) {
                                    decoderDone = true;
                                    extractorDone = true;
                                    final int encIn = encoder.dequeueInputBuffer(10_000);
                                    if (encIn >= 0) {
                                        encoder.queueInputBuffer(encIn, 0, 0, currentPtsUs,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    }
                                    break;
                                }
                                final int encIn = encoder.dequeueInputBuffer(5_000);
                                if (encIn < 0) {
                                    break;
                                }
                                final ByteBuffer encBuf = encoder.getInputBuffer(encIn);
                                if (encBuf == null) {
                                    break;
                                }
                                encBuf.clear();
                                final int chunk = Math.min(pcm.remaining(), encBuf.remaining());
                                final int oldLimit = pcm.limit();
                                pcm.limit(pcm.position() + chunk);
                                encBuf.put(pcm);
                                pcm.limit(oldLimit);
                                encoder.queueInputBuffer(encIn, 0, chunk, currentPtsUs, 0);
                                pcmBytesSubmitted += chunk;
                            }
                        }
                        decoder.releaseOutputBuffer(decStatus, false);
                        if (eos && !decoderDone) {
                            decoderDone = true;
                            final long endPtsUs = (pcmBytesSubmitted / bytesPerFrame) * 1_000_000L / sampleRate;
                            final int encIn = encoder.dequeueInputBuffer(10_000);
                            if (encIn >= 0) {
                                encoder.queueInputBuffer(encIn, 0, 0, endPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                    }
                }

                while (true) {
                    final int encStatus = encoder.dequeueOutputBuffer(encInfo, 2_000);
                    if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            outA = muxer.addTrack(encoder.getOutputFormat());
                            muxer.start();
                            muxerStarted = true;
                            copyStream(vEx, muxer, outV, targetDurationUs + 500_000L, false, cancel);
                        }
                    } else if (encStatus >= 0) {
                        final ByteBuffer encoded = encoder.getOutputBuffer(encStatus);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encInfo.size = 0;
                        }
                        if (encInfo.size > 0 && muxerStarted && encoded != null && encInfo.presentationTimeUs <= targetDurationUs) {
                            encoded.position(encInfo.offset);
                            encoded.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(outA, encoded, encInfo);
                        }
                        encoder.releaseOutputBuffer(encStatus, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
            muxer = null;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Throwable ignore) {
                }
                try {
                    decoder.release();
                } catch (Throwable ignore) {
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable ignore) {
                }
                try {
                    encoder.release();
                } catch (Throwable ignore) {
                }
            }
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                } catch (Throwable ignore) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignore) {
                }
            }
            try {
                vEx.release();
            } catch (Throwable ignore) {
            }
            try {
                aEx.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        final int count = ex.getTrackCount();
        for (int i = 0; i < count; i++) {
            final MediaFormat f = ex.getTrackFormat(i);
            final String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static Bitmap composeSlideBitmap(File photoFile, int outW, int outH) throws Exception {
        final Bitmap src = decodeOrientedBitmap(photoFile, Math.max(outW, outH));
        if (src == null) {
            throw new Exception("bad photo: " + photoFile.getName());
        }
        final Bitmap frame = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(frame);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        final int sw = src.getWidth();
        final int sh = src.getHeight();
        final float scaleFit = Math.min((float) outW / sw, (float) outH / sh);
        final float fitW = sw * scaleFit;
        final float fitH = sh * scaleFit;

        // Если фото не закрывает весь кадр — рисуем мягкий размытый фон из самого фото.
        if (Math.abs(fitW - outW) > 4f || Math.abs(fitH - outH) > 4f) {
            final Bitmap blurred = createBlurredThumb(src, 72);
            final float scaleFill = Math.max((float) outW / blurred.getWidth(), (float) outH / blurred.getHeight()) * 1.06f;
            final float bw = blurred.getWidth() * scaleFill;
            final float bh = blurred.getHeight() * scaleFill;
            final RectF bgRect = new RectF((outW - bw) / 2f, (outH - bh) / 2f, (outW + bw) / 2f, (outH + bh) / 2f);
            canvas.drawBitmap(blurred, null, bgRect, paint);
            blurred.recycle();
            canvas.drawColor(0x58000000);
        } else {
            canvas.drawColor(0xFF000000);
        }

        final RectF fgRect = new RectF(
                (outW - fitW) / 2f,
                (outH - fitH) / 2f,
                (outW + fitW) / 2f,
                (outH + fitH) / 2f);
        canvas.drawBitmap(src, null, fgRect, paint);
        src.recycle();
        return frame;
    }

    static Bitmap decodeOrientedBitmap(File file, int maxSide) {
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2;
            }
            final BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
            if (bmp == null) {
                return null;
            }
            final int rot = readExifRotation(file);
            if (rot != 0) {
                final Matrix m = new Matrix();
                m.postRotate(rot);
                final Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) {
                    bmp.recycle();
                    bmp = rotated;
                }
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readExifRotation(File file) {
        try {
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            final int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static Bitmap createBlurredThumb(Bitmap src, int targetMaxSide) {
        final float scale = (float) targetMaxSide / Math.max(src.getWidth(), src.getHeight());
        final int w = Math.max(8, Math.round(src.getWidth() * scale));
        final int h = Math.max(8, Math.round(src.getHeight() * scale));
        final Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        final int[] pix = new int[w * h];
        final int[] tmp = new int[w * h];
        small.getPixels(pix, 0, w, 0, 0, w, h);
        final int radius = 5;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                final int row = y * w;
                for (int x = 0; x < w; x++) {
                    int r = 0, g = 0, b = 0, cnt = 0;
                    for (int k = -radius; k <= radius; k++) {
                        final int sx = Math.max(0, Math.min(w - 1, x + k));
                        final int c = pix[row + sx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        cnt++;
                    }
                    tmp[row + x] = 0xFF000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
                }
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int r = 0, g = 0, b = 0, cnt = 0;
                    for (int k = -radius; k <= radius; k++) {
                        final int sy = Math.max(0, Math.min(h - 1, y + k));
                        final int c = tmp[sy * w + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        cnt++;
                    }
                    pix[y * w + x] = 0xFF000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
                }
            }
        }
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        if (small != src) {
            small.recycle();
        }
        return out;
    }

    private static void initTexture(int texId) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static void uploadBitmap(int texId, Bitmap bmp) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
    }

    private static int createProgram(String vsSrc, String fsSrc) {
        final int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc);
        final int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        final int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        final int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (status[0] == 0) {
            final String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("GL link failed: " + log);
        }
        return prog;
    }

    private static int compileShader(int type, String src) {
        final int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        final int[] status = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            final String log = GLES20.glGetShaderInfoLog(sh);
            GLES20.glDeleteShader(sh);
            throw new RuntimeException("GL shader failed: " + log);
        }
        return sh;
    }

    private static void moveOrCopy(File src, File dst) throws Exception {
        if (dst.exists()) {
            dst.delete();
        }
        if (src.renameTo(dst)) {
            return;
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        src.delete();
    }

    private static void checkCancelled(boolean[] cancel) {
        if (cancel != null && cancel[0]) {
            throw new CancellationException("cancelled");
        }
    }

    /** Проверяет по заголовку файла, является ли он GIF87a/GIF89a или анимированным WebP. */
    public static boolean isAnimatedImageFile(File file) {
        if (file == null || !file.exists() || file.length() < 16L) {
            return false;
        }
        final byte[] header = new byte[64];
        try (FileInputStream in = new FileInputStream(file)) {
            final int read = in.read(header);
            return PengramLinkResolver.isAnimatedImageBytes(header, read);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Перекодирует анимированный GIF / Animated WebP (живой слайд TikTok без отдельного MP4)
     * в аппаратный H.264 MP4 через Movie + OpenGL ES InputSurface, чтобы Telegram проигрывал его в движении.
     */
    @SuppressWarnings("deprecation")
    public static boolean convertAnimatedImageToMp4(File animFile, File outMp4, boolean[] cancel) {
        if (animFile == null || !animFile.exists() || outMp4 == null) {
            return false;
        }
        android.graphics.Movie movie = null;
        try {
            movie = android.graphics.Movie.decodeFile(animFile.getAbsolutePath());
        } catch (Throwable ignore) {
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            return false;
        }
        final int srcW = movie.width();
        final int srcH = movie.height();
        final float maxSide = 960f;
        final float scale = Math.min(1f, maxSide / Math.max(srcW, srcH));
        int outW = Math.max(64, Math.round(srcW * scale));
        int outH = Math.max(64, Math.round(srcH * scale));
        if ((outW & 1) != 0) outW--;
        if ((outH & 1) != 0) outH--;

        int durationMs = movie.duration();
        if (durationMs <= 0) {
            durationMs = 2400;
        }
        durationMs = Math.max(1200, Math.min(8000, durationMs));
        final int animFps = 24;
        final int totalFrames = Math.max(24, (int) Math.round(durationMs * animFps / 1000.0));

        MediaCodec encoder = null;
        InputSurface inputSurface = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int program = 0;
        int texId = 0;
        Bitmap frameBmp = null;

        try {
            final MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_200_000, outW * outH * 3));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, animFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new InputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            muxer = new MediaMuxer(outMp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int trackIndex = -1;

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            GLES20.glUseProgram(program);
            final int aPos = GLES20.glGetAttribLocation(program, "aPosition");
            final int aUv = GLES20.glGetAttribLocation(program, "aTexCoord");
            final int uTexA = GLES20.glGetUniformLocation(program, "uTexA");
            final int uTexB = GLES20.glGetUniformLocation(program, "uTexB");
            final int uMix = GLES20.glGetUniformLocation(program, "uMix");
            final int uZoomA = GLES20.glGetUniformLocation(program, "uZoomA");
            final int uZoomB = GLES20.glGetUniformLocation(program, "uZoomB");

            final float[] quad = {
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f,
            };
            final FloatBuffer vb = ByteBuffer.allocateDirect(quad.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vb.put(quad).position(0);

            final int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            texId = texIds[0];
            initTexture(texId);

            frameBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            final Canvas frameCanvas = new Canvas(frameBmp);
            final float sx = (float) outW / srcW;
            final float sy = (float) outH / srcH;

            GLES20.glViewport(0, 0, outW, outH);

            for (int f = 0; f < totalFrames; f++) {
                checkCancelled(cancel);
                final int relMs = (int) (((long) f * 1000L / animFps) % Math.max(1, movie.duration() > 0 ? movie.duration() : durationMs));
                movie.setTime(relMs);
                frameCanvas.drawColor(0xFF000000);
                frameCanvas.save();
                frameCanvas.scale(sx, sy);
                movie.draw(frameCanvas, 0f, 0f);
                frameCanvas.restore();

                uploadBitmap(texId, frameBmp);

                vb.position(0);
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb);
                GLES20.glEnableVertexAttribArray(aPos);
                vb.position(2);
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vb);
                GLES20.glEnableVertexAttribArray(aUv);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLES20.glUniform1i(uTexA, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLES20.glUniform1i(uTexB, 1);
                GLES20.glUniform1f(uMix, 0f);
                GLES20.glUniform1f(uZoomA, 1f);
                GLES20.glUniform1f(uZoomB, 1f);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                final long ptsNs = (long) f * 1_000_000_000L / animFps;
                inputSurface.setPresentationTime(ptsNs);
                inputSurface.swapBuffers();

                final int[] state = drainVideoEncoder(encoder, muxer, bufferInfo, trackIndex, muxerStarted, false);
                trackIndex = state[0];
                muxerStarted = state[1] == 1;
            }

            final int[] finalState = drainVideoEncoder(encoder, muxer, bufferInfo, trackIndex, muxerStarted, true);
            muxerStarted = finalState[1] == 1;
            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
            muxer = null;
            return outMp4.exists() && outMp4.length() > 1024L;
        } catch (CancellationException ce) {
            outMp4.delete();
            throw ce;
        } catch (Throwable ignore) {
            outMp4.delete();
            return false;
        } finally {
            if (frameBmp != null && !frameBmp.isRecycled()) {
                frameBmp.recycle();
            }
            if (texId != 0) {
                GLES20.glDeleteTextures(1, new int[]{texId}, 0);
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
            if (inputSurface != null) {
                try { inputSurface.release(); } catch (Throwable ignore) {}
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignore) {}
                try { encoder.release(); } catch (Throwable ignore) {}
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Throwable ignore) {}
                }
                try { muxer.release(); } catch (Throwable ignore) {}
            }
        }
    }
}
