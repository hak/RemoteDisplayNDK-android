/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef REMOTEDISPLAYNDK_ANDROID_CUBE_H
#define REMOTEDISPLAYNDK_ANDROID_CUBE_H

//--------------------------------------------------------------------------------
// Include files
//--------------------------------------------------------------------------------
#include <jni.h>
#include <errno.h>

#include <vector>

#include <EGL/egl.h>
#include <GLES/gl.h>
#include <GLES2/gl2.h>

#include <android/sensor.h>
#include <android/log.h>
#include <android/native_window_jni.h>

#include "NDKHelper.h"

#define  LOG_TAG    "Cube"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

enum SHADER_ATTRIBUTES
{
    ATTRIB_VERTEX, ATTRIB_NORMAL, ATTRIB_UV,
};

// Number of coordinates per vertex in this array
#define COORDS_PER_VERTEX 3
#define VERTEX_STRIDE COORDS_PER_VERTEX * 4 // 4 bytes per vertex

#define COORDS_PER_COLORS 4
#define COLORS_STRIDE COORDS_PER_COLORS * 4 // 4 bytes per vertex


struct SHADER_PARAMS
{
    GLuint program_;
    GLuint color_;
    GLuint position_;
    GLuint mvp_;
};

static void checkGlError( const char* op) {
    for (GLint error = glGetError(); error; error = glGetError()) {
        LOGI("after %s() glError (0x%x)\n", op, error);
    }
}

const char VERTEX_SHADER_CODE[] =
    "// This matrix member variable provides a hook to manipulate\n"
    "// the coordinates of the objects that use this vertex shader\n"
    "uniform mat4 uMVPMatrix;\n"
    "attribute vec4 vPosition;\n"
    "attribute vec4 vColor;\n"
    "varying vec4 aColor;\n"
    "void main() {\n"
    "aColor = vColor;\n"
    "// The matrix must be included as a modifier of gl_Position.\n"
    "// Note that the uMVPMatrix factor *must be first* in order\n"
    "// for the matrix multiplication product to be correct.\n"
    "  gl_Position = uMVPMatrix * vPosition;\n"
    "}";

const char FRAGMENT_SHADER_CODE[] =
    "precision mediump float;\n"
    "varying vec4 aColor;\n"
    "void main() {\n"
    "  gl_FragColor = aColor;\n"
    "}\n";

class Cube {
    SHADER_PARAMS shader_param_;

    bool LoadShaders(SHADER_PARAMS* params);
public:
    Cube();
    virtual ~Cube();
    void Init();
    void Render(GLfloat* mvpMatrix, bool changeColor);
};


#endif //REMOTEDISPLAYNDK_ANDROID_CUBE_H
