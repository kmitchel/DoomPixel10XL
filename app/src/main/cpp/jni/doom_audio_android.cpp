#include <aaudio/AAudio.h>
#include <android/log.h>
#include <pthread.h>
#include <cmath>
#include <cstdint>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <vector>

extern "C" {
#include "config.h"
#include "doomfeatures.h"
#include "doomtype.h"
#include "i_sound.h"
#include "i_system.h"
#include "m_misc.h"
#include "w_wad.h"
#include "z_zone.h"
}

#define LOG_TAG "DoomAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Variables referenced by I_BindSoundVariables in i_sound.c when FEATURE_SOUND is enabled
extern "C" {
int use_libsamplerate = 0;
float libsamplerate_scale = 0.65f;
}

static constexpr int kOutputSampleRate = 44100;
static constexpr int kMaxSfxChannels = 16;
static constexpr int kNumMusChannels = 16;
static constexpr int kMaxPolyVoices = 20;
// DOOM MUS format runs at 140 ticks per second: 44100 / 140 = 315 frames per MUS tick
static constexpr int kSamplesPerMusTick = kOutputSampleRate / 140;

struct CachedSfx {
    std::vector<uint8_t> pcm;
    int sampleRate = 11025;
};

struct ActiveSfxChannel {
    bool active = false;
    const uint8_t* data = nullptr;
    uint32_t length = 0;
    uint64_t posFp = 0;   // 16.16 fixed-point sample index
    uint32_t stepFp = 0;  // 16.16 fixed-point step per output frame
    int leftGain = 0;     // 0..256
    int rightGain = 0;    // 0..256
};

// Real-time OPL2/FM-inspired polyphonic MUS synthesizer structures
struct MusSongHandle {
    std::vector<uint8_t> data;
    uint16_t scoreStart = 0;
    uint16_t scoreLen = 0;
};

struct SynthVoice {
    bool active = false;
    bool releasing = false;
    bool isPercussion = false;
    int musChannel = 0;
    int note = 60;
    int velocity = 100;
    int patch = 0;
    float phase = 0.0f;
    float modPhase = 0.0f;
    float baseFreq = 440.0f;
    float env = 0.0f;
    float filterState = 0.0f;
    uint32_t age = 0;
};

static pthread_mutex_t g_audioMutex = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream* g_audioStream = nullptr;
static std::atomic<bool> g_audioPaused{false};
static std::atomic<bool> g_streamNeedsRestart{false};
static boolean g_useSfxPrefix = true;

static ActiveSfxChannel g_sfxChannels[kMaxSfxChannels];

// MUS Sequencer & Synth State
static MusSongHandle* g_currentSong = nullptr;
static bool g_musicPlaying = false;
static bool g_musicPaused = false;
static bool g_musicLooping = true;
static int g_musicVolume = 100; // 0..127
static size_t g_musCursor = 0;
static int g_musDelaySamples = 0;

static uint8_t g_chanLastVelocity[kNumMusChannels];
static uint8_t g_chanVolume[kNumMusChannels];
static uint8_t g_chanPan[kNumMusChannels];
static uint8_t g_chanPatch[kNumMusChannels];
static float g_chanPitchBend[kNumMusChannels];
static SynthVoice g_voices[kMaxPolyVoices];
static uint32_t g_noiseRng = 0x12345678u;

static inline float NextWhiteNoise() {
    g_noiseRng ^= g_noiseRng << 13;
    g_noiseRng ^= g_noiseRng >> 17;
    g_noiseRng ^= g_noiseRng << 5;
    return static_cast<float>(static_cast<int32_t>(g_noiseRng)) * (1.0f / 2147483648.0f);
}

static inline float MidiNoteToFreq(float note) {
    return 440.0f * std::pow(2.0f, (note - 69.0f) * (1.0f / 12.0f));
}

