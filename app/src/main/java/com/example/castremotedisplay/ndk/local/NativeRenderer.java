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

package com.example.castremotedisplay.ndk.local;

import com.google.android.gms.cast.CastPresentation;

/**
 * JNI Interface to our native renderer, responsible for local and Remote Display output
 */
public class NativeRenderer {

    static {
        System.loadLibrary("native");
    }

    /**
     * Initialize native renderer
     *
     * @param width the current view width
     * @param height the current view height
     */
    public static native void init(int width, int height);

    /**
     * Renders a frame on the native side
     */
    public static native void renderFrame();

    /**
     * Signal the start of a Cast session
     *
     * @param presentation the Cast Presentation instance
     * @param width the remote display width
     * @param height the remote display height
     */
    public static native void castSessionStarted(
            CastPresentation presentation, int width, int height);

    /**
     * Signal the end of a Cast session
     *
     * @param presentation the Cast Presentation instance
     */
    public static native void castSessionEnded(CastPresentation presentation);

    /**
     * Tell native Renderer that the texture with specified textureId has been rendered
     * to Remote Display surface and can be re-used again
     *
     * @param textureId the textureId that is done rendering
     */
    public static native void notifyRemoteFrameDone(int textureId);
}