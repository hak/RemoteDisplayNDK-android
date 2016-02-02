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

package com.example.castremotedisplay.ndk.remote;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.util.Log;

import com.example.castremotedisplay.ndk.utils.EglConfigChooser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Handles rendering to the passed surface. Renders a quad on the passed surface. This quad will
 * render the texture passed to #setTextureId.
 */
class RemoteDisplayTextureRenderThread extends Thread {
    private static final String TAG = "RDRenderThread";

    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

    private static final String POSITION_ATTRIB_NAME = "position";
    private static final String TEXTURE_COORDS_ATTRIB_NAME = "texCoords";
    private static final String TEXTURE_SAMPLER2D_NAME = "textureSampler";

    // Simple vertex shader. Does nothing special. Orthographic camera.
    private static final String VERTEX_SHADER =
            "  attribute vec4 position;\n"
                    + "attribute vec2 texCoords;\n"
                    + "varying vec2 outTexCoords;\n"
                    + "void main(void) {\n"
                    + "    outTexCoords = texCoords;\n"
                    + "    gl_Position = position;\n"
                    + "}\n";

    // Simple fragment shader. Renders a texture while forcing its alpha to be 1.
    private static final String FRAGMENT_SHADER =
            "  precision mediump float;\n\n"
                    + "varying vec2 outTexCoords;\n"
                    + "uniform sampler2D textureSampler;\n"
                    + "void main(void) {\n"
                    + "    gl_FragColor = vec4(texture2D(textureSampler, outTexCoords).rgb, 1.0);\n"
                    + "}\n";

    private static final int BYTES_PER_FLOAT = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * BYTES_PER_FLOAT;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

    // X, Y, Z, U, V for the 4 vertices of the quad.
    private static final float[] TRIANGLE_VERTICES_DATA = {
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
            1.0f,  1.0f, 0.0f, 1.0f, 1.0f,
    };

    private static final int[] CONTEXT_ATTRIBS_OPEN_GL_ES_2 = new int[] {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL10.EGL_NONE
    };

    private static final int[] CONTEXT_ATTRIBS_OPEN_GL_ES_3 = new int[] {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL10.EGL_NONE
    };

    private static final int[] SURFACE_ATTRIBS = new int[] {
            EGL10.EGL_NONE
    };

    // Log strings. Cached here to avoid garbage collection problems.
    private static final String LOG_CLEAR_COLOR = "clear color";
    private static final String LOG_CLEAR_BUFFER = "clear color";
    private static final String LOG_TRIANGLE_VERTICES_POS = "triangle vertices pos";
    private static final String LOG_TRIANGLE_VERTICES_UV = "triangle vertices uv";
    private static final String LOG_SWAP_BUFFERS = "swap buffers";
    private static final String LOG_SWAP_BUFFERS_ERROR = "unable to swap buffers";

    // Set to false to terminate the thread.
    private volatile boolean mFinished;

    private final SurfaceTexture mSurface;
    private final EGLContext mParentContext;
    private final RemoteDisplayPresentation mPresentation;

    private EGLDisplay mEglDisplay;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private EGL10 mEgl;

    // The OpenGL handle of the texture we are currently displaying. Guarded by mTextureIdLock.
    private int mTextureId = -1;
    // Set to true if there is a new texture handle we should bind. Guarded by mTextureIdLock.
    private boolean mNewTextureId = false;

    private final Object mTextureIdLock = new Object();

    private volatile boolean mNewFrameAvailable = false;


    private FloatBuffer mTriangleVertices;
    private int mUniformTexture;
    private int mAttribPosition;
    private int mAttribTexCoords;
    private int mProgram;

    /**
     * @param presentation
     * @param parentContext The context that will be passed to eglCreateContext as the share context
     *     parameter. Resources (such as textures ids) will be shared with this context.
     * @param surface The surface where drawing will happen.
     */
    RemoteDisplayTextureRenderThread(RemoteDisplayPresentation presentation,
                                     EGLContext parentContext, SurfaceTexture surface) {
        mPresentation = presentation;
        mParentContext = parentContext;
        mSurface = surface;
    }

    /**
     * Updates the texture to be rendered to the quad.
     * @param textureId The id of the texture to be rendered. Must be a valid texture handler on the
     *         eglContext owned by this thread.
     */
    private void setTextureId(int textureId) {
        synchronized (mTextureIdLock) {
            if (mTextureId != textureId) {
                mTextureId = textureId;
                mNewTextureId = true;
            }
        }
    }

