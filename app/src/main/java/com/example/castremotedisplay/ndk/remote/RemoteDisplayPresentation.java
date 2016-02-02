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

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.TextureView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.castremotedisplay.ndk.R;
import com.example.castremotedisplay.ndk.local.NativeRenderer;
import com.google.android.gms.cast.CastPresentation;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;

/**
 * The presentation to show on the first screen (the TV).
 * <p>
 * Note that this display may have different metrics from the display on
 * which the main activity is showing so we must be careful to use the
 * presentation's own {@link Context} whenever we load resources.
 * </p>
 */
public class RemoteDisplayPresentation extends CastPresentation {

    private static final String TAG = "RDPresentation";

    private TextureView mTextureView;
    private SurfaceTexture mSurfaceTexture;
    private LinearLayout mErrorContainerView;
    private TextView mErrorMessageView;

    private RemoteDisplayTextureRenderThread mRenderThread;
    private boolean mErrored = false;
    private Handler mHandler = new Handler();

    public RemoteDisplayPresentation(Context serviceContext, Display display) {
        super(serviceContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.presentation_remote);

        mErrorContainerView = (LinearLayout) findViewById(R.id.errorContainerView);
        mErrorMessageView = (TextView) findViewById(R.id.errorMessageView);

        mTextureView = (TextureView) findViewById(R.id.remoteTextureView);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture surfaceTexture, int width, int height) {
                Log.d(TAG, "onSurfaceTextureAvailable(" + width + "x" + height + ")");
                mSurfaceTexture = surfaceTexture;

                NativeRenderer.castSessionStarted(RemoteDisplayPresentation.this, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture surfaceTexture, int width, int height) {
                Log.d(TAG, "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                NativeRenderer.castSessionEnded(RemoteDisplayPresentation.this);

                Log.d(TAG, "onSurfaceTextureDestroyed");
                if (mRenderThread != null) {
                    mRenderThread.finish();
                    mRenderThread = null;
                    Log.d(TAG, "RemoteDisplayTextureRenderThread destroyed...");
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                // No-Op.
            }
        });
    }

    /**
     * Updates the texture ID to be rendered by the render thread. Will create and initialize a
     * render thread if this is the first time this is called on this presentation. Since the
     * creation of the thread requires a parent EGL context, this method must be called from the
     * render thread, and the EGL context associated with this thread will be used. This
     * guarantees the render thread's context shares texture information with the local one, and
     * it will be able to present the texture with the provided ID.
     */
    private void initRenderThread() {
        if (mRenderThread == null) {
            if (mSurfaceTexture == null) {
                Log.w(TAG, "Can't create render thread, no active surface texture to render "
                        + "to.");
                return;
            }
            EGL10 egl = (EGL10) EGLContext.getEGL();
            if (egl == null) {
                Log.w(TAG, "Can't create render thread, could not obtain the EGL object.");
                return;
            }
            EGLContext eglContext = egl.eglGetCurrentContext();
            if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
                Log.w(TAG, "Can't create render thread, no active eglContext. This context "
                        + "must be the one Unity uses for rendering so we can share data.");
                return;
            }
            mRenderThread = new RemoteDisplayTextureRenderThread(this, eglContext, mSurfaceTexture);
            mRenderThread.start();
        }
    }

    public void renderFrameToTexture(int textureId) {
        if (mRenderThread == null) {
            initRenderThread();
        }
        mRenderThread.renderFrame(textureId);
    }

    public void notifyRemoteFrameDone(int targetIndex) {
        NativeRenderer.notifyRemoteFrameDone(targetIndex);
    }

    public void onGlError(final String source, final int error, final String message) {

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mErrorMessageView.setText(
                        String.format(getResources().getString(R.string.gl_error),
                                source,
                                Integer.toHexString(error),
                                message));

                if (!mErrored) {
                    mErrored = true;
                    mErrorContainerView.setVisibility(View.VISIBLE);
                }
            }
        });

    }
}
