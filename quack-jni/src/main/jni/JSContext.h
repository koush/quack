#ifndef JS_CONTEXT_H
#define JS_CONTEXT_H

#include <jni.h>

inline JNIEnv* getEnvFromJavaVM(JavaVM* javaVM) {
  if (javaVM == nullptr) {
    return nullptr;
  }

  JNIEnv* env;
  javaVM->AttachCurrentThread(
#ifdef __ANDROID__
      &env,
#else
      reinterpret_cast<void**>(&env),
#endif
      nullptr);
  return env;
}

class JSContext {
public:
    virtual ~JSContext() {};

    virtual void finalizeJavaScriptObjects(JNIEnv *env, jlongArray objects) = 0;

    virtual jobject evaluate(JNIEnv *env, jstring code, jstring filename) = 0;
    virtual jobject evaluateModule(JNIEnv *env, jstring code, jstring filename) = 0;
    virtual jobject compile(JNIEnv* env, jstring code, jstring filename) = 0;

    virtual jobject getGlobalObject(JNIEnv *env) = 0;
    virtual jstring stringify(JNIEnv *env, jlong object) = 0;

    virtual jobject getKeyString(JNIEnv* env, jlong object, jstring key) = 0;
    virtual jobject getKeyInteger(JNIEnv* env, jlong object, jint index) = 0;
    virtual jobject getKeyObject(JNIEnv* env, jlong object, jobject key) = 0;
    virtual jboolean setKeyString(JNIEnv* env, jlong object, jstring key, jobject value) = 0;
    virtual jboolean setKeyInteger(JNIEnv* env, jlong object, jint index, jobject value) = 0;
    virtual jboolean setKeyObject(JNIEnv* env, jlong object, jobject key, jobject value) = 0;

    virtual jobject callConstructor(JNIEnv *env, jlong object, jobjectArray args) = 0;
    virtual jobject call(JNIEnv *env, jlong object, jobjectArray args) = 0;
    virtual jobject callProperty(JNIEnv *env, jlong object, jobject property, jobjectArray args) = 0;
    virtual jobject callMethod(JNIEnv *env, jlong method, jobject object, jobjectArray args) = 0;

    virtual jboolean hasPendingJobs(JNIEnv *env) = 0;
    virtual void runJobs(JNIEnv *env) = 0;

    virtual void waitForDebugger(JNIEnv *env, jstring connectionString) = 0;
    virtual void cooperateDebugger() = 0;
    virtual jboolean isDebugging() = 0;
    virtual void debuggerAppNotify(JNIEnv *env, jobjectArray args) = 0;
    virtual jlong getHeapSize(JNIEnv *env) = 0;
};

#endif