static void ComputeStereoGains(int vol, int sep, int* outLeft, int* outRight) {
    if (vol <= 0) {
        *outLeft = 0;
        *outRight = 0;
        return;
    }
    if (vol > 127) vol = 127;
    if (sep < 0) sep = 0;
    if (sep > 254) sep = 254;

    // Perceptual volume curve:
    // DOOM volume max is 120 (sfxVolume 15 * 8) or 127.
    // At default volume (sfxVolume = 8 => vol = 64), linear attenuation cuts
    // perceived acoustic power down to 25%. A mild sqrt perceptual scale preserves
    // punch and presence for distant enemy fire while respecting the volume slider.
    float vNorm = static_cast<float>(vol) / 120.0f;
    if (vNorm > 1.0f) vNorm = 1.0f;
    float vScale = std::sqrt(vNorm);

    // Map sep (0..254, 128 = center) to normalized pan [-1.0f, +1.0f]
    // sep < 128: sound is to the LEFT
    // sep > 128: sound is to the RIGHT
    // With S_STEREO_SWING = 120, extreme angles reach sep=8 and sep=248 (+/- 120 offset)
    float pan = (static_cast<float>(sep) - 128.0f) / 120.0f;
    if (pan < -1.0f) pan = -1.0f;
    if (pan > 1.0f) pan = 1.0f;

    // Equal-power (constant energy) panning curve:
    // Maintains identical total acoustic energy across all pan angles.
    // At pan = -1 (hard left): theta = 0     => cos = 1, sin = 0
    // At pan =  0 (center):    theta = pi/4  => cos = 0.7071, sin = 0.7071
    // At pan = +1 (hard right): theta = pi/2 => cos = 0, sin = 1
    constexpr float kPiOver4 = 0.785398163f;
    float theta = (pan + 1.0f) * kPiOver4;
    float pL = std::cos(theta);
    float pR = std::sin(theta);

    // Subtle 4% crossfeed floor prevents unnatural ear-plugging feeling on headphones,
    // while providing an ultra-sharp 26 dB separation on phone stereo speakers.
    constexpr float kCrossfeed = 0.04f;
    float gainL = pL * (1.0f - kCrossfeed) + pR * kCrossfeed;
    float gainR = pR * (1.0f - kCrossfeed) + pL * kCrossfeed;

    // In DoomAudioDataCallback: mixL += (interp * c.leftGain * 2)
    // For 8-bit sample interp in [-128..127], gain of 128 gives full 16-bit range (127 * 128 * 2 = 32512).
    int lGain = static_cast<int>(std::round(vScale * gainL * 128.0f));
    int rGain = static_cast<int>(std::round(vScale * gainR * 128.0f));

    if (lGain < 0) lGain = 0;
    else if (lGain > 128) lGain = 128;
    if (rGain < 0) rGain = 0;
    else if (rGain > 128) rGain = 128;

    *outLeft = lGain;
    *outRight = rGain;
}

static void ResetMusChannelStatesLocked() {
    for (int i = 0; i < kNumMusChannels; ++i) {
        g_chanLastVelocity[i] = 100;
        g_chanVolume[i] = 100;
        g_chanPan[i] = 64;
        g_chanPatch[i] = 0;
        g_chanPitchBend[i] = 1.0f;
    }
    for (int i = 0; i < kMaxPolyVoices; ++i) {
        g_voices[i].active = false;
    }
}

static void TriggerMusNoteLocked(int chan, int note, int velocity) {
    bool isPerc = (chan == 15);
    // Retrigger existing voice on same channel+note if present
    int slot = -1;
    for (int i = 0; i < kMaxPolyVoices; ++i) {
        if (g_voices[i].active && !g_voices[i].releasing &&
            g_voices[i].musChannel == chan && g_voices[i].note == note) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        for (int i = 0; i < kMaxPolyVoices; ++i) {
            if (!g_voices[i].active) {
                slot = i;
                break;
            }
        }
    }
    if (slot < 0) {
        // Steal oldest releasing or oldest active voice
        uint32_t bestScore = 0;
        slot = 0;
        for (int i = 0; i < kMaxPolyVoices; ++i) {
            uint32_t score = g_voices[i].age + (g_voices[i].releasing ? 1000000u : 0u);
            if (score >= bestScore) {
                bestScore = score;
                slot = i;
            }
        }
    }

    SynthVoice& v = g_voices[slot];
    v.active = true;
    v.releasing = false;
    v.isPercussion = isPerc;
    v.musChannel = chan;
    v.note = note;
    v.velocity = velocity;
    v.patch = g_chanPatch[chan];
    v.phase = 0.0f;
    v.modPhase = 0.0f;
    v.baseFreq = MidiNoteToFreq(static_cast<float>(note));
    v.env = 0.05f;
    v.filterState = 0.0f;
    v.age = 0;
}

static void ReleaseMusNoteLocked(int chan, int note) {
    for (int i = 0; i < kMaxPolyVoices; ++i) {
        if (g_voices[i].active && !g_voices[i].releasing &&
            g_voices[i].musChannel == chan && g_voices[i].note == note) {
            g_voices[i].releasing = true;
        }
    }
}

