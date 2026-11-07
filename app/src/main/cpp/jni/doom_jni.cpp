#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <atomic>
#include <vector>

#include "doomgeneric.h"
#include "doomgeneric_android.h"
#include "doomkeys.h"

#define LOG_TAG "DoomJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_bridgeClass = nullptr;
static jmethodID g_onHapticMethod = nullptr;
static jmethodID g_onQuitMethod = nullptr;
static jmethodID g_onFirstFrameMethod = nullptr;

static ANativeWindow* g_window = nullptr;
static pthread_mutex_t g_windowMutex = PTHREAD_MUTEX_INITIALIZER;

static pthread_t g_gameThread = 0;
static std::atomic<bool> g_threadStarted{false};
static std::atomic<bool> g_gameRunning{false};
static std::atomic<bool> g_gamePaused{false};
static std::atomic<int> s_engineSessionId{0};
static std::atomic<int> s_dispatchedSessionId{0};
static std::string g_wadPath;
static std::string g_saveDir;

#include <condition_variable>
#include <chrono>

// Virtual analog stick state
static std::atomic<float> g_analogForward{0.0f};
static std::atomic<float> g_analogStrafe{0.0f};
static std::atomic<float> g_analogTurn{0.0f};

extern "C" int g_aiUpscaleMode;
static std::atomic<int> s_pendingUpscaleMode{2};
static int g_currentWindowScale = -1;
static std::atomic<bool> s_pendingConfigSave{false};
static std::mutex s_saveMutex;
static std::condition_variable s_saveCv;
static uint32_t s_saveSequence{0};
static std::atomic<int> s_cachedUiStateMask{1}; // Default bit 0 (MENU) enabled

extern "C" void R_SyncFrameUpscaleMode(void)
{
    g_aiUpscaleMode = s_pendingUpscaleMode.load(std::memory_order_relaxed);
}

// Fast SWAR 50/50 and 75/25 32-bit RGBA color blending
static inline uint32_t Blend50(uint32_t c1, uint32_t c2)
{
    return 0xFF000000u | (((c1 & 0x00FEFEFEu) >> 1) + ((c2 & 0x00FEFEFEu) >> 1) + (c1 & c2 & 0x00010101u));
}

static inline uint32_t Blend75(uint32_t c1, uint32_t c2)
{
    return Blend50(c1, Blend50(c1, c2));
}

static inline int ColorDist(uint32_t c1, uint32_t c2)
{
    if (c1 == c2) return 0;
    int dr = static_cast<int>(c1 & 0xFFu) - static_cast<int>(c2 & 0xFFu);
    int dg = static_cast<int>((c1 >> 8) & 0xFFu) - static_cast<int>((c2 >> 8) & 0xFFu);
    int db = static_cast<int>((c1 >> 16) & 0xFFu) - static_cast<int>((c2 >> 16) & 0xFFu);
    return (dr * dr * 3) + (dg * dg * 4) + (db * db * 2);
}

// Contrast-Adaptive Sharpening (CAS) for mid-tone texture and sprite detail
static inline uint32_t ApplyCAS(uint32_t E, uint32_t B, uint32_t D, uint32_t F, uint32_t H)
{
    uint32_t avg = Blend50(Blend50(B, H), Blend50(D, F));
    if (E == avg) return E;

    int rE = static_cast<int>(E & 0xFFu);
    int gE = static_cast<int>((E >> 8) & 0xFFu);
    int bE = static_cast<int>((E >> 16) & 0xFFu);

    int rA = static_cast<int>(avg & 0xFFu);
    int gA = static_cast<int>((avg >> 8) & 0xFFu);
    int bA = static_cast<int>((avg >> 16) & 0xFFu);

    // Subtle local micro-contrast boost (25% unsharp mask clamped to valid 0..255)
    int rOut = rE + ((rE - rA) >> 2);
    int gOut = gE + ((gE - gA) >> 2);
    int bOut = bE + ((bE - bA) >> 2);

    rOut = (rOut < 0) ? 0 : ((rOut > 255) ? 255 : rOut);
    gOut = (gOut < 0) ? 0 : ((gOut > 255) ? 255 : gOut);
    bOut = (bOut < 0) ? 0 : ((bOut > 255) ? 255 : bOut);

    return 0xFF000000u | (static_cast<uint32_t>(bOut) << 16) |
           (static_cast<uint32_t>(gOut) << 8) | static_cast<uint32_t>(rOut);
}

