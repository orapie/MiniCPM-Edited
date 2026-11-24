#include <android/log.h>
#include <jni.h>
#include <chrono>
#include <atomic>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <vector>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

// Inference parameters use the iOS demo's context and batch sizes, with
// Android-specific conversational sampling to discourage short-range loops.
// Sampling values are tuned through fixed-prompt device evaluation.
constexpr int   N_THREADS               = 4;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 2048;
// Keep moderate randomness for natural Chinese conversation. Candidate filters
// and repetition penalties are configured in new_sampler().
constexpr float DEFAULT_SAMPLER_TEMP    = 0.7f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
// Whether the loaded text model may enter its reasoning section. This is
// registry-driven from Kotlin; MiniCPM5-2B disables thinking because the
// mobile UI's short generation budget otherwise often ends before </think>.
static bool                               g_enable_thinking = true;

using SteadyClock = std::chrono::steady_clock;

#if defined(ATTRIBUTION_RUNTIME)
static std::atomic<bool> g_attribution_cancelled{false};
static SteadyClock::time_point g_attribution_deadline = SteadyClock::time_point::max();
static std::string g_attribution_grammar;

static bool attribution_should_abort(void *) {
    return g_attribution_cancelled.load(std::memory_order_relaxed) ||
           SteadyClock::now() >= g_attribution_deadline;
}
#endif

extern "C"
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
#if !defined(ATTRIBUTION_RUNTIME)
    llama_log_set(minicpm_android_log_callback, nullptr);

    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    if (ggml_backend_reg_count() == 0) {
        LOGw("%s: No backends loaded from path, trying ggml_backend_load_all()", __func__);
        ggml_backend_load_all();
    }

    LOGi("%s: Backend count: %zu", __func__, ggml_backend_reg_count());

    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
#else
    // The attribution runtime is a second JNI DSO with its own model/context
    // globals. llama.cpp's backend itself is process-global (shared by both
    // DSOs), so only the foreground runtime initializes and frees it.
    (void) env;
    (void) nativeLibDir;
    LOGi("Attribution runtime initialized against existing llama backend");
#endif
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGi("%s: Loading model from: \n%s\n", __func__, model_path);

    FILE *f = fopen(model_path, "rb");
    if (!f) {
        LOGe("%s: Cannot open model file! Permission denied or file not found: %s", __func__, model_path);
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        return 1;
    }
    fseek(f, 0, SEEK_END);
    long file_size = ftell(f);
    fclose(f);
    LOGi("%s: Model file size: %ld bytes (%.2f GB)", __func__, file_size, file_size / (1024.0 * 1024.0 * 1024.0));

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        LOGe("%s: llama_model_load_from_file returned null! Check logcat for llama.cpp internal errors.", __func__);
        return 2;
    }
    g_model = model;
    LOGi("%s: Model loaded successfully!", __func__);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_setEnableThinkingNative(JNIEnv * /*env*/,
                                                                     jobject,
                                                                     jboolean jenabled) {
    g_enable_thinking = jenabled == JNI_TRUE;
    LOGi("%s: enable_thinking set to %s", __func__, g_enable_thinking ? "true" : "false");
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    LOGi("%s: Using %d threads (aligned with iOS demo MTMDParams)", __func__, N_THREADS);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = N_THREADS;
    ctx_params.n_threads_batch = N_THREADS;
#if defined(ATTRIBUTION_RUNTIME)
    ctx_params.abort_callback = attribution_should_abort;
#endif
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    // Android conversational defaults: preserve fluent Chinese while
    // discouraging local token loops. Change one value at a time only after
    // evaluating fixed prompts on device.
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = 40;
    sparams.top_p = 0.90f;
    sparams.min_p = 0.05f;
    sparams.penalty_last_n = 128;
    sparams.penalty_repeat = 1.12f;
    sparams.penalty_freq = 0.05f;
    sparams.penalty_present = 0.0f;
#if defined(ATTRIBUTION_RUNTIME)
    if (!g_attribution_grammar.empty()) {
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, g_attribution_grammar);
        sparams.temp = 0.0f;
    }
#endif
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) {
#if defined(ATTRIBUTION_RUNTIME)
        llama_model_free(g_model);
        g_model = nullptr;
#endif
        return 1;
    }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
static llama_pos generation_start_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, true);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message, bytes=%zu", __func__, role.c_str(), formatted.size());
    return formatted;
}

// common_chat_format_single() currently does not expose the template's
// enable_thinking input. MiniCPM5's enable_thinking=false generation prompt
// differs by closing an empty reasoning block before answer generation, so
// apply that equivalent prefix locally while preserving common.cpp's BOS/EOS
// and incremental-history formatting behavior.
static void apply_thinking_mode_to_generation_prompt(std::string &prompt) {
    if (g_enable_thinking) {
        return;
    }

    constexpr const char * open_with_newline = "<think>\n";
    constexpr size_t open_with_newline_len = 8;
    if (prompt.size() >= open_with_newline_len &&
        prompt.compare(prompt.size() - open_with_newline_len,
                       open_with_newline_len, open_with_newline) == 0) {
        prompt += "\n</think>\n\n";
        LOGi("Applied enable_thinking=false assistant prefix");
        return;
    }

    constexpr const char * open_without_newline = "<think>";
    constexpr size_t open_without_newline_len = 7;
    if (prompt.size() >= open_without_newline_len &&
        prompt.compare(prompt.size() - open_without_newline_len,
                       open_without_newline_len, open_without_newline) == 0) {
        prompt += "\n\n</think>\n\n";
        LOGi("Applied enable_thinking=false assistant prefix");
    }
}

