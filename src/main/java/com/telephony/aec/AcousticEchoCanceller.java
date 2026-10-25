package com.telephony.aec;

import java.util.Arrays;
import java.util.Objects;

/**
 * Real-Time Server-Side Acoustic Echo Canceller (AEC) for Telephony Audio Pipelines.
 *
 * <p>Signal Processing Stages (per 20 ms frame at 8 kHz):
 * <ol>
 *   <li><b>DC Blocker (1st-Order High-Pass IIR):</b> Removes PSTN / G.711 DC offset below 80 Hz.</li>
 *   <li><b>Bulk Delay Estimator:</b> Aligns variable network / telephony gateway / jitter buffer delay
 *       (0 to {@code maxBulkDelayMs}) using exponentially smoothed cross-correlation across
 *       the far-end reference ring buffer.</li>
 *   <li><b>Normalized Least Mean Squares (NLMS) Adaptive FIR Filter:</b> Estimates the linear
 *       acoustic/hybrid echo path {@code w[k]} and subtracts predicted echo {@code yHat[n]}
 *       from the near-end capture signal {@code d[n]}.</li>
 *   <li><b>Geigel & Residual Coherence Double-Talk Detector (DTD):</b> Detects caller barge-in
 *       while the far-end voice agent is speaking, freezing NLMS weight updates to prevent filter
 *       divergence and preserving the caller's voice for downstream speech recognition & VAD.</li>
 *   <li><b>Non-Linear Processor (NLP) & Comfort Noise Generator (CNG):</b> Suppresses residual
 *       non-linear G.711/speakerphone echo during far-end single-talk while injecting natural
 *       comfort noise.</li>
 * </ol>
 */
public final class AcousticEchoCanceller {

    private static final double DC_BLOCKER_ALPHA = 0.96;
    private static final double FAR_END_ACTIVE_RMS_THRESHOLD = 180.0;
    private static final double NEAR_END_SPEECH_RMS_THRESHOLD = 260.0;
    private static final double MAX_FILTER_WEIGHT_MAGNITUDE = 4.0;

    private final AecConfig config;
    private final int frameSize;
    private final int filterLen;
    private final int maxDelayFrames;
    private final int historyCapacitySamples;

    // 1st-order DC blocker state for near-end and far-end streams
    private double nearDcPrevX = 0.0;
    private double nearDcPrevY = 0.0;
    private double farDcPrevX = 0.0;
    private double farDcPrevY = 0.0;

    // Contiguous circular sample history for DC-blocked far-end reference signal
    private final double[] farSampleHistory;
    private int farWritePos = 0;

    // Frame-level ring buffer for bulk delay cross-correlation estimation
    private final double[][] farFrameHistory;
    private final double[] farFrameEnergy;
    private final double[] delayCorrelationScore;
    private int frameRingIndex = 0;
    private int estimatedBulkDelayFrames = 0;

    // NLMS adaptive FIR filter tap weights w[0 .. filterLen - 1]
    private final double[] weights;
    private boolean filterConverged = false;
    private int adaptationFramesCount = 0;

    // Double-talk detector hangover & telemetry state
    private int doubleTalkHangoverCounter = 0;
    private double smoothedNearPower = 1.0;
    private double smoothedErrorPower = 1.0;
    private double smoothedErleDb = 0.0;
    private long framesProcessed = 0;
    private long cngRandomSeed = 0x5DEECE66DL;

    // Streaming byte accumulators for arbitrary chunk sizes in pipeline integrations
    private byte[] pendingFar8kPcmBytes = new byte[0];
    private byte[] pendingNear8kPcmBytes = new byte[0];

    public AcousticEchoCanceller() {
        this(AecConfig.defaults());
    }

