package com.telephony.aec;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Comprehensive Unit and Functional Test Suite for the Server-Side Telephony
 * Acoustic Echo Canceller (AEC) Library.
 *
 * <p>Run directly via:
 * {@code java -cp target/test-classes:target/classes com.telephony.aec.AcousticEchoCancellerTest}
 */
public final class AcousticEchoCancellerTest {

    private static int passedTests = 0;
    private static int failedTests = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println(" Server-Side Telephony Acoustic Echo Canceller (AEC) Test Suite");
        System.out.println("================================================================================");

        // --- UNIT TESTS ---
        System.out.println("\n[Unit Tests]");
        runTest("testConfigValidationRejectsInvalidParameters", AcousticEchoCancellerTest::testConfigValidationRejectsInvalidParameters);
        runTest("testPcm16LeByteConversionsAndOddByteHandling", AcousticEchoCancellerTest::testPcm16LeByteConversionsAndOddByteHandling);
        runTest("testG711UlawAndAlawRoundTripAccuracy", AcousticEchoCancellerTest::testG711UlawAndAlawRoundTripAccuracy);
        runTest("testResampling24kTo8k16kTo8kAnd8kTo16k", AcousticEchoCancellerTest::testResampling24kTo8k16kTo8kAnd8kTo16k);
        runTest("testDcBlockerRemovesStrongDcBias", AcousticEchoCancellerTest::testDcBlockerRemovesStrongDcBias);
        runTest("testSilenceAndNearEndOnlyPassthroughIntegrity", AcousticEchoCancellerTest::testSilenceAndNearEndOnlyPassthroughIntegrity);
        runTest("testExtremeClippingStabilityNoNaNOrOverflow", AcousticEchoCancellerTest::testExtremeClippingStabilityNoNaNOrOverflow);

        // --- FUNCTIONAL TESTS ---
        System.out.println("\n[Functional Tests (End-to-End Telephony Pipeline Scenarios)]");
        runTest("testMultiTapEchoCancellationWith60msNetworkDelay", AcousticEchoCancellerTest::testMultiTapEchoCancellationWith60msNetworkDelay);
        runTest("testCallerBargeInDoubleTalkPreservesNearEndSpeech", AcousticEchoCancellerTest::testCallerBargeInDoubleTalkPreservesNearEndSpeech);
        runTest("testG711UlawCompandedTelephonyEchoCancellation", AcousticEchoCancellerTest::testG711UlawCompandedTelephonyEchoCancellation);
        runTest("testVariableBulkDelayAdaptationFrom40msTo100ms", AcousticEchoCancellerTest::testVariableBulkDelayAdaptationFrom40msTo100ms);
        runTest("testStreamingArbitraryChunkSizePipelineIntegration", AcousticEchoCancellerTest::testStreamingArbitraryChunkSizePipelineIntegration);
        runTest("testConcurrentMultiCallPipelineServiceIsolation", AcousticEchoCancellerTest::testConcurrentMultiCallPipelineServiceIsolation);

        System.out.println("\n================================================================================");
        System.out.printf(" TEST SUMMARY: %d passed, %d failed (Total: %d)%n",
                passedTests, failedTests, passedTests + failedTests);
        System.out.println("================================================================================");

