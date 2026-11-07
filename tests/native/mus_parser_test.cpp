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
}
