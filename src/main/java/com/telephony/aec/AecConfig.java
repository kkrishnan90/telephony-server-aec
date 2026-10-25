package com.telephony.aec;

/**
 * Configuration parameters for the Server-Side Acoustic Echo Canceller (AEC)
 * designed for 8 kHz telephony streams (RTP / G.711 / PCM16LE) bridging into
 * real-time conversational AI voice models (16 kHz PCM16LE input / 24 kHz PCM16LE output).
 */
public final class AecConfig {

    public static final int DEFAULT_SAMPLE_RATE_HZ = 8000;
    public static final int DEFAULT_FRAME_SIZE_SAMPLES = 160; // 20 ms at 8 kHz (standard telephony RTP frame)
    public static final int DEFAULT_FILTER_LENGTH_SAMPLES = 512; // 64 ms active adaptive FIR echo tail
    public static final int DEFAULT_MAX_BULK_DELAY_MS = 240; // Up to 240 ms gateway + PSTN jitter delay

    private final int sampleRateHz;
    private final int frameSizeSamples;
    private final int filterLengthSamples;
    private final int maxBulkDelayMs;
    private final double nlmsStepSize;
    private final double regularizationEpsilon;
    private final double weightLeakage;
    private final double doubleTalkGeigelThreshold;
    private final int doubleTalkHangoverFrames;
    private final boolean nlpEnabled;
    private final double nlpAttenuationFloor;
    private final double comfortNoiseLevel;
    private final boolean dcBlockerEnabled;

    private AecConfig(Builder builder) {
        if (builder.sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive, got: " + builder.sampleRateHz);
        }
        if (builder.frameSizeSamples <= 0) {
            throw new IllegalArgumentException("frameSizeSamples must be positive, got: " + builder.frameSizeSamples);
        }
        if (builder.filterLengthSamples < builder.frameSizeSamples) {
            throw new IllegalArgumentException(
                    "filterLengthSamples (" + builder.filterLengthSamples
                            + ") must be >= frameSizeSamples (" + builder.frameSizeSamples + ")");
        }
        if (builder.maxBulkDelayMs < 0 || builder.maxBulkDelayMs > 1000) {
            throw new IllegalArgumentException(
                    "maxBulkDelayMs must be between 0 and 1000 ms, got: " + builder.maxBulkDelayMs);
        }
        if (builder.nlmsStepSize <= 0.0 || builder.nlmsStepSize >= 2.0 || Double.isNaN(builder.nlmsStepSize)) {
            throw new IllegalArgumentException(
                    "nlmsStepSize (mu) must be in (0.0, 2.0) for NLMS stability, got: " + builder.nlmsStepSize);
        }
        if (builder.regularizationEpsilon <= 0.0 || Double.isNaN(builder.regularizationEpsilon)) {
            throw new IllegalArgumentException(
                    "regularizationEpsilon must be positive, got: " + builder.regularizationEpsilon);
        }
        if (builder.doubleTalkGeigelThreshold <= 0.0) {
            throw new IllegalArgumentException(
                    "doubleTalkGeigelThreshold must be positive, got: " + builder.doubleTalkGeigelThreshold);
        }
        if (builder.doubleTalkHangoverFrames < 0) {
            throw new IllegalArgumentException(
                    "doubleTalkHangoverFrames must be >= 0, got: " + builder.doubleTalkHangoverFrames);
        }
        if (builder.nlpAttenuationFloor < 0.0 || builder.nlpAttenuationFloor > 1.0) {
            throw new IllegalArgumentException(
                    "nlpAttenuationFloor must be in [0.0, 1.0], got: " + builder.nlpAttenuationFloor);
        }
        if (builder.comfortNoiseLevel < 0.0) {
            throw new IllegalArgumentException(
                    "comfortNoiseLevel must be >= 0.0, got: " + builder.comfortNoiseLevel);
        }

        this.sampleRateHz = builder.sampleRateHz;
        this.frameSizeSamples = builder.frameSizeSamples;
        this.filterLengthSamples = builder.filterLengthSamples;
        this.maxBulkDelayMs = builder.maxBulkDelayMs;
        this.nlmsStepSize = builder.nlmsStepSize;
        this.regularizationEpsilon = builder.regularizationEpsilon;
        this.weightLeakage = builder.weightLeakage;
        this.doubleTalkGeigelThreshold = builder.doubleTalkGeigelThreshold;
        this.doubleTalkHangoverFrames = builder.doubleTalkHangoverFrames;
        this.nlpEnabled = builder.nlpEnabled;
        this.nlpAttenuationFloor = builder.nlpAttenuationFloor;
        this.comfortNoiseLevel = builder.comfortNoiseLevel;
        this.dcBlockerEnabled = builder.dcBlockerEnabled;
    }