// Real-time 2x (640x400 -> 1280x800) Edge-Directed xBR + CAS Super-Resolution Pass
static void UpscaleFrameXbrCas2x(const uint32_t* src, int width, int height, uint32_t* dstPixels, int stride)
{
    for (int y = 0; y < height; ++y) {
        const uint32_t* rowPrev = src + ((y > 0) ? (y - 1) : 0) * width;
        const uint32_t* rowCurr = src + y * width;
        const uint32_t* rowNext = src + ((y + 1 < height) ? (y + 1) : (height - 1)) * width;

        uint32_t* dstRow0 = dstPixels + (y * 2) * stride;
        uint32_t* dstRow1 = dstPixels + (y * 2 + 1) * stride;

        for (int x = 0; x < width; ++x) {
            uint32_t E = rowCurr[x];
            uint32_t B = rowPrev[x];
            uint32_t H = rowNext[x];
            int xm1 = (x > 0) ? (x - 1) : 0;
            int xp1 = (x + 1 < width) ? (x + 1) : (width - 1);
            uint32_t D = rowCurr[xm1];
            uint32_t F = rowCurr[xp1];

            // Uniform 5-tap cross fast-path
            if (E == B && E == H && E == D && E == F) {
                dstRow0[x * 2]     = E;
                dstRow0[x * 2 + 1] = E;
                dstRow1[x * 2]     = E;
                dstRow1[x * 2 + 1] = E;
                continue;
            }

            uint32_t A = rowPrev[xm1];
            uint32_t C = rowPrev[xp1];
            uint32_t G = rowNext[xm1];
            uint32_t I = rowNext[xp1];

            uint32_t Es = ApplyCAS(E, B, D, F, H);
            uint32_t E0 = Es, E1 = Es, E2 = Es, E3 = Es;

            // xBR Edge-Directed Reconstruction for Top-Left (E0)
            int d_DB = ColorDist(D, B);
            int d_EA = ColorDist(E, A);
            if (d_DB < d_EA && d_DB < 9500 && ColorDist(E, D) > d_DB && ColorDist(E, B) > d_DB) {
                uint32_t diag = Blend50(D, B);
                E0 = (ColorDist(D, G) < 4000 || ColorDist(B, C) < 4000) ? Blend75(diag, Es) : Blend50(diag, Es);
            }

            // Top-Right (E1)
            int d_FB = ColorDist(F, B);
            int d_EC = ColorDist(E, C);
            if (d_FB < d_EC && d_FB < 9500 && ColorDist(E, F) > d_FB && ColorDist(E, B) > d_FB) {
                uint32_t diag = Blend50(F, B);
                E1 = (ColorDist(F, I) < 4000 || ColorDist(B, A) < 4000) ? Blend75(diag, Es) : Blend50(diag, Es);
            }

            // Bottom-Left (E2)
            int d_DH = ColorDist(D, H);
            int d_EG = ColorDist(E, G);
            if (d_DH < d_EG && d_DH < 9500 && ColorDist(E, D) > d_DH && ColorDist(E, H) > d_DH) {
                uint32_t diag = Blend50(D, H);
                E2 = (ColorDist(D, A) < 4000 || ColorDist(H, I) < 4000) ? Blend75(diag, Es) : Blend50(diag, Es);
            }

            // Bottom-Right (E3)
            int d_FH = ColorDist(F, H);
            int d_EI = ColorDist(E, I);
            if (d_FH < d_EI && d_FH < 9500 && ColorDist(E, F) > d_FH && ColorDist(E, H) > d_FH) {
                uint32_t diag = Blend50(F, H);
                E3 = (ColorDist(F, C) < 4000 || ColorDist(H, G) < 4000) ? Blend75(diag, Es) : Blend50(diag, Es);
            }

            dstRow0[x * 2]     = E0;
            dstRow0[x * 2 + 1] = E1;
            dstRow1[x * 2]     = E2;
            dstRow1[x * 2 + 1] = E3;
        }
    }
}

