#include "model_probe_engine.h"
#include <jni.h>
#include <atomic>
#include <map>
#include <memory>
#include <mutex>

namespace {
std::mutex handles_mutex;
std::map<jlong, std::shared_ptr<subtitle_probe::Cancellation>> handles;
std::atomic<jlong> next_handle{1};
std::shared_ptr<subtitle_probe::Cancellation> find(jlong id) {
    std::lock_guard<std::mutex> lock(handles_mutex);
    auto at = handles.find(id);
    return at == handles.end() ? nullptr : at->second;
}
jbyteArray bytes(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_fr_soustitresvoix_player_LocalModelProbe_nativeCreate(JNIEnv *, jobject) {
    try {
        const auto id = next_handle.fetch_add(1);
        std::lock_guard<std::mutex> lock(handles_mutex);
        handles.emplace(id, std::make_shared<subtitle_probe::Cancellation>());
        return id;
    } catch (...) { return 0; } // Never unwind a C++ allocation failure through JNI.
}
extern "C" JNIEXPORT void JNICALL
Java_fr_soustitresvoix_player_LocalModelProbe_nativeCancel(JNIEnv *, jobject, jlong id) {
    auto handle = find(id); if (handle) handle->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_fr_soustitresvoix_player_LocalModelProbe_nativeDestroy(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(handles_mutex); handles.erase(id);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_fr_soustitresvoix_player_LocalModelProbe_nativeRun(JNIEnv * env, jobject, jlong id,
        jbyteArray path, jint threads, jint max_tokens, jobject listener) {
    try {
        if (!path || !listener) return nullptr;
        auto handle = find(id);
        if (!handle) { subtitle_probe::Result failed; failed.detail = "Session native invalide."; return bytes(env, failed.json()); }
        // POSIX filenames use UTF-8, while GetStringUTFChars uses JNI's modified
        // UTF-8 and cannot represent supplementary characters as filesystem bytes.
        const auto size = env->GetArrayLength(path);
        std::string filename(static_cast<size_t>(size), '\0');
        env->GetByteArrayRegion(path, 0, size, reinterpret_cast<jbyte *>(filename.data()));
        if (env->ExceptionCheck()) return nullptr;
        jclass listener_class = env->GetObjectClass(listener);
        if (!listener_class) return nullptr;
        const auto method = env->GetMethodID(listener_class, "onStage", "(Ljava/lang/String;)V");
        if (!method) { env->DeleteLocalRef(listener_class); return nullptr; }
        auto result = subtitle_probe::run(filename, *handle, threads, max_tokens, [&](const std::string & stage) {
            auto message = env->NewStringUTF(stage.c_str());
            if (!message) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                handle->cancelled.store(true); return;
            }
            env->CallVoidMethod(listener, method, message);
            env->DeleteLocalRef(message);
            if (env->ExceptionCheck()) { env->ExceptionClear(); handle->cancelled.store(true); }
        });
        env->DeleteLocalRef(listener_class);
        return bytes(env, result.json());
    } catch (...) { return nullptr; }
}
