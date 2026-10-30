#include "local_translation_engine.h"
#include <jni.h>
#include <atomic>
#include <map>
#include <memory>
#include <mutex>

namespace {
std::mutex registry_mutex;
std::map<jlong, std::shared_ptr<subtitle_local::Session>> sessions;
std::atomic<jlong> next_id{1};
std::shared_ptr<subtitle_local::Session> find(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto at = sessions.find(id); return at == sessions.end() ? nullptr : at->second;
}
jbyteArray bytes(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
bool read(JNIEnv * env, jbyteArray array, std::string & result, int maximum) {
    if (!array) return false;
    const auto n = env->GetArrayLength(array);
    if (n < 1 || n > maximum) return false;
    result.resize(n); env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(result.data()));
    return !env->ExceptionCheck();
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_fr_soustitresvoix_player_LocalGgufRuntime_nativeCreate(JNIEnv *, jobject) {
    try {
        const auto id = next_id.fetch_add(1);
        const auto session = std::make_shared<subtitle_local::Session>();
        std::lock_guard<std::mutex> lock(registry_mutex); sessions.emplace(id, session); return id;
    } catch (...) { return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_fr_soustitresvoix_player_LocalGgufRuntime_nativeCancel(JNIEnv *, jobject, jlong id) {
    const auto session = find(id); if (session) session->cancel();
}
extern "C" JNIEXPORT void JNICALL
Java_fr_soustitresvoix_player_LocalGgufRuntime_nativeDestroy(JNIEnv *, jobject, jlong id) {
    std::shared_ptr<subtitle_local::Session> retained;
    { std::lock_guard<std::mutex> lock(registry_mutex); const auto at = sessions.find(id); if (at != sessions.end()) { retained = at->second; sessions.erase(at); } }
    if (retained) retained->cancel();
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_fr_soustitresvoix_player_LocalGgufRuntime_nativeOpen(JNIEnv * env, jobject, jlong id, jbyteArray path,
        jint threads, jint context, jstring execution, jint gpu_layers, jbyteArray cache_path,
        jint batch_tokens, jint micro_batch_tokens, jboolean compact_swa, jobject progress) {
    try {
        const auto session = find(id); std::string filename, cache;
        if (!session || !read(env, path, filename, 32768) || !execution || !cache_path) return nullptr;
        if (env->GetArrayLength(cache_path) > 0 && !read(env, cache_path, cache, 32768)) return nullptr;
        const char * mode_chars = env->GetStringUTFChars(execution, nullptr);
        if (!mode_chars) return nullptr;
        const std::string mode(mode_chars);
        env->ReleaseStringUTFChars(execution, mode_chars);
        jmethodID on_progress = nullptr;
        if (progress) {
            jclass type = env->GetObjectClass(progress);
            if (type) {
                on_progress = env->GetMethodID(type, "onProgress", "(Ljava/lang/String;I)V");
                env->DeleteLocalRef(type);
            }
            // Optional telemetry must not prevent the actual model attempt.
            if (env->ExceptionCheck()) { env->ExceptionClear(); on_progress = nullptr; }
        }
        const auto report = [env, progress, on_progress](const char * stage, int percent) {
            if (!on_progress) return;
            // Pinned llama.cpp invokes its loading progress callback on the
            // nativeOpen calling thread. This JNI env and argument stay valid
            // for the synchronous duration of Session::open.
            jstring text = env->NewStringUTF(stage);
            if (text) {
                env->CallVoidMethod(progress, on_progress, text, static_cast<jint>(percent));
                env->DeleteLocalRef(text);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        };
        return bytes(env, session->open(filename, threads, context, mode, gpu_layers, cache,
            batch_tokens, micro_batch_tokens, compact_swa == JNI_TRUE, report).json());
    } catch (...) { return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_fr_soustitresvoix_player_LocalGgufRuntime_nativeGenerate(JNIEnv * env, jobject, jlong id, jbyteArray prompt, jint tokens, jboolean add_special) {
    try {
        const auto session = find(id); std::string text;
        if (!session || !read(env, prompt, text, 65536)) return nullptr;
        return bytes(env, session->generate(text, tokens, add_special).json());
    } catch (...) { return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_fr_soustitresvoix_player_LocalGgufRuntime_nativeGenerateChat(JNIEnv * env, jobject, jlong id, jbyteArray user_content, jint tokens) {
    try {
        const auto session = find(id); std::string text;
        if (!session) return nullptr;
        if (!read(env, user_content, text, 131072)) {
            if (env->ExceptionCheck()) return nullptr;
            // Reject an absent, empty or oversized input without copying it,
            // while preserving the visible TEMPLATE/CANCELLED result and the
            // normal failure path that invalidates any reusable prompt cache.
            return bytes(env, session->generate("", tokens, false, true).json());
        }
        return bytes(env, session->generate(text, tokens, false, true).json());
    } catch (...) { return nullptr; }
}
