#pragma once
#include <cstdint>
// Host-test stand-ins: these tests exercise the MUS parser, not an audio device.
struct AAudioStream {};
struct AAudioStreamBuilder {};
using aaudio_result_t = int32_t;
using aaudio_data_callback_result_t = int32_t;
constexpr int AAUDIO_OK = 0;
constexpr int AAUDIO_CALLBACK_RESULT_CONTINUE = 0;
constexpr int AAUDIO_DIRECTION_OUTPUT = 0;
constexpr int AAUDIO_FORMAT_PCM_I16 = 0;
constexpr int AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 0;
constexpr int AAUDIO_SHARING_MODE_SHARED = 0;
constexpr int AAUDIO_USAGE_GAME = 0;
constexpr int AAUDIO_CONTENT_TYPE_SONIFICATION = 0;
inline int AAudio_createStreamBuilder(AAudioStreamBuilder**) { return -1; }
inline int AAudioStreamBuilder_openStream(AAudioStreamBuilder*, AAudioStream**) { return -1; }
inline void AAudioStreamBuilder_delete(AAudioStreamBuilder*) {}
inline int AAudioStream_requestStart(AAudioStream*) { return -1; }
inline int AAudioStream_requestStop(AAudioStream*) { return 0; }
inline int AAudioStream_close(AAudioStream*) { return 0; }
inline void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setFormat(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setUsage(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setContentType(AAudioStreamBuilder*, int) {}
inline void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder*,
    aaudio_data_callback_result_t (*)(AAudioStream*, void*, void*, int32_t), void*) {}
inline void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder*,
    void (*)(AAudioStream*, void*, aaudio_result_t), void*) {}
