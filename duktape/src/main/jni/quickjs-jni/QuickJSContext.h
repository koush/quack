#ifndef QUICKJS_CONTEXT_H
#define QUICKJS_CONTEXT_H

#include <jni.h>
#include <list>
#include "../quickjs/quickjs.h"
#include "../quickjs/quickjs-debugger.h"
#include "../JSContext.h"

class QuickJSContext;

typedef void CustomFinalizer(QuickJSContext *ctx, JSValue val, void *udata);
typedef struct CustomFinalizerData {
    QuickJSContext *ctx;
    CustomFinalizer *finalizer;
    void *udata;
} CustomFinalizerData;


class LocalRefHolder {
public:
    LocalRefHolder(JNIEnv *env, jobject value):
        env(env),
        value(value) {
    }
    ~LocalRefHolder() {
        env->DeleteLocalRef(value);
    }
    LocalRefHolder(const LocalRefHolder &other) {
        env = other.env;
        value = env->NewLocalRef(other.value);
    }  

    inline LocalRefHolder& operator=(const LocalRefHolder& other) {
        throw;
    }

    inline operator jobject() const {
        return value;
    }
private:
    JNIEnv *env;
    jobject value;
};


class JSValueHolder {
public:
    JSValueHolder(JSContext *ctx, JSValue value):
        ctx(ctx),
        value(value) {
    }
    ~JSValueHolder() {
        JS_FreeValue(ctx, value);
    }
    JSValueHolder(const JSValueHolder &other) {
        ctx = other.ctx;
        value = JS_DupValue(ctx, other.value);
    }  

    inline JSValueHolder& operator=(const JSValueHolder& other) {
        throw;
    }

    inline operator JSValue() const {
        return value;
    }
private:
    JSContext *ctx;
    JSValue value;
};

class QuickJSContext : public JSContext {
public:
    explicit QuickJSContext(JavaVM* javaVM, jobject javaDuktape);
    ~QuickJSContext();
    QuickJSContext(const QuickJSContext &) = delete;
    QuickJSContext & operator=(const QuickJSContext &) = delete;

    JSAtom privateAtom(const char *str);

    inline JSValueHolder hold(JSValue value) {
        return JSValueHolder(ctx, value);
    }

    jclass findClass(JNIEnv *env, const char *className);

    std::string toStdString(JSValue value);
    jstring toString(JNIEnv *env, JSValue value);
    JSValue toString(JNIEnv *env, jstring value);

    jobject toObject(JNIEnv *env, JSValue value);
    jobject toObjectCheckQuickJSError(JNIEnv *env, JSValue value);
    JSValue toObject(JNIEnv *env, jobject value);
    
    void setFinalizer(JSValue value, CustomFinalizer finalizer, void *udata);
    void setFinalizerOnFinalizerObject(JSValue finalizerObject, CustomFinalizer finalizer, void *udata);

    void finalizeJavaScriptObject(JNIEnv *env, jlong object);

    jobject evaluate(JNIEnv *env, jstring code, jstring filename);
    jobject compile(JNIEnv* env, jstring code, jstring filename);
    void setGlobalProperty(JNIEnv *env, jobject property, jobject value);
    jstring stringify(JNIEnv *env, jlong object);

    jobject getKeyString(JNIEnv* env, jlong object, jstring key);
    jobject getKeyInteger(JNIEnv* env, jlong object, jint index);
    jobject getKeyObject(JNIEnv* env, jlong object, jobject key);
    jboolean setKeyString(JNIEnv* env, jlong object, jstring key, jobject value);
    jboolean setKeyInteger(JNIEnv* env, jlong object, jint index, jobject value);
    jboolean setKeyInternal(JNIEnv* env, JSValue thiz, jobject key, jobject value);
    jboolean setKeyObject(JNIEnv* env, jlong object, jobject key, jobject value);

    jobject callInternal(JNIEnv *env, JSValue thiz, JSValue func, jobjectArray args);
    jobject call(JNIEnv *env, jlong object, jobjectArray args);
    jobject callProperty(JNIEnv *env, jlong object, jobject property, jobjectArray args);
    jobject callMethod(JNIEnv *env, jlong method, jobject object, jobjectArray args);

    void waitForDebugger(JNIEnv *env, jstring connectionString);
    void cooperateDebugger();
    jboolean isDebugging();
    void debuggerAppNotify(JNIEnv *env, jobjectArray args) {}
    jlong getHeapSize(JNIEnv* env);

    // DuktapeObject class traps
    int quickjs_has(jobject object, JSAtom atom);
    JSValue quickjs_get(jobject object, JSAtom atom, JSValueConst receiver);
    int quickjs_set(jobject object, JSAtom atom, JSValueConst value, JSValueConst receiver, int flags);
    JSValue quickjs_apply(jobject func_obj, JSValueConst this_val, int argc, JSValueConst *argv);

    bool rethrowQuickJSErrorToJava(JNIEnv *env);
    bool rethrowJavaExceptionToQuickJS(JNIEnv *env);

    void runJobs(JNIEnv *env);

    JavaVM* javaVM;
    jobject javaDuktape;
    JSRuntime *runtime;
    JSContext *ctx;
    JSValue stash;
    JSValue thrower_function;

    jclass objectClass;
    jmethodID objectToString;

    jclass duktapeJavaObject;
    jclass duktapeClass;
    jclass duktapeObjectClass;
    jclass javaScriptObjectClass;
    jclass javaObjectClass;
    jmethodID duktapeJavaObjectGetObject;
    jclass duktapejsonObjectClass;
    jmethodID duktapeHasMethod;
    jmethodID duktapeGetMethod;
    jmethodID duktapeSetMethod;
    jmethodID duktapeCallMethodMethod;
    jmethodID javaScriptObjectConstructor;
    jmethodID javaObjectConstructor;
    jmethodID byteBufferAllocateDirect;
    jfieldID contextField;
    jfieldID pointerField;
    jfieldID duktapeJsonField;

    jclass booleanClass;
    jmethodID booleanValueOf;
    jmethodID booleanValue;
    jclass intClass;
    jmethodID intValueOf;
    jmethodID intValue;
    jclass doubleClass;
    jmethodID doubleValueOf;
    jmethodID doubleValue;
    jclass stringClass;
    jclass byteBufferClass;

    jclass duktapeExceptionClass;
    jmethodID addDuktapeStack;
    jmethodID addJavaStack;

    JSAtom atomHoldsJavaObject;
    JSAtom customFinalizerAtom;
    JSAtom javaExceptionAtom;
};

#endif