    public AcousticEchoCanceller(AecConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.frameSize = config.getFrameSizeSamples();
        this.filterLen = config.getFilterLengthSamples();
        this.maxDelayFrames = config.getMaxBulkDelayFrames();
        this.historyCapacitySamples = (maxDelayFrames + 2) * frameSize + filterLen;

        this.farSampleHistory = new double[historyCapacitySamples];
        this.farFrameHistory = new double[maxDelayFrames + 1][frameSize];
        this.farFrameEnergy = new double[maxDelayFrames + 1];
        this.delayCorrelationScore = new double[maxDelayFrames + 1];
        this.weights = new double[filterLen];
    }

    public AecConfig getConfig() {
        return config;
    }

    /**
     * Resets all adaptive filter weights, delay buffers, and telemetry state.
     */
    public synchronized void reset() {
        nearDcPrevX = 0.0;
        nearDcPrevY = 0.0;
        farDcPrevX = 0.0;
        farDcPrevY = 0.0;
        Arrays.fill(farSampleHistory, 0.0);
        farWritePos = 0;
        for (double[] frame : farFrameHistory) {
            Arrays.fill(frame, 0.0);
        }
        Arrays.fill(farFrameEnergy, 0.0);
        Arrays.fill(delayCorrelationScore, 0.0);
        frameRingIndex = 0;
        estimatedBulkDelayFrames = 0;
        Arrays.fill(weights, 0.0);
        filterConverged = false;
        adaptationFramesCount = 0;
        doubleTalkHangoverCounter = 0;
        smoothedNearPower = 1.0;
        smoothedErrorPower = 1.0;
        smoothedErleDb = 0.0;
        framesProcessed = 0;
        pendingFar8kPcmBytes = new byte[0];
        pendingNear8kPcmBytes = new byte[0];
    }

