#ifndef DUKTAPE_ANDROID_DUKTAPE_CONTEXT_H
#define DUKTAPE_ANDROID_DUKTAPE_CONTEXT_H

#include <jni.h>
#include <list>
#include "../quickjs/quickjs.h"
#include "../quickjs/quickjs-debugger.h"

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
        JSValueHolder ret = JSValueHolder(other);
        return ret;
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

    inline JSValueHolder hold(JSValue value) {
        return JSValueHolder(ctx, value);
    }

    jstring toString(JNIEnv *env, JSValue value);
    JSValue toString(JNIEnv *env, jstring value);
    jstring stringify(JNIEnv *env, long object);
    
    jobject evaluate(JNIEnv *env, jstring code, jstring filename);

    JavaVM* javaVM;
    jobject javaDuktape;
    JSRuntime *runtime;
    JSContext *ctx;
};


#endif
