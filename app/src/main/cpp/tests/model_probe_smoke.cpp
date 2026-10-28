#include "model_probe_engine.h"
#include "ggml.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <thread>
#include <unistd.h>

int main(int argc, char ** argv) {
    assert(argc == 2);
    assert(std::string(ggml_commit()) == "8ca4060b");
    subtitle_probe::Cancellation cancel;
    const auto result = subtitle_probe::run(argv[1], cancel, 1, 4);
    if (result.status != "COMPLETED") { std::cerr << result.json() << '\n'; return 1; }
    assert(result.generated_tokens == 4);
    assert(result.output == " probe probe probe probe");
    assert(result.prompt_tokens > 0);
    assert(result.model_bytes > 0 && result.model_parameters > 0);
    assert(result.stop_reason == "token_limit");
    assert(result.context_tokens == 512);
    assert(result.json().find("\"offloadedLayers\":0") != std::string::npos);
    assert(result.json().find("\"backend\":\"CPU\"") != std::string::npos);

    subtitle_probe::Cancellation pre_cancelled;
    pre_cancelled.cancelled.store(true);
    assert(subtitle_probe::run(argv[1], pre_cancelled, 1, 4).status == "CANCELLED");
    subtitle_probe::Cancellation invalid;
    assert(subtitle_probe::run("/missing/synthetic.gguf", invalid, 1, 4).status == "FAILED");
    char corrupted_path[] = "/tmp/subtitle-probe-corrupt-XXXXXX";
    const int descriptor = mkstemp(corrupted_path);
    assert(descriptor >= 0);
    const char corrupted_header[24] = {'G', 'G', 'U', 'F', 3};
    assert(write(descriptor, corrupted_header, sizeof(corrupted_header)) == sizeof(corrupted_header));
    close(descriptor);
    const auto corrupt = subtitle_probe::run(corrupted_path, invalid, 1, 4);
    unlink(corrupted_path);
    assert(corrupt.status == "FAILED" && corrupt.backend == "non exécuté");
    assert(corrupt.generated_tokens == 0 && corrupt.output.empty());
    assert(corrupt.detail.find(corrupted_path) == std::string::npos);

    std::mutex mutex;
    std::condition_variable condition;
    bool entered = false, release = false;
    subtitle_probe::Cancellation during;
    subtitle_probe::Result stopped;
    std::thread first([&] {
        stopped = subtitle_probe::run(argv[1], during, 1, 4, [&](const std::string &) {
            std::unique_lock<std::mutex> lock(mutex);
            entered = true; condition.notify_all();
            condition.wait(lock, [&] { return release; });
        });
    });
    { std::unique_lock<std::mutex> lock(mutex); condition.wait(lock, [&] { return entered; }); }
    subtitle_probe::Cancellation second;
    assert(subtitle_probe::run(argv[1], second, 1, 4).status == "BUSY");
    during.cancelled.store(true);
    { std::lock_guard<std::mutex> lock(mutex); release = true; condition.notify_all(); }
    first.join();
    assert(stopped.status == "CANCELLED");
    // A new run must still succeed after cancellation and invalid model loading.
    assert(subtitle_probe::run(argv[1], cancel, 1, 4).status == "COMPLETED");
    std::cout << "Native model probe: real load/decode/output, cancellation, busy, missing/corrupt file and cleanup passed.\n";
}