    /**
     * Processes one synchronized 20 ms frame (default 160 samples at 8 kHz) of near-end
     * microphone/caller capture audio against the contemporary far-end reference frame.
     *
     * @param nearEndSamples8k 8 kHz 16-bit signed linear PCM samples from the inbound caller leg
     * @param farEndSamples8k  8 kHz 16-bit signed linear PCM samples sent to the outbound caller leg
     * @return {@link AecFrameResult} containing cleaned 8 kHz and 16 kHz audio and telemetry
     */
    public synchronized AecFrameResult processFrame(short[] nearEndSamples8k, short[] farEndSamples8k) {
        Objects.requireNonNull(nearEndSamples8k, "nearEndSamples8k must not be null");
        Objects.requireNonNull(farEndSamples8k, "farEndSamples8k must not be null");
        if (nearEndSamples8k.length != frameSize || farEndSamples8k.length != frameSize) {
            throw new IllegalArgumentException(
                    "Expected frame size of " + frameSize + " samples, got near="
                            + nearEndSamples8k.length + ", far=" + farEndSamples8k.length);
        }

        // Step 1: Pre-filter with 1st-order DC Blocker (High-Pass IIR)
        double[] nearClean = new double[frameSize];
        double[] farClean = new double[frameSize];
        double currentNearEnergy = 0.0;
        double currentFarEnergy = 0.0;

        for (int i = 0; i < frameSize; i++) {
            double nx = nearEndSamples8k[i];
            double fx = farEndSamples8k[i];
            if (config.isDcBlockerEnabled()) {
                double ny = nx - nearDcPrevX + DC_BLOCKER_ALPHA * nearDcPrevY;
                nearDcPrevX = nx;
                nearDcPrevY = ny;
                nearClean[i] = ny;

                double fy = fx - farDcPrevX + DC_BLOCKER_ALPHA * farDcPrevY;
                farDcPrevX = fx;
                farDcPrevY = fy;
                farClean[i] = fy;
            } else {
                nearClean[i] = nx;
                farClean[i] = fx;
            }
            currentNearEnergy += nearClean[i] * nearClean[i];
            currentFarEnergy += farClean[i] * farClean[i];
        }

        // Step 2: Store far-end frame in frame ring buffer for Bulk Delay Estimation
        System.arraycopy(farClean, 0, farFrameHistory[frameRingIndex], 0, frameSize);
        farFrameEnergy[frameRingIndex] = currentFarEnergy;

        updateBulkDelayEstimate(nearClean, currentNearEnergy);

        // Compute aligned far-end energy at the estimated bulk delay
        int alignedRingIdx = (frameRingIndex - estimatedBulkDelayFrames + farFrameHistory.length)
                % farFrameHistory.length;
        double alignedFarEnergy = farFrameEnergy[alignedRingIdx];
        double alignedFarRms = Math.sqrt(alignedFarEnergy / frameSize);
        double nearRms = Math.sqrt(currentNearEnergy / frameSize);
        boolean farEndActive = alignedFarRms >= FAR_END_ACTIVE_RMS_THRESHOLD;

        // Step 3: Check Geigel Double-Talk condition prior to weight adaptation
        double geigelRatio = nearRms / Math.max(1.0, alignedFarRms);
        boolean preDoubleTalk = farEndActive && (geigelRatio > config.getDoubleTalkGeigelThreshold());

        // Step 4: Sample-by-sample NLMS filtering (and conditional adaptation)
        double[] linearError = new double[frameSize];
        double[] predictedEcho = new double[frameSize];
        double linearErrorEnergy = 0.0;
        double predictedEchoEnergy = 0.0;
        double crossNearPred = 0.0;

        int bulkDelaySamples = estimatedBulkDelayFrames * frameSize;
        double mu = config.getNlmsStepSize();
        double eps = config.getRegularizationEpsilon() * filterLen;
        double leak = 1.0 - config.getWeightLeakage();

        // Save snapshot of weights before frame in case post-frame residual DTD triggers
        double[] weightsSnapshot = Arrays.copyOf(weights, filterLen);

        for (int i = 0; i < frameSize; i++) {
            farSampleHistory[farWritePos] = farClean[i];

            int tapZeroIdx = farWritePos - bulkDelaySamples;
            if (tapZeroIdx < 0) {
                tapZeroIdx += historyCapacitySamples;
            }

            double yHat = 0.0;
            double xNormSq = 0.0;
            int idx = tapZeroIdx;
            for (int k = 0; k < filterLen; k++) {
                double xk = farSampleHistory[idx];
                yHat += weights[k] * xk;
                xNormSq += xk * xk;
                idx--;
                if (idx < 0) {
                    idx += historyCapacitySamples;
                }
            }

            double err = nearClean[i] - yHat;
            predictedEcho[i] = yHat;
            linearError[i] = err;
            linearErrorEnergy += err * err;
            predictedEchoEnergy += yHat * yHat;
            crossNearPred += nearClean[i] * yHat;

            if (farEndActive && !preDoubleTalk && doubleTalkHangoverCounter == 0) {
                double step = (mu * err) / (xNormSq + eps);
                int wIdx = tapZeroIdx;
                for (int k = 0; k < filterLen; k++) {
                    double updated = weights[k] * leak + step * farSampleHistory[wIdx];
                    if (updated > MAX_FILTER_WEIGHT_MAGNITUDE) {
                        updated = MAX_FILTER_WEIGHT_MAGNITUDE;
                    } else if (updated < -MAX_FILTER_WEIGHT_MAGNITUDE) {
                        updated = -MAX_FILTER_WEIGHT_MAGNITUDE;
                    }
                    weights[k] = updated;
                    wIdx--;
                    if (wIdx < 0) {
                        wIdx += historyCapacitySamples;
                    }
                }
            }

            farWritePos++;
            if (farWritePos >= historyCapacitySamples) {
                farWritePos = 0;
            }
        }

        frameRingIndex = (frameRingIndex + 1) % farFrameHistory.length;

        // Step 5: Post-subtraction Double-Talk verification & rollback if sudden barge-in occurred
        double linearErrorRms = Math.sqrt(linearErrorEnergy / frameSize);
        double coherence = Math.abs(crossNearPred)
                / Math.sqrt(currentNearEnergy * predictedEchoEnergy + 1.0);

        boolean postDoubleTalk = false;
        if (farEndActive && filterConverged) {
            boolean residualSurge = (linearErrorRms > NEAR_END_SPEECH_RMS_THRESHOLD)
                    && (linearErrorRms / Math.max(1.0, alignedFarRms) > 0.36)
                    && (coherence < 0.78);
            if (residualSurge) {
                postDoubleTalk = true;
            }
        }

        if (preDoubleTalk || postDoubleTalk) {
            System.arraycopy(weightsSnapshot, 0, weights, 0, filterLen);
            doubleTalkHangoverCounter = config.getDoubleTalkHangoverFrames();
        } else if (doubleTalkHangoverCounter > 0) {
            doubleTalkHangoverCounter--;
        } else if (farEndActive) {
            adaptationFramesCount++;
            if (adaptationFramesCount >= 12 && linearErrorEnergy < 0.35 * currentNearEnergy) {
                filterConverged = true;
            }
        }

        boolean doubleTalkDetected = farEndActive
                && (preDoubleTalk || postDoubleTalk || doubleTalkHangoverCounter > 0);

        // Step 6: Non-Linear Processor (NLP) & Comfort Noise Generation (CNG)
        short[] cleaned8k = new short[frameSize];
        double finalOutputEnergy = 0.0;

        boolean applyNlpSuppression = config.isNlpEnabled() && farEndActive && !doubleTalkDetected;
        double nlpGain = 1.0;
        if (applyNlpSuppression) {
            double residualRatio = linearErrorRms / Math.max(1.0, nearRms);
            if (filterConverged || residualRatio < 0.55) {
                nlpGain = config.getNlpAttenuationFloor();
            } else {
                nlpGain = Math.max(config.getNlpAttenuationFloor(), 0.35 * residualRatio);
            }
        }

        for (int i = 0; i < frameSize; i++) {
            double sampleOut = linearError[i] * nlpGain;
            if (applyNlpSuppression && config.getComfortNoiseLevel() > 0.0) {
                sampleOut += nextComfortNoiseSample(config.getComfortNoiseLevel());
            }
            short clamped = TelephonyAudioCodec.clampToShort(Math.round(sampleOut));
            cleaned8k[i] = clamped;
            finalOutputEnergy += (double) clamped * clamped;
        }

        // Step 7: Update ERLE & VAD/Barge-In telemetry
        if (farEndActive && !doubleTalkDetected && currentNearEnergy > 100.0) {
            smoothedNearPower = 0.85 * smoothedNearPower + 0.15 * currentNearEnergy;
            smoothedErrorPower = 0.85 * smoothedErrorPower + 0.15 * Math.max(1.0, finalOutputEnergy);
            smoothedErleDb = 10.0 * Math.log10(smoothedNearPower / Math.max(1.0, smoothedErrorPower));
        }

        double outputRms = Math.sqrt(finalOutputEnergy / frameSize);
        boolean nearEndSpeechActive = (!farEndActive && nearRms >= NEAR_END_SPEECH_RMS_THRESHOLD)
                || (doubleTalkDetected && outputRms >= NEAR_END_SPEECH_RMS_THRESHOLD);
        boolean bargeInRecommended = farEndActive && doubleTalkDetected && nearEndSpeechActive;

        short[] cleaned16k = TelephonyAudioCodec.upsample8kHzTo16kHz(cleaned8k);
        framesProcessed++;

        return new AecFrameResult(
                cleaned8k,
                cleaned16k,
                smoothedErleDb,
                estimatedBulkDelayFrames * config.getFrameDurationMs(),
                farEndActive,
                nearEndSpeechActive,
                doubleTalkDetected,
                bargeInRecommended,
                framesProcessed);
    }

