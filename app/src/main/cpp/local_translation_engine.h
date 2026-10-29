#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include "execution_diagnostic.h"
#ifdef SUBTITLE_NATIVE_TESTING
#include <vector>
#endif

struct llama_model;
struct llama_context;
namespace subtitle_local {
/** Coarse, sequential load phases. Only a completed open publishes this profile. */
struct LoadProfile {
    bool present = false;
    long long backend_init_ms = 0;
    long long opencl_prepare_ms = 0;
    long long model_load_ms = 0;
    long long context_init_ms = 0;
    long long open_total_ms = 0;
    int load_attempts = 0;
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
    int prompt_tokens = 0;
    int generated_tokens = 0;
    std::string json() const;
};
class Session {
public:
    Session();
    ~Session();
    Response open(const std::string & path, int threads, int context_tokens, const std::string & execution = "CPU", int gpu_layers = -1, const std::string & cache_path = "");
    Response generate(const std::string & prompt, int output_tokens, bool add_special);
    void cancel();
#ifdef SUBTITLE_NATIVE_TESTING
    std::vector<float> test_logits();
#endif
private:
    struct State;
    std::unique_ptr<State> state_;
    std::mutex operation_;
    std::atomic<bool> cancelled_{false};
};
}
