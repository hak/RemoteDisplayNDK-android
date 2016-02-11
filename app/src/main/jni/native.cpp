/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <GLES2/gl2.h>

#include "Cube.h"

#define  LOG_TAG    "NativeRenderer"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

#define  RD_TARGET_COUNT 2
#define  ANGLE_INCREMENT 0.2f

struct remote_display_target {
    GLuint remoteDisplayTexture;
    GLuint remoteDisplayFrameBuffer;
    bool locked;
};

jobject gPresentation = NULL;
jmethodID gRemoteRenderMethod = NULL;
jmethodID gShowGlErrorMethod = NULL;

remote_display_target remoteDisplayTargets[RD_TARGET_COUNT];

int gRemoteDisplayWidth, gRemoteDisplayHeight;
int gLocalWidth, gLocalHeight;

Cube cube;

ndk_helper::Mat4 mat_model;
ndk_helper::Mat4 mat_model_view;
ndk_helper::Mat4 mat_model_view_projection;

ndk_helper::Mat4 mat_projection;
ndk_helper::Mat4 mat_view;
ndk_helper::Mat4 mat_rotation;

float angle;

static void printGLString(const char *name, GLenum s) {
    const char *v = (const char *) glGetString(s);
    LOGI("GL %s = %s\n", name, v);
}

static void checkGlError(JNIEnv *env, const char* op) {
    for (GLint error = glGetError(); error; error = glGetError()) {
        LOGI("after %s() glError (0x%x)\n", op, error);

        jstring jop = env->NewStringUTF(op);
        jstring source = env->NewStringUTF("Native");

        if (gPresentation) {
            env->CallVoidMethod(gPresentation, gShowGlErrorMethod, source, error, jop);
        }
    }
}

remote_display_target initRemoteDisplayTexture(JNIEnv *env, int width, int height) {
    LOGI("initRemoteDisplayTexture(), %d x %d", width, height);

    remote_display_target target;
    target.locked = false;

    glGenTextures(1, &target.remoteDisplayTexture);
    glBindTexture(GL_TEXTURE_2D, target.remoteDisplayTexture);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height,
                 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, NULL);

    glGenFramebuffers(1, &target.remoteDisplayFrameBuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, target.remoteDisplayFrameBuffer);

    glFramebufferTexture2D(
            GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.remoteDisplayTexture, 0);

    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    LOGI("initRemoteDisplayTexture(), textureId: %d", target.remoteDisplayTexture);

    return target;
}

void setupViewport(int width, int height) {
    glViewport( 0, 0, width, height);
    checkGlError("glViewport");

    // Configure perspective with field of view
    float ratio = (float) width / height;
    float fov = 30.0f;
    float near = 1.0f;
    float far = 100.0f;
    float top = (float) tanf(fov * M_PI / 360.0f) * near;
    float bottom = -top;
    float left = ratio * bottom;
    float right = ratio * top;

    mat_projection = ndk_helper::Mat4::Perspective(left, right, bottom, top, near, far);

    glFrontFace( GL_CCW );
    glCullFace(GL_FRONT);
    glEnable(GL_CULL_FACE);
}

void renderLocalFrame(JNIEnv *env, bool colorChange) {

    glClearColor( 0.5f, 0.5f, 0.5f, 1.f );
    glClear( GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT );

    mat_view = ndk_helper::Mat4::LookAt(
            ndk_helper::Vec3(0.0f, 0.0f, -10.0f),
            ndk_helper::Vec3(0.0f, 0.0f, 0.0f),
            ndk_helper::Vec3(0.0f, 1.0f, 0.0f)
    );

    mat_model = ndk_helper::Mat4::Identity();
    mat_model = mat_model + ndk_helper::Mat4::Translation(0.0f, -0.5f, -1.5f);

    mat_rotation = ndk_helper::Mat4::Rotation(2 * angle, 0.f, 1.f, 1.f);
    mat_model *= mat_rotation;

    mat_model_view = mat_view * mat_model;
    mat_model_view_projection = mat_projection * mat_model_view;

    cube.Render(mat_model_view_projection.Ptr(), colorChange);

    angle += ANGLE_INCREMENT;
}

void renderRemoteDisplayFrame(JNIEnv *env, remote_display_target target) {

    glViewport( 0, 0, gRemoteDisplayWidth, gRemoteDisplayHeight);

    if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        return;

    glBindFramebuffer(GL_FRAMEBUFFER, target.remoteDisplayFrameBuffer);

    setupViewport(gRemoteDisplayWidth, gRemoteDisplayHeight);

    renderLocalFrame(env, false);

    glBindFramebuffer(GL_FRAMEBUFFER,0);

}

void notifyRemoteDisplayRenderThread(JNIEnv *env, remote_display_target target) {
    env->CallVoidMethod(gPresentation, gRemoteRenderMethod, target.remoteDisplayTexture);
}

