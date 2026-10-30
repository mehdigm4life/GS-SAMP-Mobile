package com.samp.mobile.cache;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import java.util.Locale;

/**
 * Decides which texture variant of the cache this device needs.
 *
 * The cache ships the same textures three times:
 *
 * <pre>
 *   .../texdb/....dxt.dat   S3TC/DXT   Adreno, Tegra, most desktop-class GPUs
 *   .../texdb/....etc.dat   ETC1      Mali and other ETC-only mobile GPUs
 *   .../texdb/....pvr.dat   PVRTC     PowerVR (some Samsung/MediaTek SoCs)
 * </pre>
 *
 * Downloading all three would roughly triple the cache size, so the manifest is
 * filtered down to the family this device can actually sample.
 *
 * The renderer string is read from a throw-away 1x1 pbuffer surface: it needs
 * no window, works before the UI is laid out, and every EGL object is released
 * again. Any failure falls back to DXT, which is what the original client uses
 * for Adreno-class hardware and is by far the most common case.
 */
public final class GpuDetector {

    private static final String TAG = "GpuDetector";

    public static final int DXT = 1;
    public static final int ETC = 2;
    public static final int PVR = 3;

    private static volatile int cached = 0;
    private static volatile String cachedRenderer = "";

    private GpuDetector() {
    }

    /** @return {@link #DXT}, {@link #ETC} or {@link #PVR} */
    public static int detect() {
        int value = cached;
        if (value != 0) {
            return value;
        }
        String renderer = queryRenderer();
        cachedRenderer = renderer;
        value = fromRenderer(renderer);
        cached = value;
        Log.i(TAG, "renderer=\"" + renderer + "\" -> gpuType=" + value);
        return value;
    }

    /** Last renderer string seen, for the diagnostics log. */
    public static String lastRenderer() {
        return cachedRenderer;
    }

    /** Pure mapping so it can be unit tested without a GL context. */
    public static int fromRenderer(String renderer) {
        if (renderer == null) {
            return DXT;
        }
        String r = renderer.toLowerCase(Locale.US);
        if (r.contains("powervr") || r.contains("sgx") || r.contains("rogue")) {
            return PVR;
        }
        if (r.contains("mali")) {
            return ETC;
        }
        return DXT;
    }

    private static String queryRenderer() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLConfig config = null;

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                return "";
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return "";
            }

            int[] attribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) {
                return "";
            }
            config = configs[0];

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    contextAttribs, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                return "";
            }

            int[] surfaceAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                return "";
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return "";
            }

            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            return renderer == null ? "" : renderer;

        } catch (Throwable t) {
            Log.w(TAG, "cannot query GL renderer", t);
            return "";
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, surface);
                    }
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context);
                    }
                    EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
