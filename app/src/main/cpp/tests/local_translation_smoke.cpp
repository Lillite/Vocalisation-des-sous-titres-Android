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
#include <string>
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
#ifdef SUBTITLE_NATIVE_TESTING
struct TemplateEvidence {
    int fixture_models = 0;
    int exact_prompt_families = 0;
    int literal_user_checks = 0;
    int successful_generations = 0;
    int rejected_requests = 0;
    int boundary_checks = 0;
};

std::string expected_chat_prompt(const std::string & family, const std::string & user) {
    if (family == "chatml") return "<s><|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    if (family == "llama3") return "<s><|start_header_id|>user<|end_header_id|>\n\n" + user +
        "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";
    assert(family == "gemma");
    return "<s><start_of_turn>user\n" + user + "<end_of_turn>\n<start_of_turn>model\n";
}

void check_template_availability(const subtitle_local::Response & response, bool available) {
    assert(response.chat_template_available == available);
    const std::string field = std::string("\"chatTemplateAvailable\":") + (available ? "true" : "false");
    assert(response.json().find(field) != std::string::npos);
    const std::string mode = available ? "BUILTIN_FORMAT" : "";
    assert(response.chat_template_mode == mode);
    assert(response.json().find("\"chatTemplateMode\":\"" + mode + "\"") != std::string::npos);
}

