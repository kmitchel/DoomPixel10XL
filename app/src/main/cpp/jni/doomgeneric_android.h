#ifndef DOOMGENERIC_ANDROID_H
#define DOOMGENERIC_ANDROID_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Thread-safe key queue management
void Android_QueueKey(int pressed, unsigned char doomKey);
void Android_ReleaseHeldKeys(void);

// Surface blit callback called from DG_DrawFrame
void Android_BlitFrame(const uint32_t* screenBuffer, int width, int height);

// Timing and lifecycle
uint32_t Android_GetTimeMs(void);
void Android_SleepMs(uint32_t ms);

// Mouse motion (camera turning)
void Android_SendMouseMove(float dx, float dy);
int Android_GetMouseDeltaX(void);
int Android_GetMouseDeltaY(void);

// Automap panning
void Android_SendMapPan(float dx, float dy);
void Android_ConsumeMapPan(int* outDx, int* outDy);

// Haptic trigger callback into JVM
void Android_TriggerHaptic(int effectType);

#ifdef __cplusplus
}
#endif

#endif // DOOMGENERIC_ANDROID_H
