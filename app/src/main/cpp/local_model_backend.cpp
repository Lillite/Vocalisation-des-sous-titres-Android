#include "local_model_backend.h"
#include "llama.h"
#include "ggml-backend.h"
#include <mutex>

namespace subtitle_native {
namespace {
std::atomic_flag model_in_use = ATOMIC_FLAG_INIT;
std::once_flag initialized;
void silent_log(ggml_log_level, const char *, void *) { }
}
ModelLease::ModelLease() : acquired_(!model_in_use.test_and_set(std::memory_order_acquire)) { }
ModelLease::~ModelLease() { if (acquired_) model_in_use.clear(std::memory_order_release); }
void initialize_backend() {
    std::call_once(initialized, [] {
        llama_log_set(silent_log, nullptr);
        ggml_log_set(silent_log, nullptr);
        llama_backend_init();
    });
}
}
