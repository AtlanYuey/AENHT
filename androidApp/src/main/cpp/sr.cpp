// Super-resolution for the reader (see core/SuperResolution.kt): one tile
// of a page through a Real-ESRGAN model (x4) with ncnn, the tile's middle
// shrunk to the size it shows at and written into the result bitmap.

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <vector>

#include "cpu.h"
#include "gpu.h"
#include <atomic>
#include "net.h"

namespace {

std::once_flag gpuOnce;
bool hasGpu = false;
/// The GPU gave broken pictures on this device (checked in Kotlin): the CPU from then on.
std::atomic<bool> cpuOnly{false};

bool gpu() {
    std::call_once(gpuOnce, [] {
        ncnn::create_gpu_instance();
        hasGpu = ncnn::get_gpu_count() > 0;
    });
    return hasGpu;
}

ncnn::Net* newNet() {
    auto* net = new ncnn::Net;
    bool vulkan = gpu() && !cpuOnly.load();
    net->opt.use_vulkan_compute = vulkan;
    net->opt.use_fp16_packed = vulkan;
    net->opt.use_fp16_storage = vulkan;
    net->opt.use_fp16_arithmetic = false;
    net->opt.num_threads = ncnn::get_big_cpu_count();
    return net;
}

/// Box-filter resampling along one axis (area average; the scale is at most 1).
void resample(const float* src, int count, int srcStep, float* dst, int outCount, int dstStep) {
    double ratio = static_cast<double>(count) / outCount;
    for (int i = 0; i < outCount; i++) {
        double a = i * ratio;
        double b = (i + 1) * ratio;
        double sum = 0;
        double weight = 0;
        for (int k = static_cast<int>(a); k < b && k < count; k++) {
            double w = std::min(b, k + 1.0) - std::max(a, static_cast<double>(k));
            if (w <= 0) continue;
            sum += w * src[k * srcStep];
            weight += w;
        }
        dst[i * dstStep] = weight > 0 ? static_cast<float>(sum / weight) : 0.f;
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_atlanyuey_aenht_core_SrNative_loadAssets(JNIEnv* env, jclass, jobject assets, jstring param, jstring model) {
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* m = env->GetStringUTFChars(model, nullptr);
    ncnn::Net* net = newNet();
    bool ok = net->load_param(manager, p) == 0 && net->load_model(manager, m) == 0;
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(model, m);
    if (!ok) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

JNIEXPORT jlong JNICALL
Java_com_atlanyuey_aenht_core_SrNative_loadFiles(JNIEnv* env, jclass, jstring param, jstring model) {
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* m = env->GetStringUTFChars(model, nullptr);
    ncnn::Net* net = newNet();
    bool ok = net->load_param(p) == 0 && net->load_model(m) == 0;
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(model, m);
    if (!ok) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

JNIEXPORT void JNICALL
Java_com_atlanyuey_aenht_core_SrNative_setCpuOnly(JNIEnv*, jclass, jboolean value) {
    cpuOnly.store(value == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_atlanyuey_aenht_core_SrNative_release(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_atlanyuey_aenht_core_SrNative_usesGpu(JNIEnv*, jclass) {
    return gpu() && !cpuOnly.load();
}

/// Runs the region (rx, ry, rw, rh) of [input] (its context included), and
/// writes the part for (cx, cy, cw, ch) into [output] at dx0..dx1, dy0..dy1.
JNIEXPORT jboolean JNICALL
Java_com_atlanyuey_aenht_core_SrNative_tile(JNIEnv* env, jclass, jlong handle, jobject input,
                                            jint rx, jint ry, jint rw, jint rh,
                                            jint cx, jint cy, jint cw, jint ch,
                                            jobject output, jint dx0, jint dy0, jint dx1, jint dy1) {
    auto* net = reinterpret_cast<ncnn::Net*>(handle);
    if (!net) return JNI_FALSE;

    AndroidBitmapInfo inInfo;
    if (AndroidBitmap_getInfo(env, input, &inInfo) < 0 || inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* inPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input, &inPixels) < 0) return JNI_FALSE;
    ncnn::Mat in = ncnn::Mat::from_pixels_roi(static_cast<const unsigned char*>(inPixels), ncnn::Mat::PIXEL_RGBA2RGB,
                                              static_cast<int>(inInfo.width), static_cast<int>(inInfo.height),
                                              static_cast<int>(inInfo.stride), rx, ry, rw, rh);
    AndroidBitmap_unlockPixels(env, input);

    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(nullptr, norm);

    ncnn::Mat out;
    {
        ncnn::Extractor ex = net->create_extractor();
        if (ex.input("data", in) != 0 || ex.extract("output", out) != 0) return JNI_FALSE;
    }
    if (out.c != 3 || out.w != rw * 4 || out.h != rh * 4) return JNI_FALSE;

    // The tile's middle in the x4 result, shrunk to the size it shows at.
    const int sx = 4 * (cx - rx), sy = 4 * (cy - ry), sw = 4 * cw, sh = 4 * ch;
    const int dw = dx1 - dx0, dh = dy1 - dy0;
    if (dw <= 0 || dh <= 0 || dw > sw || dh > sh) return JNI_FALSE;
    std::vector<float> rows(static_cast<size_t>(sh) * dw);
    std::vector<float> result(static_cast<size_t>(dh) * dw * 3);
    for (int c = 0; c < 3; c++) {
        const ncnn::Mat plane = out.channel(c);
        for (int y = 0; y < sh; y++) {
            const float* line = plane.row(sy + y) + sx;
            resample(line, sw, 1, rows.data() + static_cast<size_t>(y) * dw, dw, 1);
        }
        for (int x = 0; x < dw; x++) {
            resample(rows.data() + x, sh, dw, result.data() + static_cast<size_t>(c) * dh * dw + x, dh, dw);
        }
    }

    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, output, &outInfo) < 0 || outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (dx1 > static_cast<int>(outInfo.width) || dy1 > static_cast<int>(outInfo.height) || dx0 < 0 || dy0 < 0) return JNI_FALSE;
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, output, &outPixels) < 0) return JNI_FALSE;
    auto* base = static_cast<unsigned char*>(outPixels);
    for (int y = 0; y < dh; y++) {
        unsigned char* p = base + static_cast<size_t>(dy0 + y) * outInfo.stride + static_cast<size_t>(dx0) * 4;
        for (int x = 0; x < dw; x++) {
            for (int c = 0; c < 3; c++) {
                float v = result[static_cast<size_t>(c) * dh * dw + static_cast<size_t>(y) * dw + x] * 255.f + 0.5f;
                p[x * 4 + c] = static_cast<unsigned char>(std::clamp(v, 0.f, 255.f));
            }
            p[x * 4 + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, output);
    return JNI_TRUE;
}

}  // extern "C"