    /**
     * Streaming Pipeline Helper: Buffers arbitrary-length 8 kHz PCM16LE far-end reference bytes.
     */
    public synchronized void feedFarEndReference8kHzPcmLe(byte[] farPcm8kLe) {
        Objects.requireNonNull(farPcm8kLe, "farPcm8kLe must not be null");
        if (farPcm8kLe.length == 0) {
            return;
        }
        pendingFar8kPcmBytes = concatBytes(pendingFar8kPcmBytes, farPcm8kLe);
        int maxBufferedBytes = (maxDelayFrames + 16) * frameSize * 2;
        if (pendingFar8kPcmBytes.length > maxBufferedBytes) {
            pendingFar8kPcmBytes = Arrays.copyOfRange(
                    pendingFar8kPcmBytes,
                    pendingFar8kPcmBytes.length - maxBufferedBytes,
                    pendingFar8kPcmBytes.length);
        }
    }

    /**
     * Streaming Pipeline Helper: Processes arbitrary-length inbound 8 kHz PCM16LE bytes
     * against buffered far-end audio and returns cleaned, upsampled 16 kHz PCM16LE bytes
     * ready to send directly to a 16 kHz voice model.
     */
    public synchronized byte[] processInboundStreamTo16kHzPcmLe(byte[] nearPcm8kLe) {
        Objects.requireNonNull(nearPcm8kLe, "nearPcm8kLe must not be null");
        pendingNear8kPcmBytes = concatBytes(pendingNear8kPcmBytes, nearPcm8kLe);
        int frameBytes = frameSize * 2;
        int availableFrames = pendingNear8kPcmBytes.length / frameBytes;
        if (availableFrames == 0) {
            return new byte[0];
        }

        byte[] out16kTotal = new byte[availableFrames * frameBytes * 2];
        for (int f = 0; f < availableFrames; f++) {
            byte[] nearFrameBytes = Arrays.copyOfRange(
                    pendingNear8kPcmBytes, f * frameBytes, (f + 1) * frameBytes);
            short[] nearShorts = TelephonyAudioCodec.pcm16LeBytesToShorts(nearFrameBytes);

            short[] farShorts;
            if (pendingFar8kPcmBytes.length >= frameBytes) {
                byte[] farFrameBytes = Arrays.copyOfRange(pendingFar8kPcmBytes, 0, frameBytes);
                pendingFar8kPcmBytes = Arrays.copyOfRange(
                        pendingFar8kPcmBytes, frameBytes, pendingFar8kPcmBytes.length);
                farShorts = TelephonyAudioCodec.pcm16LeBytesToShorts(farFrameBytes);
            } else {
                farShorts = new short[frameSize];
            }

            AecFrameResult res = processFrame(nearShorts, farShorts);
            byte[] clean16kBytes = res.getCleaned16kHzPcmLeBytes();
            System.arraycopy(clean16kBytes, 0, out16kTotal, f * clean16kBytes.length, clean16kBytes.length);
        }

        pendingNear8kPcmBytes = Arrays.copyOfRange(
                pendingNear8kPcmBytes, availableFrames * frameBytes, pendingNear8kPcmBytes.length);
        return out16kTotal;
    }

