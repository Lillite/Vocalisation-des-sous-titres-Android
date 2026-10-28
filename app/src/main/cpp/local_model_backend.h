#pragma once
#include <atomic>

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
}
