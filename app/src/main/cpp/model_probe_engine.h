#pragma once
#include <atomic>
#include <functional>
#include <string>

namespace subtitle_probe {
struct Cancellation { std::atomic<bool> cancelled{false}; };
struct Result {
    std::string status = "FAILED";
    std::string detail;
    std::string output;
    std::string stop_reason = "error";
    std::string prompt_kind = "synthetic_plain_text";
    std::string architecture;
    std::string backend = "non exécuté";
    long long load_ms = 0;
    long long prompt_ms = 0;
    long long generation_ms = 0;
    long long peak_rss_bytes = 0;
    long long model_bytes = 0;
    long long model_parameters = 0;
    int prompt_tokens = 0;
    int generated_tokens = 0;
    int context_tokens = 512;
    int output_limit = 32;
    int threads = 0;
    int process_id = 0;
    std::string json() const;
};
Result run(const std::string & path, Cancellation & cancellation, int threads,
           int max_output_tokens, const std::function<void(const std::string &)> & stage = {});
}
