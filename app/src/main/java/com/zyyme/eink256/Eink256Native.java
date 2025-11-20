package com.zyyme.eink256;

import de.robv.android.xposed.XposedBridge;

public class Eink256Native {
    public static void load(String path) {
        XposedBridge.log("library path: " + path);
        try {
            System.load(path);
            XposedBridge.log("load Jni library");
        } catch (Throwable e) {
            XposedBridge.log(e);
            XposedBridge.log("load jni library have error ");
        }
    }

    // 该方法将在 C++ 层被实现
    public static native void ditherBitmap(android.graphics.Bitmap bitmap);
}
