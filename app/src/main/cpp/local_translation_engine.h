#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <string>

struct llama_model;
struct llama_context;
namespace subtitle_local {
struct Response {
    std::string code = "MODEL";
    std::string name;
    std::string architecture;
    std::string file_type;
    std::string output;
    std::string stop_reason = "error";
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
    Response open(const std::string & path, int threads, int context_tokens);
    Response generate(const std::string & prompt, int output_tokens, bool add_special);
    void cancel();
private:
    struct State;
    std::unique_ptr<State> state_;
    std::mutex operation_;
    std::atomic<bool> cancelled_{false};
};
}
