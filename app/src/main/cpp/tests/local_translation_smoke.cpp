#include "local_translation_engine.h"
#include "model_probe_engine.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <iostream>

int main(int argc, char ** argv) {
    assert(argc == 3);
    {
    subtitle_local::Session first;
    const auto info = first.open(argv[1], 1, 512);
    if (info.code != "OK") { std::cerr << info.json() << '\n'; return 1; }
    assert(info.architecture == "llama" && info.model_bytes > 0 && info.model_parameters > 0);
    assert(info.context_tokens == 512 && info.process_id > 0);
    subtitle_local::Session second;
    assert(second.open(argv[1], 1, 512).code == "BUSY");
    subtitle_probe::Cancellation probe_cancel;
    assert(subtitle_probe::run(argv[1], probe_cancel, 1, 4).status == "BUSY");
    const auto generated = first.generate("The train leaves at seven.", 1, true);
    if (generated.code != "OK") { std::cerr << generated.json() << '\n'; return 1; }
    assert(generated.output == " probe" && generated.generated_tokens == 1 && generated.stop_reason == "end_of_generation");
    const auto again = first.generate("A different synthetic sentence.", 1, true);
    assert(again.code == "OK" && again.output == " probe" && again.generated_tokens == 1);
    const auto too_long = first.generate(std::string(8192, 'a'), 32, true);
    assert(too_long.code == "INPUT_TOO_LONG" && too_long.output.empty());
    assert(first.generate("retry after rejected input", 1, true).code == "OK");
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
        assert(retry.open("/missing/model.gguf", 1, 512).code == "MODEL");
        assert(retry.open(argv[1], 1, 512).code == "OK");
        assert(retry.generate("model is released after failure", 1, true).code == "OK");
    }
    std::cout << "Persistent native session: metadata, true EOS, repeated generation/reset, input rejection, cancellation and probe exclusion passed.\n";
    return 0;
}