static void ReleaseAllChannelNotesLocked(int chan) {
    for (int i = 0; i < kMaxPolyVoices; ++i) {
        if (g_voices[i].active && g_voices[i].musChannel == chan) {
            g_voices[i].releasing = true;
        }
    }
}

static void AdvanceMusSequencerLocked() {
    if (!g_musicPlaying || g_musicPaused || !g_currentSong) {
        return;
    }
    const auto& buf = g_currentSong->data;
    const size_t totalLen = static_cast<size_t>(g_currentSong->scoreStart) + g_currentSong->scoreLen;
    int safetyCount = 0;

    while (g_musDelaySamples <= 0 && safetyCount++ < 512) {
        if (g_musCursor >= totalLen) {
            if (g_musicLooping) {
                g_musCursor = g_currentSong->scoreStart;
                for (int i = 0; i < kMaxPolyVoices; ++i) g_voices[i].releasing = true;
            } else {
                g_musicPlaying = false;
            }
            return;
        }

        uint8_t desc = buf[g_musCursor++];
        int chan = desc & 0x0F;
        int eventType = desc & 0x70;

        switch (eventType) {
            case 0x00: { // Release key
                if (g_musCursor < totalLen) {
                    uint8_t key = buf[g_musCursor++] & 0x7F;
                    ReleaseMusNoteLocked(chan, key);
                }
                break;
            }
            case 0x10: { // Press key
                if (g_musCursor < totalLen) {
                    uint8_t keyByte = buf[g_musCursor++];
                    uint8_t key = keyByte & 0x7F;
                    if (keyByte & 0x80) {
                        if (g_musCursor < totalLen) {
                            g_chanLastVelocity[chan] = buf[g_musCursor++] & 0x7F;
                        }
                    }
                    TriggerMusNoteLocked(chan, key, g_chanLastVelocity[chan]);
                }
                break;
            }
            case 0x20: { // Pitch wheel (0..255, 128 = center, +/- 2 semitones)
                if (g_musCursor < totalLen) {
                    uint8_t wheel = buf[g_musCursor++];
                    float semitones = (static_cast<int>(wheel) - 128) * (2.0f / 128.0f);
                    g_chanPitchBend[chan] = std::pow(2.0f, semitones * (1.0f / 12.0f));
                }
                break;
            }
            case 0x30: { // System event
                if (g_musCursor < totalLen) {
                    uint8_t sysCtrl = buf[g_musCursor++] & 0x7F;
                    if (sysCtrl == 10 || sysCtrl == 11 || sysCtrl == 14) {
                        ReleaseAllChannelNotesLocked(chan);
                    }
                }
                break;
            }
            case 0x40: { // Controller change
                if (g_musCursor + 1 < totalLen) {
                    uint8_t ctrlNum = buf[g_musCursor++] & 0x7F;
                    uint8_t ctrlVal = buf[g_musCursor++] & 0x7F;
                    if (ctrlNum == 0) {
                        g_chanPatch[chan] = ctrlVal;
                    } else if (ctrlNum == 3) {
                        g_chanVolume[chan] = ctrlVal;
                    } else if (ctrlNum == 4) {
                        g_chanPan[chan] = ctrlVal;
                    }
                } else {
                    g_musCursor = totalLen;
                }
                break;
            }
            case 0x60: { // Score end
                if (g_musicLooping) {
                    g_musCursor = g_currentSong->scoreStart;
                    for (int i = 0; i < kMaxPolyVoices; ++i) g_voices[i].releasing = true;
                } else {
                    g_musicPlaying = false;
                }
                return;
            }
            default:
                break;
        }

        // If high bit is set, a variable-length delay follows this event
        if (desc & 0x80) {
            uint32_t delayTicks = 0;
            bool complete = false;
            constexpr uint32_t maxDelayTicks = INT_MAX / kSamplesPerMusTick;
            while (g_musCursor < totalLen) {
                uint8_t b = buf[g_musCursor++];
                if (delayTicks > (maxDelayTicks - (b & 0x7F)) / 128) {
                    g_musicPlaying = false;
                    return;
                }
                delayTicks = (delayTicks << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    complete = true;
                    break;
                }
            }
            if (!complete) {
                g_musicPlaying = false;
                return;
            }
            g_musDelaySamples += static_cast<int>(delayTicks * kSamplesPerMusTick);
        }
    }
}

