#include <android/log.h>
#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr auto log_tag = "NitanLocalModel";
constexpr int context_size = 2048;
constexpr int batch_size = 256;
constexpr int output_limit = 12;
constexpr auto command_grammar = R"(root ::= "ASK" | ("TAP " | "DONE ") [1-9] [0-9]? [0-9]?)";

// The worker owns the llama objects. The activity thread can only signal cancellation.
struct Session {
    std::atomic<bool> cancelled{false};
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
};

void load(Session& session, const std::string& path, int threads) {
    static const bool initialized = [] {
        llama_log_set([](ggml_log_level level, const char* message, void*) {
            if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) {
                __android_log_write(ANDROID_LOG_WARN, log_tag, message);
            }
        }, nullptr);
        llama_backend_init();
        return true;
    }();
    (void)initialized;

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.progress_callback = [](float, void* data) {
        return !static_cast<Session*>(data)->cancelled.load();
    };
    model_params.progress_callback_user_data = &session;
    session.model.reset(llama_model_load_from_file(path.c_str(), model_params));
    if (!session.model) throw std::runtime_error("Could not load the GGUF model");

    auto params = llama_context_default_params();
    params.n_ctx = context_size;
    params.n_batch = batch_size;
    params.n_ubatch = batch_size;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.abort_callback = [](void* data) {
        return static_cast<Session*>(data)->cancelled.load();
    };
    params.abort_callback_data = &session;
    session.context.reset(llama_init_from_model(session.model.get(), params));
    if (!session.context) throw std::runtime_error("Could not create the GGUF context");
}

std::string read_bytes(JNIEnv* env, jbyteArray bytes) {
    std::string text(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<jbyte*>(text.data()));
    return text;
}

std::string generate(Session& session, const std::string& prompt) {
    auto* context = session.context.get();
    auto* vocab = llama_model_get_vocab(session.model.get());
    std::vector<llama_token> tokens(context_size - output_limit);
    const auto count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(),
                                      tokens.size(), false, true);
    if (count <= 0) throw std::runtime_error("The prompt exceeds the GGUF context");

    llama_memory_clear(llama_get_memory(context), true);
    for (int offset = 0; offset < count; offset += batch_size) {
        const auto batch = llama_batch_get_one(tokens.data() + offset, std::min(batch_size, count - offset));
        if (session.cancelled || llama_decode(context, batch) != 0) {
            throw std::runtime_error("Could not evaluate the GGUF prompt");
        }
    }

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    auto* grammar = llama_sampler_init_grammar(vocab, command_grammar, "root");
    if (!grammar) throw std::runtime_error("Could not initialize the command grammar");
    llama_sampler_chain_add(sampler.get(), grammar);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());

    std::string answer;
    for (int i = 0; i < output_limit && !session.cancelled; ++i) {
        auto token = llama_sampler_sample(sampler.get(), context, -1);
        if (llama_vocab_is_eog(vocab, token)) return answer;
        char piece[32];
        const auto length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (length <= 0) throw std::runtime_error("Could not decode a command token");
        answer.append(piece, length);
        if (llama_decode(context, llama_batch_get_one(&token, 1)) != 0) {
            throw std::runtime_error("Could not generate the GGUF command");
        }
    }
    throw std::runtime_error("GGUF command generation did not finish");
}

void report_error(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    if (auto type = env->FindClass("java/lang/IllegalStateException")) env->ThrowNew(type, message);
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mingshenhk_nitanassistant_LocalAiModel_nativeCreate(JNIEnv* env, jobject) {
    try {
        return reinterpret_cast<jlong>(new Session);
    } catch (const std::exception& error) {
        report_error(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mingshenhk_nitanassistant_LocalAiModel_nativeLoad(
    JNIEnv* env, jobject, jlong handle, jbyteArray path, jint threads) {
    try {
        load(*reinterpret_cast<Session*>(handle), read_bytes(env, path), threads);
    } catch (const std::exception& error) {
        report_error(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mingshenhk_nitanassistant_LocalAiModel_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jbyteArray prompt) {
    try {
        const auto answer = generate(*reinterpret_cast<Session*>(handle), read_bytes(env, prompt));
        // The grammar only allows ASCII characters.
        return env->NewStringUTF(answer.c_str());
    } catch (const std::exception& error) {
        report_error(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mingshenhk_nitanassistant_LocalAiModel_nativeCancel(JNIEnv*, jobject, jlong handle) {
    reinterpret_cast<Session*>(handle)->cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mingshenhk_nitanassistant_LocalAiModel_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
