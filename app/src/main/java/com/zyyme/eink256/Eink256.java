package com.zyyme.eink256;


import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Eink256 implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static String modulePath;

    String findModuleSo(String soName) {
        File baseDir = new File(modulePath).getParentFile();
        File libDir = new File(baseDir, "lib");

        File[] archDirs = libDir.listFiles();
        if (archDirs != null) {
            for (File archDir : archDirs) {
                File candidate = new File(archDir, soName);
                if (candidate.exists()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        // 获取模块自身的安装路径，用于后续加载 .so 文件
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        // 防止 Hook 模块自身导致死循环
        if (lpparam.packageName.equals("com.zyyme.eink256")) return;

        XposedBridge.log("zyymeEink256: Processing package " + lpparam.packageName);

        // 加载 Native 库
        loadNativeLib();

        // 1. BitmapRegionDecoder
        // 目标: SubsamplingScaleImageView, 阅读器, 漫画应用等大图控件
        hookBitmapRegionDecoder();

        // 2. BitmapFactory  用的最多
        // 目标: Glide, Picasso, 普通 ImageView, 背景图等
        hookBitmapFactory();

        // 3. ImageDecoder (Android P / API 28+)
        // 目标: TachiyomiJ2K, Coil, 以及所有使用新版 API 的应用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hookImageDecoder();
        }

        // 4. Canvas 绘制 Hook   实测他们抖不用这个绘制
//        hookCanvasDraw();
    }

    /**
     * 加载 JNI 库的逻辑
     */
    private void loadNativeLib() {
//        try {
//            // 尝试直接加载 (在较新的 Android 版本或某些环境可能有效)
//            System.loadLibrary("zyymeEink256");
//        } catch (Throwable t1) {
            try {
                // 备用加载方式 抄伏犬的
                Eink256Native.load(findModuleSo("/libzyymeEink256.so"));
            } catch (Throwable t2) {
                XposedBridge.log("zyymeEink256: Native 库加载失败: " + t2.getMessage());
            }
//        }
    }

    /**
     * Hook 区域解码器，这是大图分块加载的核心
     */
    private void hookBitmapRegionDecoder() {
        Class<?> decoderClass = android.graphics.BitmapRegionDecoder.class;

        // Hook decodeRegion(Rect rect, BitmapFactory.Options options)
        XposedBridge.hookAllMethods(decoderClass, "decodeRegion", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // 关键优化：在解码发生前，强制设置 Options
                // 参数索引 1 通常是 BitmapFactory.Options
                if (param.args.length > 1 && param.args[1] instanceof BitmapFactory.Options) {
                    BitmapFactory.Options opts = (BitmapFactory.Options) param.args[1];
                    forceMutable(opts);
                } else if (param.args.length > 1 && param.args[1] == null) {
                    // 如果 App 传了 null，我们需要创建一个 Options 塞进去，确保可变性
                    // 注意：这可能会改变 App 的预期行为，但在墨水屏场景下是必要的
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    forceMutable(opts);
                    param.args[1] = opts;
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Bitmap result = (Bitmap) param.getResult();
//                Log.d("zyymeEink256", "hookBitmapRegionDecoder");
                processBitmap(result);
            }
        });
    }

    /**
     * Hook 通用图片工厂
     */
    private void hookBitmapFactory() {
        Set<String> targetMethods = new HashSet<>(Arrays.asList(
                "decodeFile", "decodeStream", "decodeResource",
                "decodeByteArray", "decodeFileDescriptor"
        ));

        XC_MethodHook factoryHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // 遍历参数寻找 BitmapFactory.Options
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof BitmapFactory.Options) {
                        forceMutable((BitmapFactory.Options) param.args[i]);
                        break;
                    }
                }
                // 如果没找到 Options，原则上应该新建一个并注入，
                // 但 BitmapFactory 的重载非常多，简单起见只处理带 Options 的版本，
                // 或者依赖它内部调用链最终会走到带 Options 的底层方法。
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
//                Log.d("zyymeEink256", "hookBitmapFactory " + param.method.getName());
                processBitmap((Bitmap) param.getResult());
            }
        };

        for (String method : targetMethods) {
            XposedBridge.hookAllMethods(BitmapFactory.class, method, factoryHook);
        }
    }

    /**
     * 强制修改解码选项
     */
    private void forceMutable(BitmapFactory.Options options) {
        if (options == null) return;

        // 1. 强制可变：这是 JNI 能修改像素的前提
        // 如果不设置，系统可能会返回 Immutable Bitmap，JNI lockPixels 会失败
        options.inMutable = true;

        // 2. 禁用硬件位图 (Hardware Bitmap)
        // Android 8.0+ 引入，存储在显存中，JNI 无法读取。
        // 必须降级为软件位图 (ARGB_8888 或 RGB_565)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (options.inPreferredConfig == Bitmap.Config.HARDWARE) {
                // 降级为 ARGB_8888，确保兼容性
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            }
        }
    }

    /**
     * Hook 新的ImageDecoder
     */
    @TargetApi(Build.VERSION_CODES.P)
    private void hookImageDecoder() {
        // ImageDecoder.decodeBitmap(Source, OnHeaderDecodedListener)
        XposedBridge.hookAllMethods(ImageDecoder.class, "decodeBitmap", new XC_MethodHook() {
            @TargetApi(Build.VERSION_CODES.P)
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // 参数通常是 (Source, Listener) 或者 (Source)

                ImageDecoder.OnHeaderDecodedListener originalListener = null;
                int listenerIndex = -1;

                // 寻找原始的 Listener 参数
                if (param.args.length > 1 && param.args[1] instanceof ImageDecoder.OnHeaderDecodedListener) {
                    originalListener = (ImageDecoder.OnHeaderDecodedListener) param.args[1];
                    listenerIndex = 1;
                }

                // 创建我们的代理 Listener，用于注入配置
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    final ImageDecoder.OnHeaderDecodedListener proxyListener =
                            new DitherHeaderListener(originalListener);

                    // 如果原方法有 Listener 参数，替换它
                    if (listenerIndex != -1) {
                        param.args[listenerIndex] = proxyListener;
                    }
                    // 如果原方法没有 Listener 参数（例如单参数重载），我们很难强行插入，
                    // 因为 decodeBitmap 是静态方法。不过 Coil 等库通常会使用带 Listener 的版本。
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Bitmap result = (Bitmap) param.getResult();
//                Log.d("zyymeEink256", "hookImageDecoder");
                processBitmap(result);
            }
        });
    }

    /**
     * Hook Canvas绘制
     */
    private void hookCanvasDraw() {
        // Hook drawBitmap(Bitmap bitmap, float left, float top, Paint paint)
        XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, float.class, float.class, Paint.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // 绘制前的位图是参数 0
                Bitmap bitmap = (Bitmap) param.args[0];
//                Log.d("zyymeEink256", "hookCanvasDraw1");
                processBitmap(bitmap);
            }
        });

        // Hook drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) - 用于缩放/裁剪绘制
        XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Rect.class, RectF.class, Paint.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Bitmap bitmap = (Bitmap) param.args[0];
