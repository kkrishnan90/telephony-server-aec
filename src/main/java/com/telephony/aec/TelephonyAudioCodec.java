package com.telephony.aec;

import java.util.Objects;

/**
 * Telephony codec and resampling utilities for bridging 8 kHz telephony trunks
 * (ITU-T G.711 u-law, G.711 A-law, and 16-bit signed linear PCM16LE) with
 * real-time AI voice models (16 kHz PCM16LE input, 24 kHz PCM16LE output).
 */
public final class TelephonyAudioCodec {

    private static final int ULAW_BIAS = 0x84; // 132
    private static final int ULAW_CLIP = 32635;

    // 7-tap symmetric anti-aliasing low-pass FIR filter for 24 kHz -> 8 kHz (3:1) decimation
    private static final double[] DECIMATE_3_TO_1_FIR = {
        0.045, 0.120, 0.215, 0.240, 0.215, 0.120, 0.045
    };

    private TelephonyAudioCodec() {}

    /**
     * Converts little-endian 16-bit signed PCM bytes into short samples.
     * Any trailing odd byte is safely ignored to preserve frame alignment.
     */
    public static short[] pcm16LeBytesToShorts(byte[] pcmBytes) {
        Objects.requireNonNull(pcmBytes, "pcmBytes must not be null");
        int sampleCount = pcmBytes.length / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int low = pcmBytes[2 * i] & 0xFF;
            int high = pcmBytes[2 * i + 1];
            samples[i] = (short) ((high << 8) | low);
        }
        return samples;
    }

    /**
     * Converts 16-bit signed PCM short samples into a little-endian byte array.
     */
    public static byte[] shortsToPcm16LeBytes(short[] samples) {
        Objects.requireNonNull(samples, "samples must not be null");
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short s = samples[i];
            bytes[2 * i] = (byte) (s & 0xFF);
            bytes[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return bytes;
    }

    /**
     * ITU-T G.711 u-law (PCMU, RTP Payload Type 0) decoder to 16-bit linear PCM.
     */
    public static short[] decodeG711Ulaw(byte[] ulawBytes) {
        Objects.requireNonNull(ulawBytes, "ulawBytes must not be null");
        short[] pcm = new short[ulawBytes.length];
        for (int i = 0; i < ulawBytes.length; i++) {
            pcm[i] = decodeUlawSample(ulawBytes[i]);
        }
        return pcm;
    }

    /**
     * ITU-T G.711 u-law (PCMU, RTP Payload Type 0) encoder from 16-bit linear PCM.
     */
    public static byte[] encodeG711Ulaw(short[] pcmSamples) {
        Objects.requireNonNull(pcmSamples, "pcmSamples must not be null");
        byte[] ulaw = new byte[pcmSamples.length];
        for (int i = 0; i < pcmSamples.length; i++) {
            ulaw[i] = encodeUlawSample(pcmSamples[i]);
        }
        return ulaw;
    }

    public static short decodeUlawSample(byte ulawByte) {
        int u = ~ulawByte & 0xFF;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = (((mantissa << 3) + ULAW_BIAS) << exponent) - ULAW_BIAS;
        return (short) (sign != 0 ? -sample : sample);
    }

    public static byte encodeUlawSample(short pcmSample) {
        int sample = pcmSample;
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) {
            sample = -sample;
        }
        if (sample > ULAW_CLIP) {
            sample = ULAW_CLIP;
        }
        sample += ULAW_BIAS;

        int exponent = 7;
        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {
            // Locate active segment
        }
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        int ulawByte = ~(sign | (exponent << 4) | mantissa);
        return (byte) (ulawByte & 0xFF);
    }

    /**
     * ITU-T G.711 A-law (PCMA, RTP Payload Type 8) decoder to 16-bit linear PCM.
     */
    public static short[] decodeG711Alaw(byte[] alawBytes) {
        Objects.requireNonNull(alawBytes, "alawBytes must not be null");
        short[] pcm = new short[alawBytes.length];
        for (int i = 0; i < alawBytes.length; i++) {
            pcm[i] = decodeAlawSample(alawBytes[i]);
        }
        return pcm;
    }

    /**
     * ITU-T G.711 A-law (PCMA, RTP Payload Type 8) encoder from 16-bit linear PCM.
     */
    public static byte[] encodeG711Alaw(short[] pcmSamples) {
        Objects.requireNonNull(pcmSamples, "pcmSamples must not be null");
        byte[] alaw = new byte[pcmSamples.length];
        for (int i = 0; i < pcmSamples.length; i++) {
            alaw[i] = encodeAlawSample(pcmSamples[i]);
        }
        return alaw;
    }

    public static short decodeAlawSample(byte alawByte) {
        int a = (alawByte ^ 0x55) & 0xFF;
        int sign = a & 0x80;
        int exponent = (a >> 4) & 0x07;
        int mantissa = a & 0x0F;
        int sample;
        if (exponent == 0) {
            sample = (mantissa << 4) + 8;
        } else {
            sample = ((mantissa << 4) + 0x108) << (exponent - 1);
        }
        return (short) (sign != 0 ? sample : -sample);
    }

    public static byte encodeAlawSample(short pcmSample) {
        int sample = pcmSample;
        int sign = ((~sample) >> 8) & 0x80;
        if (sign == 0) {
            sample = -sample;
        }
        if (sample > 32635) {
            sample = 32635;
        }
        int exponent;
        int mantissa;
        if (sample >= 256) {
            exponent = 7;
            for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 1; exponent--, expMask >>= 1) {
                // Locate active segment
            }
            mantissa = (sample >> (exponent + 3)) & 0x0F;
        } else {
            exponent = 0;
            mantissa = sample >> 4;
        }
        int alaw = (sign | (exponent << 4) | mantissa) ^ 0x55;
        return (byte) (alaw & 0xFF);
    }

    /**
     * Upsamples 8 kHz 16-bit PCM samples to 16 kHz (1:2) using 4-point cubic Catmull-Rom
     * interpolation for streaming into 16 kHz voice models.
     */
    public static short[] upsample8kHzTo16kHz(short[] samples8k) {
        Objects.requireNonNull(samples8k, "samples8k must not be null");
        if (samples8k.length == 0) {
            return new short[0];
        }
        if (samples8k.length == 1) {
            return new short[] {samples8k[0], samples8k[0]};
        }
        int n = samples8k.length;
        short[] out16k = new short[n * 2];
        for (int i = 0; i < n; i++) {
            out16k[2 * i] = samples8k[i];
            double y0 = samples8k[Math.max(0, i - 1)];
            double y1 = samples8k[i];
            double y2 = samples8k[Math.min(n - 1, i + 1)];
            double y3 = samples8k[Math.min(n - 1, i + 2)];
            double mid = (-0.0625 * y0) + (0.5625 * y1) + (0.5625 * y2) - (0.0625 * y3);
            out16k[2 * i + 1] = clampToShort(Math.round(mid));
        }
        return out16k;
    }

    /**
     * Downsamples 24 kHz 16-bit PCM samples to 8 kHz (3:1) using a 7-tap anti-aliasing
     * low-pass FIR filter for the telephony leg and AEC far-end reference buffer.
     */
    public static short[] downsample24kHzTo8kHz(short[] samples24k) {
        Objects.requireNonNull(samples24k, "samples24k must not be null");
        int outLen = samples24k.length / 3;
        if (outLen == 0) {
            return new short[0];
        }
        short[] out8k = new short[outLen];
        int halfTap = DECIMATE_3_TO_1_FIR.length / 2;
        for (int i = 0; i < outLen; i++) {
            int center = i * 3 + 1;
            double acc = 0.0;
            for (int k = 0; k < DECIMATE_3_TO_1_FIR.length; k++) {
                int idx = Math.max(0, Math.min(samples24k.length - 1, center + k - halfTap));
                acc += DECIMATE_3_TO_1_FIR[k] * samples24k[idx];
            }
            out8k[i] = clampToShort(Math.round(acc));
        }
        return out8k;
    }

    /**
     * Downsamples 16 kHz 16-bit PCM samples to 8 kHz (2:1) using a 3-tap anti-aliasing filter.
     */
    public static short[] downsample16kHzTo8kHz(short[] samples16k) {
        Objects.requireNonNull(samples16k, "samples16k must not be null");
        int outLen = samples16k.length / 2;
        if (outLen == 0) {
            return new short[0];
        }
        short[] out8k = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            int idx = i * 2;
            double s0 = samples16k[Math.max(0, idx - 1)];
            double s1 = samples16k[idx];
            double s2 = samples16k[Math.min(samples16k.length - 1, idx + 1)];
            out8k[i] = clampToShort(Math.round(0.25 * s0 + 0.50 * s1 + 0.25 * s2));
        }
        return out8k;
    }

    public static short clampToShort(long value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }
}