    /**
     * Wakes this thread up if it was asleep and notifies it there is a new frame to be rendered.
     */
    synchronized void renderFrame(int textureId) {
        setTextureId(textureId);
        mNewFrameAvailable = true;
        notify();
    }

    /**
     * Stops rendering and terminates this thread.
     */
    synchronized void finish() {
        mFinished = true;
        // In case the thread is asleep.
        notify();
    }

    @Override
    public void run() {
        synchronized (mTextureIdLock) {
            if (!initializeGL()) {
                return;
            }
        }

        // It is important to keep this while loop from allocating objects to avoid garbage
        // collection issues. Cache all log strings and objects needed for rendering.
        while (!mFinished) {
            // We are rendering the next frame.
            mNewFrameAvailable = false;
            makeCurrent();

            // Lock needed to keep both variables in sync.
            synchronized (mTextureIdLock) {
                if (mNewTextureId) {
                    bindTexture(mTextureId);
                    mNewTextureId = false;
                }
            }

            // Clear on black.
            GLES20.glClearColor(0.0f, 0.0f, 1.0f, 0.0f);
            checkError(LOG_CLEAR_COLOR);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            checkError(LOG_CLEAR_BUFFER);

            // Draw the quad.
            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(mAttribPosition, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkError(LOG_TRIANGLE_VERTICES_POS);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(mAttribTexCoords, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkError(LOG_TRIANGLE_VERTICES_UV);

            // 4 vertices with no offset.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkError(LOG_SWAP_BUFFERS);

            // Present it.
            if (!mEgl.eglSwapBuffers(mEglDisplay, mEglSurface)) {
                Log.w(TAG, LOG_SWAP_BUFFERS_ERROR);
            }
            checkError(LOG_SWAP_BUFFERS);

            mPresentation.notifyRemoteFrameDone(mTextureId);

            // After this render pass, check if we are supposed to wait().
            synchronized (this) {
                try {
                    // Don't wait() if there is a new texture id available. This ensures new
                    // textures are always shown, even if the app is backgrounded (pause screen).
                    // After the next render pass the new texture will be presented and we can
                    // wait() then.
                    if (!mNewFrameAvailable && !mNewTextureId) {
                        wait();
                    }
                } catch (InterruptedException ex) { /* Nothing to do. */}
            }
        }
        finishGL();
    }

    /**
     * Binds a texture to the first texture unit (GL_TEXTURE_0) and updates the sampler2d named
     * "texture" of the fragment shader with this texture. Uses linear sampling and no mipmaps.
     * @param textureId The handler of the texture to bind. Must be accessible in the current
     *         eglContext.
     */
    private void bindTexture(int textureId) {
        //Log.d(TAG, "Binding texture id: " + textureId);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        checkError("bind texture");

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glUniform1i(mUniformTexture, 0);
        checkError("set texture uniform");
        //Log.d(TAG, "Texture bound to GL_TEXTURE0");
    }

    private int buildProgram(String vertex, String fragment) {
        int vertexShader = buildShader(vertex, GLES20.GL_VERTEX_SHADER);
        if (vertexShader == 0) {
            return 0;
        }

        int fragmentShader = buildShader(fragment, GLES20.GL_FRAGMENT_SHADER);
        if (fragmentShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        checkError("attach vertex shader");

        GLES20.glAttachShader(program, fragmentShader);
        checkError("attach fragment shader");

        GLES20.glLinkProgram(program);
        checkError("link program");

        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(program);
            Log.d(TAG, "Error while linking program:\n" + error);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            GLES20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }

    private int buildShader(String source, int type) {
        int shader = GLES20.glCreateShader(type);

        GLES20.glShaderSource(shader, source);
        checkError("shader source");

        GLES20.glCompileShader(shader);
        checkError("compile shader");

        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetShaderInfoLog(shader);
            Log.d(TAG, "Error while compiling shader:\n" + error);
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /**
     * Initializes OpenGL, compiles the shaders and creates an eglContext.
     */
    private boolean initializeGL() {
        mEgl = (EGL10) EGLContext.getEGL();
        if (mEgl == null) {
            checkError("getEGL");
            Log.e(TAG, "Initialization failed. Could not obtain the EGL object.");
            return false;
        }

        mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
            checkError("eglGetDisplay");
            Log.e(TAG, "Initialization failed. eglGetDisplay failed: " + mEgl.eglGetError());
            return false;
        }

        int[] version = new int[2];
        if (!mEgl.eglInitialize(mEglDisplay, version)) {
            checkError("eglInitialize");
            Log.e(TAG, "Initialization failed. eglInitialize failed: " + mEgl.eglGetError());
            return false;
        }

        mEglConfig = EglConfigChooser.chooseConfig(mEgl, mEglDisplay);
        if (mEglConfig == null) {
            checkError("chooseConfig");
            Log.e(TAG, "Initialization failed. eglConfig not initialized");
            return false;
        }

        mEglContext = createContext(mEglDisplay, mEglConfig);
        if (mEglContext == null || mEglContext == EGL10.EGL_NO_CONTEXT) {
            checkError("createEglcontext");
            Log.e(TAG, "Initialization failed. Could not create EGL context.");
            return false;
        }

        mEglSurface =
                mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, mSurface, SURFACE_ATTRIBS);
        if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
            checkError("createWindowSurface");
            Log.e(TAG, "createWindowSurface failed: " + mEgl.eglGetError());
            return false;
        }

        makeCurrent();

        mTriangleVertices = ByteBuffer.allocateDirect(TRIANGLE_VERTICES_DATA.length
                * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(TRIANGLE_VERTICES_DATA).position(0);

        mProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        mAttribPosition = GLES20.glGetAttribLocation(mProgram, POSITION_ATTRIB_NAME);
        checkError("initialize - position");

        mAttribTexCoords = GLES20.glGetAttribLocation(mProgram, TEXTURE_COORDS_ATTRIB_NAME);
        checkError("initialize - texCoords");

        mUniformTexture = GLES20.glGetUniformLocation(mProgram, TEXTURE_SAMPLER2D_NAME);
        checkError("initialize - texture");

        GLES20.glUseProgram(mProgram);
        checkError("use program");

        GLES20.glEnableVertexAttribArray(mAttribPosition);
        checkError("enable vertex attrib array for position");

        GLES20.glEnableVertexAttribArray(mAttribTexCoords);
        checkError("enable vertex attrib array for tex coords");

        return (GLES20.glGetError() == GLES20.GL_NO_ERROR)
                && (mEgl.eglGetError() == EGL10.EGL_SUCCESS);
    }

    private EGLContext createContext(EGLDisplay eglDisplay, EGLConfig eglConfig) {
        if (mParentContext == null || mParentContext == EGL10.EGL_NO_CONTEXT) {
            Log.w(TAG, "mParentContext is null");
        } else {
            Log.w(TAG, "mParentContext is " + mParentContext.toString());
        }

        Log.d(TAG, "Attempting creation of an OpenGLES3 context.");
        EGLContext newContext = mEgl.eglCreateContext(
                eglDisplay, eglConfig, mParentContext, CONTEXT_ATTRIBS_OPEN_GL_ES_3);
        if (newContext == null || newContext == EGL10.EGL_NO_CONTEXT) {
            Log.d(TAG, "OpenGLES3 context creation failed. Parent context might be OpenGLES2.");
            Log.d(TAG, "Attempting creation of an OpenGLES2 context.");
            newContext = mEgl.eglCreateContext(
                    eglDisplay, eglConfig, mParentContext, CONTEXT_ATTRIBS_OPEN_GL_ES_2);
        }

        if (newContext == null || newContext == EGL10.EGL_NO_CONTEXT) {
            Log.w(TAG, "Could not create context.");
            return newContext;
        }
        Log.d(TAG, "New context is " + newContext.toString());
        return newContext;
    }

    private void makeCurrent() {
        if (!mEglContext.equals(mEgl.eglGetCurrentContext())
                || !mEglSurface.equals(mEgl.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
            // Ok to leave this strings here since this should only happen once.
            Log.d(TAG, "Making current: " + mEglContext.toString());
            Log.d(TAG, "Old context:    " + mEgl.eglGetCurrentContext());
            if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                Log.w(TAG, "eglMakeCurrent failed: " + mEgl.eglGetError());
            }
        }
    }

    private void finishGL() {
        if (mEgl != null) {
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            mEgl = null;
        }
        mEglDisplay = null;
        mEglSurface = null;
        mEglContext = null;
    }

    /**
     * Checks if there is an GLES or an EGL error on the last operation performed.
     * @param message The text to be logged if there was an error.
     * @return {@code true} if there was an error, {@code false} otherwise.
     */
    private void checkError(String message) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "GL error 0x" + Integer.toHexString(error) + " while doing: " + message);
            mPresentation.onGlError("RDTexture", error, message);
        }
        if (mEgl == null) {
            return;
        }
        error = mEgl.eglGetError();
        if (error != EGL10.EGL_SUCCESS) {
            Log.w(TAG, "EGL error 0x" + Integer.toHexString(error) +  " while doing: " + message);
            mPresentation.onGlError("RDTexture", error, message);
        }
    }
}