#include "local_translation_engine.h"
#include "local_model_backend.h"
#include "optional_opencl_loader.h"
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <sys/resource.h>
#include <vector>
#include <sstream>
#include <unistd.h>
#include <fstream>
#include <sys/stat.h>
#include <cstdlib>
#include <new>

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
namespace {
long long resident_bytes() {
    std::ifstream stream("/proc/self/statm");
    unsigned long long virtual_pages = 0, resident_pages = 0;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size > 0 && stream >> virtual_pages >> resident_pages)
        return static_cast<long long>(resident_pages * static_cast<unsigned long long>(page_size));
    return -1;
}
long long available_ram_bytes() {
    std::ifstream stream("/proc/meminfo");
    std::string line;
    while (std::getline(stream, line)) {
        long long kib = -1;
        if (std::sscanf(line.c_str(), "MemAvailable: %lld kB", &kib) == 1 && kib >= 0) return kib * 1024;
    }
    return -1;
}
long long file_bytes(const std::string & path) {
    struct stat status{};
    return stat(path.c_str(), &status) == 0 && status.st_size > 0 ? status.st_size : -1;
}
void measure_memory(Response & value) {
    value.peak_rss_bytes = peak_rss();
    value.resident_bytes = resident_bytes();
    value.available_ram_bytes = available_ram_bytes();
}
ggml_backend_dev_t opencl_device() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto * device = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(device);
        auto * registration = ggml_backend_dev_backend_reg(device);
        const std::string name = registration ? ggml_backend_reg_name(registration) : "";
        if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) && name == "OpenCL") return device;
    }
    return nullptr;
}
}
struct Session::State {
    std::unique_ptr<subtitle_native::ModelLease> lease;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    std::vector<llama_token> previous_prompt;
    Response info;
    bool gpu_selected = false;
    void clear_cache() {
        previous_prompt.clear();
        if (context) llama_memory_clear(llama_get_memory(context.get()), true);
    }
    void clear_model() { clear_cache(); context.reset(); model.reset(); }
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
        << ",\"generatedTokens\":" << generated_tokens << ",\"backend\":" << quote(backend) << ",\"device\":" << quote(device)
        << ",\"requestedMode\":" << quote(requested_mode) << ",\"fallbackReason\":" << quote(fallback_reason)
        << ",\"requestedGpuLayers\":" << requested_gpu_layers << ",\"actualGpuLayers\":" << actual_gpu_layers
        << ",\"gpuObserved\":" << (gpu_observed ? "true" : "false") << ",\"gpuGraphNodes\":" << gpu_graph_nodes
        << ",\"cachedPromptTokens\":" << cached_prompt_tokens << ",\"promptEvaluatedTokens\":" << prompt_evaluated_tokens
        << ",\"residentBytes\":" << resident_bytes << ",\"availableRamBytes\":" << available_ram_bytes << '}';
    return out.str();
}
Response Session::open(const std::string & path, int threads, int context_tokens, const std::string & execution,
        int gpu_layers, const std::string & cache_path) {
    std::lock_guard<std::mutex> lock(operation_);
    Response result;
    result.process_id = static_cast<int>(getpid());
    result.requested_mode = execution;
    result.requested_gpu_layers = execution == "CPU" ? 0 : gpu_layers;
    if (cancelled_.load()) { result.code = "CANCELLED"; return result; }
    if (state_->model || context_tokens < 128 || context_tokens > 2048 || threads < 1 || threads > 8 || path.empty() ||
        path.find('\0') != std::string::npos || (execution != "CPU" && execution != "AUTO" && execution != "OPENCL") ||
        gpu_layers < -1 || gpu_layers > 1024 || (execution != "CPU" && gpu_layers == 0)) { result.code = "CONTEXT"; return result; }
    state_->lease = std::make_unique<subtitle_native::ModelLease>();
    if (!*state_->lease) { state_->lease.reset(); result.code = "BUSY"; return result; }
    auto release_on_error = [&]() { state_->clear_model(); state_->lease.reset(); };
    try {
        subtitle_native::initialize_backend();
        auto * cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!cpu) { result.code = "BACKEND"; release_on_error(); return result; }
        const std::string cpu_description = ggml_backend_dev_description(cpu);
        auto * gpu = static_cast<ggml_backend_dev_t>(nullptr);
        const auto available = available_ram_bytes();
        const auto bytes = file_bytes(path);
        // A budget is an admission heuristic, not an allocation measurement.
        // Android's OpenCL free-memory figure is only a total-memory upper bound.
        // AUTO avoids a full GPU allocation when the measured RAM cannot leave
        // 1 GiB for the context, staging buffers, player and other applications.
        const bool budget_known = available >= 0 && bytes > 0;
        const bool budget_short = budget_known && bytes > std::max(0LL, available - (1LL << 30));
        if (execution == "AUTO" && gpu_layers == -1 && (!budget_known || budget_short)) {
            result.fallback_reason = budget_known ? "OPENCL_RAM_BUDGET" : "OPENCL_RAM_UNKNOWN";
        } else if (execution != "CPU") {
            if (!cache_path.empty() && cache_path.find('\0') == std::string::npos)
                setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache_path.c_str(), 1);
            if (subtitle_native::prepare_opencl()) gpu = opencl_device();
            if (!gpu) {
                if (execution == "OPENCL") { result.code = "BACKEND"; release_on_error(); return result; }
                result.fallback_reason = "OPENCL_UNAVAILABLE";
            }
        }
        bool use_gpu = gpu != nullptr;
        const auto load_start = Clock::now();
        for (int attempt = 0; attempt < 2; ++attempt) {
            state_->clear_model();
            auto p = llama_model_default_params();
            ggml_backend_dev_t devices[] = {use_gpu ? gpu : cpu, nullptr};
            p.devices = devices; p.n_gpu_layers = use_gpu ? gpu_layers : 0; p.load_mode = LLAMA_LOAD_MODE_MMAP;
            p.progress_callback = progress; p.progress_callback_user_data = &cancelled_; p.check_tensors = true;
            subtitle_native::reset_offload_observation();
            state_->model.reset(llama_model_load_from_file(path.c_str(), p));
            if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
            if (!state_->model) {
                if (use_gpu && execution == "AUTO") { use_gpu = false; result.fallback_reason = "OPENCL_MODEL_LOAD_FAILED"; continue; }
                result.code = use_gpu ? "BACKEND" : "MODEL"; release_on_error(); return result;
            }
            auto cp = llama_context_default_params();
            cp.n_ctx = context_tokens; cp.n_batch = std::min(context_tokens, 512); cp.n_ubatch = 128;
            cp.n_threads = threads; cp.n_threads_batch = threads; cp.offload_kqv = use_gpu; cp.op_offload = use_gpu;
            cp.abort_callback = abort; cp.abort_callback_data = &cancelled_;
            state_->context.reset(llama_init_from_model(state_->model.get(), cp));
            if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
            if (!state_->context) {
                if (use_gpu && execution == "AUTO") { use_gpu = false; result.fallback_reason = "OPENCL_CONTEXT_FAILED"; continue; }
                result.code = use_gpu ? "BACKEND" : "CONTEXT"; release_on_error(); return result;
            }
            break;
        }
        if (!state_->model || !state_->context) { result.code = "BACKEND"; release_on_error(); return result; }
        state_->gpu_selected = use_gpu;
        result.load_ms = elapsed(load_start);
        result.backend = use_gpu ? "OPENCL" : "CPU";
        result.device = use_gpu ? ggml_backend_dev_description(gpu) : cpu_description;
        result.actual_gpu_layers = use_gpu ? subtitle_native::observed_offload_layers() : 0;
        result.name = metadata(state_->model.get(), "general.name");
        result.architecture = metadata(state_->model.get(), "general.architecture");
        result.file_type = llama_ftype_name(llama_model_ftype(state_->model.get()));
        result.model_bytes = static_cast<long long>(llama_model_size(state_->model.get()));
        result.model_parameters = static_cast<long long>(llama_model_n_params(state_->model.get()));
        result.context_tokens = llama_n_ctx(state_->context.get());
        measure_memory(result); result.code = "OK";
        state_->info = result;
        return result;
    } catch (const std::bad_alloc &) { release_on_error(); result.code = "MEMORY"; return result; }
      catch (...) { release_on_error(); result.code = "MODEL"; return result; }
}
Response Session::generate(const std::string & prompt, int output_tokens, bool add_special) {
    std::lock_guard<std::mutex> lock(operation_);
    Response result = state_->info;
    result.code = "MODEL";
    result.process_id = static_cast<int>(getpid());
    auto failed = [&](const char * code, const char * reason = "error") {
        result.code = code; result.stop_reason = reason; result.output.clear();
        state_->clear_cache(); measure_memory(result); return result;
    };
    if (cancelled_.load()) return failed("CANCELLED", "cancelled");
    if (!state_->context || !state_->model) return failed("MODEL");
    if (prompt.empty() || prompt.size() > 65536 || !utf8(prompt) || output_tokens < 1 || output_tokens > 1024) return failed("INPUT_TOO_LONG");
    try {
        const auto * vocab = llama_model_get_vocab(state_->model.get());
        const int needed = -llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, add_special, true);
        if (needed <= 0 || needed + output_tokens >= result.context_tokens) return failed("INPUT_TOO_LONG");
        std::vector<llama_token> tokens(static_cast<size_t>(needed));
        const int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), needed, add_special, true);
        if (count <= 0 || count != needed) return failed("RESPONSE");
        result.prompt_tokens = count;
        auto memory = llama_get_memory(state_->context.get());
        size_t shared = 0;
        const auto previous_size = state_->previous_prompt.size();
        while (shared < tokens.size() - 1 && shared < previous_size && state_->previous_prompt[shared] == tokens[shared]) ++shared;
        // SWA/recurrent memories can have discarded earlier positions. Only the
        // actual contiguous [min,max] range from llama.cpp proves availability.
        if (shared > 0 && (llama_memory_seq_pos_min(memory, 0) != 0 ||
            llama_memory_seq_pos_max(memory, 0) < static_cast<llama_pos>(shared - 1) ||
            !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(shared), -1))) shared = 0;
        if (shared == 0) state_->clear_cache();
        result.cached_prompt_tokens = static_cast<int>(shared);
        result.prompt_evaluated_tokens = count - result.cached_prompt_tokens;
        subtitle_native::reset_gpu_graph_observation();
        const auto prompt_start = Clock::now();
        const int batch_size = static_cast<int>(llama_n_batch(state_->context.get()));
        for (int offset = static_cast<int>(shared); offset < count; offset += batch_size) {
            if (cancelled_.load()) return failed("CANCELLED", "cancelled");
            if (llama_decode(state_->context.get(), llama_batch_get_one(tokens.data() + offset, std::min(batch_size, count - offset))) != 0)
                return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE");
        }
        llama_synchronize(state_->context.get());
        result.prompt_ms = elapsed(prompt_start);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) return failed("RESPONSE");
        const auto generation_start = Clock::now();
        result.code = "OUTPUT_LIMIT"; result.stop_reason = "token_limit";
        for (int i = 0; i <= output_tokens; ++i) {
            if (cancelled_.load()) return failed("CANCELLED", "cancelled");
            const auto id = llama_sampler_sample(sampler.get(), state_->context.get(), -1);
            if (llama_vocab_is_eog(vocab, id)) { result.code = "OK"; result.stop_reason = "end_of_generation"; break; }
            if (i == output_tokens) break;
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (length < 0 && -length <= 65536) { piece.resize(static_cast<size_t>(-length)); length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false); }
            if (length < 0 || result.output.size() + static_cast<size_t>(length) > 65536) return failed("OUTPUT_LIMIT", "byte_limit");
            result.output.append(piece.data(), static_cast<size_t>(length)); ++result.generated_tokens;
            auto next = id;
            if (llama_decode(state_->context.get(), llama_batch_get_one(&next, 1)) != 0)
                return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE");
        }
        llama_synchronize(state_->context.get());
        result.generation_ms = elapsed(generation_start);
        if (result.code != "OK") return failed(result.code.c_str(), result.stop_reason.c_str());
        if (!utf8(result.output)) return failed("RESPONSE", "invalid_utf8");
        result.gpu_graph_nodes = static_cast<long long>(subtitle_native::observed_gpu_graph_nodes());
        result.gpu_observed = state_->gpu_selected && result.gpu_graph_nodes > 0;
        if (state_->gpu_selected && !result.gpu_observed) {
            if (result.requested_mode == "OPENCL") return failed("BACKEND", "no_gpu_execution");
            result.backend = "CPU"; result.device = "CPU"; result.fallback_reason = "OPENCL_NO_COMPUTE";
        }
        state_->previous_prompt = std::move(tokens);
        measure_memory(result);
        return result;
    } catch (const std::bad_alloc &) { return failed("MEMORY"); }
      catch (...) { return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE"); }
}
#ifdef SUBTITLE_NATIVE_TESTING
std::vector<float> Session::test_logits() {
    std::lock_guard<std::mutex> lock(operation_);
    if (!state_->context || !state_->model) return {};
    const auto * logits = llama_get_logits_ith(state_->context.get(), -1);
    const int count = llama_vocab_n_tokens(llama_model_get_vocab(state_->model.get()));
    return logits && count > 0 ? std::vector<float>(logits, logits + count) : std::vector<float>();
}
#endif
}