// Thread blit callback
extern "C" void Android_BlitFrame(const uint32_t* screenBuffer, int width, int height)
{
    pthread_mutex_lock(&g_windowMutex);
    if (!g_window || !screenBuffer) {
        pthread_mutex_unlock(&g_windowMutex);
        return;
    }

    int desiredScale = (g_aiUpscaleMode >= 2) ? 2 : 1;
    if (g_currentWindowScale != desiredScale) {
        ANativeWindow_setBuffersGeometry(
            g_window,
            width * desiredScale,
            height * desiredScale,
            WINDOW_FORMAT_RGBA_8888
        );
        g_currentWindowScale = desiredScale;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_window, &buffer, nullptr) < 0) {
        pthread_mutex_unlock(&g_windowMutex);
        return;
    }

    auto* dstPixels = reinterpret_cast<uint32_t*>(buffer.bits);
    int stride = buffer.stride;

    if (desiredScale == 2 && buffer.width >= width * 2 && buffer.height >= height * 2) {
        UpscaleFrameXbrCas2x(screenBuffer, width, height, dstPixels, stride);
    } else {
        int copyWidth = (width < buffer.width) ? width : buffer.width;
        int copyHeight = (height < buffer.height) ? height : buffer.height;

        for (int y = 0; y < copyHeight; ++y) {
            const uint32_t* srcRow = screenBuffer + (y * width);
            uint32_t* dstRow = dstPixels + (y * stride);
            std::memcpy(dstRow, srcRow, copyWidth * sizeof(uint32_t));

            if (buffer.width > copyWidth) {
                std::memset(dstRow + copyWidth, 0, (buffer.width - copyWidth) * sizeof(uint32_t));
            }
        }

        for (int y = copyHeight; y < buffer.height; ++y) {
            std::memset(dstPixels + (y * stride), 0, stride * sizeof(uint32_t));
        }
    }

    ANativeWindow_unlockAndPost(g_window);
    pthread_mutex_unlock(&g_windowMutex);

    int currentSession = s_engineSessionId.load(std::memory_order_relaxed);
    if (currentSession > 0 && s_dispatchedSessionId.load(std::memory_order_relaxed) != currentSession) {
        if (g_vm && g_bridgeClass && g_onFirstFrameMethod) {
            JNIEnv* env = nullptr;
            bool needsDetach = false;
            if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    needsDetach = true;
                }
            }
            if (env && !env->ExceptionCheck()) {
                env->CallStaticVoidMethod(g_bridgeClass, g_onFirstFrameMethod, (jint)currentSession);
                if (!env->ExceptionCheck()) {
                    s_dispatchedSessionId.store(currentSession, std::memory_order_relaxed);
                    LOGI("First frame event successfully dispatched for session %d", currentSession);
                } else {
                    LOGW("Exception during onFirstFrameEvent for session %d", currentSession);
                    env->ExceptionClear();
                }
                if (needsDetach) {
                    g_vm->DetachCurrentThread();
                }
            }
        }
    }
}

extern "C" void Android_TriggerHaptic(int effectType)
{
    if (!g_vm || !g_bridgeClass || !g_onHapticMethod) return;

    JNIEnv* env = nullptr;
    bool needsDetach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            needsDetach = true;
        } else {
            return;
        }
    }

    env->CallStaticVoidMethod(g_bridgeClass, g_onHapticMethod, effectType);

    if (needsDetach) {
        g_vm->DetachCurrentThread();
    }
}

extern "C" void Android_OnQuit(void)
{
    if (!g_vm || !g_bridgeClass || !g_onQuitMethod) return;

    JNIEnv* env = nullptr;
    bool needsDetach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            needsDetach = true;
        } else {
            return;
        }
    }

    env->CallStaticVoidMethod(g_bridgeClass, g_onQuitMethod);

    if (needsDetach) {
        g_vm->DetachCurrentThread();
    }
}

extern "C" int joybspeed;
extern "C" void M_SaveDefaults(void);
extern "C" int Android_ComputeUiStateMask(void);
extern "C" void I_GetEvent(void);

