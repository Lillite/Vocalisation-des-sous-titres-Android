#include "local_translation_engine.h"
#include "local_model_backend.h"
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <sys/resource.h>
#include <vector>
#include <sstream>
#include <unistd.h>

namespace subtitle_local {
namespace {
using Clock = std::chrono::steady_clock;
long long elapsed(Clock::time_point start) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count(); }
long long peak_rss() { struct rusage v{}; return getrusage(RUSAGE_SELF, &v) == 0 ? static_cast<long long>(v.ru_maxrss) * 1024 : 0; }
std::string metadata(llama_model * model, const char * key) {
    char value[512]{};
    const int length = llama_model_meta_val_str(model, key, value, sizeof(value));
    return length > 0 && length < static_cast<int>(sizeof(value)) ? std::string(value, length) : std::string();
}
bool utf8(const std::string & value) {
    const auto * p = reinterpret_cast<const unsigned char *>(value.data());
    size_t i = 0;
    while (i < value.size()) {
        unsigned int cp = p[i++];
        if (cp <= 0x7f) continue;
        int following;
        if (cp >= 0xc2 && cp <= 0xdf) { cp &= 0x1f; following = 1; }
        else if (cp >= 0xe0 && cp <= 0xef) { cp &= 0x0f; following = 2; }
        else if (cp >= 0xf0 && cp <= 0xf4) { cp &= 0x07; following = 3; }
        else return false;
        if (i + following > value.size()) return false;
        for (int n = 0; n < following; ++n) { const auto c = p[i++]; if ((c & 0xc0) != 0x80) return false; cp = (cp << 6) | (c & 0x3f); }
        if ((following == 1 && cp < 0x80) || (following == 2 && cp < 0x800) || (following == 3 && cp < 0x10000) || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return false;
    }
    return true;
}
std::string quote(const std::string & value) {
    std::string out = "\"";
    for (const unsigned char c : value) {
        if (c == '\\' || c == '"') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 32) { char encoded[7]; std::snprintf(encoded, sizeof(encoded), "\\u%04x", c); out += encoded; }
        else out += static_cast<char>(c);
    }
    return out + '"';
}
bool abort(void * value) { return static_cast<std::atomic<bool> *>(value)->load(); }
bool progress(float, void * value) { return !abort(value); }
}
struct Session::State {
    std::unique_ptr<subtitle_native::ModelLease> lease;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    Response info;
};
Session::Session() : state_(std::make_unique<State>()) { }
Session::~Session() { cancel(); std::lock_guard<std::mutex> lock(operation_); state_.reset(); }
void Session::cancel() { cancelled_.store(true); }
std::string Response::json() const {
    std::ostringstream out;
    out << "{\"code\":" << quote(code) << ",\"name\":" << quote(name) << ",\"architecture\":" << quote(architecture)
        << ",\"fileType\":" << quote(file_type) << ",\"output\":" << quote(output) << ",\"stopReason\":" << quote(stop_reason)
        << ",\"modelBytes\":" << model_bytes << ",\"modelParameters\":" << model_parameters
        << ",\"peakRssBytes\":" << peak_rss_bytes << ",\"loadMs\":" << load_ms
        << ",\"promptMs\":" << prompt_ms << ",\"generationMs\":" << generation_ms
        << ",\"processId\":" << process_id << ",\"contextTokens\":" << context_tokens << ",\"promptTokens\":" << prompt_tokens
        << ",\"generatedTokens\":" << generated_tokens << '}';
    return out.str();
}
Response Session::open(const std::string & path, int threads, int context_tokens) {
    std::lock_guard<std::mutex> lock(operation_);
    Response result;
    result.process_id = static_cast<int>(getpid());
    if (cancelled_.load()) { result.code = "CANCELLED"; return result; }
    if (state_->model || context_tokens < 128 || context_tokens > 2048 || threads < 1 || threads > 8 || path.empty() || path.find('\0') != std::string::npos) { result.code = "CONTEXT"; return result; }
    state_->lease = std::make_unique<subtitle_native::ModelLease>();
    if (!*state_->lease) { state_->lease.reset(); result.code = "BUSY"; return result; }
    auto release_on_error = [&]() { state_->context.reset(); state_->model.reset(); state_->lease.reset(); };
    try {
        subtitle_native::initialize_backend();
        auto p = llama_model_default_params();
        ggml_backend_dev_t cpu[] = {ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU), nullptr};
        if (!cpu[0]) { result.code = "BACKEND"; release_on_error(); return result; }
        p.devices = cpu; p.n_gpu_layers = 0; p.load_mode = LLAMA_LOAD_MODE_MMAP;
        p.progress_callback = progress; p.progress_callback_user_data = &cancelled_; p.check_tensors = true;
        const auto start = Clock::now();
        state_->model.reset(llama_model_load_from_file(path.c_str(), p));
        result.load_ms = elapsed(start);
        if (cancelled_.load() || !state_->model) { result.code = cancelled_.load() ? "CANCELLED" : "MODEL"; release_on_error(); return result; }
        auto cp = llama_context_default_params();
        cp.n_ctx = context_tokens; cp.n_batch = std::min(context_tokens, 512); cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = threads; cp.offload_kqv = false; cp.op_offload = false;
        cp.abort_callback = abort; cp.abort_callback_data = &cancelled_;
        state_->context.reset(llama_init_from_model(state_->model.get(), cp));
        if (cancelled_.load() || !state_->context) { result.code = cancelled_.load() ? "CANCELLED" : "CONTEXT"; release_on_error(); return result; }
        result.name = metadata(state_->model.get(), "general.name");
        result.architecture = metadata(state_->model.get(), "general.architecture");
        result.file_type = metadata(state_->model.get(), "general.file_type");
        result.model_bytes = static_cast<long long>(llama_model_size(state_->model.get()));
        result.model_parameters = static_cast<long long>(llama_model_n_params(state_->model.get()));
        result.context_tokens = llama_n_ctx(state_->context.get());
        result.peak_rss_bytes = peak_rss(); result.code = "OK";
        state_->info = result;
        return result;
    } catch (...) { release_on_error(); result.code = "MODEL"; return result; }
}
Response Session::generate(const std::string & prompt, int output_tokens, bool add_special) {
    std::lock_guard<std::mutex> lock(operation_);
    Response result;
    result.process_id = static_cast<int>(getpid());
    if (cancelled_.load()) { result.code = "CANCELLED"; return result; }
    if (!state_->context || !state_->model) return result;
    result.context_tokens = state_->info.context_tokens;
    if (prompt.empty() || prompt.size() > 65536 || !utf8(prompt) || output_tokens < 1 || output_tokens > 1024) { result.code = "INPUT_TOO_LONG"; return result; }
    try {
        const auto * vocab = llama_model_get_vocab(state_->model.get());
        const int needed = -llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, add_special, true);
        if (needed <= 0 || needed + output_tokens >= result.context_tokens) { result.code = "INPUT_TOO_LONG"; return result; }
        std::vector<llama_token> tokens(static_cast<size_t>(needed));
        const int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), needed, add_special, true);
        if (count <= 0 || count != needed) { result.code = "RESPONSE"; return result; }
        result.prompt_tokens = count;
        // Every request carries its intended context explicitly. Never leak KV
        // state from a previous cue, resumed job or another subtitle file.
        llama_memory_clear(llama_get_memory(state_->context.get()), true);
        const auto prompt_start = Clock::now();
        const int batch_size = static_cast<int>(llama_n_batch(state_->context.get()));
        for (int offset = 0; offset < count; offset += batch_size) {
            if (cancelled_.load()) { result.code = "CANCELLED"; return result; }
            if (llama_decode(state_->context.get(), llama_batch_get_one(tokens.data() + offset, std::min(batch_size, count - offset))) != 0) {
                result.code = cancelled_.load() ? "CANCELLED" : "RESPONSE"; return result;
            }
        }
        result.prompt_ms = elapsed(prompt_start);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) { result.code = "RESPONSE"; return result; }
        const auto generation_start = Clock::now();
        result.code = "OUTPUT_LIMIT"; result.stop_reason = "token_limit";
        for (int i = 0; i <= output_tokens; ++i) {
            if (cancelled_.load()) { result.code = "CANCELLED"; result.stop_reason = "cancelled"; break; }
            const auto id = llama_sampler_sample(sampler.get(), state_->context.get(), -1);
            if (llama_vocab_is_eog(vocab, id)) { result.code = "OK"; result.stop_reason = "end_of_generation"; break; }
            if (i == output_tokens) break;
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (length < 0 && -length <= 65536) { piece.resize(static_cast<size_t>(-length)); length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false); }
            if (length < 0 || result.output.size() + static_cast<size_t>(length) > 65536) { result.code = "OUTPUT_LIMIT"; result.stop_reason = "byte_limit"; break; }
            result.output.append(piece.data(), static_cast<size_t>(length)); ++result.generated_tokens;
            // Decode the last permitted output too: EOS must be observed, never
            // inferred merely because the generated text looks complete.
            auto next = id;
            if (llama_decode(state_->context.get(), llama_batch_get_one(&next, 1)) != 0) { result.code = cancelled_.load() ? "CANCELLED" : "RESPONSE"; result.stop_reason = "error"; break; }
        }
        result.generation_ms = elapsed(generation_start); result.peak_rss_bytes = peak_rss();
        if (result.code == "OK" && !utf8(result.output)) { result.code = "RESPONSE"; result.stop_reason = "invalid_utf8"; }
        if (result.code != "OK") result.output.clear();
        return result;
    } catch (...) { result.code = cancelled_.load() ? "CANCELLED" : "RESPONSE"; result.output.clear(); return result; }
}
}
