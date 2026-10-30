#include "local_translation_engine.h"
#include "local_model_backend.h"
#include "optional_opencl_loader.h"
#include "optional_vulkan_backend.h"
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
#include <cstring>
#include <stdexcept>
#include <cmath>

namespace subtitle_local {
namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t maximum_template_bytes = 65536;
constexpr size_t maximum_chat_user_bytes = 131072;
constexpr size_t maximum_chat_render_bytes = 262144;
long long elapsed(Clock::time_point start) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count(); }
long long profile_elapsed(Clock::time_point start) { return std::min(86'400'000LL, std::max(0LL, elapsed(start))); }
void diagnose(Response & result, const char * stage, const char * reason, Clock::time_point start) {
    auto & value = result.diagnostic;
    value.stage = stage; value.reason = reason;
    value.elapsed_ms = std::min(86'400'000LL, std::max(0LL, elapsed(start)));
    value.has_opencl_status = false;
    value.missing_symbol = nullptr;
    value.probe_cached = false;
}
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
struct OpenProgress {
    std::atomic<bool> & cancelled;
    const std::function<void(const char *, int)> & listener;
    int last_percent = -1;
    void notify(const char * stage, int percent = -1) const noexcept {
        // Telemetry is optional. A listener cannot change native ownership,
        // suppress an allocation error, or prevent cancellation and cleanup.
        if (listener) { try { listener(stage, percent); } catch (...) { } }
    }
};
bool progress(float completed, void * value) {
    auto & report = *static_cast<OpenProgress *>(value);
    if (report.cancelled.load()) return false;
    if (std::isfinite(completed)) {
        const int percent = static_cast<int>(std::max(0.0f, std::min(1.0f, completed)) * 100);
        if (percent > report.last_percent) {
            report.last_percent = percent;
            report.notify("MODEL_LOAD", percent);
        }
    }
    return !report.cancelled.load();
}
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
Sampler greedy_sampler() {
    auto parameters = llama_sampler_chain_default_params();
    // Generation is already measured around the whole loop. A one-element
    // chain keeps the same CPU greedy choice while allowing llama.cpp to reuse
    // its candidate buffer instead of allocating it for every sampled token.
    // Do not attach it to the context: backend sampling is a separate change.
    parameters.no_perf = true;
    Sampler chain(llama_sampler_chain_init(parameters), llama_sampler_free);
    Sampler greedy(llama_sampler_init_greedy(), llama_sampler_free);
    if (!chain || !greedy) return Sampler(nullptr, llama_sampler_free);
    llama_sampler_chain_add(chain.get(), greedy.get());
    greedy.release(); // The chain owns its child only after the add succeeded.
    return chain;
}
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
    // Exact tokens actually evaluated in this context, including successful
    // generated tokens. An EOS sampled but not decoded never belongs here.
    std::vector<llama_token> decoded_tokens;
    Response info;
    bool gpu_selected = false;
    // The embedded source is only used by llama.cpp's bounded set of builtin
    // format recognizers. No Jinja program, macro or model-supplied code runs.
    std::string chat_template;
    std::string chat_bos;
    std::vector<std::string> chat_controls;
    void clear_chat() {
        chat_template.clear(); chat_bos.clear(); chat_controls.clear();
    }
    void prepare_chat(std::atomic<bool> & cancelled) {
        clear_chat();
        const char * source = llama_model_chat_template(model.get(), nullptr);
        if (!source) return;
        const size_t size = strnlen(source, maximum_template_bytes + 1);
        if (size == 0 || size > maximum_template_bytes) return;
        std::string candidate(source, size);
        if (!utf8(candidate)) return;
        const llama_chat_message probe{"user", "probe"};
        // A single, fixed message safely probes the native format recognizer.
        // The call does not evaluate arbitrary embedded template expressions.
        const int rendered = llama_chat_apply_template(candidate.c_str(), &probe, 1, true, nullptr, 0);
        if (rendered < 1 || rendered > static_cast<int>(maximum_chat_render_bytes)) return;
        const auto * vocab = llama_model_get_vocab(model.get());
        const int vocabulary = llama_vocab_n_tokens(vocab);
        if (vocabulary < 1 || vocabulary > 1'000'000) return;
        std::vector<std::string> controls;
        size_t total = 0;
        for (int token = 0; token < vocabulary; ++token) {
            if ((token & 255) == 0 && cancelled.load()) return;
            const auto attributes = llama_vocab_get_attr(vocab, token);
            // Match the pinned tokenizer's parse_special partition exactly:
            // UNKNOWN pieces are special too, even with a custom spelling.
            if (!(attributes & (LLAMA_TOKEN_ATTR_CONTROL | LLAMA_TOKEN_ATTR_USER_DEFINED | LLAMA_TOKEN_ATTR_UNKNOWN))) continue;
            const char * text = llama_vocab_get_text(vocab, token);
            if (!text) return;
            const size_t length = strnlen(text, 1025);
            if (length == 0) continue;
            if (length > 1024 || controls.size() >= 8192 || total + length > 1'048'576) return;
            std::string value(text, length);
            if (!utf8(value)) return;
            total += length; controls.push_back(std::move(value));
        }
        std::string bos;
        if (llama_vocab_get_add_bos(vocab)) {
            const auto token = llama_vocab_bos(vocab);
            if (token < 0 || token >= vocabulary) return;
            const char * text = llama_vocab_get_text(vocab, token);
            if (!text) return;
            const size_t length = strnlen(text, 1025);
            if (length == 0 || length > 1024) return;
            bos.assign(text, length);
            if (!utf8(bos)) return;
        }
        chat_template = std::move(candidate);
        chat_controls = std::move(controls);
        chat_bos = std::move(bos);
    }
    std::string render_chat(const std::string & user, const std::atomic<bool> & cancelled) const {
        if (cancelled.load()) throw std::runtime_error("cancelled");
        if (chat_template.empty() || user.empty() || user.size() > maximum_chat_user_bytes ||
            user.find('\0') != std::string::npos || !utf8(user)) throw std::runtime_error("template_input");
        for (const auto & control : chat_controls) {
            if (cancelled.load()) throw std::runtime_error("cancelled");
            // Do not erase or escape subtitle text. A structural token in user
            // data must be a visible failure rather than change chat roles.
            if (user.find(control) != std::string::npos) throw std::runtime_error("template_control");
        }
        const llama_chat_message message{"user", user.c_str()};
        const int needed = llama_chat_apply_template(chat_template.c_str(), &message, 1, true, nullptr, 0);
        if (cancelled.load()) throw std::runtime_error("cancelled");
        if (needed < 1 || needed > static_cast<int>(maximum_chat_render_bytes)) throw std::runtime_error("template_size");
        // Check the reported byte count before allocating the output buffer.
        std::string rendered(static_cast<size_t>(needed) + 1, '\0');
        const int actual = llama_chat_apply_template(chat_template.c_str(), &message, 1, true,
            rendered.data(), static_cast<int>(rendered.size()));
        if (cancelled.load()) throw std::runtime_error("cancelled");
        if (actual != needed) throw std::runtime_error("template_size");
        rendered.resize(static_cast<size_t>(needed));
        // Builtin formatters often omit the first BOS. Add exactly the model's
        // declared BOS when required; tokenize without add_special afterwards.
        if (!chat_bos.empty() && rendered.compare(0, chat_bos.size(), chat_bos) != 0) {
            if (rendered.size() > maximum_chat_render_bytes - chat_bos.size()) throw std::runtime_error("template_size");
            rendered.insert(0, chat_bos);
        }
        if (rendered.find('\0') != std::string::npos || !utf8(rendered)) throw std::runtime_error("template_output");
        return rendered;
    }
    void clear_cache() {
        decoded_tokens.clear();
        if (context) llama_memory_clear(llama_get_memory(context.get()), true);
    }
    void clear_model() { clear_cache(); clear_chat(); context.reset(); model.reset(); }
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
        << ",\"processId\":" << process_id << ",\"contextTokens\":" << context_tokens
        << ",\"batchTokens\":" << batch_tokens << ",\"microBatchTokens\":" << micro_batch_tokens
        << ",\"compactSwa\":" << (compact_swa ? "true" : "false") << ",\"promptTokens\":" << prompt_tokens
        << ",\"generatedTokens\":" << generated_tokens << ",\"backend\":" << quote(backend) << ",\"device\":" << quote(device)
        << ",\"openClTransport\":" << quote(opencl_transport)
        << ",\"requestedMode\":" << quote(requested_mode) << ",\"fallbackReason\":" << quote(fallback_reason)
        << ",\"requestedGpuLayers\":" << requested_gpu_layers << ",\"actualGpuLayers\":" << actual_gpu_layers
        << ",\"gpuObserved\":" << (gpu_observed ? "true" : "false") << ",\"gpuGraphNodes\":" << gpu_graph_nodes
        << ",\"chatTemplateAvailable\":" << (chat_template_available ? "true" : "false")
        << ",\"chatTemplateMode\":" << quote(chat_template_mode)
        << ",\"cachedPromptTokens\":" << cached_prompt_tokens << ",\"promptEvaluatedTokens\":" << prompt_evaluated_tokens
        << ",\"residentBytes\":" << resident_bytes << ",\"availableRamBytes\":" << available_ram_bytes;
    if (load_profile.present) {
        out << ",\"loadProfile\":{\"backendInitMs\":" << load_profile.backend_init_ms
            << ",\"openClPrepareMs\":" << load_profile.opencl_prepare_ms
            << ",\"vulkanPrepareMs\":" << load_profile.vulkan_prepare_ms
            << ",\"modelLoadMs\":" << load_profile.model_load_ms
            << ",\"contextInitMs\":" << load_profile.context_init_ms
            << ",\"openTotalMs\":" << load_profile.open_total_ms
            << ",\"loadAttempts\":" << load_profile.load_attempts << '}';
    }
    if (diagnostic.present()) {
        out << ",\"diagnostic\":{\"stage\":" << quote(diagnostic.stage) << ",\"reason\":" << quote(diagnostic.reason)
            << ",\"elapsedMs\":" << diagnostic.elapsed_ms << ",\"openClStatus\":";
        if (diagnostic.has_opencl_status) out << diagnostic.opencl_status; else out << "null";
        if (diagnostic.missing_symbol) out << ",\"missingSymbol\":" << quote(diagnostic.missing_symbol);
        out << ",\"directPlatforms\":" << diagnostic.direct_platforms << ",\"icdPlatforms\":" << diagnostic.icd_platforms
            << ",\"gpuDevices\":" << diagnostic.gpu_devices << ",\"probeCached\":" << (diagnostic.probe_cached ? "true" : "false") << '}';
    }
    out << '}';
    return out.str();
}
Response Session::open(const std::string & path, int threads, int context_tokens, const std::string & execution,
        int gpu_layers, const std::string & cache_path, int batch_tokens, int micro_batch_tokens,
        bool compact_swa, const std::function<void(const char *, int)> & on_progress) {
    std::lock_guard<std::mutex> lock(operation_);
    const auto open_start = Clock::now();
    Response result;
    result.process_id = static_cast<int>(getpid());
    result.requested_mode = execution;
    result.requested_gpu_layers = execution == "CPU" ? 0 : gpu_layers;
    result.compact_swa = compact_swa;
    OpenProgress report{cancelled_, on_progress};
    if (cancelled_.load()) { result.code = "CANCELLED"; return result; }
    if (state_->model || context_tokens < 128 || context_tokens > 2048 || threads < 1 || threads > 8 || path.empty() ||
        path.find('\0') != std::string::npos ||
        (execution != "CPU" && execution != "AUTO" && execution != "OPENCL" && execution != "VULKAN") ||
        gpu_layers < -1 || gpu_layers > 1024 || (execution != "CPU" && gpu_layers == 0) ||
        batch_tokens < 16 || batch_tokens > 512 || micro_batch_tokens < 16 || micro_batch_tokens > 128 ||
        micro_batch_tokens > batch_tokens) { result.code = "CONTEXT"; return result; }
    state_->lease = std::make_unique<subtitle_native::ModelLease>();
    if (!*state_->lease) { state_->lease.reset(); result.code = "BUSY"; return result; }
    auto release_on_error = [&]() { state_->clear_model(); state_->lease.reset(); };
    const char * phase = "BACKEND";
    try {
        report.notify(phase);
        const auto backend_start = Clock::now();
        subtitle_native::initialize_backend();
        result.load_profile.backend_init_ms = profile_elapsed(backend_start);
        auto * cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!cpu) {
            diagnose(result, phase, "CPU_BACKEND_MISSING", open_start);
            result.code = "BACKEND"; release_on_error(); return result;
        }
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
        } else if (execution == "VULKAN") {
            phase = "VULKAN";
            report.notify(phase);
            const auto vulkan_start = Clock::now();
            const bool prepared = subtitle_native::prepare_vulkan(&result.diagnostic);
            if (prepared) gpu = subtitle_native::vulkan_device();
            result.load_profile.vulkan_prepare_ms = profile_elapsed(vulkan_start);
            if (!gpu) {
                if (prepared) diagnose(result, "VULKAN", "DEVICE_NOT_REGISTERED", open_start);
                else result.diagnostic.elapsed_ms = profile_elapsed(open_start);
                result.code = "BACKEND"; release_on_error(); return result;
            }
        } else if (execution != "CPU") {
            const auto opencl_start = Clock::now();
            phase = "OPENCL";
            report.notify(phase);
            if (!cache_path.empty() && cache_path.find('\0') == std::string::npos)
                setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache_path.c_str(), 1);
            const bool prepared = subtitle_native::prepare_opencl(&result.diagnostic);
            if (prepared) { result.opencl_transport = "DIRECT"; gpu = opencl_device(); }
            result.load_profile.opencl_prepare_ms = profile_elapsed(opencl_start);
            if (!gpu) {
                if (prepared) diagnose(result, "OPENCL_DEVICE", "DEVICE_NOT_REGISTERED", open_start);
                else result.diagnostic.elapsed_ms = std::min(86'400'000LL, std::max(0LL, elapsed(open_start)));
                if (execution == "OPENCL") { result.code = "BACKEND"; release_on_error(); return result; }
                result.fallback_reason = "OPENCL_UNAVAILABLE";
            }
        }
        bool use_gpu = gpu != nullptr;
        const auto load_start = Clock::now();
        for (int attempt = 0; attempt < 2; ++attempt) {
            state_->clear_model();
            phase = "MODEL_LOAD";
            report.last_percent = -1;
            report.notify(phase, 0);
            auto p = llama_model_default_params();
            ggml_backend_dev_t devices[] = {use_gpu ? gpu : cpu, nullptr};
            p.devices = devices; p.n_gpu_layers = use_gpu ? gpu_layers : 0; p.load_mode = LLAMA_LOAD_MODE_MMAP;
            p.progress_callback = progress; p.progress_callback_user_data = &report; p.check_tensors = true;
            subtitle_native::reset_offload_observation();
            ++result.load_profile.load_attempts;
            const auto model_start = Clock::now();
            state_->model.reset(llama_model_load_from_file(path.c_str(), p));
            result.load_profile.model_load_ms += profile_elapsed(model_start);
            if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
            if (!state_->model) {
                diagnose(result, phase, "MODEL_LOAD_FAILED", open_start);
                if (use_gpu && execution == "AUTO") { use_gpu = false; result.fallback_reason = "OPENCL_MODEL_LOAD_FAILED"; continue; }
                result.code = use_gpu ? "BACKEND" : "MODEL"; release_on_error(); return result;
            }
            auto cp = llama_context_default_params();
            cp.n_ctx = context_tokens; cp.n_batch = std::min(context_tokens, batch_tokens); cp.n_ubatch = micro_batch_tokens;
            cp.swa_full = !compact_swa;
            cp.n_threads = threads; cp.n_threads_batch = threads; cp.offload_kqv = use_gpu; cp.op_offload = use_gpu;
            cp.abort_callback = abort; cp.abort_callback_data = &cancelled_;
            phase = "CONTEXT_INIT";
            report.notify(phase);
            const auto context_start = Clock::now();
            state_->context.reset(llama_init_from_model(state_->model.get(), cp));
            result.load_profile.context_init_ms += profile_elapsed(context_start);
            if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
            if (!state_->context) {
                diagnose(result, phase, "CONTEXT_INIT_FAILED", open_start);
                if (use_gpu && execution == "AUTO") { use_gpu = false; result.fallback_reason = "OPENCL_CONTEXT_FAILED"; continue; }
                result.code = use_gpu ? "BACKEND" : "CONTEXT"; release_on_error(); return result;
            }
            break;
        }
        if (!state_->model || !state_->context) {
            diagnose(result, phase, "UNEXPECTED_FAILURE", open_start);
            result.code = "BACKEND"; release_on_error(); return result;
        }
        state_->gpu_selected = use_gpu;
        result.load_ms = elapsed(load_start);
        result.backend = use_gpu ? (execution == "VULKAN" ? "VULKAN" : "OPENCL") : "CPU";
        result.opencl_transport = result.backend == "OPENCL" ? "DIRECT" : "";
        result.device = use_gpu ? ggml_backend_dev_description(gpu) : cpu_description;
        result.actual_gpu_layers = use_gpu ? subtitle_native::observed_offload_layers() : 0;
        result.name = metadata(state_->model.get(), "general.name");
        result.architecture = metadata(state_->model.get(), "general.architecture");
        result.file_type = llama_ftype_name(llama_model_ftype(state_->model.get()));
        result.model_bytes = static_cast<long long>(llama_model_size(state_->model.get()));
        result.model_parameters = static_cast<long long>(llama_model_n_params(state_->model.get()));
        result.context_tokens = llama_n_ctx(state_->context.get());
        result.batch_tokens = llama_n_batch(state_->context.get());
        result.micro_batch_tokens = llama_n_ubatch(state_->context.get());
        phase = "CHAT_TEMPLATE";
        report.notify(phase);
        state_->prepare_chat(cancelled_);
        if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
        result.chat_template_available = !state_->chat_template.empty();
        result.chat_template_mode = result.chat_template_available ? "BUILTIN_FORMAT" : "";
        measure_memory(result); result.code = "OK";
        result.load_profile.open_total_ms = profile_elapsed(open_start);
        result.load_profile.present = true;
        state_->info = result;
        report.notify("READY", 100);
        return result;
    } catch (const std::bad_alloc &) {
        diagnose(result, phase, "ALLOCATION_FAILED", open_start);
        release_on_error(); result.code = "MEMORY"; return result;
    } catch (...) {
        diagnose(result, phase, "UNEXPECTED_FAILURE", open_start);
        release_on_error(); result.code = "MODEL"; return result;
    }
}
Response Session::generate(const std::string & prompt, int output_tokens, bool add_special, bool use_model_template) {
    std::lock_guard<std::mutex> lock(operation_);
    const auto generate_start = Clock::now();
    Response result = state_->info;
    result.code = "MODEL";
    result.process_id = static_cast<int>(getpid());
    auto failed = [&](const char * code, const char * reason = "error") {
        const std::string failure_code = code;
        if (failure_code == "MEMORY") diagnose(result, "GENERATION", "ALLOCATION_FAILED", generate_start);
        else if (failure_code == "RESPONSE") diagnose(result, "GENERATION", "UNEXPECTED_FAILURE", generate_start);
        result.code = code; result.stop_reason = reason; result.output.clear();
        state_->clear_cache(); measure_memory(result); return result;
    };
    if (cancelled_.load()) return failed("CANCELLED", "cancelled");
    if (!state_->context || !state_->model) return failed("MODEL");
    if (output_tokens < 1 || output_tokens > 1024) return failed("INPUT_TOO_LONG");
    std::string rendered;
    if (use_model_template) {
        try { rendered = state_->render_chat(prompt, cancelled_); }
        catch (const std::bad_alloc &) { return failed("MEMORY"); }
        catch (...) { return failed(cancelled_.load() ? "CANCELLED" : "TEMPLATE", cancelled_.load() ? "cancelled" : "template"); }
        add_special = false;
    } else if (prompt.empty() || prompt.size() > 65536 || !utf8(prompt)) return failed("INPUT_TOO_LONG");
    const auto & input = use_model_template ? rendered : prompt;
    try {
        const auto * vocab = llama_model_get_vocab(state_->model.get());
        const int needed = -llama_tokenize(vocab, input.data(), static_cast<int>(input.size()), nullptr, 0, add_special, true);
        if (needed <= 0 || needed + output_tokens >= result.context_tokens) return failed("INPUT_TOO_LONG");
        std::vector<llama_token> tokens(static_cast<size_t>(needed));
        const int count = llama_tokenize(vocab, input.data(), static_cast<int>(input.size()), tokens.data(), needed, add_special, true);
        if (count <= 0 || count != needed) return failed("RESPONSE");
        result.prompt_tokens = count;
        auto memory = llama_get_memory(state_->context.get());
        size_t shared = 0;
        const auto previous_size = state_->decoded_tokens.size();
        // Evaluate at least one token again: cached KV entries alone do not
        // provide the final logits for this new request.
        while (shared < tokens.size() - 1 && shared < previous_size && state_->decoded_tokens[shared] == tokens[shared]) ++shared;
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
        auto sampler = greedy_sampler();
        if (!sampler) return failed("RESPONSE");
#ifdef SUBTITLE_NATIVE_TESTING
        // The host smoke compares every real selection, including EOS, with
        // the previous direct sampler on the identical computed logits. This
        // reference is absent from all Android builds and production timings.
        Sampler reference(llama_sampler_init_greedy(), llama_sampler_free);
        if (!reference) return failed("RESPONSE");
#endif
        const auto generation_start = Clock::now();
        tokens.reserve(tokens.size() + static_cast<size_t>(output_tokens));
        std::vector<char> piece(256);
        result.code = "OUTPUT_LIMIT"; result.stop_reason = "token_limit";
        for (int i = 0; i <= output_tokens; ++i) {
            if (cancelled_.load()) return failed("CANCELLED", "cancelled");
            const auto id = llama_sampler_sample(sampler.get(), state_->context.get(), -1);
#ifdef SUBTITLE_NATIVE_TESTING
            if (id != llama_sampler_sample(reference.get(), state_->context.get(), -1))
                return failed("RESPONSE", "sampler_mismatch");
#endif
            if (llama_vocab_is_eog(vocab, id)) { result.code = "OK"; result.stop_reason = "end_of_generation"; break; }
            if (i == output_tokens) break;
            int length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (length < 0 && -length <= 65536) { piece.resize(static_cast<size_t>(-length)); length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false); }
            if (length < 0 || result.output.size() + static_cast<size_t>(length) > 65536) return failed("OUTPUT_LIMIT", "byte_limit");
            result.output.append(piece.data(), static_cast<size_t>(length)); ++result.generated_tokens;
            auto next = id;
            if (llama_decode(state_->context.get(), llama_batch_get_one(&next, 1)) != 0)
                return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE");
            tokens.push_back(id);
        }
        llama_synchronize(state_->context.get());
        result.generation_ms = elapsed(generation_start);
        if (result.code != "OK") return failed(result.code.c_str(), result.stop_reason.c_str());
        if (!utf8(result.output)) return failed("RESPONSE", "invalid_utf8");
        result.gpu_graph_nodes = static_cast<long long>(subtitle_native::observed_gpu_graph_nodes());
        result.gpu_observed = state_->gpu_selected && result.gpu_graph_nodes > 0;
        if (state_->gpu_selected && !result.gpu_observed) {
            diagnose(result, "GENERATION", "NO_GPU_EXECUTION", generate_start);
            if (result.requested_mode == "OPENCL" || result.requested_mode == "VULKAN")
                return failed("BACKEND", "no_gpu_execution");
            result.backend = "CPU"; result.device = "CPU"; result.fallback_reason = "OPENCL_NO_COMPUTE";
            result.opencl_transport.clear();
        }
        // Only a completed request publishes reusable tokens. Every failure
        // above clears both this record and the real llama.cpp memory.
        state_->decoded_tokens = std::move(tokens);
        measure_memory(result);
        return result;
    } catch (const std::bad_alloc &) { return failed("MEMORY"); }
      catch (...) { return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE"); }
}
#ifdef SUBTITLE_NATIVE_TESTING
std::string Session::test_render_chat(const std::string & user_content) {
    std::lock_guard<std::mutex> lock(operation_);
    if (!state_->context || !state_->model) throw std::runtime_error("model");
    return state_->render_chat(user_content, cancelled_);
}
std::vector<float> Session::test_logits() {
    std::lock_guard<std::mutex> lock(operation_);
    if (!state_->context || !state_->model) return {};
    const auto * logits = llama_get_logits_ith(state_->context.get(), -1);
    const int count = llama_vocab_n_tokens(llama_model_get_vocab(state_->model.get()));
    return logits && count > 0 ? std::vector<float>(logits, logits + count) : std::vector<float>();
}
#endif
}