// Background Game Loop
static void* GameThreadFunc(void* /*arg*/)
{
    LOGI("Starting DOOM engine game thread with IWAD: %s", g_wadPath.c_str());

    if (access(g_wadPath.c_str(), R_OK) != 0) {
        LOGE("WAD file does not exist or is not readable: %s", g_wadPath.c_str());
        size_t lastSlash = g_wadPath.find_last_of('/');
        if (lastSlash != std::string::npos) {
            std::string fallbackPath = g_wadPath.substr(0, lastSlash + 1) + "doom1.wad";
            if (access(fallbackPath.c_str(), R_OK) == 0) {
                LOGW("Falling back to bundled IWAD: %s", fallbackPath.c_str());
                g_wadPath = fallbackPath;
            }
        }
    }

    if (!g_saveDir.empty()) {
        chdir(g_saveDir.c_str());
    }

    std::vector<std::string> args = {
        "doom",
        "-iwad", g_wadPath,
        "-savedir", g_saveDir
    };

    std::vector<char*> argv;
    for (auto& s : args) {
        argv.push_back(&s[0]);
    }
    int argc = static_cast<int>(argv.size());

    doomgeneric_Create(argc, argv.data());
    joybspeed = 29; // Enable proportional full-speed analog range by default
    s_cachedUiStateMask.store(Android_ComputeUiStateMask(), std::memory_order_release);

    g_gameRunning = true;
    while (g_gameRunning) {
        if (s_pendingConfigSave.exchange(false, std::memory_order_relaxed)) {
            M_SaveDefaults();
            {
                std::lock_guard<std::mutex> lock(s_saveMutex);
                s_saveSequence++;
            }
            s_saveCv.notify_all();
        }
        if (g_gamePaused) {
            // Drain input queue while paused so keys never get stuck across pause/resume
            I_GetEvent();
            usleep(16000);
            continue;
        }
        doomgeneric_Tick();
        s_cachedUiStateMask.store(Android_ComputeUiStateMask(), std::memory_order_release);
    }

    LOGI("DOOM engine game thread stopped");
    return nullptr;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClass = env->FindClass("com/id/doom/DoomBridge");
    if (localClass) {
        g_bridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
        g_onHapticMethod = env->GetStaticMethodID(g_bridgeClass, "onHapticEvent", "(I)V");
        g_onQuitMethod = env->GetStaticMethodID(g_bridgeClass, "onQuitEvent", "()V");
        g_onFirstFrameMethod = env->GetStaticMethodID(g_bridgeClass, "onFirstFrameEvent", "(I)V");
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_id_doom_DoomBridge_nativePrepareInit(JNIEnv* /*env*/, jclass /*clazz*/)
{
    if (g_threadStarted.load(std::memory_order_acquire)) {
        g_gamePaused = false;
        LOGI("nativePrepareInit: DOOM engine already running — resuming session %d", s_engineSessionId.load());
        return 0; // 0 indicates reconnecting to an existing running engine session
    }
    // Monotonically reserve session ID so prepare and init share the exact reserved session identity
    int nextSession = ++s_engineSessionId;
    LOGI("nativePrepareInit: reserved new engine session %d", nextSession);
    return static_cast<jint>(nextSession);
}

JNIEXPORT jint JNICALL
Java_com_id_doom_DoomBridge_nativeInit(JNIEnv* env, jclass /*clazz*/, jstring wadPath, jstring saveDir)
{
    if (g_threadStarted.exchange(true)) {
        g_gamePaused = false;
        LOGI("DOOM engine already running — resumed session %d", s_engineSessionId.load());
        return 0; // 0 indicates reconnected to existing session
    }

    int session = s_engineSessionId.load(std::memory_order_relaxed);
    if (session == 0) {
        session = ++s_engineSessionId;
    }
    s_dispatchedSessionId.store(0);

    const char* cWad = env->GetStringUTFChars(wadPath, nullptr);
    const char* cSave = env->GetStringUTFChars(saveDir, nullptr);
    g_wadPath = cWad ? cWad : "";
    g_saveDir = cSave ? cSave : "";
    env->ReleaseStringUTFChars(wadPath, cWad);
    env->ReleaseStringUTFChars(saveDir, cSave);

    LOGI("Spawning new DOOM engine thread for session %d", session);
    pthread_create(&g_gameThread, nullptr, GameThreadFunc, nullptr);
    return static_cast<jint>(session);
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSetSurface(JNIEnv* env, jclass /*clazz*/, jobject surface)
{
    pthread_mutex_lock(&g_windowMutex);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    g_currentWindowScale = -1;

    if (surface) {
        g_window = ANativeWindow_fromSurface(env, surface);
        if (g_window) {
            LOGI("Surface attached for 4:3 SurfaceView");
        }
    }
    pthread_mutex_unlock(&g_windowMutex);
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSurfaceDestroyed(JNIEnv* /*env*/, jclass /*clazz*/)
{
    pthread_mutex_lock(&g_windowMutex);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    g_currentWindowScale = -1;
    pthread_mutex_unlock(&g_windowMutex);
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSendKey(JNIEnv* /*env*/, jclass /*clazz*/, jint pressed, jint doomKey)
{
    Android_QueueKey(pressed, static_cast<unsigned char>(doomKey));
}

extern "C" void M_TouchAt(int x, int y);
extern "C" void Android_TriggerCheat(int cheatId);
extern "C" int menuactive;
extern "C" int automapactive;
extern "C" int am_is_follow_player(void);
extern "C" int messageToPrint;
extern "C" int messageNeedsInput;
extern "C" int mouseSensitivity;

extern "C" void Android_GetAnalogMove(float* outForward, float* outStrafe, float* outTurn)
{
    int mask = s_cachedUiStateMask.load(std::memory_order_acquire);
    bool isMenu = (mask & (1 << 6)) != 0 || (mask & (1 << 3)) != 0;
    bool isAutomapFreePan = (mask & (1 << 7)) != 0;
    if (isMenu || isAutomapFreePan) {
        *outForward = 0.0f;
        *outStrafe = 0.0f;
        *outTurn = 0.0f;
        return;
    }
    float sensScale = 0.55f + (static_cast<float>(mouseSensitivity) * 0.09f);
    *outForward = g_analogForward.load(std::memory_order_relaxed);
    *outStrafe = g_analogStrafe.load(std::memory_order_relaxed);
    *outTurn = g_analogTurn.load(std::memory_order_relaxed) * sensScale;
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSendAnalog(JNIEnv* /*env*/, jclass /*clazz*/, jfloat forward, jfloat strafe, jfloat turn)
{
    g_analogForward.store(forward, std::memory_order_relaxed);
    g_analogStrafe.store(strafe, std::memory_order_relaxed);
    g_analogTurn.store(turn, std::memory_order_relaxed);

    static bool forwardPressed = false;
    static bool backwardPressed = false;
    static bool leftPressed = false;
    static bool rightPressed = false;

    int mask = s_cachedUiStateMask.load(std::memory_order_acquire);
    bool isMenu = (mask & (1 << 6)) != 0 || (mask & (1 << 3)) != 0;
    bool isAutomapFreePan = (mask & (1 << 7)) != 0;
    bool useArrowKeys = isMenu || isAutomapFreePan;

    // During active gameplay (!useArrowKeys), movement & turning are driven continuously
    // via Android_GetAnalogMove() in G_BuildTiccmd() — release any menu/map keys cleanly.
    if (!useArrowKeys) {
        if (forwardPressed) { Android_QueueKey(0, KEY_UPARROW); forwardPressed = false; }
        if (backwardPressed) { Android_QueueKey(0, KEY_DOWNARROW); backwardPressed = false; }
        if (leftPressed) { Android_QueueKey(0, KEY_LEFTARROW); leftPressed = false; }
        if (rightPressed) { Android_QueueKey(0, KEY_RIGHTARROW); rightPressed = false; }
        return;
    }

    // In Menus or Automap Free-Pan mode: use hysteresis threshold
    const float pressThresh = 0.40f;
    const float releaseThresh = 0.20f;
    float horiz = (turn != 0.0f) ? turn : strafe;

    if (forward > pressThresh && !forwardPressed) {
        Android_QueueKey(1, KEY_UPARROW);
        forwardPressed = true;
    } else if (forward < releaseThresh && forwardPressed) {
        Android_QueueKey(0, KEY_UPARROW);
        forwardPressed = false;
    }

    if (forward < -pressThresh && !backwardPressed) {
        Android_QueueKey(1, KEY_DOWNARROW);
        backwardPressed = true;
    } else if (forward > -releaseThresh && backwardPressed) {
        Android_QueueKey(0, KEY_DOWNARROW);
        backwardPressed = false;
    }

    if (horiz < -pressThresh && !leftPressed) {
        Android_QueueKey(1, KEY_LEFTARROW);
        leftPressed = true;
    } else if (horiz > -releaseThresh && leftPressed) {
        Android_QueueKey(0, KEY_LEFTARROW);
        leftPressed = false;
    }

    if (horiz > pressThresh && !rightPressed) {
        Android_QueueKey(1, KEY_RIGHTARROW);
        rightPressed = true;
    } else if (horiz < releaseThresh && rightPressed) {
        Android_QueueKey(0, KEY_RIGHTARROW);
        rightPressed = false;
    }
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeTouchScreen(JNIEnv* /*env*/, jclass /*clazz*/, jfloat normX, jfloat normY)
{
    int x = static_cast<int>(normX * DOOMGENERIC_RESX);
    int y = static_cast<int>(normY * DOOMGENERIC_RESY);
    M_TouchAt(x, y);
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSendLook(JNIEnv* /*env*/, jclass /*clazz*/, jfloat deltaX, jfloat deltaY)
{
    // Sub-pixel horizontal swipe directly rotates the camera in DOOM, scaled by mouseSensitivity
    (void)deltaY;
    float sensScale = 0.55f + (static_cast<float>(mouseSensitivity) * 0.09f);
    Android_SendMouseMove(deltaX * 2.2f * sensScale, 0.0f);
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSetAutoRun(JNIEnv* /*env*/, jclass /*clazz*/, jboolean enabled)
{
    // In DOOM, joybspeed >= 20 enables native Always Run without affecting shift key state
    joybspeed = enabled ? 29 : 2;
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeTriggerCheat(JNIEnv* /*env*/, jclass /*clazz*/, jint cheatId)
{
    Android_TriggerCheat(static_cast<int>(cheatId));
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSetUpscaleMode(JNIEnv* /*env*/, jclass /*clazz*/, jint mode)
{
    int m = static_cast<int>(mode);
    if (m < 0) m = 0;
    if (m > 2) m = 2;
    s_pendingUpscaleMode.store(m, std::memory_order_relaxed);
    Android_TriggerCheat(100 + m);
}

JNIEXPORT jint JNICALL
Java_com_id_doom_DoomBridge_nativeGetUpscaleMode(JNIEnv* /*env*/, jclass /*clazz*/)
{
    return static_cast<jint>(s_pendingUpscaleMode.load(std::memory_order_relaxed));
}

JNIEXPORT jboolean JNICALL
Java_com_id_doom_DoomBridge_nativeIsMenuActive(JNIEnv* /*env*/, jclass /*clazz*/)
{
    int mask = s_cachedUiStateMask.load(std::memory_order_acquire);
    return ((mask & (1 << 6)) != 0 || (mask & (1 << 3)) != 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_id_doom_DoomBridge_nativeIsPromptActive(JNIEnv* /*env*/, jclass /*clazz*/)
{
    int mask = s_cachedUiStateMask.load(std::memory_order_acquire);
    return ((mask & (1 << 3)) != 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_id_doom_DoomBridge_nativeGetUiStateMask(JNIEnv* /*env*/, jclass /*clazz*/)
{
    return static_cast<jint>(s_cachedUiStateMask.load(std::memory_order_acquire));
}

extern "C" void Android_SetAudioPaused(bool paused);

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeSetPaused(JNIEnv* /*env*/, jclass /*clazz*/, jboolean paused)
{
    bool isPaused = (paused == JNI_TRUE);
    g_gamePaused = isPaused;
    Android_SetAudioPaused(isPaused);
    if (isPaused) {
        g_analogForward.store(0.0f, std::memory_order_relaxed);
        g_analogStrafe.store(0.0f, std::memory_order_relaxed);
        g_analogTurn.store(0.0f, std::memory_order_relaxed);
        Android_ReleaseHeldKeys();
        if (g_gameRunning) {
            s_pendingConfigSave.store(true, std::memory_order_relaxed);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_id_doom_DoomBridge_nativeStop(JNIEnv* /*env*/, jclass /*clazz*/)
{
    g_gamePaused = true;
    Android_SetAudioPaused(true);
    g_analogForward.store(0.0f, std::memory_order_relaxed);
    g_analogStrafe.store(0.0f, std::memory_order_relaxed);
    g_analogTurn.store(0.0f, std::memory_order_relaxed);
    Android_ReleaseHeldKeys();
    if (g_gameRunning) {
        std::unique_lock<std::mutex> lock(s_saveMutex);
        uint32_t seq = s_saveSequence;
        s_pendingConfigSave.store(true, std::memory_order_release);
        s_saveCv.wait_for(lock, std::chrono::milliseconds(100), [&seq]() {
            return s_saveSequence != seq;
        });
    }
}

} // extern "C"