        if (failedTests > 0) {
            System.exit(1);
        }
    }

    private static void runTest(String name, ThrowingRunnable test) {
        try {
            test.run();
            passedTests++;
            System.out.printf("  [PASS] %s%n", name);
        } catch (Throwable t) {
            failedTests++;
            System.err.printf("  [FAIL] %s -> %s%n", name, t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // =========================================================================
    // UNIT TESTS
    // =========================================================================

    private static void testConfigValidationRejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> AecConfig.builder().sampleRateHz(0).build());
        assertThrows(IllegalArgumentException.class, () -> AecConfig.builder().frameSizeSamples(-10).build());
        assertThrows(IllegalArgumentException.class, () ->
                AecConfig.builder().frameSizeSamples(160).filterLengthSamples(80).build());
        assertThrows(IllegalArgumentException.class, () -> AecConfig.builder().nlmsStepSize(0.0).build());
        assertThrows(IllegalArgumentException.class, () -> AecConfig.builder().nlmsStepSize(2.0).build());
        assertThrows(IllegalArgumentException.class, () -> AecConfig.builder().maxBulkDelayMs(-1).build());
        assertThrows(IllegalArgumentException.class, () -> AecConfig.builder().nlpAttenuationFloor(1.5).build());

        AecConfig defaultCfg = AecConfig.defaults();
        assertTrue(defaultCfg.getSampleRateHz() == 8000, "Expected 8000 Hz default");
        assertTrue(defaultCfg.getFrameSizeSamples() == 160, "Expected 160 samples (20 ms)");
        assertTrue(defaultCfg.getFrameDurationMs() == 20, "Expected 20 ms frame duration");
    }

    private static void testPcm16LeByteConversionsAndOddByteHandling() {
        short[] original = {0, 1, -1, 12345, -23456, Short.MAX_VALUE, Short.MIN_VALUE};
        byte[] bytes = TelephonyAudioCodec.shortsToPcm16LeBytes(original);
        assertTrue(bytes.length == original.length * 2, "Expected 2 bytes per sample");
        short[] decoded = TelephonyAudioCodec.pcm16LeBytesToShorts(bytes);
        assertTrue(Arrays.equals(original, decoded), "PCM16LE round-trip mismatch");

        byte[] oddBytes = Arrays.copyOf(bytes, bytes.length + 1);
        oddBytes[oddBytes.length - 1] = 0x7F;
        short[] oddDecoded = TelephonyAudioCodec.pcm16LeBytesToShorts(oddBytes);
        assertTrue(Arrays.equals(original, oddDecoded), "Odd-byte array should decode valid sample pairs");
    }

    private static void testG711UlawAndAlawRoundTripAccuracy() {
        short[] sine8k = generateMultiToneSignal(160, 0, 8000, 6000.0);
        byte[] ulaw = TelephonyAudioCodec.encodeG711Ulaw(sine8k);
        short[] ulawDecoded = TelephonyAudioCodec.decodeG711Ulaw(ulaw);

        byte[] alaw = TelephonyAudioCodec.encodeG711Alaw(sine8k);
        short[] alawDecoded = TelephonyAudioCodec.decodeG711Alaw(alaw);

        double ulawSnrDb = computeSnrDb(sine8k, ulawDecoded);
        double alawSnrDb = computeSnrDb(sine8k, alawDecoded);

        assertTrue(ulawSnrDb > 30.0, "G.711 u-law SNR too low: " + ulawSnrDb + " dB");
        assertTrue(alawSnrDb > 30.0, "G.711 A-law SNR too low: " + alawSnrDb + " dB");
    }

    private static void testResampling24kTo8k16kTo8kAnd8kTo16k() {
        short[] samples24k = generateMultiToneSignal(480, 0, 24000, 8000.0);
        short[] down8k = TelephonyAudioCodec.downsample24kHzTo8kHz(samples24k);
        assertTrue(down8k.length == 160, "Expected 160 samples at 8 kHz, got " + down8k.length);

        short[] up16k = TelephonyAudioCodec.upsample8kHzTo16kHz(down8k);
        assertTrue(up16k.length == 320, "Expected 320 samples at 16 kHz, got " + up16k.length);

        short[] down8kFrom16k = TelephonyAudioCodec.downsample16kHzTo8kHz(up16k);
        assertTrue(down8kFrom16k.length == 160, "Expected 160 samples from 16k->8k decimation");

        double rms24k = computeRms(samples24k);
        double rms8k = computeRms(down8k);
        double rms16k = computeRms(up16k);
        assertTrue(Math.abs(rms24k - rms8k) / rms24k < 0.15, "8 kHz decimated RMS deviated excessively");
        assertTrue(Math.abs(rms8k - rms16k) / rms8k < 0.10, "16 kHz upsampled RMS deviated excessively");
    }

    private static void testDcBlockerRemovesStrongDcBias() {
        AecConfig config = AecConfig.builder()
                .dcBlockerEnabled(true)
                .nlpEnabled(false)
                .build();
        AcousticEchoCanceller aec = new AcousticEchoCanceller(config);

        short[] silenceFar = new short[160];
        short[] dcBiasedNear = new short[160];
        Arrays.fill(dcBiasedNear, (short) 6000);

        short[] lastOut = null;
        for (int f = 0; f < 25; f++) {
            lastOut = aec.processFrame(dcBiasedNear, silenceFar).getCleaned8kHzSamples();
        }
        double residualRms = computeRms(lastOut);
        assertTrue(residualRms < 50.0, "DC blocker should remove constant DC offset, residual RMS=" + residualRms);
    }

    private static void testSilenceAndNearEndOnlyPassthroughIntegrity() {
        AecConfig config = AecConfig.defaults();
        AcousticEchoCanceller aec = new AcousticEchoCanceller(config);

        short[] silence = new short[160];
        for (int i = 0; i < 10; i++) {
            AcousticEchoCanceller.AecFrameResult res = aec.processFrame(silence, silence);
            assertTrue(!res.isFarEndActive(), "Far end should be inactive on silence");
            assertTrue(!res.isDoubleTalkDetected(), "Double talk should not trigger on silence");
            assertTrue(computeRms(res.getCleaned8kHzSamples()) < 1.0, "Silence output must remain near zero");
        }

        double totalInEnergy = 0.0;
        double totalOutEnergy = 0.0;
        for (int f = 0; f < 20; f++) {
            short[] callerSpeech = generateCallerVoiceFrame(160, f * 160, 8000, 7500.0);
            AcousticEchoCanceller.AecFrameResult res = aec.processFrame(callerSpeech, silence);
            assertTrue(res.isNearEndSpeechActive(), "Near-end speech should be detected");
            if (f >= 2) {
                totalInEnergy += computeEnergy(callerSpeech);
                totalOutEnergy += computeEnergy(res.getCleaned8kHzSamples());
            }
        }
        double energyRatio = totalOutEnergy / totalInEnergy;
        assertTrue(energyRatio > 0.85 && energyRatio < 1.15,
                "Near-end single-talk speech must pass through undistorted, ratio=" + energyRatio);
    }

    private static void testExtremeClippingStabilityNoNaNOrOverflow() {
        AcousticEchoCanceller aec = new AcousticEchoCanceller(AecConfig.defaults());
        short[] maxFar = new short[160];
        short[] maxNear = new short[160];
        for (int i = 0; i < 160; i++) {
            maxFar[i] = (i % 2 == 0) ? Short.MAX_VALUE : Short.MIN_VALUE;
            maxNear[i] = (i % 3 == 0) ? Short.MIN_VALUE : Short.MAX_VALUE;
        }
        for (int f = 0; f < 30; f++) {
            AcousticEchoCanceller.AecFrameResult res = aec.processFrame(maxNear, maxFar);
            assertTrue(!Double.isNaN(res.getErleDb()) && !Double.isInfinite(res.getErleDb()),
                    "ERLE must remain finite under extreme clipping");
            assertTrue(res.getCleaned8kHzSamples().length == 160, "Frame length must be 160");
        }
    }

    // =========================================================================
    // FUNCTIONAL TESTS (End-to-End Telephony Pipeline Scenarios)
    // =========================================================================

    private static void testMultiTapEchoCancellationWith60msNetworkDelay() {
        final int bulkDelayFrames = 3; // 60 ms
        final double[] echoImpulse = new double[120];
        echoImpulse[0] = 0.42;
        echoImpulse[7] = -0.21;
        echoImpulse[19] = 0.14;
        echoImpulse[43] = -0.08;
        echoImpulse[85] = 0.04;

        AecConfig config = AecConfig.builder()
                .filterLengthSamples(320)
                .maxBulkDelayMs(200)
                .nlmsStepSize(0.45)
                .nlpEnabled(true)
                .comfortNoiseLevel(4.0)
                .build();
        AcousticEchoCanceller aec = new AcousticEchoCanceller(config);

        int totalFrames = 120;
        short[][] farHistory = new short[totalFrames][160];
        double[] fullFarSignal = new double[totalFrames * 160];

        for (int f = 0; f < totalFrames; f++) {
            farHistory[f] = generateRichExcitationSignal(160, f * 160, 9000.0);
            for (int i = 0; i < 160; i++) {
                fullFarSignal[f * 160 + i] = farHistory[f][i];
            }
        }

        double lateInputEchoEnergy = 0.0;
        double lateResidualErrorEnergy = 0.0;
        int falseBargeInCount = 0;
        int finalEstimatedDelayMs = 0;

        for (int f = 0; f < totalFrames; f++) {
            short[] nearMic = new short[160];
            int baseIdx = f * 160;
            int delaySamples = bulkDelayFrames * 160;
            for (int i = 0; i < 160; i++) {
                int globalSample = baseIdx + i - delaySamples;
                double echoSample = 0.0;
                if (globalSample >= 0) {
                    for (int k = 0; k < echoImpulse.length; k++) {
                        if (globalSample - k >= 0) {
                            echoSample += echoImpulse[k] * fullFarSignal[globalSample - k];
                        }
                    }
                }
                nearMic[i] = TelephonyAudioCodec.clampToShort(Math.round(echoSample));
            }

            AcousticEchoCanceller.AecFrameResult result = aec.processFrame(nearMic, farHistory[f]);
            if (f >= 50) {
                lateInputEchoEnergy += computeEnergy(nearMic);
                lateResidualErrorEnergy += computeEnergy(result.getCleaned8kHzSamples());
                if (result.isBargeInTriggerRecommended()) {
                    falseBargeInCount++;
                }
                finalEstimatedDelayMs = result.getEstimatedBulkDelayMs();
            }
        }

        double convergedErleDb = 10.0 * Math.log10(lateInputEchoEnergy / Math.max(1.0, lateResidualErrorEnergy));
        assertTrue(Math.abs(finalEstimatedDelayMs - 60) <= 20,
                "Expected estimated bulk delay near 60 ms, got " + finalEstimatedDelayMs + " ms");
        assertTrue(convergedErleDb >= 18.0,
                "Expected converged ERLE >= 18.0 dB on multi-tap echo path, got " + convergedErleDb + " dB");
        assertTrue(falseBargeInCount == 0,
                "Pure far-end echo must NOT trigger false caller barge-in, got " + falseBargeInCount);
    }

    private static void testCallerBargeInDoubleTalkPreservesNearEndSpeech() {
        final int bulkDelayFrames = 2; // 40 ms
        final double[] echoImpulse = new double[64];
        echoImpulse[0] = 0.40;
        echoImpulse[12] = -0.18;
        echoImpulse[31] = 0.09;

        AecConfig config = AecConfig.builder()
                .filterLengthSamples(320)
                .maxBulkDelayMs(200)
                .nlmsStepSize(0.45)
                .nlpEnabled(true)
                .build();
        AcousticEchoCanceller aec = new AcousticEchoCanceller(config);

        int totalFrames = 100;
        short[][] farFrames = new short[totalFrames][160];
        double[] fullFar = new double[totalFrames * 160];
        for (int f = 0; f < totalFrames; f++) {
            farFrames[f] = generateRichExcitationSignal(160, f * 160, 8500.0);
            for (int i = 0; i < 160; i++) {
                fullFar[f * 160 + i] = farFrames[f][i];
            }
        }

        int doubleTalkDetectedFrames = 0;
        int bargeInRecommendedFrames = 0;
        double callerOriginalEnergy = 0.0;
        double callerRecoveredErrorEnergy = 0.0;

        for (int f = 0; f < totalFrames; f++) {
            short[] nearMic = new short[160];
            short[] cleanCallerVoice = new short[160];
            boolean callerSpeaking = (f >= 60 && f < 85);

            if (callerSpeaking) {
                cleanCallerVoice = generateCallerVoiceFrame(160, f * 160, 8000, 10500.0);
            }

            int baseIdx = f * 160;
            int delaySamples = bulkDelayFrames * 160;
            for (int i = 0; i < 160; i++) {
                int globalSample = baseIdx + i - delaySamples;
                double echo = 0.0;
                if (globalSample >= 0) {
                    for (int k = 0; k < echoImpulse.length; k++) {
                        if (globalSample - k >= 0) {
                            echo += echoImpulse[k] * fullFar[globalSample - k];
                        }
                    }
                }
                nearMic[i] = TelephonyAudioCodec.clampToShort(Math.round(echo + cleanCallerVoice[i]));
            }

            AcousticEchoCanceller.AecFrameResult res = aec.processFrame(nearMic, farFrames[f]);
            if (callerSpeaking) {
                if (res.isDoubleTalkDetected()) {
                    doubleTalkDetectedFrames++;
                }
                if (res.isBargeInTriggerRecommended()) {
                    bargeInRecommendedFrames++;
                }
                callerOriginalEnergy += computeEnergy(cleanCallerVoice);
                short[] cleaned = res.getCleaned8kHzSamples();
                for (int i = 0; i < 160; i++) {
                    double diff = cleaned[i] - cleanCallerVoice[i];
                    callerRecoveredErrorEnergy += diff * diff;
                }
            }
        }

        double callerSignalToDistortionDb = 10.0 * Math.log10(callerOriginalEnergy / Math.max(1.0, callerRecoveredErrorEnergy));
        assertTrue(doubleTalkDetectedFrames >= 15,
                "Double-talk detector must engage during caller barge-in, detected frames=" + doubleTalkDetectedFrames);
        assertTrue(bargeInRecommendedFrames >= 12,
                "Barge-in trigger must be recommended during caller interruption, frames=" + bargeInRecommendedFrames);
        assertTrue(callerSignalToDistortionDb > 6.5,
                "Clean caller voice must be preserved during double-talk while echo is subtracted, SDR=" + callerSignalToDistortionDb + " dB");
    }

    private static void testG711UlawCompandedTelephonyEchoCancellation() {
        TelephonyAecPipelineService pipeline = new TelephonyAecPipelineService();
        String callId = "ulaw-call-01";
        pipeline.registerCallSession(callId, AecConfig.defaults());

        int frames = 90;
        short[][] far8kHistory = new short[frames][160];
        double lateEchoInEnergy = 0.0;
        double lateCleanOutEnergy = 0.0;

        for (int f = 0; f < frames; f++) {
            short[] model24k = generateRichExcitationSignal(480, f * 480, 9000.0);
            byte[] model24kBytes = TelephonyAudioCodec.shortsToPcm16LeBytes(model24k);

            byte[] outboundUlaw = pipeline.onOutboundModelAudio24kHzToUlaw(callId, model24kBytes);
            assertTrue(outboundUlaw.length == 160, "Expected 160 u-law bytes per 20 ms frame");
            far8kHistory[f] = TelephonyAudioCodec.decodeG711Ulaw(outboundUlaw);

            short[] echoed8k = new short[160];
            if (f >= 2) {
                short[] delayedFar = far8kHistory[f - 2];
                for (int i = 0; i < 160; i++) {
                    double e = 0.38 * delayedFar[i]
                            + (i >= 5 ? -0.15 * delayedFar[i - 5] : 0.0)
                            + (i >= 18 ? 0.08 * delayedFar[i - 18] : 0.0);
                    echoed8k[i] = TelephonyAudioCodec.clampToShort(Math.round(e));
                }
            }
            byte[] inboundUlawPacket = TelephonyAudioCodec.encodeG711Ulaw(echoed8k);

            TelephonyAecPipelineService.ProcessedFrame processed =
                    pipeline.onInboundCallerG711Ulaw(callId, inboundUlawPacket);

            assertTrue(processed.getClean16kHzPcmLe().length == 640,
                    "Expected 640 bytes (320 samples at 16 kHz) for voice model input");

            if (f >= 40) {
                lateEchoInEnergy += computeEnergy(TelephonyAudioCodec.decodeG711Ulaw(inboundUlawPacket));
                lateCleanOutEnergy += computeEnergy(processed.getAecResult().getCleaned8kHzSamples());
            }
        }

        double ulawErleDb = 10.0 * Math.log10(lateEchoInEnergy / Math.max(1.0, lateCleanOutEnergy));
        assertTrue(ulawErleDb >= 15.0,
                "Expected >= 15.0 dB ERLE over non-linear G.711 u-law telephony trunk, got " + ulawErleDb + " dB");
        pipeline.closeCallSession(callId);
    }

    private static void testVariableBulkDelayAdaptationFrom40msTo100ms() {
        AecConfig config = AecConfig.builder()
                .filterLengthSamples(320)
                .maxBulkDelayMs(200)
                .nlmsStepSize(0.45)
                .build();
        AcousticEchoCanceller aec = new AcousticEchoCanceller(config);

        int totalFrames = 140;
        short[][] farFrames = new short[totalFrames][160];
        for (int f = 0; f < totalFrames; f++) {
            farFrames[f] = generateRichExcitationSignal(160, f * 160, 8500.0);
        }

        int delayAtPhase1 = -1;
        int delayAtPhase2 = -1;

        for (int f = 0; f < totalFrames; f++) {
            int activeDelayFrames = (f < 70) ? 2 : 5;
            short[] nearMic = new short[160];
            if (f >= activeDelayFrames) {
                short[] delayed = farFrames[f - activeDelayFrames];
                for (int i = 0; i < 160; i++) {
                    double e = 0.45 * delayed[i] + (i >= 8 ? -0.18 * delayed[i - 8] : 0.0);
                    nearMic[i] = TelephonyAudioCodec.clampToShort(Math.round(e));
                }
            }
            AcousticEchoCanceller.AecFrameResult res = aec.processFrame(nearMic, farFrames[f]);
            if (f == 65) {
                delayAtPhase1 = res.getEstimatedBulkDelayMs();
            }
            if (f == 135) {
                delayAtPhase2 = res.getEstimatedBulkDelayMs();
            }
        }

        assertTrue(Math.abs(delayAtPhase1 - 40) <= 20, "Phase 1 bulk delay should track ~40 ms, got " + delayAtPhase1);
        assertTrue(Math.abs(delayAtPhase2 - 100) <= 20, "Phase 2 bulk delay should adapt to ~100 ms, got " + delayAtPhase2);
    }

    private static void testStreamingArbitraryChunkSizePipelineIntegration() {
        AcousticEchoCanceller aec = new AcousticEchoCanceller(AecConfig.defaults());
        // Feed 1000 bytes (not a multiple of 320-byte 20 ms frame)
        byte[] farChunk = new byte[1000];
        byte[] nearChunk = new byte[1000];
        aec.feedFarEndReference8kHzPcmLe(farChunk);
        byte[] out16k = aec.processInboundStreamTo16kHzPcmLe(nearChunk);
        // 1000 bytes at 8 kHz contains 3 full 320-byte frames (960 bytes) -> 3 * 640 = 1920 bytes at 16 kHz
        // with 40 bytes buffered for the next call
        assertTrue(out16k.length == 1920, "Expected 1920 bytes (3 frames at 16 kHz), got " + out16k.length);

        // Feed remaining 280 bytes to complete the 4th 320-byte frame
        byte[] nextOut16k = aec.processInboundStreamTo16kHzPcmLe(new byte[280]);
        assertTrue(nextOut16k.length == 640, "Expected 640 bytes once 4th frame completes, got " + nextOut16k.length);
    }

    private static void testConcurrentMultiCallPipelineServiceIsolation() throws Exception {
        final TelephonyAecPipelineService pipeline = new TelephonyAecPipelineService();
        int numConcurrentCalls = 8;
        ExecutorService pool = Executors.newFixedThreadPool(numConcurrentCalls);

        try {
            Callable<Double> callWorker = () -> {
                String callId = "call-" + UUID.randomUUID();
                pipeline.registerCallSession(callId, AecConfig.defaults());
                try {
                    int frames = 70;
                    short[][] far8k = new short[frames][160];
                    double inE = 0.0;
                    double outE = 0.0;
                    for (int f = 0; f < frames; f++) {
                        short[] s24k = generateRichExcitationSignal(480, f * 480, 8000.0);
                        byte[] out8kBytes = pipeline.onOutboundModelAudio24kHz(
                                callId, TelephonyAudioCodec.shortsToPcm16LeBytes(s24k));
                        far8k[f] = TelephonyAudioCodec.pcm16LeBytesToShorts(out8kBytes);

                        short[] echo = new short[160];
                        if (f >= 2) {
                            for (int i = 0; i < 160; i++) {
                                echo[i] = TelephonyAudioCodec.clampToShort(Math.round(0.40 * far8k[f - 2][i]));
                            }
                        }
                        TelephonyAecPipelineService.ProcessedFrame res =
                                pipeline.onInboundCallerAudio8kHz(callId, TelephonyAudioCodec.shortsToPcm16LeBytes(echo));
                        if (f >= 35) {
                            inE += computeEnergy(echo);
                            outE += computeEnergy(res.getAecResult().getCleaned8kHzSamples());
                        }
                    }
                    return 10.0 * Math.log10(inE / Math.max(1.0, outE));
                } finally {
                    pipeline.closeCallSession(callId);
                }
            };

            List<Future<Double>> futures = pool.invokeAll(
                    Collections.nCopies(numConcurrentCalls, callWorker));
            for (Future<Double> fut : futures) {
                double erle = fut.get();
                assertTrue(erle >= 16.0, "Concurrent call session failed ERLE threshold: " + erle + " dB");
            }
            assertTrue(pipeline.getActiveSessionCount() == 0, "All closed sessions must be removed");
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // TEST SIGNAL GENERATORS & ASSERTION HELPERS
    // =========================================================================

    private static short[] generateRichExcitationSignal(int length, int sampleOffset, double amplitude) {
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            int n = sampleOffset + i;
            long seed = (n * 1103515245L + 12345L) & 0x7FFFFFFFL;
            double white = ((seed / (double) 0x3FFFFFFFL) - 1.0);
            double harmonic = 0.45 * Math.sin(2.0 * Math.PI * 380.0 * n / 8000.0)
                    + 0.30 * Math.sin(2.0 * Math.PI * 920.0 * n / 8000.0)
                    + 0.20 * Math.cos(2.0 * Math.PI * 1850.0 * n / 8000.0);
            double envelope = 0.65 + 0.35 * Math.sin(2.0 * Math.PI * 4.0 * n / 8000.0);
            double val = amplitude * envelope * (0.55 * white + 0.45 * harmonic);
            out[i] = TelephonyAudioCodec.clampToShort(Math.round(val));
        }
        return out;
    }

    private static short[] generateCallerVoiceFrame(int length, int sampleOffset, int sampleRate, double amplitude) {
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            int n = sampleOffset + i;
            double f0 = 215.0;
            double v = 0.50 * Math.sin(2.0 * Math.PI * f0 * n / sampleRate)
                    + 0.30 * Math.sin(2.0 * Math.PI * (2 * f0) * n / sampleRate + 0.5)
                    + 0.20 * Math.sin(2.0 * Math.PI * (5 * f0) * n / sampleRate + 1.1);
            out[i] = TelephonyAudioCodec.clampToShort(Math.round(amplitude * v));
        }
        return out;
    }

    private static short[] generateMultiToneSignal(int length, int sampleOffset, int sampleRate, double amplitude) {
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            int n = sampleOffset + i;
            double v = 0.6 * Math.sin(2.0 * Math.PI * 500.0 * n / sampleRate)
                    + 0.4 * Math.sin(2.0 * Math.PI * 1400.0 * n / sampleRate);
            out[i] = TelephonyAudioCodec.clampToShort(Math.round(amplitude * v));
        }
        return out;
    }

    private static double computeEnergy(short[] samples) {
        double sum = 0.0;
        for (short s : samples) {
            sum += (double) s * s;
        }
        return sum;
    }

    private static double computeRms(short[] samples) {
        if (samples.length == 0) {
            return 0.0;
        }
        return Math.sqrt(computeEnergy(samples) / samples.length);
    }

    private static double computeSnrDb(short[] reference, short[] test) {
        double sigE = 0.0;
        double errE = 0.0;
        for (int i = 0; i < reference.length; i++) {
            sigE += (double) reference[i] * reference[i];
            double diff = reference[i] - test[i];
            errE += diff * diff;
        }
        return 10.0 * Math.log10(sigE / Math.max(1.0, errE));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(
                    "Expected exception " + expected.getSimpleName() + " but caught " + actual.getClass().getSimpleName(),
                    actual);
        }
        throw new AssertionError("Expected exception " + expected.getSimpleName() + " but nothing was thrown");
    }
}
