package com.cablebee.pkgserver;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Core logic:
 *  1. Obtain a real android.content.Context via ActivityThread (shell权限即可)
 *  2. Bind to IPackageManager via ServiceManager to get PackageInfo
 *  3. Build a per-APK Resources instance to resolve labelRes → string, icon → Bitmap
 *  4. Compress Bitmap → PNG → Base64, emit JSON to stdout
 */
class PackageServer {

    // ── shared display/config for Resources construction ──────────────────────
    private static final DisplayMetrics DM;
    private static final Configuration  CFG;

    static {
        DM  = new DisplayMetrics();
        DM.setToDefaults();
        // bump density so we get xxxhdpi icons when available
        DM.density       = 4.0f;
        DM.densityDpi    = 640;
        DM.xdpi          = 640;
        DM.ydpi          = 640;
        DM.widthPixels   = 1080;
        DM.heightPixels  = 1920;
        CFG = new Configuration();
        CFG.setToDefaults();
    }

    // ── IPackageManager binder obtained via reflection ────────────────────────
    private final Object ipm;   // android.content.pm.IPackageManager

    // ── getPackageInfo Method (signature differs pre/post API 32) ─────────────
    private final Method getPackageInfo;

    PackageServer() throws Exception {
        // 1. Get IPackageManager via ServiceManager
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Method   getService = smClass.getDeclaredMethod("getService", String.class);
        Object   binder = getService.invoke(null, "package"); // IBinder

        Class<?> ipmStub = Class.forName("android.content.pm.IPackageManager$Stub");
        Method   asInterface = ipmStub.getDeclaredMethod("asInterface", Class.forName("android.os.IBinder"));
        ipm = asInterface.invoke(null, binder);

        // 2. Resolve getPackageInfo method — signature changed in API 32
        Class<?> ipmClass = ipm.getClass();
        if (Build.VERSION.SDK_INT >= 32) {
            // getPackageInfo(String packageName, long flags, int userId)
            getPackageInfo = ipmClass.getMethod("getPackageInfo", String.class, long.class, int.class);
        } else {
            // getPackageInfo(String packageName, int flags, int userId)
            getPackageInfo = ipmClass.getMethod("getPackageInfo", String.class, int.class, int.class);
        }
    }

    // ── dump all packages ─────────────────────────────────────────────────────

    void dumpAll(String sortBy) throws Exception {
        List<String> packages = getAllPackageNames();
        System.err.println("====loading " + packages.size() + " packages====");

        if ("label".equals(sortBy)) {
            // 按 label 排序：先采集所有 label，再排序后输出
            // 用 LinkedHashMap 保持插入顺序
            final java.util.Map<String, String> labelMap = new java.util.LinkedHashMap<>();
            for (String pkg : packages) {
                try {
                    PackageInfo pi = getPackageInfoForPackage(pkg);
                    if (pi == null || pi.applicationInfo == null) {
                        labelMap.put(pkg, pkg);
                        continue;
                    }
                    ApplicationInfo ai = pi.applicationInfo;
                    String label = pkg;
                    if (ai.sourceDir != null) {
                        try {
                            Resources res = buildResources(ai.sourceDir);
                            label = loadLabel(ai, res);
                        } catch (Throwable ignored) {}
                    }
                    labelMap.put(pkg, label.toLowerCase());
                } catch (Throwable t) {
                    labelMap.put(pkg, pkg);
                }
            }
            // 按 label 排序包名列表
            java.util.List<String> sorted = new java.util.ArrayList<>(packages);
            java.util.Collections.sort(sorted, new java.util.Comparator<String>() {
                public int compare(String a, String b) {
                    String la = labelMap.containsKey(a) ? labelMap.get(a) : a;
                    String lb = labelMap.containsKey(b) ? labelMap.get(b) : b;
                    return la.compareTo(lb);
                }
            });
            for (String pkg : sorted) {
                try { dumpPackage(pkg); } catch (Throwable t) { emitError(pkg, t.getMessage()); }
            }
        } else {
            // 默认按包名排序（快，直接排序后逐条输出）
            java.util.Collections.sort(packages);
            for (String pkg : packages) {
                try { dumpPackage(pkg); } catch (Throwable t) { emitError(pkg, t.getMessage()); }
            }
        }
    }

    // ── dump single package ───────────────────────────────────────────────────

