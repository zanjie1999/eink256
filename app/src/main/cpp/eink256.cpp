#include <jni.h>
#include <android/bitmap.h>
#include <cmath>
#include <algorithm>
#include <vector>
#include <cstring>

// 辅助宏：将值限制在 0-255 之间
#define CLAMP(val) (val < 0 ? 0 : (val > 255 ? 255 : val))

// --- 像素访问辅助类 ---

// 针对 ARGB_8888 (32位) 格式的处理
struct Pixel8888 {
    // 计算灰度值
    static int getGray(uint32_t color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        // 经典的亮度公式: Y = 0.299R + 0.587G + 0.114B
        // 整数优化版: (77R + 150G + 29B) >> 8
        return (77 * r + 150 * g + 29 * b) >> 8;
    }

    // 将处理后的灰度值打包回像素
    static uint32_t pack(uint32_t original, int grayVal) {
        // 保留原始的 Alpha 通道
        uint32_t alpha = original & 0xFF000000;
        return alpha | (grayVal << 16) | (grayVal << 8) | grayVal;
    }
};

// 针对 RGB_565 (16位) 格式的处理
// SubsamplingScaleImageView 和 Glide 经常使用此格式以节省内存
struct Pixel565 {
    static int getGray(uint16_t color) {
        // 提取 5-6-5 分量
        int r5 = (color >> 11) & 0x1F;
        int g6 = (color >> 5) & 0x3F;
        int b5 = color & 0x1F;

        // 将分量扩展到 8位 (0-255) 以便进行灰度计算
        // (v << 3) | (v >> 2) 是 * 255 / 31 的快速近似算法
        int r8 = (r5 << 3) | (r5 >> 2);
        int g8 = (g6 << 2) | (g6 >> 4);
        int b8 = (b5 << 3) | (b5 >> 2);

        return (77 * r8 + 150 * g8 + 29 * b8) >> 8;
    }

    static uint16_t pack(uint16_t original, int grayVal) {
        // 将 8位灰度值转换回 5-6-5 格式
        int r5 = grayVal >> 3;
        int g6 = grayVal >> 2;
        int b5 = grayVal >> 3;
        return (r5 << 11) | (g6 << 5) | b5;
    }
};

// --- 模板化抖动算法 ---
// T: 像素数据类型 (uint32_t 或 uint16_t)
// Handler: 负责颜色转换的辅助类
template <typename T, typename Handler>
void applyDitherTemplate(void* pixelsRaw, int width, int height) {
    T* pixels = (T*)pixelsRaw;
    // 16阶灰度，步长 step = 255 / 15 = 17
    const int STEP = 255 / (16 - 1);

    // 使用滑动行缓冲来存储误差，极大地减少内存占用并提高缓存命中率
    // currRowErr: 当前行传递的误差
    // nextRowErr: 下一行累积的误差
    std::vector<int> currRowErr(width, 0);
    std::vector<int> nextRowErr(width, 0);

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int index = y * width + x;
            T originalColor = pixels[index];
            
            // 1. 获取灰度
            int gray = Handler::getGray(originalColor);

            // 2. 加上来自周围像素扩散过来的误差
            gray += currRowErr[x];

            // 3. 量化 (找到最近的 16阶 层级)
            int newGray = std::round((float)gray / STEP) * STEP;
            newGray = CLAMP(newGray);

            // 4. 计算量化误差
            int quantError = gray - newGray;

            // 5. 扩散误差 (Floyd-Steinberg 算法)
            // 系数: 右 7/16, 左下 3/16, 下 5/16, 右下 1/16
            
            // 向右
            if (x + 1 < width) {
                currRowErr[x + 1] += quantError * 7 / 16;
            }
            
            // 向下一行
            if (y + 1 < height) {
                // 左下
                if (x - 1 >= 0) nextRowErr[x - 1] += quantError * 3 / 16;
                // 下
                nextRowErr[x] += quantError * 5 / 16;
                // 右下
                if (x + 1 < width) nextRowErr[x + 1] += quantError * 1 / 16;
            }

            // 6. 写回内存
            pixels[index] = Handler::pack(originalColor, newGray);
        }

        // 行结束：交换缓冲区，清空下一行缓冲区
        currRowErr = nextRowErr;
        std::fill(nextRowErr.begin(), nextRowErr.end(), 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyyme_eink256_Eink256Native_ditherBitmap(JNIEnv* env, jclass clazz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;

    // 根据图片格式分发到不同的处理逻辑
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        // 标准 32位图片
        applyDitherTemplate<uint32_t, Pixel8888>(pixels, info.width, info.height);
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        // 16位图片 (SubsamplingScaleImageView 常用)
        applyDitherTemplate<uint16_t, Pixel565>(pixels, info.width, info.height);
    }
    // 其他格式暂不支持（如 HARDWARE 已在 Java 层被拦截降级）

    AndroidBitmap_unlockPixels(env, bitmap);
}