static llama_pos stop_generation_position;
// A negative predictLength from Kotlin means unlimited output.  Keep the
// finite-limit flag separate from the position so generation can continue
// across KV-cache shifts until the model emits EOG or the UI cancels it.
static bool has_generation_limit = false;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    has_generation_limit = false;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    const auto started_at = SteadyClock::now();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received", __func__);
    const std::string formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               current_position == 0, true);
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    current_position += (int) system_tokens.size();
    LOGi("%s: system_tokens=%d current_position=%d eval_ms=%lld",
         __func__, (int) system_tokens.size(), current_position,
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
             SteadyClock::now() - started_at).count());

    system_prompt_position = current_position;
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_fullReset(JNIEnv *, jobject) {
    reset_long_term_states();
    reset_short_term_states();

    // Keep the expensive model context, batch allocation and compiled chat
    // template alive between independent turns. Clearing llama memory resets
    // the KV cache; recreating the sampler is still required because Agent A's
    // grammar can change with the number of references on every request.
    common_sampler_free(g_sampler);
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);

    LOGi("%s: Fast reset complete - KV cache cleared, context reused, sampler reinitialized", __func__);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_nativeCancelGeneration(JNIEnv *, jobject) {
#if defined(ATTRIBUTION_RUNTIME)
    g_attribution_cancelled.store(true, std::memory_order_relaxed);
#else
    common_sampler_reset(g_sampler);
    assistant_ss.str("");
    assistant_ss.clear();
    cached_token_chars.clear();
    LOGi("%s: Generation cancelled, sampler reset, KV cache preserved at position %d",
         __func__, current_position);
#endif
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    const auto started_at = SteadyClock::now();
    reset_short_term_states();

    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received", __func__);

    std::string content_for_format(user_prompt);
    if (content_for_format.empty()) {
        content_for_format = " ";
    }

    auto formatted_user_prompt = chat_add_and_format(ROLE_USER, content_for_format.c_str());
    apply_thinking_mode_to_generation_prompt(formatted_user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    if (formatted_user_prompt.size() >= 8 &&
        formatted_user_prompt.compare(formatted_user_prompt.size() - 8, 8, "<think>\n") == 0) {
        cached_token_chars = "<think>\n";
    }

    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, current_position == 0, true);
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    int skipped_tokens = 0;
    if (user_prompt_size > max_batch_size) {
        skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Advance by the number of tokens actually decoded.  When the user
    // prompt exceeds the context budget, user_tokens has been resized;
    // using the original size desynchronizes current_position from KV.
    current_position += (int) user_tokens.size();
    generation_start_position = current_position;
    LOGi("%s: user_tokens=%d original_tokens=%d skipped_tokens=%d current_position=%d eval_ms=%lld",
         __func__, (int) user_tokens.size(), user_prompt_size, skipped_tokens,
         current_position,
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
             SteadyClock::now() - started_at).count());

    has_generation_limit = n_predict > 0;
    if (has_generation_limit) {
        stop_generation_position = current_position + n_predict;
        LOGi("%s: finite output limit=%d tokens, stop_position=%d",
             __func__, n_predict, stop_generation_position);
    } else {
        LOGi("%s: unlimited output; stopping only at EOG or user cancellation", __func__);
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_countTokens(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jprompt,
        jboolean add_special
) {
    if (!g_context || !jprompt) {
        return -1;
    }

    const auto *prompt = env->GetStringUTFChars(jprompt, nullptr);
    const auto tokens = common_tokenize(g_context, prompt, add_special == JNI_TRUE, true);
    env->ReleaseStringUTFChars(jprompt, prompt);
    return static_cast<jint>(tokens.size());
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    if (has_generation_limit && current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    const bool is_eog = llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    current_position++;

    if (is_eog) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        std::string assistant_content = assistant_ss.str();
        auto think_end = assistant_content.find("</think>");
        if (think_end != std::string::npos) {
            assistant_content = assistant_content.substr(think_end + 8);
            while (!assistant_content.empty() && assistant_content[0] == '\n') {
                assistant_content.erase(0, 1);
            }
        }
        chat_add_and_format(ROLE_ASSISTANT, assistant_content);
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d, valid UTF-8 token bytes=%zu", new_token_id, cached_token_chars.size());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_unload(JNIEnv * /*env*/, jobject /*unused*/) {
    reset_long_term_states();
    reset_short_term_states();
    g_enable_thinking = true;

    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_LlamaEngine_shutdown(JNIEnv *, jobject /*unused*/) {
#if !defined(ATTRIBUTION_RUNTIME)
    llama_backend_free();
#endif
}
