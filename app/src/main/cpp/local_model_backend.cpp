#include "local_model_backend.h"
#include "llama.h"
#include "ggml-backend.h"
#include <mutex>
#include <cstdio>
#include <cstring>

namespace {
std::atomic<uint64_t> executed_gpu_nodes{0};
std::atomic<int> allocated_gpu_layers{-1};
}
// Called only by the pinned OpenCL graph implementation after successful graph
// submission. The caller synchronizes the context before publishing this count.
extern "C" void subtitle_opencl_graph_executed(uint64_t nodes) {
    executed_gpu_nodes.fetch_add(nodes, std::memory_order_relaxed);
}
namespace subtitle_native {
namespace {
std::atomic_flag model_in_use = ATOMIC_FLAG_INIT;
std::once_flag initialized;
void private_log(ggml_log_level, const char * text, void *) {
    // Consume just the upstream numeric allocation summary. All raw logs remain
    // suppressed: they may contain paths, tensor names or other private content.
    if (!text) return;
    constexpr const char prefix[] = "load_tensors: offloaded ";
    if (std::strncmp(text, prefix, sizeof(prefix) - 1) != 0) return;
    int layers = -1, total = -1;
    if (std::sscanf(text + sizeof(prefix) - 1, "%d/%d layers to GPU", &layers, &total) == 2 && layers >= 0 && layers <= total)
        allocated_gpu_layers.store(layers, std::memory_order_relaxed);
}
}
ModelLease::ModelLease() : acquired_(!model_in_use.test_and_set(std::memory_order_acquire)) { }
ModelLease::~ModelLease() { if (acquired_) model_in_use.clear(std::memory_order_release); }
void initialize_backend() {
    std::call_once(initialized, [] {
        llama_log_set(private_log, nullptr);
        ggml_log_set(private_log, nullptr);
        llama_backend_init();
    });
}
void reset_offload_observation() { allocated_gpu_layers.store(-1, std::memory_order_relaxed); }
int observed_offload_layers() { return allocated_gpu_layers.load(std::memory_order_relaxed); }
void reset_gpu_graph_observation() { executed_gpu_nodes.store(0, std::memory_order_relaxed); }
uint64_t observed_gpu_graph_nodes() { return executed_gpu_nodes.load(std::memory_order_relaxed); }
}
