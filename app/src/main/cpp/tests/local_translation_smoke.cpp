#include "local_translation_engine.h"
#include "model_probe_engine.h"
#include "llama.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <memory>
#include <vector>

namespace {
void check_profile(const subtitle_local::Response & response) {
    const auto & profile = response.load_profile;
    assert(profile.present && profile.load_attempts >= 1 && profile.load_attempts <= 2);
    assert(profile.backend_init_ms >= 0 && profile.opencl_prepare_ms >= 0);
    assert(profile.model_load_ms >= 0 && profile.context_init_ms >= 0);
    assert(profile.backend_init_ms + profile.opencl_prepare_ms + profile.model_load_ms +
           profile.context_init_ms <= profile.open_total_ms);
    assert(profile.model_load_ms + profile.context_init_ms <= response.load_ms);
    assert(response.load_ms <= profile.open_total_ms);
    assert(profile.open_total_ms <= 86'400'000);
    assert(response.json().find("\"loadProfile\":{") != std::string::npos);
    assert(response.json().find("\"loadAttempts\":") != std::string::npos);
}
void check_output(const subtitle_local::Response & response) {
    if (response.code != "OK") { std::cerr << response.json() << '\n'; std::abort(); }
    assert(response.output == " probe" && response.generated_tokens == 1);
    assert(response.stop_reason == "end_of_generation");
    assert(response.prompt_tokens == response.cached_prompt_tokens + response.prompt_evaluated_tokens);
    assert(response.prompt_evaluated_tokens >= 1);
    assert(response.backend == "CPU" && !response.gpu_observed && response.gpu_graph_nodes == 0);
    assert(response.opencl_transport.empty());
    check_profile(response);
}
float difference(const std::vector<float> & left, const std::vector<float> & right) {
    assert(!left.empty() && left.size() == right.size());
    float maximum = 0;
    for (size_t i = 0; i < left.size(); ++i) maximum = std::max(maximum, std::abs(left[i] - right[i]));
    return maximum;
}
void check_greedy_chain_equivalence() {
    using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
    auto parameters = llama_sampler_chain_default_params();
    parameters.no_perf = true;
    Sampler chain(llama_sampler_chain_init(parameters), llama_sampler_free);
    Sampler direct(llama_sampler_init_greedy(), llama_sampler_free);
    assert(chain && direct);
    llama_sampler_chain_add(chain.get(), llama_sampler_init_greedy());
    assert(llama_sampler_chain_n(chain.get()) == 1);
    const auto infinity = std::numeric_limits<float>::infinity();
    const auto nan = std::numeric_limits<float>::quiet_NaN();
    // Preserve the old ordering and tie-breaking even at the extremes; this
    // checks equivalence, not a claim that NaN is a valid linguistic result.
    const std::vector<std::vector<float>> cases = {
        {-9.0f, -2.0f, -4.0f},
        {3.0f, 3.0f, 1.0f},
        {-2.0f, 5.0f, 5.0f},
        {-infinity, -infinity, -infinity},
        {0.0f, infinity, infinity},
        {nan, 4.0f, 8.0f},
        {0.0f, nan, 8.0f}
    };
    for (int pass = 0; pass < 3; ++pass) {
        if (pass == 1) { llama_sampler_reset(chain.get()); llama_sampler_reset(direct.get()); }
        for (const auto & values : cases) {
            std::vector<llama_token_data> direct_values, chain_values;
            for (size_t i = 0; i < values.size(); ++i)
                direct_values.push_back({static_cast<llama_token>(17 + i * 3), values[i], 0.0f});
            chain_values = direct_values;
            llama_token_data_array direct_array{direct_values.data(), direct_values.size(), -1, false};
            llama_token_data_array chain_array{chain_values.data(), chain_values.size(), -1, false};
            llama_sampler_apply(direct.get(), &direct_array);
            llama_sampler_apply(chain.get(), &chain_array);
            assert(direct_array.selected == chain_array.selected);
            assert(direct_array.size == chain_array.size);
            const auto selected = chain_array.data[chain_array.selected].id;
            assert(selected == direct_array.data[direct_array.selected].id);
            llama_sampler_accept(chain.get(), selected);
            llama_sampler_accept(direct.get(), selected);
        }
    }
    std::cout << "Greedy chain: 21 finite/tie/non-finite/reset selections match the prior direct rule; real host generations also compare every sampled token, including EOS.\n";
}
}
int main(int argc, char ** argv) {
    assert(argc == 3 || argc == 4);
    check_greedy_chain_equivalence();
    {
        subtitle_local::Session first;
        const auto info = first.open(argv[1], 1, 512);
        if (info.code != "OK") { std::cerr << info.json() << '\n'; return 1; }
        assert(info.architecture == "llama" && info.model_bytes > 0 && info.model_parameters > 0);
        assert(info.context_tokens == 512 && info.process_id > 0);
        assert(info.backend == "CPU" && !info.gpu_observed && info.actual_gpu_layers == 0);
        assert(info.opencl_transport.empty());
        check_profile(info);
        assert(info.load_profile.opencl_prepare_ms == 0 && info.load_profile.load_attempts == 1);
        assert(!info.diagnostic.present() && info.json().find("\"diagnostic\"") == std::string::npos);
        subtitle_local::Session second;
        assert(second.open(argv[1], 1, 512).code == "BUSY");
        subtitle_probe::Cancellation probe_cancel;
        assert(subtitle_probe::run(argv[1], probe_cancel, 1, 4).status == "BUSY");
        const auto generated = first.generate("The train leaves at seven.", 1, true);
        check_output(generated);
        assert(generated.cached_prompt_tokens == 0);
        const auto cached = first.generate("The train leaves at seven.", 1, true);
        check_output(cached);
        assert(cached.cached_prompt_tokens == cached.prompt_tokens - 1 && cached.prompt_evaluated_tokens == 1);
        const auto continued = first.generate("The train leaves at seven." + cached.output + "</s> Another request.", 1, true);
        check_output(continued);
        std::cerr << "Continuation cache: prior input=" << cached.prompt_tokens
                  << ", prior output=" << cached.generated_tokens << ", reused="
                  << continued.cached_prompt_tokens << ", evaluated=" << continued.prompt_evaluated_tokens << '\n';
        // This prefix extends beyond the previous input: the real generated
        // probe token was evaluated and is reusable without reconstructing KV.
        // The following EOS was only sampled previously, so it must be decoded
        // now rather than included among these reused tokens.
        assert(continued.cached_prompt_tokens > cached.prompt_tokens);
        assert(continued.cached_prompt_tokens == cached.prompt_tokens + cached.generated_tokens);
        std::cout << "Evaluated-output continuation: previous input " << cached.prompt_tokens
                  << ", previous generated " << cached.generated_tokens << ", reused "
                  << continued.cached_prompt_tokens << ", additional reused output tokens "
                  << continued.cached_prompt_tokens - cached.prompt_tokens << ".\n";
        check_output(first.generate("A different synthetic sentence.", 1, true));
        const auto too_long = first.generate(std::string(8192, 'a'), 32, true);
        assert(too_long.code == "INPUT_TOO_LONG" && too_long.output.empty());
        const auto retried = first.generate("retry after rejected input", 1, true);
        check_output(retried); assert(retried.cached_prompt_tokens == 0);
        first.cancel();
        assert(first.generate("cancelled", 32, true).code == "CANCELLED");
    }
    {
        subtitle_local::Session repeating;
        assert(repeating.open(argv[2], 1, 512).code == "OK");
        const auto limited = repeating.generate("finite budget", 4, true);
        assert(limited.code == "OUTPUT_LIMIT" && limited.output.empty());
        assert(limited.generated_tokens == 4 && limited.stop_reason == "token_limit");
    }
    {
        subtitle_local::Session retry;
        const auto missing = retry.open("/missing/model.gguf", 1, 512);
        assert(missing.code == "MODEL" && missing.diagnostic.present());
        assert(std::string(missing.diagnostic.stage) == "MODEL_LOAD" && std::string(missing.diagnostic.reason) == "MODEL_LOAD_FAILED");
        assert(missing.json().find("/missing/model.gguf") == std::string::npos);
        assert(!missing.load_profile.present && missing.json().find("\"loadProfile\"") == std::string::npos);
        assert(retry.open(argv[1], 1, 512).code == "OK");
        check_output(retry.generate("model is released after failure", 1, true));
    }
    {
        subtitle_local::Session auto_mode;
        const auto info = auto_mode.open(argv[1], 1, 512, "AUTO");
        assert(info.code == "OK" && info.requested_mode == "AUTO");
        assert(info.backend == "CPU" && !info.fallback_reason.empty());
        if (info.fallback_reason == "OPENCL_UNAVAILABLE") {
            assert(info.diagnostic.present() && std::string(info.diagnostic.reason) == "BACKEND_NOT_COMPILED");
            assert(!info.diagnostic.probe_cached);
        }
        check_output(auto_mode.generate("CPU fallback still computes", 1, true));
    }
    {
        subtitle_local::Session strict;
        const auto rejected = strict.open(argv[1], 1, 512, "OPENCL");
        assert(rejected.code == "BACKEND" && rejected.diagnostic.present());
        assert(std::string(rejected.diagnostic.stage) == "BACKEND" && std::string(rejected.diagnostic.reason) == "BACKEND_NOT_COMPILED");
        assert(!rejected.diagnostic.has_opencl_status && !rejected.diagnostic.probe_cached);
        assert(rejected.json().find("\"openClStatus\":null") != std::string::npos);
        assert(!rejected.load_profile.present && rejected.json().find("\"loadProfile\"") == std::string::npos);
    }
#ifdef SUBTITLE_NATIVE_TESTING
    if (argc == 4) {
        const std::string original = "Prefix common context: Alpha. The train leaves.";
        const std::string continuation = original + " probe</s> A new request.";
        const std::vector<std::string> prompts = {
            original,
            original,
            continuation,
            continuation + " probe Another new request.",
            original + " probe Short.",
            "Prefix common context: Beta. The train leaves.",
            "Prefix common context: Beta.",
            original
        };
        std::vector<std::vector<float>> warm_logits;
        {
            subtitle_local::Session warm;
            assert(warm.open(argv[3], 1, 512).code == "OK");
            int previous_prompt_tokens = 0;
            for (size_t i = 0; i < prompts.size(); ++i) {
                const auto response = warm.generate(prompts[i], 1, true);
                check_output(response);
                assert(i == 0 ? response.cached_prompt_tokens == 0 : response.cached_prompt_tokens > 0);
                if (i == 2 || i == 3) {
                    assert(response.cached_prompt_tokens > previous_prompt_tokens);
                    assert(response.cached_prompt_tokens == previous_prompt_tokens + 1);
                }
                warm_logits.push_back(warm.test_logits());
                previous_prompt_tokens = response.prompt_tokens;
            }
            assert(warm.generate(std::string(8192, 'x'), 32, true).code == "INPUT_TOO_LONG");
            const auto after_error = warm.generate(prompts[0], 1, true);
            check_output(after_error); assert(after_error.cached_prompt_tokens == 0);
            assert(difference(warm_logits[0], warm.test_logits()) < 0.00002f);
            const auto invalid_utf8 = warm.generate(std::string("\xc0\xaf", 2), 1, true);
            assert(invalid_utf8.code == "INPUT_TOO_LONG" && invalid_utf8.output.empty());
            const auto after_invalid_utf8 = warm.generate(prompts[0], 1, true);
            check_output(after_invalid_utf8); assert(after_invalid_utf8.cached_prompt_tokens == 0);
            assert(difference(warm_logits[0], warm.test_logits()) < 0.00002f);
        }
        float max_error = 0;
        for (size_t i = 0; i < prompts.size(); ++i) {
            subtitle_local::Session fresh;
            assert(fresh.open(argv[3], 1, 512).code == "OK");
            check_output(fresh.generate(prompts[i], 1, true));
            max_error = std::max(max_error, difference(warm_logits[i], fresh.test_logits()));
        }
        assert(max_error < 0.00002f);
        // The fixture is not a constant-logit model: changing earlier context
        // while keeping the ending fixed must affect real computed logits.
        const auto context_difference = difference(warm_logits[0], warm_logits[5]);
        assert(context_difference > 0.000001f);
        std::cout << "Context-sensitive logits: cached-vs-fresh maximum error " << max_error
                  << ", changed-context delta " << context_difference << ".\n";
    }
#endif
    std::cout << "Persistent native session: EOS, exact evaluated input/output prefix reuse, context isolation, failure reset, cancellation, CPU fallback, strict GPU rejection, probe exclusion and measured load-phase accounting passed.\n";
    return 0;
}
