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
 * The cache ships the same textures in four flavours, and the game picks one at
 * startup:
 *
 * <pre>
 *   texdb/&lt;db&gt;/&lt;db&gt;.dxt      + &lt;db&gt;.dxt.tmb    S3TC / DXT
 *   texdb/&lt;db&gt;/&lt;db&gt;.pvr      + &lt;db&gt;.pvr.tmb    PVRTC
 *   texdb/&lt;db&gt;/&lt;db&gt;.etc      + &lt;db&gt;.etc.tmb    ETC1
 *   texdb/&lt;db&gt;/&lt;db&gt;.unc.tmb                 uncompressed fallback
 * </pre>
 *
 * <b>Why the renderer name is not enough.</b> libGTASA does not look at the
 * vendor string; it probes the GL extension list and sets three flags, which are
 * visible as strings inside the shipped binary:
 *
 * <pre>
 *   has_s3tc    GL_EXT_texture_compression_s3tc
 *   has_pvrtc   GL_IMG_texture_compression_pvrtc
 *   has_atitc   GL_AMD_compressed_ATC_texture
 * </pre>
 *
 * A PowerVR Rogue GE8320 device reports "PowerVR" as the renderer but its driver
 * also advertises S3TC, so the game loads {@code mobile.dxt.tmb}. Choosing PVR
 * from the vendor name made the installer fetch the wrong index, the game opened
 * a missing file and died with SIGSEGV in TextureDatabaseRuntime::SortEntries.
 *
 * This class therefore walks the exact same extension list, in the same order of
 * preference: S3TC first, then PVRTC, then ETC1 - ETC1 is mandatory in OpenGL ES
 * 2.0, so it is also the safe answer when nothing else is advertised.
 */
public final class GpuDetector {

    private static final String TAG = "GpuDetector";

    public static final int DXT = 1;
    public static final int ETC = 2;
    public static final int PVR = 3;

    /** Extension names, taken verbatim from libGTASA.so. */
    static final String EXT_S3TC = "GL_EXT_texture_compression_s3tc";
    static final String EXT_DXT1 = "GL_EXT_texture_compression_dxt1";
    static final String EXT_PVRTC = "GL_IMG_texture_compression_pvrtc";
    static final String EXT_ATC = "GL_AMD_compressed_ATC_texture";

    private static volatile int cached = 0;
    private static volatile String cachedRenderer = "";
    private static volatile String cachedExtensions = "";

    private GpuDetector() {
    }

    /** @return {@link #DXT}, {@link #ETC} or {@link #PVR} */
    public static int detect() {
        int value = cached;
        if (value != 0) {
            return value;
        }
        query();          // fills renderer + extensions
        value = decide(cachedRenderer, cachedExtensions);
        cached = value;
        Log.i(TAG, "renderer=\"" + cachedRenderer + "\" -> gpuType=" + value
                + " (" + formatSuffixFor(value) + ")");
        return value;
    }

    /** Last renderer string seen, for the diagnostics log. */
    public static String lastRenderer() {
        return cachedRenderer;
    }

    /** Last GL extension string seen, for the diagnostics log. */
    public static String lastExtensions() {
        return cachedExtensions;
    }

    /** File-name token the cache uses for this family: dxt, etc or pvr. */
    public static String formatSuffixFor(int gpuType) {
        switch (gpuType) {
            case DXT:
                return "dxt";
            case PVR:
                return "pvr";
            default:
                return "etc";
        }
    }

    /** Suffix of the family this device needs, after detection. */
    public static String formatSuffix() {
        return formatSuffixFor(detect());
    }

    /**
     * Mirrors the game's own decision.
     *
     * @param renderer   GL_RENDERER, only used when the extension list is empty
     * @param extensions GL_EXTENSIONS
     */
    static int decide(String renderer, String extensions) {
        String ext = extensions == null ? "" : extensions.toUpperCase(Locale.US);

        if (ext.contains(EXT_S3TC.toUpperCase(Locale.US))
                || ext.contains(EXT_DXT1.toUpperCase(Locale.US))) {
            return DXT;
        }
        if (ext.contains(EXT_PVRTC.toUpperCase(Locale.US))) {
            return PVR;
        }
        if (ext.contains(EXT_ATC.toUpperCase(Locale.US))) {
            // ATC is ETC1 on the wire, so the .etc family is the right one.
            return ETC;
        }
        if (!ext.isEmpty()) {
            // ETC1 is compulsory in GLES2: usable on every driver.
            return ETC;
        }

        // The GL query failed entirely (no EGL, no surface...). Fall back to the
        // vendor name; the game could not start in this state anyway.
        return fromRenderer(renderer);
    }

    /** Vendor-name fallback, kept for the "no GL at all" case and for tests. */
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

    /** Extensions-only helper. */
    public static int fromExtensions(String extensions) {
        return decide("", extensions);
    }

    // ------------------------------------------------------------------
    // GL query: a throw-away 1x1 pbuffer, released immediately
    // ------------------------------------------------------------------

    private static synchronized void query() {
        if (cachedRenderer != null && !cachedRenderer.isEmpty()) {
            return;
        }

        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                return;
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return;
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
                return;
            }

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    contextAttribs, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                return;
            }

            int[] surfaceAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                return;
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return;
            }

            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            cachedRenderer = renderer == null ? "" : renderer;
            cachedExtensions = extensions == null ? "" : extensions;

        } catch (Throwable t) {
            Log.w(TAG, "cannot query GL renderer/extensions", t);
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
