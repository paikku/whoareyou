package com.carcast.server;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which video encoders this phone has, by codec — the phone's half of "could we stream something other
 * than H.264?".
 *
 * <p>Why: the car's diag page can ask its browser whether it would decode HEVC or AV1 in hardware
 * ({@code isConfigSupported}), but that answer is worthless without knowing whether the phone could produce
 * such a stream in the first place, and at what cost. This lists, per codec, the encoder names Android
 * offers and whether each is hardware-accelerated, so the two halves of the question sit next to each other
 * in the same status/report. It is a catalogue, not a switch: the pipeline still speaks H.264 only
 * ({@link H264Encoder}, the fMP4 muxer's avcC, the car's avcC parsing) and this changes none of that.
 *
 * <p>Computed once — enumerating codecs costs a binder round trip per codec and the list does not change
 * while we run.
 */
final class Encoders {
    /** codec → MIME; the keys are what the status shows. */
    private static final String[][] CODECS = {
            {"avc", MediaFormat.MIMETYPE_VIDEO_AVC},
            {"hevc", MediaFormat.MIMETYPE_VIDEO_HEVC},
            {"av1", MediaFormat.MIMETYPE_VIDEO_AV1},
            {"vp9", MediaFormat.MIMETYPE_VIDEO_VP9},
    };
    private static volatile Map<String, Object> cached;

    private Encoders() {
    }

    /**
     * {@code {avc: ["c2.qti.avc.encoder", "c2.android.avc.encoder (sw)"], hevc: [...], av1: [...], vp9: [...]}}.
     * Hardware encoders first, software ones marked. An empty list means Android offers none for that codec.
     * Never throws: a phone whose codec list cannot be read gets {@code {error: "..."}} instead of a crash
     * in {@code /api/status}.
     */
    static Map<String, Object> list() {
        Map<String, Object> m = cached;
        if (m != null) {
            return m;
        }
        m = new LinkedHashMap<>();
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (String[] codec : CODECS) {
                List<String> hw = new ArrayList<>();
                List<String> sw = new ArrayList<>();
                for (MediaCodecInfo info : infos) {
                    if (!info.isEncoder() || !supports(info, codec[1])) {
                        continue;
                    }
                    if (info.isHardwareAccelerated()) {
                        hw.add(info.getName());
                    } else {
                        sw.add(info.getName() + " (sw)");
                    }
                }
                List<String> all = new ArrayList<>(hw);
                all.addAll(sw);
                m.put(codec[0], all);
            }
        } catch (Throwable t) {
            m.put("error", String.valueOf(t));
        }
        cached = m;
        return m;
    }

    private static boolean supports(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }
}