static inline void SynthesizeOneMusicFrameLocked(float* outLeft, float* outRight) {
    *outLeft = 0.0f;
    *outRight = 0.0f;
    if (!g_musicPlaying || g_musicPaused || g_musicVolume <= 0 || !g_currentSong) {
        return;
    }

    if (g_musDelaySamples <= 0) {
        AdvanceMusSequencerLocked();
    }
    --g_musDelaySamples;

    constexpr float kTwoPi = 6.283185307f;
    constexpr float kInvSampleRate = 1.0f / static_cast<float>(kOutputSampleRate);
    float sumL = 0.0f;
    float sumR = 0.0f;

    for (int i = 0; i < kMaxPolyVoices; ++i) {
        SynthVoice& v = g_voices[i];
        if (!v.active) continue;
        ++v.age;

        float sample = 0.0f;
        if (v.isPercussion) {
            float t = static_cast<float>(v.age) * kInvSampleRate;
            float noise = NextWhiteNoise();
            if (v.note == 35 || v.note == 36) {
                // Kick drum: fast pitch drop + punch
                float freq = 52.0f + 135.0f * std::exp(-t * 36.0f);
                v.phase += freq * kInvSampleRate;
                if (v.phase >= 1.0f) v.phase -= 1.0f;
                float env = std::exp(-t * 14.0f);
                sample = (std::sin(v.phase * kTwoPi) + 0.25f * noise * std::exp(-t * 60.0f)) * env * 1.35f;
                if (env < 0.003f) v.active = false;
            } else if (v.note == 38 || v.note == 40) {
                // Snare drum: tonal body + crispy noise snare
                float freq = 180.0f * std::exp(-t * 12.0f);
                v.phase += freq * kInvSampleRate;
                if (v.phase >= 1.0f) v.phase -= 1.0f;
                float envNoise = std::exp(-t * 18.0f);
                float envTone = std::exp(-t * 28.0f);
                sample = (0.45f * std::sin(v.phase * kTwoPi) * envTone + 0.85f * noise * envNoise);
                if (envNoise < 0.003f) v.active = false;
            } else if (v.note == 42 || v.note == 44 || v.note == 46 || v.note == 49 || v.note == 51 || v.note == 57) {
                // Hi-hats and crash/ride cymbals: high-passed metallic noise
                float decay = (v.note == 42 || v.note == 44) ? 42.0f : 7.5f;
                float env = std::exp(-t * decay);
                float hp = noise - v.filterState;
                v.filterState = noise * 0.75f;
                sample = hp * env * 0.70f;
                if (env < 0.003f) v.active = false;
            } else {
                // Toms / other percussion
                float freq = v.baseFreq * std::exp(-t * 8.0f);
                v.phase += freq * kInvSampleRate;
                if (v.phase >= 1.0f) v.phase -= 1.0f;
                float env = std::exp(-t * 13.0f);
                sample = (0.8f * std::sin(v.phase * kTwoPi) + 0.2f * noise) * env;
                if (env < 0.003f) v.active = false;
            }
        } else {
            // Melodic OPL2/FM-inspired voice
            float freq = v.baseFreq * g_chanPitchBend[v.musChannel];
            float dt = freq * kInvSampleRate;
            v.phase += dt;
            if (v.phase >= 1.0f) v.phase -= 1.0f;

            // Envelope update
            if (!v.releasing) {
                if (v.age < 180) {
                    v.env += (1.0f - v.env) * 0.035f;
                } else {
                    // Gently decay towards sustain level (0.65)
                    v.env += (0.65f - v.env) * 0.00018f;
                }
            } else {
                v.env *= 0.9965f;
                if (v.env < 0.002f) {
                    v.active = false;
                    continue;
                }
            }

            float p = v.phase;
            float raw = 0.0f;
            if (v.patch >= 24 && v.patch <= 31) {
                // Overdriven / Distorted Guitars (E1M1, E1M2, etc.): FM + rich harmonics
                float mod = std::sin(p * 2.0f * kTwoPi) * 0.85f;
                float carrier = std::sin(p * kTwoPi + mod);
                float sq = (p < 0.5f) ? 1.0f : -1.0f;
                raw = 0.65f * carrier + 0.35f * sq;
            } else if (v.patch >= 32 && v.patch <= 39) {
                // Bass (Slap / Fingered / Picked): punchy fundamental + 2nd harmonic
                float mod = std::sin(p * kTwoPi) * (0.9f * v.env);
                raw = std::sin(p * kTwoPi + mod) + 0.35f * std::sin(p * 2.0f * kTwoPi);
            } else {
                // Brass / Synth / Strings / Organ: warm saw + pulse hybrid
                float saw = 2.0f * p - 1.0f;
                float pulse = (p < 0.35f) ? 0.8f : -0.8f;
                raw = 0.55f * saw + 0.45f * pulse;
            }

            // One-pole warm low-pass filter so highs are smooth and never harsh
            v.filterState += 0.24f * (raw - v.filterState);
            sample = v.filterState * v.env;
        }

        float chanVol = static_cast<float>(g_chanVolume[v.musChannel]) * (1.0f / 127.0f);
        float noteVel = static_cast<float>(v.velocity) * (1.0f / 127.0f);
        float amp = sample * chanVol * noteVel;

        float pan = static_cast<float>(g_chanPan[v.musChannel]) * (1.0f / 127.0f);
        sumL += amp * (1.0f - 0.5f * pan);
        sumR += amp * (0.5f + 0.5f * pan);
    }

    float masterMusicScale = (static_cast<float>(g_musicVolume) * (1.0f / 127.0f)) * 5200.0f;
    *outLeft = sumL * masterMusicScale;
    *outRight = sumR * masterMusicScale;
}

