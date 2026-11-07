#include <assert.h>
#include <stdint.h>
#include <stdio.h>

#include "doomgeneric.h"
#include "doomstat.h"
#include "doomkeys.h"
#include "g_game.h"
#include "i_sound.h"
#include "m_controls.h"
#include "m_menu.h"
#include "s_sound.h"
#include "v_video.h"
#include "w_wad.h"
#include "z_zone.h"

void Android_GetAnalogMove(float *a, float *b, float *c) { *a = *b = *c = 0; }
void Android_BlitFrame(const uint32_t *pixels, int width, int height) {}
void Android_TriggerHaptic(int type) {}
void Android_OnQuit(void) {}
void R_SyncFrameUpscaleMode(void) {}
sound_module_t DG_sound_module = {0};
music_module_t DG_music_module = {0};
int use_libsamplerate = 0;
float libsamplerate_scale = 0.65f;

extern int usegamma;
extern gameaction_t gameaction;
extern int quickSaveSlot;
extern int saveStringEnter;
extern boolean sendsave;
extern char *savegamedir;

static void press(int key)
{
    event_t event = {0};
    event.type = ev_keydown;
    event.data1 = key;
    event.data2 = key;
    M_Responder(&event);
}

int main(int argc, char **argv)
{
    assert(argc == 3);
    Z_Init();
    S_Init(0, 0);
    assert(W_AddFile(argv[1]));
    W_GenerateHashTable();
    savegamedir = argv[2];
    gamemode = shareware;
    gamestate = GS_LEVEL;
    usergame = true;
    M_Init();
    M_StartControlPanel();
    assert(menuactive);

    // The overlay exposes these keys while the menu is open.
    usegamma = 0;
    for (int i = 1; i <= 5; ++i)
    {
        press(KEY_F11);
        assert(usegamma == i % 5);
        assert(menuactive);
    }

    // No quicksave slot: show the existing explanatory message.
    press(KEY_F9);
    assert(messageToPrint);
    press(KEY_ENTER);
    assert(!messageToPrint);

    M_StartControlPanel();
    press(KEY_F6);
    assert(menuactive && quickSaveSlot == -2);
    press(KEY_ENTER); // Pick the first save slot.
    assert(saveStringEnter);
    press(KEY_F11); // Preserve save-name entry instead of triggering gamma.
    assert(usegamma == 0 && saveStringEnter);
    press(KEY_ENTER); // Accept the default name and queue the save.
    assert(!saveStringEnter && sendsave && quickSaveSlot == 0);

    M_StartControlPanel();
    press(KEY_F6);
    assert(messageToPrint);
    press(KEY_F11); // Confirmation must not be bypassed by a shortcut.
    assert(messageToPrint && usegamma == 0);
    press(KEY_ESCAPE);
    assert(!messageToPrint);

    M_StartControlPanel();
    press(KEY_F9);
    assert(messageToPrint);
    press(KEY_ENTER);
    assert(!messageToPrint && gameaction == ga_loadgame);

    // Gamma still works with the menu closed.
    assert(!menuactive);
    press(KEY_F11);
    assert(usegamma == 1);
    puts("Menu shortcut regression tests passed");
    return 0;
}
