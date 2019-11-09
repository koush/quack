#ifndef DUKTAPE_ANDROID_DUKTAPE_CONTEXT_H
#define DUKTAPE_ANDROID_DUKTAPE_CONTEXT_H

#include <jni.h>
#include <list>
#include "../quickjs/quickjs.h"
#include "../quickjs/quickjs-debugger.h"

class QuickJSContext;

typedef void CustomFinalizer(QuickJSContext *ctx, JSValue val, void *udata);
typedef struct CustomFinalizerData {
    QuickJSContext *ctx;
    CustomFinalizer *finalizer;
    void *udata;
} CustomFinalizerData;

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

    inline operator JSValue() {
        return value;
    }
private:
    JSContext *ctx;
    JSValue value;
};

class QuickJSContext {
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

    jstring toString(JNIEnv *env, JSValue value);
    JSValue toString(JNIEnv *env, jstring value);
    jstring stringify(JNIEnv *env, long object);

    jobject toObject(JNIEnv *env, JSValue value);
    
    jobject evaluate(JNIEnv *env, jstring code, jstring filename);

    void setFinalizer(JavaVM *vm, JSValue value, CustomFinalizer finalizer, void *udata);

    JavaVM* javaVM;
    jobject javaDuktape;
    JSRuntime *runtime;
    JSContext *ctx;
    JSValue stash;

    jclass objectClass;

    jclass duktapeJavaObject;
    jclass duktapeClass;
    jclass duktapeObjectClass;
    jclass javaScriptObjectClass;
    jclass javaObjectClass;
    jmethodID javaObjectGetObject;
    jclass jsonObjectClass;
    jclass byteBufferClass;
    jmethodID duktapeHasMethod;
    jmethodID duktapeGetMethod;
    jmethodID duktapeSetMethod;
    jmethodID duktapeCallMethodMethod;
    jmethodID javaScriptObjectConstructor;
    jmethodID javaObjectConstructor;
    jmethodID byteBufferAllocateDirect;
    jfieldID contextField;
    jfieldID pointerField;
    jfieldID jsonField;



    JSAtom javaThisAtom;
    JSAtom customFinalizerAtom;
};

#endif
