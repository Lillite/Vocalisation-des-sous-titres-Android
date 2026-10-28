#include "model_probe_engine.h"
#include "local_model_backend.h"
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <sstream>
#include <sys/resource.h>
#include <vector>
#include <unistd.h>

namespace subtitle_probe {
namespace {
using Clock = std::chrono::steady_clock;
constexpr int context_size = 512;
constexpr size_t max_output_bytes = 8192;
long long millis(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}
long long peak_rss() {
    struct rusage value{};
    return getrusage(RUSAGE_SELF, &value) == 0 ? static_cast<long long>(value.ru_maxrss) * 1024 : 0;
}
std::string quote(const std::string & s) {
    std::string out = "\"";
    for (unsigned char ch : s) {
        if (ch == '\\' || ch == '"') { out += '\\'; out += static_cast<char>(ch); }
        else if (ch < 32) { char encoded[7]; std::snprintf(encoded, sizeof(encoded), "\\u%04x", ch); out += encoded; }
        else out += static_cast<char>(ch);
    }
    return out + '"';
}
std::string metadata(llama_model * model, const char * key) {
    char buffer[256]{};
    const int n = llama_model_meta_val_str(model, key, buffer, sizeof(buffer));
    return n > 0 && n < static_cast<int>(sizeof(buffer)) ? std::string(buffer, n) : std::string();
}
bool aborted(void * data) { return static_cast<Cancellation *>(data)->cancelled.load(); }
bool loading(float, void * data) { return !aborted(data); }
std::string prompt_for(llama_model * model, Result & result) {
    auto name = metadata(model, "general.name");
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    if (name.find("translategemma") != std::string::npos || name.find("translate-gemma") != std::string::npos) {
        result.prompt_kind = "translategemma_en_fr_official_template";
        // Exact text-only English→French task template from Google's model card.
        // No system role, custom instructions, personal subtitles or chat history.
        return "<bos><start_of_turn>user\nYou are a professional English (en) to French (fr) translator. "
               "Your goal is to accurately convey the meaning and nuances of the original English text "
               "while adhering to French grammar, vocabulary, and cultural sensitivities.\n"
               "Produce only the French translation, without any additional explanations or commentary. "
               "Please translate the following English text into French:\n\n"
               "The train leaves at seven.<end_of_turn>\n<start_of_turn>model\n";
    }
    // Other architectures are tested only as completion engines. This isn't an
    // assertion that their chat template or their translation quality is valid.
    return "The train leaves at seven.";
}
}
std::string Result::json() const {
    std::ostringstream out;
    out << "{\"status\":" << quote(status) << ",\"detail\":" << quote(detail)
        << ",\"output\":" << quote(output) << ",\"stopReason\":" << quote(stop_reason)
        << ",\"promptKind\":" << quote(prompt_kind) << ",\"architecture\":" << quote(architecture)
        << ",\"backend\":" << quote(backend) << ",\"offloadedLayers\":0,\"loadMs\":" << load_ms
        << ",\"promptMs\":" << prompt_ms << ",\"generationMs\":" << generation_ms
        << ",\"peakRssBytes\":" << peak_rss_bytes << ",\"modelBytes\":" << model_bytes
        << ",\"modelParameters\":" << model_parameters << ",\"promptTokens\":" << prompt_tokens
        << ",\"generatedTokens\":" << generated_tokens << ",\"contextTokens\":" << context_tokens << ",\"outputLimit\":" << output_limit
        << ",\"threads\":" << threads << ",\"processId\":" << process_id << ",\"runtimeRevision\":\"8ca4060b7e30e76baaa5670ee1ec1b760b7f8552\"}";
    return out.str();
}
Result run(const std::string & path, Cancellation & cancellation, int threads,
           int max_output_tokens, const std::function<void(const std::string &)> & stage) {
    Result result;
    result.process_id = static_cast<int>(getpid());
    result.threads = std::clamp(threads, 1, 8);
    const int output_limit = std::clamp(max_output_tokens, 1, 64);
    result.output_limit = output_limit;
    auto report = [&](const std::string & value) { if (stage) stage(value); };
    auto cancel_result = [&]() {
        result.status = "CANCELLED"; result.stop_reason = "cancelled";
        result.detail = "Essai annulé ; les ressources du modèle sont libérées.";
    };
    subtitle_native::ModelLease execution;
    if (!execution) { result.status = "BUSY"; result.detail = "Un autre essai natif est en cours."; return result; }
    if (cancellation.cancelled.load()) { cancel_result(); return result; }
    try {
        subtitle_native::initialize_backend();
        auto parameters = llama_model_default_params();
        ggml_backend_dev_t cpu_devices[] = {ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU), nullptr};
        if (!cpu_devices[0]) { result.detail = "Moteur CPU indisponible dans cette bibliothèque."; return result; }
        parameters.devices = cpu_devices;
        parameters.n_gpu_layers = 0;
        parameters.load_mode = LLAMA_LOAD_MODE_MMAP;
        parameters.progress_callback = loading;
        parameters.progress_callback_user_data = &cancellation;
        parameters.check_tensors = true;
        report("Chargement réel du modèle sur CPU…");
        const auto load_start = Clock::now();
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), parameters), llama_model_free);
        result.load_ms = millis(load_start);
        if (cancellation.cancelled.load()) { cancel_result(); return result; }
        if (!model) { result.detail = "Chargement refusé : modèle incomplet, mémoire insuffisante ou format non pris en charge."; return result; }
        result.architecture = metadata(model.get(), "general.architecture");
        result.model_bytes = static_cast<long long>(llama_model_size(model.get()));
        result.model_parameters = static_cast<long long>(llama_model_n_params(model.get()));
        report("Préparation du contexte synthétique de 512 tokens…");
        auto context_parameters = llama_context_default_params();
        context_parameters.n_ctx = context_size;
        context_parameters.n_batch = context_size;
        context_parameters.n_ubatch = 128;
        context_parameters.n_threads = result.threads;
        context_parameters.n_threads_batch = result.threads;
        context_parameters.offload_kqv = false;
        context_parameters.op_offload = false;
        context_parameters.abort_callback = aborted;
        context_parameters.abort_callback_data = &cancellation;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), context_parameters), llama_free);
        if (cancellation.cancelled.load()) { cancel_result(); return result; }
        if (!context) { result.detail = "Le contexte de génération n’a pas pu être créé ; mémoire ou architecture incompatible."; return result; }
        const auto * vocab = llama_model_get_vocab(model.get());
        const std::string prompt = prompt_for(model.get(), result);
        // The rendered TranslateGemma template already includes <bos>. Asking
        // llama_tokenize to add model specials as well would prepend a second
        // BOS and alter the official input (and possibly append an EOS).
        const bool add_special = result.prompt_kind != "translategemma_en_fr_official_template";
        const int needed = -llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, add_special, true);
        if (needed <= 0 || needed + output_limit >= context_size) { result.detail = "Le texte synthétique dépasse le contexte borné de cette sonde."; return result; }
        std::vector<llama_token> tokens(static_cast<size_t>(needed));
        const int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), needed, add_special, true);
        if (count <= 0) { result.detail = "Le modèle n’a pas pu encoder le texte synthétique."; return result; }
        tokens.resize(static_cast<size_t>(count)); result.prompt_tokens = count;
        const auto prompt_start = Clock::now();
        const int decode = llama_decode(context.get(), llama_batch_get_one(tokens.data(), count));
        result.prompt_ms = millis(prompt_start);
        if (cancellation.cancelled.load()) { cancel_result(); return result; }
        if (decode != 0) { result.detail = "Échec du calcul sur le texte synthétique."; return result; }
        result.backend = "CPU";
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        report("Génération réelle sur CPU ; GPU et NPU non exécutés…");
        const auto generation_start = Clock::now();
        result.stop_reason = "token_limit";
        for (int i = 0; i < output_limit; ++i) {
            if (cancellation.cancelled.load()) { cancel_result(); break; }
            const auto id = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, id)) { result.stop_reason = "end_of_generation"; break; }
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (length < 0 && -length <= 8192) { piece.resize(static_cast<size_t>(-length)); length = llama_token_to_piece(vocab, id, piece.data(), static_cast<int>(piece.size()), 0, false); }
            if (length < 0 || result.output.size() + static_cast<size_t>(length) > max_output_bytes) { result.stop_reason = "byte_limit"; break; }
            result.output.append(piece.data(), static_cast<size_t>(length)); ++result.generated_tokens;
            if (i + 1 == output_limit) break;
            auto next = id;
            if (llama_decode(context.get(), llama_batch_get_one(&next, 1)) != 0) {
                if (cancellation.cancelled.load()) cancel_result();
                else { result.detail = "Génération interrompue par une erreur du moteur."; result.stop_reason = "error"; }
                break;
            }
        }
        result.generation_ms = millis(generation_start);
        result.peak_rss_bytes = peak_rss();
        if (cancellation.cancelled.load()) { cancel_result(); return result; }
        if (result.stop_reason != "error") {
            result.status = "COMPLETED";
            result.detail = "Calcul CPU exécuté sur un texte synthétique. La sortie ne constitue pas une mesure de qualité de traduction ni un test GPU/NPU.";
        }
    } catch (...) { result.detail = "L’essai natif a échoué ; aucune qualité ni accélération n’est déduite de cet échec."; }
    return result;
}
}