//                Log.d("zyymeEink256", "hookCanvasDraw2");
                processBitmap(bitmap);
            }
        });

        // Hook drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) - 用于复杂变换绘制
        XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Matrix.class, Paint.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Bitmap bitmap = (Bitmap) param.args[0];
//                Log.d("zyymeEink256", "hookCanvasDraw3");
                processBitmap(bitmap);
            }
        });
    }


    // 代理 Listener 类，用于强制修改 ImageDecoder 的配置
    // 必须是静态类以避免内存泄漏
    @TargetApi(Build.VERSION_CODES.P)
    static class DitherHeaderListener implements ImageDecoder.OnHeaderDecodedListener {
        private final ImageDecoder.OnHeaderDecodedListener original;

        DitherHeaderListener(ImageDecoder.OnHeaderDecodedListener original) {
            this.original = original;
        }

        @TargetApi(Build.VERSION_CODES.P)
        @Override
        public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
            // 1. 强制使用软件内存分配 (Software Allocator)
            // 这是解决 TachiyomiJ2K 黑屏/无效果的关键。Hardware Bitmap 无法被 JNI 修改。
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);

            // 2. 强制可变 (Mutable)
            decoder.setMutableRequired(true);

            // 3. 执行 App 原本的逻辑 (如果有)
            if (original != null) {
                original.onHeaderDecoded(decoder, info, source);
            }
        }
    }

    /**
     * 调用 JNI 处理 Bitmap
     */
    private void processBitmap(Bitmap bitmap) {
        if (bitmap == null) return;
        if (bitmap.isRecycled()) return;

        // 二次检查：如果 Bitmap 不可变，我们无法原地修改
        if (!bitmap.isMutable()) {
            XposedBridge.log("zyymeEink256: Bitmap不可变，跳过");
            // 可以在这里尝试 copy 一份并 replaceResult，但性能开销大，
            // 最好还是依靠 beforeHookedMethod 里的 forceMutable。
            return;
        }

        // 只对足够大的位图应用抖动，避免处理小图标等
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 64 || height < 64) {
            return;
        }

        try {
            Eink256Native.ditherBitmap(bitmap);
        } catch (Throwable t) {
            XposedBridge.log("zyymeEink256: 调用jni出现异常: " + t.getMessage());
            // 捕获所有异常，防止导致宿主应用崩溃
            // 常见错误：UnsatisfiedLinkError (库未加载), IllegalStateException
        }
    }
}
