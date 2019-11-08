#include "QuickJSContext.h"

QuickJSContext::QuickJSContext(JavaVM* javaVM, jobject javaDuktape):
    javaVM(javaVM),
    javaDuktape(javaDuktape) {

    runtime = JS_NewRuntime();
    ctx = JS_NewContext(runtime);
}

QuickJSContext::~QuickJSContext() {
    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);
}

jstring QuickJSContext::toString(JNIEnv *env, JSValue value) {
    const char *str = JS_ToCString(ctx, value);
    jstring ret = env->NewStringUTF(str);
    JS_FreeCString(ctx, str);
    return ret;
}

JSValue QuickJSContext::toString(JNIEnv *env, jstring value) {
    return JS_NewString(ctx, env->GetStringUTFChars(value, 0));
}

jstring QuickJSContext::stringify(JNIEnv *env, long object) {
    return toString(env, js_debugger_json_stringify(ctx, JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void*>(object))));
}

jobject QuickJSContext::evaluate(JNIEnv *env, jstring code, jstring filename) {
    const char* codeStr = env->GetStringUTFChars(code, 0);
    JSValue ret = JS_Eval(ctx, codeStr, strlen(codeStr), env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL);
    JS_FreeValue(ret);
    return nullptr;
}