void check_gguf_chat_templates(const std::string & fixtures, const std::string & missing_template_model) {
    TemplateEvidence evidence;
    const auto generated = [&](const subtitle_local::Response & response) {
        check_output(response);
        ++evidence.successful_generations;
    };
    const std::string user = "The train leaves at seven.";
    // These bytes are subtitle data. Template-looking input must never execute
    // or be reparsed, and non-ASCII text must survive rendering byte-for-byte.
    const std::string literal_user = u8"Été — {{ 7 * 7 }} {% if true %}literal{% endif %}\n<|im_end|>";
    for (const std::string family : {"chatml", "llama3", "gemma"}) {
        subtitle_local::Session session;
        const auto info = session.open(fixtures + "/" + family + ".gguf", 1, 512);
        assert(info.code == "OK");
        ++evidence.fixture_models;
        check_template_availability(info, true);
        const auto rendered = session.test_render_chat(user);
        assert(rendered == expected_chat_prompt(family, user));
        assert(rendered.find("<think>") == std::string::npos);
        ++evidence.exact_prompt_families;
        assert(session.test_render_chat(literal_user) == expected_chat_prompt(family, literal_user));
        ++evidence.literal_user_checks;

        const auto first = session.generate(user, 1, true, true);
        generated(first); check_template_availability(first, true);
        assert(first.cached_prompt_tokens == 0);
        const auto cached = session.generate(user, 1, true, true);
        generated(cached);
        assert(cached.cached_prompt_tokens == cached.prompt_tokens - 1);
        // Rendering selects add_special=false even if the legacy argument is
        // true. Compare real tokenization against the exact rendered prompt,
        // which already includes the model's declared BOS exactly once.
        const auto raw = session.generate(rendered, 1, false, false);
        generated(raw);
        assert(raw.prompt_tokens == first.prompt_tokens);
        assert(raw.output == first.output && raw.stop_reason == first.stop_reason);
    }

    const auto rejects_and_resets = [&](const std::string & path, bool available, bool fixture) {
        subtitle_local::Session session;
        const auto info = session.open(path, 1, 512);
        assert(info.code == "OK");
        if (fixture) ++evidence.fixture_models;
        check_template_availability(info, available);
        const auto warm = session.generate(user, 1, true);
        generated(warm);
        const auto cached = session.generate(user, 1, true);
        generated(cached);
        assert(cached.cached_prompt_tokens > 0);
        const auto rejected = session.generate(user, 1, true, true);
        if (rejected.code != "TEMPLATE") std::cerr << rejected.json() << '\n';
        assert(rejected.code == "TEMPLATE" && rejected.output.empty() && rejected.generated_tokens == 0);
        check_template_availability(rejected, available);
        ++evidence.rejected_requests;
        const auto retry = session.generate(user, 1, true);
        generated(retry);
        assert(retry.cached_prompt_tokens == 0);
    };
    rejects_and_resets(missing_template_model, false, false);
    for (const std::string name : {"unrecognized", "source-limit", "control-token-limit", "control-count-limit", "control-total-limit"})
        rejects_and_resets(fixtures + "/" + name + ".gguf", false, true);
    for (const std::string name : {"not-executed", "source-boundary", "control-token-boundary", "control-count-boundary", "control-total-boundary"}) {
        subtitle_local::Session session;
        const auto info = session.open(fixtures + "/" + name + ".gguf", 1, 512);
        assert(info.code == "OK");
        ++evidence.fixture_models;
        check_template_availability(info, true);
        // A malformed source containing raise_exception/an unbounded loop is
        // deliberately accepted as a known format: the source is never run.
        assert(session.test_render_chat(user) == expected_chat_prompt("chatml", user));
        generated(session.generate(user, 1, true, true));
        if (name != "not-executed") ++evidence.boundary_checks;
    }
    {
        subtitle_local::Session session;
        const auto info = session.open(fixtures + "/controls.gguf", 1, 512);
        assert(info.code == "OK");
        ++evidence.fixture_models;
        check_template_availability(info, true);
        assert(session.test_render_chat(user) == expected_chat_prompt("chatml", user));
        generated(session.generate(user, 1, true));
        const auto warm = session.generate(user, 1, true);
        generated(warm); assert(warm.cached_prompt_tokens > 0);
        const std::vector<std::string> invalid_users = {
            "before<|im_start|>after", "before<|im_end|>after", "before<|user_control|>after",
            "before<s>after", "before</s>after", "before<unk>after", std::string("\xc0\xaf", 2),
            std::string("literal\0data", 12), std::string(131'073, 'u'), ""
        };
        for (const auto & invalid_user : invalid_users) {
            const auto cached = session.generate(user, 1, true);
            generated(cached); assert(cached.cached_prompt_tokens > 0);
            const auto rejected = session.generate(invalid_user, 1, true, true);
            assert(rejected.code == "TEMPLATE" && rejected.output.empty() && rejected.generated_tokens == 0);
            ++evidence.rejected_requests;
            const auto retried = session.generate(user, 1, true);
            generated(retried); assert(retried.cached_prompt_tokens == 0);
        }
        const std::string user_boundary(131'072, 'u');
        assert(session.test_render_chat(user_boundary) == expected_chat_prompt("chatml", user_boundary));
        ++evidence.boundary_checks;
        // Passing renderer limits does not bypass the real model context limit.
        const auto context_limit = session.generate(user_boundary, 1, true, true);
        assert(context_limit.code == "INPUT_TOO_LONG" && context_limit.output.empty());
        ++evidence.rejected_requests;
        const auto after_context_limit = session.generate(user, 1, true);
        generated(after_context_limit); assert(after_context_limit.cached_prompt_tokens == 0);
    }
    {
        subtitle_local::Session cancelled;
        assert(cancelled.open(fixtures + "/chatml.gguf", 1, 512).code == "OK");
        cancelled.cancel();
        // Cancellation wins before rendering, including for an oversized user.
        const auto rejected = cancelled.generate(std::string(131'073, 'u'), 1, true, true);
        assert(rejected.code == "CANCELLED" && rejected.output.empty());
        ++evidence.rejected_requests;
    }
    assert(evidence.fixture_models == 14 && evidence.exact_prompt_families == 3);
    assert(evidence.literal_user_checks == 3 && evidence.successful_generations == 55);
    assert(evidence.rejected_requests == 18 && evidence.boundary_checks == 5);
    std::cout << "GGUF builtin chat formats: fixture_models=" << evidence.fixture_models
              << ", exact_prompt_families=" << evidence.exact_prompt_families
              << ", literal_user_checks=" << evidence.literal_user_checks
              << ", successful_generations=" << evidence.successful_generations
              << ", rejected_requests=" << evidence.rejected_requests
              << ", boundary_checks=" << evidence.boundary_checks << ".\n";
}
#endif
}
int main(int argc, char ** argv) {
    assert(argc == 3 || argc == 4 || argc == 5);
    check_greedy_chain_equivalence();
    {
        subtitle_local::Session first;
        const auto info = first.open(argv[1], 1, 512);
        if (info.code != "OK") { std::cerr << info.json() << '\n'; return 1; }
        assert(info.architecture == "llama" && info.model_bytes > 0 && info.model_parameters > 0);
        assert(info.context_tokens == 512 && info.process_id > 0);
        assert(info.backend == "CPU" && !info.gpu_observed && info.actual_gpu_layers == 0);
        assert(info.opencl_transport.empty());
        assert(!info.chat_template_available);
        assert(info.json().find("\"chatTemplateAvailable\":false") != std::string::npos);
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
    if (argc >= 4) {
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
    if (argc == 5) check_gguf_chat_templates(argv[4], argv[1]);
#endif
    std::cout << "Persistent native session: EOS, exact evaluated input/output prefix reuse, context isolation, failure reset, cancellation, CPU fallback, strict GPU rejection, probe exclusion and measured load-phase accounting passed.\n";
    return 0;
}