    private void updateBulkDelayEstimate(double[] nearClean, double nearEnergy) {
        if (nearEnergy < frameSize * 80.0 * 80.0) {
            return;
        }
        int bestDelay = estimatedBulkDelayFrames;
        double bestScore = -1.0;

        for (int d = 0; d <= maxDelayFrames; d++) {
            int ringIdx = (frameRingIndex - d + farFrameHistory.length) % farFrameHistory.length;
            double farE = farFrameEnergy[ringIdx];
            double instantCorr = 0.0;
            if (farE >= frameSize * FAR_END_ACTIVE_RMS_THRESHOLD * FAR_END_ACTIVE_RMS_THRESHOLD) {
                double[] candidateFar = farFrameHistory[ringIdx];
                double maxLagCorr = 0.0;
                int[] testLags = {0, 7, 16, 32};
                for (int lag : testLags) {
                    double c = 0.0;
                    for (int i = lag; i < frameSize; i++) {
                        c += nearClean[i] * candidateFar[i - lag];
                    }
                    double absC = Math.abs(c);
                    if (absC > maxLagCorr) {
                        maxLagCorr = absC;
                    }
                }
                instantCorr = maxLagCorr / Math.sqrt(nearEnergy * farE + 1.0);
            }
            delayCorrelationScore[d] = 0.86 * delayCorrelationScore[d] + 0.14 * instantCorr;
            if (delayCorrelationScore[d] > bestScore) {
                bestScore = delayCorrelationScore[d];
                bestDelay = d;
            }
        }

        double currentDelayScore = delayCorrelationScore[estimatedBulkDelayFrames];
        if (bestDelay != estimatedBulkDelayFrames
                && bestScore > 0.18
                && bestScore > currentDelayScore * 1.18) {
            estimatedBulkDelayFrames = bestDelay;
            Arrays.fill(weights, 0.0);
            filterConverged = false;
            adaptationFramesCount = 0;
        }
    }