    public static AecConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getFrameSizeSamples() {
        return frameSizeSamples;
    }

    public int getFrameDurationMs() {
        return (frameSizeSamples * 1000) / sampleRateHz;
    }

    public int getFilterLengthSamples() {
        return filterLengthSamples;
    }

    public int getMaxBulkDelayMs() {
        return maxBulkDelayMs;
    }

    public int getMaxBulkDelayFrames() {
        int frameMs = Math.max(1, getFrameDurationMs());
        return Math.max(1, maxBulkDelayMs / frameMs);
    }

    public double getNlmsStepSize() {
        return nlmsStepSize;
    }

    public double getRegularizationEpsilon() {
        return regularizationEpsilon;
    }

    public double getWeightLeakage() {
        return weightLeakage;
    }

    public double getDoubleTalkGeigelThreshold() {
        return doubleTalkGeigelThreshold;
    }

    public int getDoubleTalkHangoverFrames() {
        return doubleTalkHangoverFrames;
    }

    public boolean isNlpEnabled() {
        return nlpEnabled;
    }

    public double getNlpAttenuationFloor() {
        return nlpAttenuationFloor;
    }

    public double getComfortNoiseLevel() {
        return comfortNoiseLevel;
    }

    public boolean isDcBlockerEnabled() {
        return dcBlockerEnabled;
    }

    public static final class Builder {
        private int sampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
        private int frameSizeSamples = DEFAULT_FRAME_SIZE_SAMPLES;
        private int filterLengthSamples = DEFAULT_FILTER_LENGTH_SAMPLES;
        private int maxBulkDelayMs = DEFAULT_MAX_BULK_DELAY_MS;
        private double nlmsStepSize = 0.40;
        private double regularizationEpsilon = 1.0e-4;
        private double weightLeakage = 1.0e-5;
        private double doubleTalkGeigelThreshold = 0.72;
        private int doubleTalkHangoverFrames = 6;
        private boolean nlpEnabled = true;
        private double nlpAttenuationFloor = 0.05;
        private double comfortNoiseLevel = 8.0;
        private boolean dcBlockerEnabled = true;

        private Builder() {}

        public Builder sampleRateHz(int sampleRateHz) {
            this.sampleRateHz = sampleRateHz;
            return this;
        }

        public Builder frameSizeSamples(int frameSizeSamples) {
            this.frameSizeSamples = frameSizeSamples;
            return this;
        }

        public Builder filterLengthSamples(int filterLengthSamples) {
            this.filterLengthSamples = filterLengthSamples;
            return this;
        }

        public Builder maxBulkDelayMs(int maxBulkDelayMs) {
            this.maxBulkDelayMs = maxBulkDelayMs;
            return this;
        }

        public Builder nlmsStepSize(double nlmsStepSize) {
            this.nlmsStepSize = nlmsStepSize;
            return this;
        }

        public Builder regularizationEpsilon(double regularizationEpsilon) {
            this.regularizationEpsilon = regularizationEpsilon;
            return this;
        }

        public Builder weightLeakage(double weightLeakage) {
            this.weightLeakage = weightLeakage;
            return this;
        }

        public Builder doubleTalkGeigelThreshold(double doubleTalkGeigelThreshold) {
            this.doubleTalkGeigelThreshold = doubleTalkGeigelThreshold;
            return this;
        }

        public Builder doubleTalkHangoverFrames(int doubleTalkHangoverFrames) {
            this.doubleTalkHangoverFrames = doubleTalkHangoverFrames;
            return this;
        }

        public Builder nlpEnabled(boolean nlpEnabled) {
            this.nlpEnabled = nlpEnabled;
            return this;
        }

        public Builder nlpAttenuationFloor(double nlpAttenuationFloor) {
            this.nlpAttenuationFloor = nlpAttenuationFloor;
            return this;
        }

        public Builder comfortNoiseLevel(double comfortNoiseLevel) {
            this.comfortNoiseLevel = comfortNoiseLevel;
            return this;
        }

        public Builder dcBlockerEnabled(boolean dcBlockerEnabled) {
            this.dcBlockerEnabled = dcBlockerEnabled;
            return this;
        }

        public AecConfig build() {
            return new AecConfig(this);
        }
    }
}