    void dumpPackage(String packageName) throws Exception {
        PackageInfo pi = getPackageInfoForPackage(packageName);
        if (pi == null) {
            emitError(packageName, "PackageInfo is null");
            return;
        }

        ApplicationInfo ai = pi.applicationInfo;
        if (ai == null) {
            emitError(packageName, "ApplicationInfo is null");
            return;
        }

        JSONObject json = new JSONObject();
        json.put("package", packageName);

        // ── APK metadata ───────────────────────────────────────────────────
        String sourceDir = ai.sourceDir;
        json.put("apkPath", sourceDir != null ? sourceDir : "");
        if (sourceDir != null) {
            try { json.put("apkSize", new java.io.File(sourceDir).length()); }
            catch (Throwable ignored) {}
        }
        json.put("flags",       ai.flags);
        json.put("enabled",     ai.enabled);
        json.put("dataDir",     ai.dataDir != null ? ai.dataDir : "");
        json.put("versionCode", pi.versionCode);
        json.put("versionName", pi.versionName != null ? pi.versionName : "");
        json.put("firstInstallTime", pi.firstInstallTime);
        json.put("lastUpdateTime",   pi.lastUpdateTime);
        json.put("targetSdkVersion", ai.targetSdkVersion);
        if (Build.VERSION.SDK_INT >= 24) {
            json.put("minSdkVersion", ai.minSdkVersion);
        }

        // ── label + icon via per-APK Resources ────────────────────────────
        if (sourceDir != null) {
            try {
                Resources res = buildResources(sourceDir);
                json.put("label", loadLabel(ai, res));
                String iconB64 = loadIconBase64(ai, res);
                if (iconB64 != null) json.put("icon", iconB64);
            } catch (Throwable t) {
                // fallback: just use packageName as label, no icon
                json.put("label", packageName);
                System.err.println("resources error for " + packageName + ": " + t.getMessage());
            }
        } else {
            json.put("label", packageName);
        }

        System.out.println(json.toString());
        System.out.flush();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Get all package names via IPackageManager.getAllPackages() */
    @SuppressWarnings("unchecked")
    private List<String> getAllPackageNames() throws Exception {
        Method getAllPackages = ipm.getClass().getMethod("getAllPackages");
        return (List<String>) getAllPackages.invoke(ipm);
    }

    /** Call IPackageManager.getPackageInfo — userId 0 */
    private PackageInfo getPackageInfoForPackage(String pkg) throws Exception {
        Object result;
        if (Build.VERSION.SDK_INT >= 32) {
            result = getPackageInfo.invoke(ipm, pkg, 1L /* GET_ACTIVITIES */, 0);
        } else {
            result = getPackageInfo.invoke(ipm, pkg, 1  /* GET_ACTIVITIES */, 0);
        }
        return (PackageInfo) result;
    }

    /**
     * Build an android.content.res.Resources instance that reads from the given APK.
     * Uses reflection to call AssetManager.addAssetPath(String) which is hidden API
     * but always available in shell/app_process context.
     */
    private Resources buildResources(String apkPath) throws Exception {
        // AssetManager am = new AssetManager();
        Constructor<?> amCtor = AssetManager.class.getDeclaredConstructor();
        amCtor.setAccessible(true);
        AssetManager am = (AssetManager) amCtor.newInstance();

        // am.addAssetPath(apkPath)
        Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
        addAssetPath.invoke(am, apkPath);

        return new Resources(am, DM, CFG);
    }

    /**
     * Resolve applicationInfo.labelRes → string, with fallbacks:
     *   nonLocalizedLabel → resources.getText(labelRes) → packageName
     */
    private String loadLabel(ApplicationInfo ai, Resources res) {
        // nonLocalizedLabel is set for some system apps
        if (ai.nonLocalizedLabel != null && ai.nonLocalizedLabel.length() > 0) {
            return ai.nonLocalizedLabel.toString();
        }
        if (ai.labelRes != 0) {
            try {
                CharSequence cs = res.getText(ai.labelRes);
                if (cs != null && cs.length() > 0) return cs.toString();
            } catch (Throwable ignored) {}
        }
        return ai.packageName;
    }

    /**
     * Resolve applicationInfo.icon resource ID → Drawable → Bitmap → PNG → Base64.
     * Returns null if icon is unavailable or conversion fails.
     */
    private String loadIconBase64(ApplicationInfo ai, Resources res) {
        if (ai.icon == 0) return null;
        try {
            Drawable drawable;
            if (Build.VERSION.SDK_INT >= 21) {
                drawable = res.getDrawable(ai.icon, null);
            } else {
                //noinspection deprecation
                drawable = res.getDrawable(ai.icon);
            }
            if (drawable == null) return null;

            Bitmap bmp = drawableToBitmap(drawable);
            if (bmp == null) return null;

            // Scale down to 96×96 — enough for display, keeps JSON small
            if (bmp.getWidth() > 96 || bmp.getHeight() > 96) {
                bmp = Bitmap.createScaledBitmap(bmp, 96, 96, true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            bmp.recycle();

            byte[] pngBytes = baos.toByteArray();
            return android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            System.err.println("icon error for " + ai.packageName + ": " + t.getMessage());
            return null;
        }
    }

    /** Convert any Drawable (including AdaptiveIconDrawable) to a Bitmap */
    private Bitmap drawableToBitmap(Drawable drawable) {
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0) w = 96;
        if (h <= 0) h = 96;

        // Detect AdaptiveIconDrawable (API 26+) — needs special handling
        // We wrap it in a plain canvas; Android will composite fg+bg automatically
        // when drawn via Canvas, so the result looks correct.
        Bitmap.Config config = drawable.getOpacity() != android.graphics.PixelFormat.OPAQUE
                ? Bitmap.Config.ARGB_8888
                : Bitmap.Config.RGB_565;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // For AdaptiveIconDrawable, fill white background first
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Class<?> aidClass = Class.forName("android.graphics.drawable.AdaptiveIconDrawable");
                if (aidClass.isInstance(drawable)) {
                    canvas.drawColor(0xFFFFFFFF); // white bg
                }
            }
        } catch (Throwable ignored) {}

        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bmp;
    }

    private static void emitError(String pkg, String msg) {
        try {
            JSONObject j = new JSONObject();
            j.put("package", pkg);
            j.put("error", msg != null ? msg : "unknown");
            System.out.println(j.toString());
            System.out.flush();
        } catch (Throwable ignored) {}
    }
}
