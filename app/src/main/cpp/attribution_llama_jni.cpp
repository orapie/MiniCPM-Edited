// A second shared object gives Agent A an isolated copy of llama_jni.cpp's
// model/context/sampler globals. It deliberately shares llama.cpp's process
// backend with Agent B, but never its KV cache or loaded GGUF model.
#define ATTRIBUTION_RUNTIME 1
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_init Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_init
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_load Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_load
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_setEnableThinkingNative Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_setEnableThinkingNative
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_prepare Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_prepare
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_systemInfo Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_systemInfo
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_processSystemPrompt Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_processSystemPrompt
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_fullReset Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_fullReset
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_nativeCancelGeneration Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_nativeCancelGeneration
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_processUserPrompt Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_processUserPrompt
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_countTokens Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_countTokens
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_generateNextToken Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_generateNextToken
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_unload Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_unload
#define Java_com_example_minicpm_1v_1demo_LlamaEngine_shutdown Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_shutdown
#include "llama_jni.cpp"

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_configureRequest(
        JNIEnv *env, jobject, jlong remaining_ms, jstring grammar) {
    const char *value = env->GetStringUTFChars(grammar, nullptr);
    if (!value) return;
    g_attribution_grammar = value;
    env->ReleaseStringUTFChars(grammar, value);
    g_attribution_cancelled.store(false, std::memory_order_relaxed);
    g_attribution_deadline = SteadyClock::now() + std::chrono::milliseconds(remaining_ms);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_requestAborted(JNIEnv *, jobject) {
    return attribution_should_abort(nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_AttributionLlamaEngine_finishRequest(JNIEnv *, jobject) {
    g_attribution_deadline = SteadyClock::time_point::max();
    g_attribution_grammar.clear();
}
