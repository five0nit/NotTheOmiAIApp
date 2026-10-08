// Test double for the *Whisper backend only*. Production JNI is compiled unchanged.
// This is API/lifetime/Unicode/cancellation evidence, NOT speech recognition proof.
#include <whisper.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <stdexcept>
#include <string>
#include <thread>

struct whisper_context { int mode = 0; std::string text = " caf\xc3\xa9 \xf0\x9f\x98\x80"; };
static std::atomic<int> mode{0};
static std::atomic<bool> entered{false};
static std::atomic<int> live{0};
extern "C" {
void whisper_log_set(ggml_log_callback, void *) {}
void ggml_log_set(ggml_log_callback, void *) {}
whisper_context_params whisper_context_default_params() { return {}; }
whisper_context *whisper_init_from_file_with_params(const char *path, whisper_context_params p) {
    if (p.use_gpu || p.flash_attn) throw std::runtime_error("GPU enabled");
    if (std::string(path) == "bad-model") return nullptr;
    if (std::string(path) == "oom") throw std::bad_alloc();
    if (std::string(path) != "test-model-\xf0\x9f\x98\x80.bin") throw std::runtime_error("Wrong UTF-8 path");
    auto *ctx = new whisper_context;
    ++live;
    return ctx;
}
void whisper_free(whisper_context *ctx) { --live; delete ctx; }
whisper_full_params whisper_full_default_params(whisper_sampling_strategy) { return {}; }
int whisper_full(whisper_context *ctx, whisper_full_params p, const float *samples, int count) {
    if (p.n_threads < 1 || p.n_threads > 4 || !p.no_context || !p.no_timestamps ||
        !p.suppress_blank || !p.suppress_nst || p.print_realtime || p.print_progress ||
        p.print_special || p.print_timestamps || p.debug_mode || p.vad ||
        p.translate || p.detect_language || p.temperature != 0 || p.temperature_inc != 0 ||
        p.audio_ctx != 0 || p.n_max_text_ctx != 0 || std::string(p.language) != "en" ||
        count < 16000 || count > 480000 || samples[0] != 0.25f)
        throw std::runtime_error("Unsafe inference parameters");
    ctx->mode = mode.load();
    entered.store(true);
    if (ctx->mode == 1) {
        for (int i = 0; i < 10000; ++i) {
            if (p.abort_callback(p.abort_callback_user_data)) return -99;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        throw std::runtime_error("Cancellation never arrived");
    }
    if (ctx->mode == 2) return -7;
    if (ctx->mode == 3) throw std::bad_alloc();
    if (ctx->mode == 4) ctx->text.assign(32769, 'x');
    if (ctx->mode == 5) ctx->text = "bad \xf0\x9f";
    return 0;
}
int whisper_full_n_segments(whisper_context *) { return 1; }
float whisper_full_get_segment_no_speech_prob(whisper_context *ctx, int) {
    if (ctx->mode == 6) return 0.95f;
    if (ctx->mode == 7) return std::nanf("");
    return 0.1f;
}
const char *whisper_full_get_segment_text(whisper_context *ctx, int) { return ctx->text.c_str(); }
JNIEXPORT void JNICALL Java_NativeSafetyTest_setMode(JNIEnv *, jclass, jint value) {
    mode.store(value); entered.store(false);
}
JNIEXPORT jboolean JNICALL Java_NativeSafetyTest_entered(JNIEnv *, jclass) { return entered.load(); }
JNIEXPORT jint JNICALL Java_NativeSafetyTest_live(JNIEnv *, jclass) { return live.load(); }
}
