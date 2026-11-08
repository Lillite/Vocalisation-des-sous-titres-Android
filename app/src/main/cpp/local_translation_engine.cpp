#include "local_translation_engine.h"
#include "local_model_backend.h"
#include "optional_opencl_loader.h"
#include "optional_vulkan_backend.h"
#include "optional_hexagon_backend.h"
#include "model_memory_budget.h"
#include "llama.h"
#include "llama-ext.h" // One pinned internal API, contained in the helpers below.
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
#include <limits>

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
    std::function<bool()> memory_check;
    bool memory_stopped = false;
    Clock::time_point next_memory_check{};
    void notify(const char * stage, int percent = -1) const noexcept {
        // Telemetry is optional. A listener cannot change native ownership,
        // suppress an allocation error, or prevent cancellation and cleanup.
        if (listener) { try { listener(stage, percent); } catch (...) { } }
    }
};
bool progress(float completed, void * value) {
    auto & report = *static_cast<OpenProgress *>(value);
    if (report.cancelled.load()) return false;
    const auto now = Clock::now();
    if (report.memory_check && now >= report.next_memory_check) {
        report.next_memory_check = now + std::chrono::milliseconds(100);
        if (!report.memory_check()) { report.memory_stopped = true; return false; }
    }
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
long long resident_sample() noexcept { try { return resident_bytes(); } catch (...) { return -1; } }
long long available_ram_bytes() {
    std::ifstream stream("/proc/meminfo");
    std::string line;
    while (std::getline(stream, line)) {
        long long kib = -1;
        if (std::sscanf(line.c_str(), "MemAvailable: %lld kB", &kib) == 1 && kib >= 0) return kib * 1024;
    }
    return -1;
}
void measure_memory(Response & value) noexcept {
    // Diagnostics must still return a useful MEMORY failure under allocation
    // pressure. iostream construction can itself allocate; each missing sample
    // stays explicitly unknown instead of hiding the original failure.
    value.peak_rss_bytes = peak_rss();
    value.resident_bytes = resident_sample();
    try { value.available_ram_bytes = available_ram_bytes(); } catch (...) { value.available_ram_bytes = -1; }
    value.opencl_memory = subtitle_native::opencl_memory::snapshot();
    value.vulkan_memory = subtitle_native::vulkan_memory_snapshot();
}
void memory_sample(Response & value, const char * stage) noexcept {
    measure_memory(value);
    try {
        if (value.memory_samples.size() >= 8) value.memory_samples.erase(value.memory_samples.begin());
        value.memory_samples.push_back({stage, value.resident_bytes, value.peak_rss_bytes, value.available_ram_bytes});
    } catch (...) { } // Optional telemetry must not hide a low-memory failure.
}
long long bounded_sum(long long a, long long b) noexcept {
    constexpr auto maximum = std::numeric_limits<long long>::max();
    return a > maximum - b ? maximum : a + b;
}
long long bounded_bytes(size_t value) noexcept {
    return value > static_cast<size_t>(std::numeric_limits<long long>::max()) ?
        std::numeric_limits<long long>::max() : static_cast<long long>(value);
}
// This is the only consumer of llama-ext.h's pinned C++ buffer accounting.
// Values describe backend buffers; never add them to RSS as separate physical
// allocations, and do not treat the estimate as a device allocation guarantee.
bool collect_memory(llama_context * context, std::vector<MemoryAllocation> & output) noexcept {
    output.clear();
    try {
        const auto breakdown = llama_get_memory_breakdown(context);
        for (const auto & entry : breakdown) {
            const char * label = "OTHER";
            if (ggml_backend_buft_is_host(entry.first)) label = "HOST";
            else if (auto * device = ggml_backend_buft_get_device(entry.first)) {
                auto * registration = ggml_backend_dev_backend_reg(device);
                const char * name = registration ? ggml_backend_reg_name(registration) : "";
                if (std::strcmp(name, "CPU") == 0) label = "CPU";
                else if (std::strcmp(name, "OpenCL") == 0) label = "OPENCL";
                else if (std::strcmp(name, "Vulkan") == 0) label = "VULKAN";
                else if (std::strcmp(name, "HTP") == 0) label = "HEXAGON";
            }
            auto found = std::find_if(output.begin(), output.end(), [&](const auto & row) { return row.device == label; });
            if (found == output.end()) { output.push_back({label}); found = output.end() - 1; }
            found->model_bytes = bounded_sum(found->model_bytes, bounded_bytes(entry.second.model));
            found->context_bytes = bounded_sum(found->context_bytes, bounded_bytes(entry.second.context));
            found->compute_bytes = bounded_sum(found->compute_bytes, bounded_bytes(entry.second.compute));
        }
        return !output.empty();
    } catch (...) { output.clear(); return false; }
}
bool reserve_available(Response & value, long long reserve) noexcept {
    value.memory_reserve_bytes = reserve;
    value.memory_admission = {true, value.available_ram_bytes, reserve, reserve,
        value.available_ram_bytes >= 0 && value.available_ram_bytes < reserve ? reserve - value.available_ram_bytes : 0};
    return value.available_ram_bytes < 0 || value.available_ram_bytes >= reserve;
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
    // The operation mutex serializes all requests. Two bounded token vectors
    // rotate ownership at commit, keeping both their allocations between cues.
    std::vector<llama_token> input_tokens;
    std::vector<char> token_piece;
    Sampler sampler{nullptr, llama_sampler_free};
    long long workspace_growths = 0;
    long long cache_resets = 0;
    Response info;
    long long observed_prefill_extra_bytes = 0;
#ifdef SUBTITLE_NATIVE_TESTING
    std::atomic<long long> test_available_ram_bytes{-2}; // -2 uses the real host sample.
#endif
    bool gpu_selected = false;
    // The embedded source is only used by llama.cpp's bounded set of builtin
    // format recognizers. No Jinja program, macro or model-supplied code runs.
    std::string chat_template;
    std::string chat_bos;
    std::vector<std::string> chat_controls;
    void prepare_workspace(size_t tokens) {
        if (decoded_tokens.capacity() < tokens) { decoded_tokens.reserve(tokens); ++workspace_growths; }
        if (input_tokens.capacity() < tokens) { input_tokens.reserve(tokens); ++workspace_growths; }
        if (token_piece.capacity() < 256) { token_piece.reserve(256); ++workspace_growths; }
        token_piece.resize(256);
        sampler = greedy_sampler();
        if (!sampler) throw std::bad_alloc();
    }
    void measure_workspace(Response & result) const {
        result.workspace_growths = workspace_growths;
        result.workspace_bytes = static_cast<long long>((decoded_tokens.capacity() + input_tokens.capacity()) * sizeof(llama_token) + token_piece.capacity());
        result.cache_resets = cache_resets;
    }
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
        if (context) {
            // Invalidating sequence positions suffices: the next request
            // evaluates every needed position before it can be attended to.
            // Zeroing the physical KV buffers here wastes memory bandwidth
            // (and GPU fills) whenever the two-turn prefix window slides.
            llama_memory_clear(llama_get_memory(context.get()), false);
            ++cache_resets;
        }
    }
    void clear_model() {
        // Never clear/zero the attention allocation immediately before
        // freeing it. The context owns all of its sequence state and buffers.
        context.reset(); model.reset(); sampler.reset(); clear_chat();
        std::vector<llama_token>().swap(decoded_tokens);
        std::vector<llama_token>().swap(input_tokens);
        std::vector<char>().swap(token_piece);
        observed_prefill_extra_bytes = 0;
    }
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
        << ",\"cacheTypeK\":" << quote(cache_type_k) << ",\"cacheTypeV\":" << quote(cache_type_v)
        << ",\"flashAttentionPolicy\":" << quote(flash_attention_policy)
        << ",\"qualcommCompatibility\":" << (qualcomm_compatibility ? "true" : "false")
        << ",\"nativeCpuWeights\":" << (native_cpu_weights ? "true" : "false")
        << ",\"memoryReserveBytes\":" << memory_reserve_bytes
        << ",\"observedPrefillExtraBytes\":" << observed_prefill_extra_bytes
        << ",\"cacheResetReason\":" << quote(cache_reset_reason) << ",\"cacheResets\":" << cache_resets
        << ",\"workspaceGrowths\":" << workspace_growths << ",\"workspaceBytes\":" << workspace_bytes
        << ",\"startResidentBytes\":" << start_resident_bytes << ",\"prefillResidentBytes\":" << prefill_resident_bytes
        << ",\"generatedTokens\":" << generated_tokens << ",\"backend\":" << quote(backend) << ",\"device\":" << quote(device)
        << ",\"openClTransport\":" << quote(opencl_transport)
        << ",\"requestedMode\":" << quote(requested_mode) << ",\"fallbackReason\":" << quote(fallback_reason)
        << ",\"requestedGpuLayers\":" << requested_gpu_layers << ",\"actualGpuLayers\":" << actual_gpu_layers
        << ",\"gpuObserved\":" << (gpu_observed ? "true" : "false") << ",\"gpuGraphNodes\":" << gpu_graph_nodes
        << ",\"requestedNpuLayers\":" << requested_npu_layers << ",\"actualNpuLayers\":" << actual_npu_layers
        << ",\"npuObserved\":" << (npu_observed ? "true" : "false") << ",\"npuGraphNodes\":" << npu_graph_nodes
        << ",\"chatTemplateAvailable\":" << (chat_template_available ? "true" : "false")
        << ",\"chatTemplateMode\":" << quote(chat_template_mode)
        << ",\"cachedPromptTokens\":" << cached_prompt_tokens << ",\"promptEvaluatedTokens\":" << prompt_evaluated_tokens
        << ",\"cacheSharedPrefixTokens\":" << cache_shared_prefix_tokens
        << ",\"cachePositionMin\":" << cache_position_min << ",\"cachePositionMax\":" << cache_position_max
        << ",\"residentBytes\":" << resident_bytes << ",\"availableRamBytes\":" << available_ram_bytes;
    if (memory_admission.present) {
        out << ",\"memoryAdmission\":{\"policy\":\"SYSTEM_RESERVE\",\"availableBytes\":" << memory_admission.available_bytes
            << ",\"requiredBytes\":" << memory_admission.required_bytes << ",\"reserveBytes\":" << memory_admission.reserve_bytes
            << ",\"deficitBytes\":" << memory_admission.deficit_bytes << '}';
    }
    out << ",\"memoryEstimateAvailable\":" << (memory_estimate_available ? "true" : "false")
        << ",\"memoryBreakdownAvailable\":" << (memory_breakdown_available ? "true" : "false");
    auto rows = [&](const char * key, const std::vector<MemoryAllocation> & values) {
        out << ",\"" << key << "\":[";
        bool first = true;
        for (const auto & row : values) {
            if (!first) out << ',';
            first = false;
            out << "{\"device\":" << quote(row.device) << ",\"modelBytes\":" << row.model_bytes
                << ",\"contextBytes\":" << row.context_bytes << ",\"computeBytes\":" << row.compute_bytes << '}';
        }
        out << ']';
    };
    rows("memoryEstimate", memory_estimate);
    rows("memoryBreakdown", memory_breakdown);
    out << ",\"memorySamples\":[";
    for (size_t i = 0; i < memory_samples.size(); ++i) {
        if (i) out << ',';
        const auto & sample = memory_samples[i];
        out << "{\"stage\":" << quote(sample.stage) << ",\"residentBytes\":" << sample.resident_bytes
            << ",\"peakRssBytes\":" << sample.peak_rss_bytes << ",\"availableRamBytes\":" << sample.available_ram_bytes << '}';
    }
    out << "],\"openClMemory\":{\"scratchCurrentBytes\":" << opencl_memory.scratch_current_bytes
        << ",\"scratchPeakBytes\":" << opencl_memory.scratch_peak_bytes << ",\"uploadPeakBytes\":" << opencl_memory.upload_peak_bytes
        << ",\"allocationFailures\":" << opencl_memory.allocation_failures << ",\"hostFallbacks\":" << opencl_memory.host_fallbacks
        << ",\"poolEntries\":" << opencl_memory.pool_entries << ",\"poolBytes\":" << opencl_memory.pool_bytes
        << ",\"skippedInitialBytes\":" << opencl_memory.skipped_initial_bytes << '}';
    out << ",\"vulkanMemory\":{\"observed\":" << (vulkan_memory.observed ? "true" : "false")
        << ",\"allocatedBytes\":" << vulkan_memory.allocated_bytes << ",\"peakAllocatedBytes\":" << vulkan_memory.peak_allocated_bytes
        << ",\"allocations\":" << vulkan_memory.allocations << ",\"allocationFailures\":" << vulkan_memory.allocation_failures
        << ",\"largestAllocationBytes\":" << vulkan_memory.largest_allocation_bytes << ",\"importedBytes\":" << vulkan_memory.imported_bytes
        << ",\"hostVisibleBytes\":" << vulkan_memory.host_visible_bytes << '}';
    out << ",\"vulkanBudget\":{\"observed\":" << (vulkan_memory.budget_observed ? "true" : "false")
        << ",\"extension\":" << (vulkan_memory.budget_extension ? "true" : "false")
        << ",\"budgetBytes\":" << vulkan_memory.budget_bytes << ",\"heapUsageBytes\":" << vulkan_memory.heap_usage_bytes
        << ",\"freeBytes\":" << vulkan_memory.budget_free_bytes << ",\"heapCount\":" << vulkan_memory.budget_heap_count << '}';
    if (load_profile.present) {
        out << ",\"loadProfile\":{\"backendInitMs\":" << load_profile.backend_init_ms
            << ",\"openClPrepareMs\":" << load_profile.opencl_prepare_ms
            << ",\"vulkanPrepareMs\":" << load_profile.vulkan_prepare_ms
            << ",\"hexagonPrepareMs\":" << load_profile.hexagon_prepare_ms
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
        bool compact_swa, const std::function<void(const char *, int)> & on_progress,
        const std::string & kv_cache_type, const std::string & hexagon_library_path,
        const std::string & flash_attention_policy, bool qualcomm_compatibility, bool native_cpu_weights) {
    std::lock_guard<std::mutex> lock(operation_);
    const auto open_start = Clock::now();
    Response result;
    result.process_id = static_cast<int>(getpid());
    result.requested_mode = execution;
    result.requested_gpu_layers = execution == "CPU" || execution == "HEXAGON" ? 0 : gpu_layers;
    result.requested_npu_layers = execution == "HEXAGON" ? gpu_layers : 0;
    result.compact_swa = compact_swa;
    result.qualcomm_compatibility = qualcomm_compatibility;
    result.native_cpu_weights = native_cpu_weights;
    result.flash_attention_policy = flash_attention_policy;
    if (kv_cache_type == "Q8_0") {
        result.cache_type_k = result.cache_type_v = "Q8_0";
        // A quantized V cache requires Flash Attention. Explicit selection
        // either creates this exact context or fails, never silently reverts.
        result.flash_attention_policy = "ENABLED";
    }
    OpenProgress report{cancelled_, on_progress};
    if (cancelled_.load()) { result.code = "CANCELLED"; return result; }
    if (state_->model || context_tokens < 128 || context_tokens > 2048 || threads < 1 || threads > 8 || path.empty() ||
        path.find('\0') != std::string::npos ||
        (execution != "CPU" && execution != "AUTO" && execution != "OPENCL" && execution != "VULKAN" && execution != "HEXAGON") ||
        gpu_layers < -1 || gpu_layers > 1024 || (execution != "CPU" && gpu_layers == 0) ||
        batch_tokens < 16 || batch_tokens > 512 || micro_batch_tokens < 16 || micro_batch_tokens > 128 ||
        micro_batch_tokens > batch_tokens || (kv_cache_type != "F16" && kv_cache_type != "Q8_0") ||
        (flash_attention_policy != "AUTO" && flash_attention_policy != "ENABLED" && flash_attention_policy != "DISABLED") ||
        (kv_cache_type == "Q8_0" && flash_attention_policy == "DISABLED")) {
        result.code = "CONTEXT"; return result;
    }
    state_->lease = std::make_unique<subtitle_native::ModelLease>();
    if (!*state_->lease) { state_->lease.reset(); result.code = "BUSY"; return result; }
    auto release_on_error = [&]() { state_->clear_model(); state_->lease.reset(); };
    subtitle_native::opencl_memory::reset_peaks();
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
        // MMAP file size and backend buffer totals are not incremental physical
        // RAM requirements. In particular, do not reject a previously usable
        // model or force AUTO to CPU by subtracting the whole GGUF a second time.
        auto load_reserve_available = [&]() {
            measure_memory(result);
#ifdef SUBTITLE_NATIVE_TESTING
            if (state_->test_available_ram_bytes >= -1) result.available_ram_bytes = state_->test_available_ram_bytes;
#endif
            return reserve_available(result, subtitle_native::request_memory_reserve(0));
        };
        report.memory_check = load_reserve_available;
        if (!load_reserve_available()) {
            diagnose(result, "MODEL_LOAD", "RAM_BUDGET_INSUFFICIENT", open_start);
            result.code = "MEMORY"; release_on_error(); return result;
        }
        if (execution == "HEXAGON") {
            phase = "HEXAGON";
            report.notify(phase);
            const auto hexagon_start = Clock::now();
            const bool prepared = subtitle_native::prepare_hexagon(hexagon_library_path, &result.diagnostic);
            if (prepared) gpu = subtitle_native::hexagon_device();
            result.load_profile.hexagon_prepare_ms = profile_elapsed(hexagon_start);
            if (!gpu) {
                if (prepared) diagnose(result, "HEXAGON", "DEVICE_NOT_REGISTERED", open_start);
                else result.diagnostic.elapsed_ms = profile_elapsed(open_start);
                result.code = "BACKEND"; release_on_error(); return result;
            }
        } else if (execution == "VULKAN") {
            phase = "VULKAN";
            report.notify(phase);
            const auto vulkan_start = Clock::now();
            const bool prepared = subtitle_native::prepare_vulkan(qualcomm_compatibility, &result.diagnostic);
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
        auto context_parameters = [&]() {
            auto cp = llama_context_default_params();
            cp.n_ctx = context_tokens; cp.n_batch = std::min(context_tokens, batch_tokens); cp.n_ubatch = micro_batch_tokens;
            cp.swa_full = !compact_swa;
            if (kv_cache_type == "Q8_0") cp.type_k = cp.type_v = GGML_TYPE_Q8_0;
            cp.flash_attn_type = result.flash_attention_policy == "ENABLED" ? LLAMA_FLASH_ATTN_TYPE_ENABLED :
                result.flash_attention_policy == "DISABLED" ? LLAMA_FLASH_ATTN_TYPE_DISABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
            cp.n_threads = threads; cp.n_threads_batch = threads; cp.offload_kqv = use_gpu; cp.op_offload = use_gpu;
            cp.abort_callback = abort; cp.abort_callback_data = &cancelled_;
            return cp;
        };
        const auto load_start = Clock::now();
        for (int attempt = 0; attempt < 2; ++attempt) {
            state_->clear_model();
            phase = "MODEL_LOAD";
            report.last_percent = -1;
            report.notify(phase, 0);
            auto p = llama_model_default_params();
            ggml_backend_dev_t devices[] = {use_gpu ? gpu : cpu, nullptr};
            p.devices = devices; p.n_gpu_layers = use_gpu ? gpu_layers : 0; p.load_mode = LLAMA_LOAD_MODE_MMAP;
            p.use_extra_bufts = !native_cpu_weights;
            p.progress_callback = progress; p.progress_callback_user_data = &report; p.check_tensors = true;
            const auto cp = context_parameters();
            // A no_alloc model followed by llama_init_from_model still prepares
            // drivers, output buffers and graphs. Avoid that duplicate context
            // on the production path. Report only the real context's accounting.
            memory_sample(result, "BEFORE_MODEL_LOAD");
            if (!load_reserve_available()) {
                diagnose(result, phase, "RAM_BUDGET_INSUFFICIENT", open_start);
                result.code = "MEMORY"; release_on_error(); return result;
            }
            subtitle_native::reset_offload_observation();
            ++result.load_profile.load_attempts;
            const auto model_start = Clock::now();
            state_->model.reset(llama_model_load_from_file(path.c_str(), p));
            result.load_profile.model_load_ms += profile_elapsed(model_start);
            memory_sample(result, "AFTER_MODEL_LOAD");
            if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
            if (report.memory_stopped) {
                diagnose(result, phase, "RAM_BUDGET_INSUFFICIENT", open_start);
                result.code = "MEMORY"; release_on_error(); return result;
            }
            if (!state_->model) {
                diagnose(result, phase, "MODEL_LOAD_FAILED", open_start);
                if (use_gpu && execution == "AUTO") { use_gpu = false; result.fallback_reason = "OPENCL_MODEL_LOAD_FAILED"; continue; }
                result.code = use_gpu ? "BACKEND" : "MODEL"; release_on_error(); return result;
            }
            phase = "CONTEXT_INIT";
            report.notify(phase);
            if (!load_reserve_available()) {
                diagnose(result, phase, "RAM_BUDGET_INSUFFICIENT", open_start);
                result.code = "MEMORY"; release_on_error(); return result;
            }
            const auto context_start = Clock::now();
            state_->context.reset(llama_init_from_model(state_->model.get(), cp));
            result.load_profile.context_init_ms += profile_elapsed(context_start);
            memory_sample(result, "AFTER_CONTEXT_INIT");
            if (cancelled_.load()) { result.code = "CANCELLED"; release_on_error(); return result; }
            if (!state_->context) {
                diagnose(result, phase, "CONTEXT_INIT_FAILED", open_start);
                if (use_gpu && execution == "AUTO") { use_gpu = false; result.fallback_reason = "OPENCL_CONTEXT_FAILED"; continue; }
                result.code = use_gpu ? "BACKEND" : "CONTEXT"; release_on_error(); return result;
            }
            if (!load_reserve_available()) {
                diagnose(result, phase, "RAM_BUDGET_INSUFFICIENT", open_start);
                result.code = "MEMORY"; release_on_error(); return result;
            }
            break;
        }
        if (!state_->model || !state_->context) {
            diagnose(result, phase, "UNEXPECTED_FAILURE", open_start);
            result.code = "BACKEND"; release_on_error(); return result;
        }
        state_->gpu_selected = use_gpu;
        result.load_ms = elapsed(load_start);
        result.backend = use_gpu ? (execution == "HEXAGON" ? "HEXAGON" : execution == "VULKAN" ? "VULKAN" : "OPENCL") : "CPU";
        result.opencl_transport = result.backend == "OPENCL" ? "DIRECT" : "";
        result.device = use_gpu ? ggml_backend_dev_description(gpu) : cpu_description;
        result.actual_gpu_layers = use_gpu && execution != "HEXAGON" ? subtitle_native::observed_offload_layers() : 0;
        result.actual_npu_layers = use_gpu && execution == "HEXAGON" ? subtitle_native::observed_offload_layers() : 0;
        result.name = metadata(state_->model.get(), "general.name");
        result.architecture = metadata(state_->model.get(), "general.architecture");
        result.file_type = llama_ftype_name(llama_model_ftype(state_->model.get()));
        result.model_bytes = static_cast<long long>(llama_model_size(state_->model.get()));
        result.model_parameters = static_cast<long long>(llama_model_n_params(state_->model.get()));
        result.context_tokens = llama_n_ctx(state_->context.get());
        result.batch_tokens = llama_n_batch(state_->context.get());
        result.micro_batch_tokens = llama_n_ubatch(state_->context.get());
        result.memory_breakdown_available = collect_memory(state_->context.get(), result.memory_breakdown);
        result.memory_reserve_bytes = subtitle_native::request_memory_reserve(0);
        state_->prepare_workspace(static_cast<size_t>(result.context_tokens));
        state_->measure_workspace(result);
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
Response Session::generate(const std::string & prompt, int output_tokens, bool add_special, bool use_model_template,
        const std::function<void(const char *, int)> & on_progress) {
    std::lock_guard<std::mutex> lock(operation_);
    const auto generate_start = Clock::now();
    Response result = state_->info;
    result.code = "MODEL";
    result.process_id = static_cast<int>(getpid());
    result.start_resident_bytes = resident_sample();
    result.observed_prefill_extra_bytes = state_->observed_prefill_extra_bytes;
    result.memory_reserve_bytes = subtitle_native::request_memory_reserve(0);
    memory_sample(result, "BEFORE_REQUEST");
    OpenProgress report{cancelled_, on_progress};
    auto failed = [&](const char * code, const char * reason = "error") {
        const std::string failure_code = code;
        if (failure_code == "MEMORY" && !(result.diagnostic.present() && std::strcmp(result.diagnostic.reason, "RESERVE_INSUFFICIENT") == 0))
            diagnose(result, "GENERATION", "ALLOCATION_FAILED", generate_start);
        else if (failure_code == "RESPONSE") diagnose(result, "GENERATION", "UNEXPECTED_FAILURE", generate_start);
        result.code = code; result.stop_reason = reason; result.output.clear();
        // decode may have queued GPU work before returning an error, or a
        // cancellation can arrive between successful prefill batches. Drain it
        // before invalidating/reusing sequence state; clear(false) is no barrier.
        if (state_->context) llama_synchronize(state_->context.get());
        state_->clear_cache(); result.cache_reset_reason = "FAILURE";
        state_->measure_workspace(result); measure_memory(result); return result;
    };
    if (cancelled_.load()) return failed("CANCELLED", "cancelled");
    if (!state_->context || !state_->model) return failed("MODEL");
    auto reserve_available = [&]() {
        // This is a calibrated scheduling policy, not a claim that Android's
        // limit has been reached. Unknown memory remains explicitly unknown.
        measure_memory(result);
#ifdef SUBTITLE_NATIVE_TESTING
        if (state_->test_available_ram_bytes >= -1) result.available_ram_bytes = state_->test_available_ram_bytes;
#endif
        const bool system_short = !subtitle_local::reserve_available(result, result.memory_reserve_bytes);
        // Model allocations already exist at this point: compare remaining
        // budget only with the calibrated request reserve, never with the
        // model a second time or with RSS added to Vulkan allocations.
        const bool device_short = result.backend == "VULKAN" &&
            subtitle_native::device_memory_budget(result.vulkan_memory.budget_observed, result.vulkan_memory.budget_extension,
                result.memory_reserve_bytes, bounded_bytes(result.vulkan_memory.budget_free_bytes), 0) ==
                    subtitle_native::ModelMemoryBudget::HEADROOM_SHORT;
        return !system_short && !device_short;
    };
    if (!reserve_available()) {
        diagnose(result, "GENERATION", "RESERVE_INSUFFICIENT", generate_start);
        return failed("MEMORY", "reserve_policy");
    }
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
        report.notify("TOKENIZE");
        const auto * vocab = llama_model_get_vocab(state_->model.get());
        auto & tokens = state_->input_tokens;
        // The context is a hard bound. Tokenize once into its preallocated
        // capacity rather than repeating the whole tokenizer sizing pass.
        tokens.resize(static_cast<size_t>(result.context_tokens));
        const int count = llama_tokenize(vocab, input.data(), static_cast<int>(input.size()),
            tokens.data(), result.context_tokens, add_special, true);
        if (count <= 0 || count + output_tokens >= result.context_tokens) return failed("INPUT_TOO_LONG");
        tokens.resize(static_cast<size_t>(count));
        result.prompt_tokens = count;
        auto memory = llama_get_memory(state_->context.get());
        size_t shared = 0;
        const auto previous_size = state_->decoded_tokens.size();
        report.notify("CACHE_REUSE");
        // Evaluate at least one token again: cached KV entries alone do not
        // provide the final logits for this new request.
        while (shared < tokens.size() - 1 && shared < previous_size && state_->decoded_tokens[shared] == tokens[shared]) ++shared;
        result.cache_shared_prefix_tokens = static_cast<int>(shared);
        result.cache_position_min = llama_memory_seq_pos_min(memory, 0);
        result.cache_position_max = llama_memory_seq_pos_max(memory, 0);
        // SWA/recurrent memories can have discarded earlier positions. Only the
        // actual contiguous [min,max] range from llama.cpp proves availability.
        // Keep these pre-reset observations: otherwise WINDOW_EXPIRED alone
        // cannot distinguish a discarded prefix from an absent source context.
        result.cache_reset_reason = previous_size == 0 ? "EMPTY" : "PREFIX_CHANGED";
        if (shared > 0) {
            if (result.cache_position_min != 0 ||
                result.cache_position_max < static_cast<llama_pos>(shared - 1)) {
                shared = 0; result.cache_reset_reason = "WINDOW_EXPIRED";
            } else if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(shared), -1)) {
                shared = 0; result.cache_reset_reason = "REMOVE_FAILED";
            } else result.cache_reset_reason = "REUSED";
        }
        if (shared == 0) state_->clear_cache();
        result.cached_prompt_tokens = static_cast<int>(shared);
        result.prompt_evaluated_tokens = count - result.cached_prompt_tokens;
        subtitle_native::reset_gpu_graph_observation();
        subtitle_native::reset_npu_graph_observation();
        const auto prompt_start = Clock::now();
        const int batch_size = static_cast<int>(llama_n_batch(state_->context.get()));
        memory_sample(result, "BEFORE_PREFILL");
        if (!reserve_available()) {
            diagnose(result, "GENERATION", "RESERVE_INSUFFICIENT", generate_start);
            return failed("MEMORY", "reserve_policy");
        }
        report.notify("PREFILL", 0);
        for (int offset = static_cast<int>(shared); offset < count; offset += batch_size) {
            if (cancelled_.load()) return failed("CANCELLED", "cancelled");
            if (!reserve_available()) {
                diagnose(result, "GENERATION", "RESERVE_INSUFFICIENT", generate_start);
                return failed("MEMORY", "reserve_policy");
            }
            if (llama_decode(state_->context.get(), llama_batch_get_one(tokens.data() + offset, std::min(batch_size, count - offset))) != 0)
                return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE");
            report.notify("PREFILL", static_cast<int>(100LL * (std::min(count, offset + batch_size) - shared) / (count - shared)));
        }
        llama_synchronize(state_->context.get());
        result.prompt_ms = elapsed(prompt_start);
        result.prefill_resident_bytes = resident_sample();
        memory_sample(result, "AFTER_PREFILL");
        if (result.start_resident_bytes >= 0 && result.prefill_resident_bytes > result.start_resident_bytes)
            state_->observed_prefill_extra_bytes = std::max(state_->observed_prefill_extra_bytes,
                result.prefill_resident_bytes - result.start_resident_bytes);
        result.observed_prefill_extra_bytes = state_->observed_prefill_extra_bytes;
        // RSS growth can be MMAP weight pages becoming resident. It remains a
        // diagnostic observation, never a second reservation of those weights.
        result.memory_reserve_bytes = subtitle_native::request_memory_reserve(0);
        if (!reserve_available()) {
            diagnose(result, "GENERATION", "RESERVE_INSUFFICIENT", generate_start);
            return failed("MEMORY", "reserve_policy");
        }
        auto & sampler = state_->sampler;
        if (!sampler) return failed("RESPONSE");
        llama_sampler_reset(sampler.get());
#ifdef SUBTITLE_NATIVE_TESTING
        // The host smoke compares every real selection, including EOS, with
        // the previous direct sampler on the identical computed logits. This
        // reference is absent from all Android builds and production timings.
        Sampler reference(llama_sampler_init_greedy(), llama_sampler_free);
        if (!reference) return failed("RESPONSE");
#endif
        const auto generation_start = Clock::now();
        auto & piece = state_->token_piece;
        report.notify("DECODE", 0);
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
            if (length < 0 && -static_cast<long long>(length) <= 65536) {
                const auto needed_piece = static_cast<size_t>(-static_cast<long long>(length));
                if (piece.capacity() < needed_piece) { piece.reserve(needed_piece); ++state_->workspace_growths; }
                piece.resize(needed_piece);
                length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (length < 0 || result.output.size() + static_cast<size_t>(length) > 65536) return failed("OUTPUT_LIMIT", "byte_limit");
            result.output.append(piece.data(), static_cast<size_t>(length)); ++result.generated_tokens;
            auto next = id;
            if (llama_decode(state_->context.get(), llama_batch_get_one(&next, 1)) != 0)
                return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE");
            tokens.push_back(id);
            if ((result.generated_tokens & 15) == 0)
                report.notify("DECODE", static_cast<int>(100LL * result.generated_tokens / output_tokens));
            if ((result.generated_tokens & 15) == 0 && !reserve_available()) {
                diagnose(result, "GENERATION", "RESERVE_INSUFFICIENT", generate_start);
                return failed("MEMORY", "reserve_policy");
            }
        }
        llama_synchronize(state_->context.get());
        result.generation_ms = elapsed(generation_start);
        if (result.code != "OK") return failed(result.code.c_str(), result.stop_reason.c_str());
        if (!utf8(result.output)) return failed("RESPONSE", "invalid_utf8");
        const bool is_npu = result.backend == "HEXAGON";
        result.gpu_graph_nodes = is_npu ? 0 : static_cast<long long>(subtitle_native::observed_gpu_graph_nodes());
        result.gpu_observed = state_->gpu_selected && !is_npu && result.gpu_graph_nodes > 0;
        result.npu_graph_nodes = is_npu ? static_cast<long long>(subtitle_native::observed_npu_graph_nodes()) : 0;
        result.npu_observed = state_->gpu_selected && is_npu && result.npu_graph_nodes > 0;
        if (state_->gpu_selected && is_npu && !result.npu_observed) {
            diagnose(result, "GENERATION", "NO_NPU_EXECUTION", generate_start);
            return failed("BACKEND", "no_npu_execution");
        }
        if (state_->gpu_selected && !is_npu && !result.gpu_observed) {
            diagnose(result, "GENERATION", "NO_GPU_EXECUTION", generate_start);
            if (result.requested_mode == "OPENCL" || result.requested_mode == "VULKAN")
                return failed("BACKEND", "no_gpu_execution");
            result.backend = "CPU"; result.device = "CPU"; result.fallback_reason = "OPENCL_NO_COMPUTE";
            result.opencl_transport.clear();
        }
        // Only a completed request publishes reusable tokens. Every failure
        // above clears both this record and the real llama.cpp memory.
        state_->decoded_tokens.swap(tokens);
        tokens.clear();
        state_->measure_workspace(result);
        memory_sample(result, "AFTER_GENERATION");
        result.memory_breakdown_available = collect_memory(state_->context.get(), result.memory_breakdown);
        report.notify("COMPLETE", 100);
        return result;
    } catch (const std::bad_alloc &) { return failed("MEMORY"); }
      catch (...) { return failed(cancelled_.load() ? "CANCELLED" : "RESPONSE"); }
}
#ifdef SUBTITLE_NATIVE_TESTING
void Session::test_set_observed_prefill_extra_bytes(long long bytes) {
    std::lock_guard<std::mutex> lock(operation_);
    state_->observed_prefill_extra_bytes = std::max(0LL, bytes);
}
void Session::test_set_available_ram_bytes(long long bytes) {
    state_->test_available_ram_bytes.store(std::max(-2LL, bytes));
}
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
