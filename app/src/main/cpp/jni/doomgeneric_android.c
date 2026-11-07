#include "doomgeneric.h"
#include "doomgeneric_android.h"
#include "doomkeys.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>

#define LOG_TAG "DoomGenericAndroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define KEYQUEUE_SIZE 64

static unsigned short s_KeyQueue[KEYQUEUE_SIZE];
static unsigned int s_KeyQueueWriteIndex = 0;
static unsigned int s_KeyQueueReadIndex = 0;
static pthread_mutex_t s_KeyMutex = PTHREAD_MUTEX_INITIALIZER;

static float s_mouseDeltaX = 0.0f;
static float s_mouseDeltaY = 0.0f;
static pthread_mutex_t s_MouseMutex = PTHREAD_MUTEX_INITIALIZER;

void Android_SendMouseMove(float dx, float dy)
{
    pthread_mutex_lock(&s_MouseMutex);
    s_mouseDeltaX += dx;
    s_mouseDeltaY += dy;
    pthread_mutex_unlock(&s_MouseMutex);
}

int Android_GetMouseDeltaX(void)
{
    pthread_mutex_lock(&s_MouseMutex);
    int dx = (int)s_mouseDeltaX;
    s_mouseDeltaX -= (float)dx;
    pthread_mutex_unlock(&s_MouseMutex);
    return dx;
}

int Android_GetMouseDeltaY(void)
{
    pthread_mutex_lock(&s_MouseMutex);
    int dy = (int)s_mouseDeltaY;
    s_mouseDeltaY -= (float)dy;
    pthread_mutex_unlock(&s_MouseMutex);
    return dy;
}

void Android_QueueKey(int pressed, unsigned char doomKey)
{
    pthread_mutex_lock(&s_KeyMutex);
    unsigned short keyData = ((pressed ? 1 : 0) << 8) | (doomKey & 0xFF);
    s_KeyQueue[s_KeyQueueWriteIndex] = keyData;
    s_KeyQueueWriteIndex = (s_KeyQueueWriteIndex + 1) % KEYQUEUE_SIZE;
    pthread_mutex_unlock(&s_KeyMutex);
}

void DG_Init()
{
    LOGI("DG_Init: Initializing Android DOOM backend (%dx%d)", DOOMGENERIC_RESX, DOOMGENERIC_RESY);
}

void DG_DrawFrame()
{
    if (DG_ScreenBuffer) {
        Android_BlitFrame((const uint32_t*)DG_ScreenBuffer, DOOMGENERIC_RESX, DOOMGENERIC_RESY);
    }
}

void DG_SleepMs(uint32_t ms)
{
    usleep(ms * 1000);
}

uint32_t DG_GetTicksMs()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)((ts.tv_sec * 1000) + (ts.tv_nsec / 1000000));
}

int DG_GetKey(int* pressed, unsigned char* doomKey)
{
    int hasKey = 0;
    pthread_mutex_lock(&s_KeyMutex);
    if (s_KeyQueueReadIndex != s_KeyQueueWriteIndex) {
        unsigned short keyData = s_KeyQueue[s_KeyQueueReadIndex];
        s_KeyQueueReadIndex = (s_KeyQueueReadIndex + 1) % KEYQUEUE_SIZE;
        *pressed = (keyData >> 8) & 0x01;
        *doomKey = (unsigned char)(keyData & 0xFF);
        hasKey = 1;
    }
    pthread_mutex_unlock(&s_KeyMutex);
    return hasKey;
}

void DG_SetWindowTitle(const char * title)
{
    LOGI("DOOM Title: %s", title ? title : "");
}
