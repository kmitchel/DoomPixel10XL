#include <assert.h>
#include <stdint.h>
#include <stdio.h>

#include "doomgeneric.h"
#include "g_game.h"
#include "i_sound.h"
#include "m_controls.h"

static float forward_axis;
static float strafe_axis;
static float turn_axis;

void Android_GetAnalogMove(float *forward, float *strafe, float *turn)
{
    *forward = forward_axis;
    *strafe = strafe_axis;
    *turn = turn_axis;
}

void Android_BlitFrame(const uint32_t *pixels, int width, int height) {}
void Android_TriggerHaptic(int type) {}
void Android_OnQuit(void) {}
void R_SyncFrameUpscaleMode(void) {}
sound_module_t DG_sound_module = {0};
music_module_t DG_music_module = {0};
int use_libsamplerate = 0;
float libsamplerate_scale = 0.65f;

static void check_move(float forward, float strafe, int expected_forward, int expected_side)
{
    ticcmd_t cmd;
    forward_axis = forward;
    strafe_axis = strafe;
    turn_axis = 0.0f;
    G_BuildTiccmd(&cmd, 0);
    assert(cmd.forwardmove == expected_forward);
    assert(cmd.sidemove == expected_side);
    assert(cmd.angleturn == 0);
}

int main(void)
{
    // Analog movement must span walking and running even with digital run off.
    joybspeed = 2;
    check_move(0.0f, 0.0f, 0, 0);
    check_move(0.25f, 0.0f, 12, 0);
    check_move(0.5f, 0.0f, 25, 0);
    check_move(1.0f, 0.0f, 50, 0);
    check_move(-0.5f, 0.0f, -25, 0);
    check_move(-1.0f, 0.0f, -50, 0);
    check_move(0.0f, 0.5f, 0, 20);
    check_move(0.0f, -1.0f, 0, -40);
    check_move(1.0f, 1.0f, 50, 40);
    check_move(0.0f, 0.0f, 0, 0);

    // Saved autorun bindings must not change the analog speed range.
    joybspeed = 29;
    check_move(0.5f, 0.5f, 25, 20);
    check_move(1.0f, 1.0f, 50, 40);
    puts("Analog movement regression tests passed");
    return 0;
}
