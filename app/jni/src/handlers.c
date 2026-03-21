#include "SDL3/SDL.h"
#include <jni.h>
#include "src/backends/m8.h"
#include "src/backends/audio.h"
#include "src/log_overlay.h"

int device_active = 0;

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8SDLActivity_connect(JNIEnv *env, jobject thiz, jint fd) {
    device_active = 1;
    SDL_Log("Connecting to the device");
    init_serial_with_file_descriptor(fd);
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8TouchListener_00024Companion_sendClickEvent(JNIEnv *env, jobject thiz,
                                                                     jchar event) {
    if (device_active) {
        SDL_Log("Sending message to M8");
        m8_send_msg_controller((unsigned char)event);
    }
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8SDLActivity_hintAudioDriver(JNIEnv *env, jobject thiz,
                                                     jstring audio_driver) {
    if (audio_driver != NULL) {
        const char *path;
        path = (*env)->GetStringUTFChars(env, audio_driver, NULL);
        SDL_Log("Setting audio driver to %s", path);
        SDL_SetHint(SDL_HINT_AUDIODRIVER, path);
    }
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8TouchListener_00024Companion_resetScreen(JNIEnv *env, jobject thiz) {
    if (device_active) {
        m8_enable_display(1);
    }
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8TouchListener_00024Companion_exit(JNIEnv *env, jobject thiz) {
    device_active = 0;
    SDL_Log("Sending Alt+F4 to M8");
    SDL_Event sdlevent = {};
    sdlevent.type = SDL_KEYDOWN;
    sdlevent.key.key = SDLK_F4;
    sdlevent.key.mod = KMOD_ALT;
    SDL_PushEvent(&sdlevent);
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8SDLActivity_toggleDebugOverlay(JNIEnv *env, jobject thiz) {
    log_overlay_toggle();
    SDL_Log("Debug overlay toggled: %s", log_overlay_is_visible() ? "ON" : "OFF");
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8SDLActivity_setDebugMode(JNIEnv *env, jobject thiz, jboolean enabled) {
    if (enabled) {
        SDL_SetLogPriorities(SDL_LOG_PRIORITY_DEBUG);
        SDL_Log("Debug mode ON — verbose logging enabled");
    } else {
        SDL_SetLogPriorities(SDL_LOG_PRIORITY_INFO);
        SDL_Log("Debug mode OFF");
    }
}

JNIEXPORT void JNICALL
Java_io_maido_m8client_M8SDLActivity_lockOrientation(JNIEnv *env, jobject thiz, jstring lock) {
    if (lock == NULL) {
        SDL_Log("Don't lock orientation");
        SDL_SetHint(SDL_HINT_ORIENTATIONS,
                    "LandscapeLeft LandscapeRight Portrait PortraitUpsideDown");
    } else {
        const char *lockOrientation;
        lockOrientation = (*env)->GetStringUTFChars(env, lock, NULL);
        SDL_Log("Lock orientation to %s", lockOrientation);
        SDL_SetHint(SDL_HINT_ORIENTATIONS, lockOrientation);
    }
}