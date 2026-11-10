#include <cassert>
#include <cstdio>
#include <initializer_list>
#ifndef DOOM_AUDIO_SOURCE
#define DOOM_AUDIO_SOURCE "../../app/src/main/cpp/jni/doom_audio_android.cpp"
#endif
#include DOOM_AUDIO_SOURCE

static std::vector<uint8_t> songBytes(std::initializer_list<uint8_t> score) {
    std::vector<uint8_t> data(16, 0);
    data[0] = 'M'; data[1] = 'U'; data[2] = 'S'; data[3] = 0x1a;
    data[4] = static_cast<uint8_t>(score.size());
    data[6] = 16;
    data.insert(data.end(), score);
    return data;
}

static void* registerSong(std::vector<uint8_t>& data) {
    return DG_Music_RegisterSong(data.data(), static_cast<int>(data.size()));
}

int main() {
    // A note followed by a one-tick delay and end-of-score remains playable.
    auto valid = songBytes({0x90, 60, 1, 0x60});
    void* handle = registerSong(valid);
    assert(handle);
    DG_Music_PlaySong(handle, false);
    AdvanceMusSequencerLocked();
    assert(g_musicPlaying && g_musDelaySamples == kSamplesPerMusTick);
    assert(g_voices[0].active && g_voices[0].note == 60);
    g_musDelaySamples = 0;
    AdvanceMusSequencerLocked();
    assert(!g_musicPlaying);
    DG_Music_UnRegisterSong(handle);

    // A declared score cannot extend past EOF, overlap its header, or be empty.
    auto invalid = valid;
    invalid[4] = 100;
    assert(!registerSong(invalid));
    invalid = valid; invalid[6] = 4;
    assert(!registerSong(invalid));
    invalid = valid; invalid[4] = 0;
    assert(!registerSong(invalid));

    // Bytes beyond the declared score must never become note events.
    auto trailing = songBytes({0x00, 60});
    trailing.insert(trailing.end(), {0x10, 72, 0x60});
    handle = registerSong(trailing);
    assert(handle);
    DG_Music_PlaySong(handle, false);
    AdvanceMusSequencerLocked();
    assert(!g_musicPlaying);
    for (const auto& voice : g_voices) assert(!voice.active);
    DG_Music_UnRegisterSong(handle);

    // Delay continuation cannot read outside the score or overflow sample time.
    for (auto data : {songBytes({0x80, 60, 0x81}),
                      songBytes({0x80, 60, 0xff, 0xff, 0xff, 0xff, 0x7f})}) {
        handle = registerSong(data);
        assert(handle);
        DG_Music_PlaySong(handle, true);
        AdvanceMusSequencerLocked();
        assert(!g_musicPlaying && g_musDelaySamples == 0);
        DG_Music_UnRegisterSong(handle);
    }
    std::puts("MUS parser regression tests passed");

    // =========================================================================
    // Spatial Audio & Stereo Panning Regression Tests
    // =========================================================================
    {
        int left = 0, right = 0;

        // 1. Zero volume must yield silence in both channels
        ComputeStereoGains(0, 128, &left, &right);
        assert(left == 0 && right == 0);

        // 2. Dead center (sep = 128) must have perfectly balanced gains
        ComputeStereoGains(120, 128, &left, &right);
        assert(left == right);
        assert(left > 0);

        // 3. Enemy firing hard left (sep = 8): left ear dominant, right ear attenuated
        int hardLeftL = 0, hardLeftR = 0;
        ComputeStereoGains(120, 8, &hardLeftL, &hardLeftR);
        assert(hardLeftL > 100);
        assert(hardLeftR < 10);
        assert(hardLeftL > hardLeftR * 15); // >= 24 dB directional separation

        // 4. Enemy firing hard right (sep = 248): right ear dominant, left ear attenuated
        int hardRightL = 0, hardRightR = 0;
        ComputeStereoGains(120, 248, &hardRightL, &hardRightR);
        assert(hardRightR > 100);
        assert(hardRightL < 10);
        assert(hardRightR > hardRightL * 15);

        // 5. Perfect stereo symmetry between left and right sound sources
        assert(hardLeftL == hardRightR);
        assert(hardLeftR == hardRightL);

        // 6. Monotonic spatial graduation from left to center
        int midLeftL = 0, midLeftR = 0;
        ComputeStereoGains(120, 68, &midLeftL, &midLeftR);
        assert(midLeftL > left);  // More left than center
        assert(midLeftR < right); // Less right than center
        assert(hardLeftL > midLeftL);
        assert(hardLeftR < midLeftR);

        // 7. Perceptual volume scaling provides punch at default volume (vol = 64)
        int defCenterL = 0, defCenterR = 0;
        ComputeStereoGains(64, 128, &defCenterL, &defCenterR);
        assert(defCenterL == defCenterR);
        assert(defCenterL >= 60); // Punchy presence against background music

        // 8. Distant enemy fire retention
        int distL = 0, distR = 0;
        ComputeStereoGains(35, 8, &distL, &distR);
        assert(distL > distR * 10);
        assert(distL > 40); // Clearly audible across room
    }
    std::puts("Spatial audio & stereo panning regression tests passed");
}
