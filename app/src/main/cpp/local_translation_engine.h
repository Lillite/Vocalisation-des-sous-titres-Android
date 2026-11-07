#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <functional>
#include <vector>
#include "opencl_memory_observation.h"
#include "vulkan_memory_telemetry.h"
#include "execution_diagnostic.h"

struct llama_model;
struct llama_context;
namespace subtitle_local {
/** Coarse, sequential load phases. Only a completed open publishes this profile. */
struct LoadProfile {
    bool present = false;
    long long backend_init_ms = 0;
    long long opencl_prepare_ms = 0;
    long long vulkan_prepare_ms = 0;
    long long hexagon_prepare_ms = 0;
    long long model_load_ms = 0;
    long long context_init_ms = 0;
    long long open_total_ms = 0;
    int load_attempts = 0;
};
struct MemoryAllocation {
    std::string device;
    long long model_bytes = 0, context_bytes = 0, compute_bytes = 0;
};
struct MemorySample {
    const char * stage;
    long long resident_bytes = -1, peak_rss_bytes = 0, available_ram_bytes = -1;
};
struct Response {
    subtitle_native::ExecutionDiagnostic diagnostic;
    LoadProfile load_profile;
    std::string code = "MODEL";
    std::string name;
    std::string architecture;
    std::string file_type;
    std::string output;
    std::string stop_reason = "error";
    std::string backend = "CPU";
    std::string opencl_transport;
    std::string device = "CPU";
    std::string requested_mode = "CPU";
    std::string fallback_reason;
    int requested_gpu_layers = 0;
    int actual_gpu_layers = 0;
    bool gpu_observed = false;
    int requested_npu_layers = 0;
    int actual_npu_layers = 0;
    bool npu_observed = false;
    long long npu_graph_nodes = 0;
    // Reports only a bounded, locally supported model chat framing path.
    // It is not proof that the weights, language or GPU are compatible.
    bool chat_template_available = false;
    std::string chat_template_mode;
    long long gpu_graph_nodes = 0;
    int cached_prompt_tokens = 0;
    int prompt_evaluated_tokens = 0;
    long long resident_bytes = -1;
    long long available_ram_bytes = -1;
    long long model_bytes = 0;
    long long model_parameters = 0;
    long long peak_rss_bytes = 0;
    long long load_ms = 0;
    long long prompt_ms = 0;
    long long generation_ms = 0;
    int process_id = 0;
    int context_tokens = 0;
    int batch_tokens = 0;
    int micro_batch_tokens = 0;
    bool compact_swa = false;
    std::string cache_type_k = "F16";
    std::string cache_type_v = "F16";
    std::string flash_attention_policy = "AUTO";
    bool qualcomm_compatibility = false;
    bool native_cpu_weights = false;
    long long memory_reserve_bytes = 0;
    long long observed_prefill_extra_bytes = 0;
    bool memory_estimate_available = false;
    bool memory_breakdown_available = false;
    std::vector<MemoryAllocation> memory_estimate;
    std::vector<MemoryAllocation> memory_breakdown;
    std::vector<MemorySample> memory_samples;
    subtitle_native::opencl_memory::Snapshot opencl_memory{};
    subtitle_native::VulkanMemorySnapshot vulkan_memory{};
    std::string cache_reset_reason;
    long long cache_resets = 0;
    long long workspace_growths = 0;
    long long workspace_bytes = 0;
    long long start_resident_bytes = -1;
    long long prefill_resident_bytes = -1;
    int prompt_tokens = 0;
    int generated_tokens = 0;
    std::string json() const;
};
class Session {
public:
    Session();
    ~Session();
    Response open(const std::string & path, int threads, int context_tokens, const std::string & execution = "CPU",
        int gpu_layers = -1, const std::string & cache_path = "", int batch_tokens = 512,
        int micro_batch_tokens = 128, bool compact_swa = false,
        const std::function<void(const char *, int)> & on_progress = {},
        const std::string & kv_cache_type = "F16", const std::string & hexagon_library_path = "",
        const std::string & flash_attention_policy = "AUTO", bool qualcomm_compatibility = false,
        bool native_cpu_weights = false);
    Response generate(const std::string & prompt, int output_tokens, bool add_special,
        bool use_model_template = false,
        const std::function<void(const char *, int)> & on_progress = {});
    void cancel();
#ifdef SUBTITLE_NATIVE_TESTING
    std::vector<float> test_logits();
    std::string test_render_chat(const std::string & user_content);
    void test_set_available_ram_bytes(long long bytes);
    static bool test_memory_estimate_failure_clears_rows();
#endif
private:
    struct State;
    std::unique_ptr<State> state_;
    std::mutex operation_;
    std::atomic<bool> cancelled_{false};
};
}