    private double nextComfortNoiseSample(double amplitude) {
        cngRandomSeed = (cngRandomSeed * 25214903917L + 11L) & ((1L << 48) - 1);
        double u1 = ((cngRandomSeed >>> 16) & 0xFFFF) / 65535.0;
        cngRandomSeed = (cngRandomSeed * 25214903917L + 11L) & ((1L << 48) - 1);
        double u2 = ((cngRandomSeed >>> 16) & 0xFFFF) / 65535.0;
        return (u1 - u2) * amplitude;
    }

    private static byte[] concatBytes(byte[] a, byte[] b) {
        if (a.length == 0) {
            return Arrays.copyOf(b, b.length);
        }
        byte[] res = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }

    /**
     * Immutable result for a single processed 20 ms telephony frame.
     */
    public static final class AecFrameResult {
        private final short[] cleaned8kHzSamples;
        private final short[] cleaned16kHzSamples;
        private final double erleDb;
        private final int estimatedBulkDelayMs;
        private final boolean farEndActive;
        private final boolean nearEndSpeechActive;
        private final boolean doubleTalkDetected;
        private final boolean bargeInTriggerRecommended;
        private final long frameIndex;

        public AecFrameResult(
                short[] cleaned8kHzSamples,
                short[] cleaned16kHzSamples,
                double erleDb,
                int estimatedBulkDelayMs,
                boolean farEndActive,
                boolean nearEndSpeechActive,
                boolean doubleTalkDetected,
                boolean bargeInTriggerRecommended,
                long frameIndex) {
            this.cleaned8kHzSamples = cleaned8kHzSamples;
            this.cleaned16kHzSamples = cleaned16kHzSamples;
            this.erleDb = erleDb;
            this.estimatedBulkDelayMs = estimatedBulkDelayMs;
            this.farEndActive = farEndActive;
            this.nearEndSpeechActive = nearEndSpeechActive;
            this.doubleTalkDetected = doubleTalkDetected;
            this.bargeInTriggerRecommended = bargeInTriggerRecommended;
            this.frameIndex = frameIndex;
        }

        public short[] getCleaned8kHzSamples() {
            return cleaned8kHzSamples;
        }

        public short[] getCleaned16kHzSamples() {
            return cleaned16kHzSamples;
        }

        public byte[] getCleaned8kHzPcmLeBytes() {
            return TelephonyAudioCodec.shortsToPcm16LeBytes(cleaned8kHzSamples);
        }

        public byte[] getCleaned16kHzPcmLeBytes() {
            return TelephonyAudioCodec.shortsToPcm16LeBytes(cleaned16kHzSamples);
        }

        public double getErleDb() {
            return erleDb;
        }

        public int getEstimatedBulkDelayMs() {
            return estimatedBulkDelayMs;
        }

        public boolean isFarEndActive() {
            return farEndActive;
        }

        public boolean isNearEndSpeechActive() {
            return nearEndSpeechActive;
        }

        public boolean isDoubleTalkDetected() {
            return doubleTalkDetected;
        }

        public boolean isBargeInTriggerRecommended() {
            return bargeInTriggerRecommended;
        }

        public long getFrameIndex() {
            return frameIndex;
        }
    }
}