extern "C" {
JNIEXPORT void JNICALL Java_com_example_castremotedisplay_ndk_local_NativeRenderer_init
        (JNIEnv * env, jclass type, jint width, jint height);
JNIEXPORT void JNICALL Java_com_example_castremotedisplay_ndk_local_NativeRenderer_renderFrame
        (JNIEnv * env, jclass type);
JNIEXPORT void JNICALL Java_com_example_castremotedisplay_ndk_local_NativeRenderer_castSessionStarted
        (JNIEnv *env, jclass type, jobject presentation, jint width, jint height);
JNIEXPORT void JNICALL Java_com_example_castremotedisplay_ndk_local_NativeRenderer_castSessionEnded
        (JNIEnv *env, jclass type, jobject presentation);
JNIEXPORT void JNICALL Java_com_example_castremotedisplay_ndk_local_NativeRenderer_notifyRemoteFrameDone
        (JNIEnv *env, jclass type, jint textureId);
};

JNIEXPORT void JNICALL
Java_com_example_castremotedisplay_ndk_local_NativeRenderer_init(JNIEnv *env, jclass type,
                                                                 jint width, jint height) {
    LOGI("Initialize native renderer");

    printGLString("Version", GL_VERSION);
    printGLString("Vendor", GL_VENDOR);
    printGLString("Renderer", GL_RENDERER);
    printGLString("Extensions", GL_EXTENSIONS);

    // Initialize Cube Renderer ie. load Shaders
    cube.Init();

    // Initialize GL state.
    glEnable( GL_DEPTH_TEST );
    glDepthFunc( GL_LEQUAL );

    // Set anti-aliasing
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    gLocalWidth = width;
    gLocalHeight = height;
}

JNIEXPORT void JNICALL
Java_com_example_castremotedisplay_ndk_local_NativeRenderer_renderFrame(JNIEnv *env, jclass type) {

    if(!remoteDisplayTargets[0].remoteDisplayTexture && gPresentation) {
        for(int i = 0; i < RD_TARGET_COUNT; i++) {
            remoteDisplayTargets[i] =
                    initRemoteDisplayTexture(env, gRemoteDisplayWidth, gRemoteDisplayHeight);
        }
    };

    // Render local frame (ie. displayed on the device)
    setupViewport(gLocalWidth, gLocalHeight);
    renderLocalFrame(env, true);

    if(remoteDisplayTargets[0].remoteDisplayTexture && gRemoteRenderMethod) {
        remote_display_target target;
        for(int i = 0; i < RD_TARGET_COUNT; i++) {
            if (!remoteDisplayTargets[i].locked) {
                target = remoteDisplayTargets[i];
            }
        }

        if(target.remoteDisplayTexture) {
            target.locked = true;

            // Render remote frame (ie. to be displayed on the TV)
            renderRemoteDisplayFrame(env, target);
            notifyRemoteDisplayRenderThread(env, target);
        } else {
            LOGE("No free texture...");
        }
    }
}

JNIEXPORT void JNICALL
Java_com_example_castremotedisplay_ndk_local_NativeRenderer_castSessionStarted(
        JNIEnv *env, jclass type, jobject presentation, jint width, jint height) {
    LOGI("Got notified that Cast session started. Init.");

    gRemoteDisplayWidth = width;
    gRemoteDisplayHeight = height;

    // Acquire refs to Java methods
    gPresentation = env->NewGlobalRef(presentation);
    jclass cls = env->GetObjectClass(presentation);
    gRemoteRenderMethod = env->GetMethodID(cls, "renderFrameToTexture", "(I)V");
    gShowGlErrorMethod = env->GetMethodID(cls,
                                          "onGlError", "(Ljava/lang/String;ILjava/lang/String;)V");
}

JNIEXPORT void JNICALL
Java_com_example_castremotedisplay_ndk_local_NativeRenderer_castSessionEnded(JNIEnv *env,
                                                                             jclass type,
                                                                             jobject presentation) {
    LOGI("Got notified that Cast session ended");
    env->DeleteGlobalRef(gPresentation);
    gPresentation = NULL;
    gRemoteRenderMethod = NULL;

    for(int i = 0; i < RD_TARGET_COUNT; i++) {
        glDeleteFramebuffers(1, &remoteDisplayTargets[i].remoteDisplayFrameBuffer);
        remoteDisplayTargets[i].remoteDisplayFrameBuffer = 0;
        glDeleteTextures(1, &remoteDisplayTargets[i].remoteDisplayTexture);
        remoteDisplayTargets[i].remoteDisplayTexture = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_castremotedisplay_ndk_local_NativeRenderer_notifyRemoteFrameDone(JNIEnv *env,
                                                                             jclass type,
                                                                             jint textureId) {
    // Unlock target as RD thread has finished rendering to remote surface
    for(int i = 0; i < RD_TARGET_COUNT; i++) {
        if (!remoteDisplayTargets[i].remoteDisplayTexture == textureId) {
            remoteDisplayTargets[i].locked = false;
        }
    }
}
