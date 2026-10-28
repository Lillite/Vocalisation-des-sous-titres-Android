#pragma once
#include <atomic>
#include <cstdint>

namespace subtitle_native {
// Both services live in the same private process. A loaded translation session
// owns this lease until destruction, so a probe cannot load a second model.
class ModelLease {
public:
    ModelLease();
    ~ModelLease();
    ModelLease(const ModelLease &) = delete;
    ModelLease & operator=(const ModelLease &) = delete;
    explicit operator bool() const { return acquired_; }
private:
    bool acquired_;
};
void initialize_backend();
void reset_offload_observation();
int observed_offload_layers();
void reset_gpu_graph_observation();
uint64_t observed_gpu_graph_nodes();
}
