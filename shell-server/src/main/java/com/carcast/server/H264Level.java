package com.carcast.server;

import android.media.MediaCodecInfo;

/**
 * Which H.264 level a given size and frame rate needs.
 *
 * Why this exists: {@link H264Encoder} used to ask for Baseline **and a hard-coded level 3.2**, with a
 * comment saying 3.2 covers 720p60. It does — and nothing else we offer. Level 3.2 allows 5120 macroblocks
 * per frame and 216000 per second, so 900p (5700 MBs) is already over the frame limit and 1080p30 (244800
 * MB/s) is over both. The car's quality sheet has been able to ask for those sizes all along.
 *
 * What went wrong when it did is worse than a rejected level. {@link H264Encoder#open} treats a rejected
 * configure as "the vendor did not like something" and falls back to the **vendor's own profile**, which on
 * every phone we have is High — and a car on the plain-HTTP path decodes with h264bsd (WASM), which reads
 * Baseline only. The picture would simply stop, with the log saying the encoder had been rebuilt.
 *
 * So the level is computed, not assumed. The numbers are Annex A, Table A-1: MaxFS is macroblocks per
 * frame, MaxMBPS macroblocks per second. We pick the lowest level that fits both, because the level is a
 * promise to the decoder about what it will have to handle — promising more than needed is not free on a
 * decoder that reserves buffers from it.
 */
final class H264Level {

    private H264Level() {
    }

    /** One row of Annex A Table A-1: the level constant, macroblocks per frame, macroblocks per second. */
    private static final int[][] LEVELS = {
            {MediaCodecInfo.CodecProfileLevel.AVCLevel31, 3600, 108_000},   // 720p30
            {MediaCodecInfo.CodecProfileLevel.AVCLevel32, 5120, 216_000},   // 720p60
            {MediaCodecInfo.CodecProfileLevel.AVCLevel4, 8192, 245_760},    // 900p30, 1080p30 (just)
            {MediaCodecInfo.CodecProfileLevel.AVCLevel41, 8192, 245_760},
            {MediaCodecInfo.CodecProfileLevel.AVCLevel42, 8704, 522_240},   // 900p60, 1080p60
            {MediaCodecInfo.CodecProfileLevel.AVCLevel5, 22_080, 589_824},
            {MediaCodecInfo.CodecProfileLevel.AVCLevel51, 36_864, 983_040},
            {MediaCodecInfo.CodecProfileLevel.AVCLevel52, 36_864, 2_073_600},
    };

    /** Macroblocks in one frame: 16x16 each, partial ones count. */
    static int macroblocks(int width, int height) {
        return ((width + 15) / 16) * ((height + 15) / 16);
    }

    /**
     * The lowest level that can carry [width]x[height] at [fps], or the highest we know when nothing can
     * (the encoder then rejects it and {@link H264Encoder} falls back — better than claiming a level the
     * stream exceeds, which is what makes a decoder give up halfway through).
     */
    static int levelFor(int width, int height, int fps) {
        int mbs = macroblocks(width, height);
        long mbps = (long) mbs * Math.max(1, fps);
        for (int[] level : LEVELS) {
            if (mbs <= level[1] && mbps <= level[2]) {
                return level[0];
            }
        }
        return LEVELS[LEVELS.length - 1][0];
    }

    /** "4.2" for the log line, so a rejected configure can be read without a constant table. */
    static String describe(int level) {
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel31) return "3.1";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel32) return "3.2";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel4) return "4.0";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel41) return "4.1";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel42) return "4.2";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel5) return "5.0";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel51) return "5.1";
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel52) return "5.2";
        return "0x" + Integer.toHexString(level);
    }
}
