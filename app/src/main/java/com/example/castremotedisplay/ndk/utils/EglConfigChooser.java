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

package com.example.castremotedisplay.ndk.utils;

import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Utility class to select the best possible EGL configuration given the
 * hardware capabilities of the Android device.
 */
public final class EglConfigChooser {

    private static final String TAG = "EglConfigChooser";

    // This constant is not defined in the Android package of OpenGL,
    // but indicates we want OpenGL ES 2.
    // http://stackoverflow.com/questions/8090608/how-to-set-opengl-version-in-either-egl-or-glsurfaceview
    private static final int EGL_OPEN_GL_ES2_BIT = 4;

    // Format for each fragment, in bits.
    private static final int RED_SIZE = 8;
    private static final int GREEN_SIZE = 8;
    private static final int BLUE_SIZE = 8;
    private static final int ALPHA_SIZE = 8;
    private static final int DEPTH_SIZE = 16;
    private static final int STENCIL_SIZE = 0;

    private static final int[] DEFAULT_CONFIG_SPEC = {
            EGL10.EGL_RED_SIZE, RED_SIZE,
            EGL10.EGL_GREEN_SIZE, GREEN_SIZE,
            EGL10.EGL_BLUE_SIZE, BLUE_SIZE,
            EGL10.EGL_ALPHA_SIZE, ALPHA_SIZE,
            EGL10.EGL_DEPTH_SIZE, DEPTH_SIZE,
            EGL10.EGL_STENCIL_SIZE, STENCIL_SIZE,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPEN_GL_ES2_BIT,
            EGL10.EGL_SAMPLE_BUFFERS, 1,
            EGL10.EGL_SAMPLES, 4,
            EGL10.EGL_NONE
    };

    private static final int[] SIMPLE_CONFIG_SPEC = {
            EGL10.EGL_RED_SIZE, RED_SIZE,
            EGL10.EGL_GREEN_SIZE, GREEN_SIZE,
            EGL10.EGL_BLUE_SIZE, BLUE_SIZE,
            EGL10.EGL_ALPHA_SIZE, ALPHA_SIZE,
            EGL10.EGL_DEPTH_SIZE, DEPTH_SIZE,
            EGL10.EGL_STENCIL_SIZE, STENCIL_SIZE,
            EGL10.EGL_NONE
    };

    private static final int[] sConfigAttributeValue = new int[1];

    /**
     * Chooses the best config for the available hardware.
     * See https://www.khronos.org/registry/egl/sdk/docs/man/html/eglChooseConfig.xhtml for more
     * information on the config spec.
     */
    public static EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        int[] numConfigsArray = new int[1];
        int numConfigs;

        int[] configSpec = DEFAULT_CONFIG_SPEC;

        // Get the list of configs.
        if (!egl.eglChooseConfig(display, configSpec, null, 0, numConfigsArray)) {
            Log.e(TAG, "Could not fetch configs for default spec.");
            return null;
        }

        numConfigs = numConfigsArray[0];
        if (numConfigs == 0) {
            // Switch to the simple config and try again.
            configSpec = SIMPLE_CONFIG_SPEC;
            if (!egl.eglChooseConfig(display, configSpec, null, 0, numConfigsArray)) {
                Log.e(TAG, "Could not fetch configs for simple spec.");
                return null;
            }
        }

        numConfigs = numConfigsArray[0];
        if (numConfigs <= 0) {
            Log.e(TAG, "No compatible EGL configs found.");
            return null;
        }

        // Get the actual configs in the array.
        EGLConfig[] configs = new EGLConfig[numConfigs];
        if (!egl.eglChooseConfig(display, configSpec, configs, numConfigs, numConfigsArray)) {
            Log.e(TAG, "Failed to populate array of EGL configs.");
            return null;
        }
        EGLConfig config = findBestConfig(egl, display, configs);
        if (config == null) {
            Log.e(TAG, "Failed to find config.");
            return null;
        }
        return config;
    }

    private static EGLConfig findBestConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs) {
        for (EGLConfig config : configs) {
            int depthSize = findConfigAttribute(egl, display, config, EGL10.EGL_DEPTH_SIZE, 0);
            int stencilSize = findConfigAttribute(egl, display, config, EGL10.EGL_STENCIL_SIZE, 0);
            if ((depthSize >= DEPTH_SIZE) && (stencilSize >= STENCIL_SIZE)) {
                int redSize = findConfigAttribute(egl, display, config, EGL10.EGL_RED_SIZE, 0);
                int greenSize = findConfigAttribute(egl, display, config, EGL10.EGL_GREEN_SIZE, 0);
                int blueSize = findConfigAttribute(egl, display, config, EGL10.EGL_BLUE_SIZE, 0);
                int alphaSize = findConfigAttribute(egl, display, config, EGL10.EGL_ALPHA_SIZE, 0);
                if ((redSize == RED_SIZE)
                        && (greenSize == GREEN_SIZE)
                        && (blueSize == BLUE_SIZE)
                        && (alphaSize == ALPHA_SIZE)) {
                    return config;
                }
            }
        }
        return null;
    }

    private static int findConfigAttribute(EGL10 egl, EGLDisplay display, EGLConfig config,
                                           int attribute, int defaultValue) {
        if (egl.eglGetConfigAttrib(display, config, attribute, sConfigAttributeValue)) {
            return sConfigAttributeValue[0];
        }
        return defaultValue;
    }
}