static aaudio_data_callback_result_t DoomAudioDataCallback(
    AAudioStream* /*stream*/,
    void* /*userData*/,
    void* audioData,
    int32_t numFrames)
{
    auto* out = static_cast<int16_t*>(audioData);

    if (g_audioPaused.load(std::memory_order_relaxed)) {
        std::memset(out, 0, sizeof(int16_t) * 2 * numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    if (pthread_mutex_trylock(&g_audioMutex) != 0) {
        std::memset(out, 0, sizeof(int16_t) * 2 * numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        float musL = 0.0f;
        float musR = 0.0f;
        SynthesizeOneMusicFrameLocked(&musL, &musR);

        int32_t mixL = static_cast<int32_t>(musL);
        int32_t mixR = static_cast<int32_t>(musR);

        // Mix all active digital sound effect channels
        for (int ch = 0; ch < kMaxSfxChannels; ++ch) {
            ActiveSfxChannel& c = g_sfxChannels[ch];
            if (!c.active || !c.data || c.length == 0) {
                continue;
            }

            uint32_t idx = static_cast<uint32_t>(c.posFp >> 16);
            if (idx >= c.length) {
                c.active = false;
                continue;
            }

            // Linear interpolation between adjacent 8-bit unsigned samples
            uint32_t frac = static_cast<uint32_t>(c.posFp & 0xFFFFu);
            int32_t s0 = static_cast<int32_t>(c.data[idx]) - 128;
            int32_t s1 = (idx + 1 < c.length) ? (static_cast<int32_t>(c.data[idx + 1]) - 128) : s0;
            int32_t interp = ((s0 * static_cast<int32_t>(65536u - frac)) + (s1 * static_cast<int32_t>(frac))) >> 16;

            // Scale 8-bit signed (-128..127) with channel stereo gain into 16-bit range
            mixL += (interp * c.leftGain * 2);
            mixR += (interp * c.rightGain * 2);

            c.posFp += c.stepFp;
            if ((c.posFp >> 16) >= c.length) {
                c.active = false;
            }
        }

        // Smooth soft-knee saturation prevents harsh digital clipping when multiple loud sounds overlap
        auto softLimit = [](int32_t val) -> int16_t {
            constexpr int32_t kLinearThreshold = 24576;
            constexpr int32_t kMaxAmp = 32767;
            constexpr int32_t kHeadroom = kMaxAmp - kLinearThreshold; // 8191
            if (val > kLinearThreshold) {
                int32_t excess = val - kLinearThreshold;
                val = kLinearThreshold + (excess * kHeadroom) / (excess + kHeadroom);
                if (val > kMaxAmp) val = kMaxAmp;
            } else if (val < -kLinearThreshold) {
                int32_t excess = -val - kLinearThreshold;
                val = -(kLinearThreshold + (excess * kHeadroom) / (excess + kHeadroom));
                if (val < -32768) val = -32768;
            }
            return static_cast<int16_t>(val);
        };

        out[frame * 2] = softLimit(mixL);
        out[frame * 2 + 1] = softLimit(mixR);
    }

    pthread_mutex_unlock(&g_audioMutex);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void DoomAudioErrorCallback(
    AAudioStream* /*stream*/,
    void* /*userData*/,
    aaudio_result_t error)
{
    LOGE("AAudio stream error (%d), flagging stream restart", static_cast<int>(error));
    g_streamNeedsRestart.store(true, std::memory_order_relaxed);
}

static bool StartAAudioStream() {
    if (g_audioStream != nullptr) {
        AAudioStream_requestStop(g_audioStream);
        AAudioStream_close(g_audioStream);
        g_audioStream = nullptr;
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK || !builder) {
        LOGE("AAudio_createStreamBuilder failed: %d", static_cast<int>(res));
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, kOutputSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);
    AAudioStreamBuilder_setDataCallback(builder, DoomAudioDataCallback, nullptr);
    AAudioStreamBuilder_setErrorCallback(builder, DoomAudioErrorCallback, nullptr);

    res = AAudioStreamBuilder_openStream(builder, &g_audioStream);
    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK || !g_audioStream) {
        LOGE("AAudioStreamBuilder_openStream failed: %d", static_cast<int>(res));
        g_audioStream = nullptr;
        return false;
    }

    res = AAudioStream_requestStart(g_audioStream);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %d", static_cast<int>(res));
        AAudioStream_close(g_audioStream);
        g_audioStream = nullptr;
        return false;
    }

    LOGI("AAudio low-latency stereo stream started at %d Hz", kOutputSampleRate);
    return true;
}

extern "C" void Android_SetAudioPaused(bool paused) {
    g_audioPaused.store(paused, std::memory_order_relaxed);
}

// ============================================================================
// DOOM Sound Effects Module (DG_sound_module)
// ============================================================================

static snddevice_t g_androidSfxDevices[] = {
    SNDDEVICE_SB,
    SNDDEVICE_PAS,
    SNDDEVICE_GUS,
    SNDDEVICE_WAVEBLASTER,
    SNDDEVICE_SOUNDCANVAS,
    SNDDEVICE_AWE32,
    SNDDEVICE_PCSPEAKER,
    SNDDEVICE_ADLIB,
};

static boolean DG_Sound_Init(boolean use_sfx_prefix) {
    g_useSfxPrefix = use_sfx_prefix;
    pthread_mutex_lock(&g_audioMutex);
    for (int i = 0; i < kMaxSfxChannels; ++i) {
        g_sfxChannels[i].active = false;
    }
    ResetMusChannelStatesLocked();
    pthread_mutex_unlock(&g_audioMutex);

    return StartAAudioStream() ? true : false;
}

static void DG_Sound_Shutdown(void) {
    if (g_audioStream != nullptr) {
        AAudioStream_requestStop(g_audioStream);
        AAudioStream_close(g_audioStream);
        g_audioStream = nullptr;
    }
}

static int DG_Sound_GetSfxLumpNum(sfxinfo_t* sfx) {
    char namebuf[16];
    if (sfx->link != nullptr) {
        sfx = sfx->link;
    }
    if (g_useSfxPrefix) {
        M_snprintf(namebuf, sizeof(namebuf), "ds%s", sfx->name);
    } else {
        M_StringCopy(namebuf, sfx->name, sizeof(namebuf));
    }
    int lump = W_CheckNumForName(namebuf);
    if (lump < 0) {
        lump = W_GetNumForName(const_cast<char*>("dspistol"));
    }
    return lump;
}

static void DG_Sound_Update(void) {
    if (g_streamNeedsRestart.exchange(false, std::memory_order_relaxed)) {
        StartAAudioStream();
    }
}

static void DG_Sound_UpdateSoundParams(int channel, int vol, int sep) {
    if (channel < 0 || channel >= kMaxSfxChannels) {
        return;
    }
    int leftGain = 0;
    int rightGain = 0;
    ComputeStereoGains(vol, sep, &leftGain, &rightGain);

    pthread_mutex_lock(&g_audioMutex);
    g_sfxChannels[channel].leftGain = leftGain;
    g_sfxChannels[channel].rightGain = rightGain;
    pthread_mutex_unlock(&g_audioMutex);
}

static CachedSfx* GetOrCacheSfx(sfxinfo_t* sfxinfo) {
    if (sfxinfo->driver_data != nullptr) {
        return static_cast<CachedSfx*>(sfxinfo->driver_data);
    }

    int lumpnum = sfxinfo->lumpnum;
    if (lumpnum < 0) {
        lumpnum = DG_Sound_GetSfxLumpNum(sfxinfo);
        sfxinfo->lumpnum = lumpnum;
    }

    int lumpLen = W_LumpLength(lumpnum);
    if (lumpLen <= 8) {
        return nullptr;
    }

    auto* rawData = static_cast<const uint8_t*>(W_CacheLumpNum(lumpnum, PU_STATIC));
    if (!rawData) {
        return nullptr;
    }

    uint16_t format = static_cast<uint16_t>(rawData[0] | (rawData[1] << 8));
    uint16_t sampleRate = static_cast<uint16_t>(rawData[2] | (rawData[3] << 8));
    uint32_t numSamples = static_cast<uint32_t>(rawData[4]) |
                          (static_cast<uint32_t>(rawData[5]) << 8) |
                          (static_cast<uint32_t>(rawData[6]) << 16) |
                          (static_cast<uint32_t>(rawData[7]) << 24);

    if (format != 3) {
        W_ReleaseLumpNum(lumpnum);
        return nullptr;
    }
    if (sampleRate < 4000 || sampleRate > 48000) {
        sampleRate = 11025;
    }

    uint32_t availSamples = static_cast<uint32_t>(lumpLen - 8);
    if (numSamples > availSamples) {
        numSamples = availSamples;
    }

    const uint8_t* pcmStart = rawData + 8;
    uint32_t pcmLen = numSamples;
    // Strip DMX 16-byte lead-in and 16-byte lead-out padding if present
    if (pcmLen > 32) {
        pcmStart += 16;
        pcmLen -= 32;
    }

    auto* cached = new CachedSfx();
    cached->sampleRate = sampleRate;
    cached->pcm.assign(pcmStart, pcmStart + pcmLen);
    sfxinfo->driver_data = cached;

    W_ReleaseLumpNum(lumpnum);
    return cached;
}

static int DG_Sound_StartSound(sfxinfo_t* sfxinfo, int channel, int vol, int sep) {
    if (!sfxinfo || channel < 0 || channel >= kMaxSfxChannels) {
        return -1;
    }

    CachedSfx* cached = GetOrCacheSfx(sfxinfo);
    if (!cached || cached->pcm.empty()) {
        return -1;
    }

    int leftGain = 0;
    int rightGain = 0;
    ComputeStereoGains(vol, sep, &leftGain, &rightGain);

    uint32_t stepFp = static_cast<uint32_t>((static_cast<uint64_t>(cached->sampleRate) << 16) / kOutputSampleRate);

    pthread_mutex_lock(&g_audioMutex);
    ActiveSfxChannel& c = g_sfxChannels[channel];
    c.data = cached->pcm.data();
    c.length = static_cast<uint32_t>(cached->pcm.size());
    c.posFp = 0;
    c.stepFp = stepFp;
    c.leftGain = leftGain;
    c.rightGain = rightGain;
    c.active = true;
    pthread_mutex_unlock(&g_audioMutex);

    return channel;
}

static void DG_Sound_StopSound(int channel) {
    if (channel < 0 || channel >= kMaxSfxChannels) {
        return;
    }
    pthread_mutex_lock(&g_audioMutex);
    g_sfxChannels[channel].active = false;
    pthread_mutex_unlock(&g_audioMutex);
}

static boolean DG_Sound_SoundIsPlaying(int channel) {
    if (channel < 0 || channel >= kMaxSfxChannels) {
        return false;
    }
    pthread_mutex_lock(&g_audioMutex);
    bool playing = g_sfxChannels[channel].active;
    pthread_mutex_unlock(&g_audioMutex);
    return playing ? true : false;
}

static void DG_Sound_CacheSounds(sfxinfo_t* sounds, int num_sounds) {
    for (int i = 1; i < num_sounds; ++i) {
        if (sounds[i].link == nullptr) {
            sounds[i].lumpnum = DG_Sound_GetSfxLumpNum(&sounds[i]);
        }
    }
}

// ============================================================================
// DOOM Music Module (DG_music_module)
// ============================================================================

static snddevice_t g_androidMusicDevices[] = {
    SNDDEVICE_SB,
    SNDDEVICE_ADLIB,
    SNDDEVICE_GUS,
    SNDDEVICE_WAVEBLASTER,
    SNDDEVICE_SOUNDCANVAS,
    SNDDEVICE_GENMIDI,
    SNDDEVICE_AWE32,
};

static boolean DG_Music_Init(void) {
    pthread_mutex_lock(&g_audioMutex);
    ResetMusChannelStatesLocked();
    g_musicPlaying = false;
    g_musicPaused = false;
    pthread_mutex_unlock(&g_audioMutex);
    return true;
}

static void DG_Music_Shutdown(void) {
    pthread_mutex_lock(&g_audioMutex);
    g_musicPlaying = false;
    g_currentSong = nullptr;
    ResetMusChannelStatesLocked();
    pthread_mutex_unlock(&g_audioMutex);
}

static void DG_Music_SetMusicVolume(int volume) {
    if (volume < 0) volume = 0;
    if (volume > 127) volume = 127;
    pthread_mutex_lock(&g_audioMutex);
    g_musicVolume = volume;
    pthread_mutex_unlock(&g_audioMutex);
}

static void DG_Music_PauseMusic(void) {
    pthread_mutex_lock(&g_audioMutex);
    g_musicPaused = true;
    pthread_mutex_unlock(&g_audioMutex);
}

static void DG_Music_ResumeMusic(void) {
    pthread_mutex_lock(&g_audioMutex);
    g_musicPaused = false;
    pthread_mutex_unlock(&g_audioMutex);
}

static void* DG_Music_RegisterSong(void* data, int len) {
    if (!data || len < 16) {
        return nullptr;
    }
    const auto* bytes = static_cast<const uint8_t*>(data);
    if (bytes[0] != 'M' || bytes[1] != 'U' || bytes[2] != 'S' || bytes[3] != 0x1A) {
        return nullptr;
    }

    uint16_t scoreLen = static_cast<uint16_t>(bytes[4] | (bytes[5] << 8));
    uint16_t scoreStart = static_cast<uint16_t>(bytes[6] | (bytes[7] << 8));
    if (scoreStart < 16 || scoreLen == 0 ||
        static_cast<size_t>(scoreStart) + scoreLen > static_cast<size_t>(len)) {
        return nullptr;
    }

    auto* song = new MusSongHandle();
    song->data.assign(bytes, bytes + len);
    song->scoreStart = scoreStart;
    song->scoreLen = scoreLen;
    return song;
}

static void DG_Music_UnRegisterSong(void* handle) {
    if (!handle) return;
    auto* song = static_cast<MusSongHandle*>(handle);
    pthread_mutex_lock(&g_audioMutex);
    if (g_currentSong == song) {
        g_musicPlaying = false;
        g_currentSong = nullptr;
        ResetMusChannelStatesLocked();
    }
    pthread_mutex_unlock(&g_audioMutex);
    delete song;
}

static void DG_Music_PlaySong(void* handle, boolean looping) {
    if (!handle) return;
    auto* song = static_cast<MusSongHandle*>(handle);
    pthread_mutex_lock(&g_audioMutex);
    ResetMusChannelStatesLocked();
    g_currentSong = song;
    g_musCursor = song->scoreStart;
    g_musDelaySamples = 0;
    g_musicLooping = (looping != 0);
    g_musicPaused = false;
    g_musicPlaying = true;
    pthread_mutex_unlock(&g_audioMutex);
}

static void DG_Music_StopSong(void) {
    pthread_mutex_lock(&g_audioMutex);
    g_musicPlaying = false;
    ResetMusChannelStatesLocked();
    pthread_mutex_unlock(&g_audioMutex);
}

static boolean DG_Music_MusicIsPlaying(void) {
    pthread_mutex_lock(&g_audioMutex);
    bool playing = g_musicPlaying && !g_musicPaused;
    pthread_mutex_unlock(&g_audioMutex);
    return playing ? true : false;
}

static void DG_Music_Poll(void) {
    // Driven sample-accurately in the AAudio real-time callback
}

extern "C" {

sound_module_t DG_sound_module = {
    g_androidSfxDevices,
    static_cast<int>(sizeof(g_androidSfxDevices) / sizeof(g_androidSfxDevices[0])),
    DG_Sound_Init,
    DG_Sound_Shutdown,
    DG_Sound_GetSfxLumpNum,
    DG_Sound_Update,
    DG_Sound_UpdateSoundParams,
    DG_Sound_StartSound,
    DG_Sound_StopSound,
    DG_Sound_SoundIsPlaying,
    DG_Sound_CacheSounds,
};

music_module_t DG_music_module = {
    g_androidMusicDevices,
    static_cast<int>(sizeof(g_androidMusicDevices) / sizeof(g_androidMusicDevices[0])),
    DG_Music_Init,
    DG_Music_Shutdown,
    DG_Music_SetMusicVolume,
    DG_Music_PauseMusic,
    DG_Music_ResumeMusic,
    DG_Music_RegisterSong,
    DG_Music_UnRegisterSong,
    DG_Music_PlaySong,
    DG_Music_StopSong,
    DG_Music_MusicIsPlaying,
    DG_Music_Poll,
};

} // extern "C"
