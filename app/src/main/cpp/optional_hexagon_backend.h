#pragma once
#include "execution_diagnostic.h"
#include "ggml-backend.h"
#include <string>

namespace subtitle_native {
// Only an explicit HEXAGON request may prepare this optional driver. The path
// comes from the app's verified private asset extractor, never a model or URI.
bool prepare_hexagon(const std::string & library_path, ExecutionDiagnostic * diagnostic = nullptr) noexcept;
ggml_backend_dev_t hexagon_device() noexcept;
}
