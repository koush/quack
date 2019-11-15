/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <memory>
#include <mutex>
#include <chrono>
#include <jni.h>
#include "JSContext.h"
#include "quickjs-jni/QuickJSContext.h"
#include "duktape-jni/DuktapeContext.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_koushikdutta_quack_QuackContext_createContext(JNIEnv* env, jclass type, jobject javaDuktape, jboolean useQuickJS) {
    JavaVM* javaVM;
    env->GetJavaVM(&javaVM);
    try {
        if (useQuickJS)
            return reinterpret_cast<jlong>(new QuickJSContext(javaVM, javaDuktape));
        else
            return reinterpret_cast<jlong>(new DuktapeContext(javaVM, javaDuktape));
    }
    catch (std::bad_alloc&) {
        return 0L;
    }
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_destroyContext(JNIEnv *env, jclass type, jlong context) {
  delete reinterpret_cast<JSContext *>(context);
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_waitForDebugger(JNIEnv *env, jclass type, jlong context, jstring connectionString) {
    reinterpret_cast<JSContext *>(context)->waitForDebugger(env, connectionString);
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_cooperateDebugger(JNIEnv *env, jclass type, jlong context) {
    reinterpret_cast<JSContext *>(context)->cooperateDebugger();
}

JNIEXPORT jboolean JNICALL
Java_com_koushikdutta_quack_QuackContext_isDebugging(JNIEnv *env, jclass type, jlong context) {
  return reinterpret_cast<JSContext *>(context)->isDebugging();
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_debuggerAppNotify(JNIEnv *env, jclass type,
                                           jlong context,
                                           jobjectArray args) {
    reinterpret_cast<JSContext *>(context)->debuggerAppNotify(env, args);
}

JNIEXPORT jstring JNICALL
Java_com_koushikdutta_quack_QuackContext_stringify(JNIEnv *env, jclass type, jlong context, jlong object) {
  return reinterpret_cast<JSContext *>(context)->stringify(env, object);
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_setGlobalProperty(JNIEnv *env, jclass type, jlong context,
                                                    jobject property, jobject value) {
    return reinterpret_cast<JSContext *>(context)->setGlobalProperty(env, property, value);
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_finalizeJavaScriptObject__JJ(JNIEnv *env, jclass type,
                                                               jlong context, jlong object) {
    return reinterpret_cast<JSContext *>(context)->finalizeJavaScriptObject(env, object);
                                       
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_call(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobjectArray args) {
    return reinterpret_cast<JSContext *>(context)->call(env, object, args);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_callMethod(
        JNIEnv *env, jclass type, jlong context, jlong object, jobject thiz, jobjectArray args) {
    return reinterpret_cast<JSContext *>(context)->callMethod(env, object, thiz, args);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_callProperty(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobject property,
                                           jobjectArray args) {
    return reinterpret_cast<JSContext *>(context)->callProperty(env, object, property, args);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_getKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key) {
    return reinterpret_cast<JSContext *>(context)->getKeyObject(env, object, key);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_getKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index) {
    return reinterpret_cast<JSContext *>(context)->getKeyInteger(env, object, index);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_getKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key) {
    return reinterpret_cast<JSContext *>(context)->getKeyString(env, object, key);
}

JNIEXPORT jboolean JNICALL
Java_com_koushikdutta_quack_QuackContext_setKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key, jobject value) {
    return reinterpret_cast<JSContext *>(context)->setKeyObject(env, object, key, value);
}

JNIEXPORT jboolean JNICALL
Java_com_koushikdutta_quack_QuackContext_setKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index, jobject value) {
    return reinterpret_cast<JSContext *>(context)->setKeyInteger(env, object, index, value);
}

JNIEXPORT jboolean JNICALL
Java_com_koushikdutta_quack_QuackContext_setKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key, jobject value) {
    return reinterpret_cast<JSContext *>(context)->setKeyString(env, object, key, value);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_compileFunction(
        JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
    return reinterpret_cast<JSContext *>(context)->compile(env, code, fname);
}

JNIEXPORT jobject JNICALL
Java_com_koushikdutta_quack_QuackContext_evaluate(
    JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
    return reinterpret_cast<JSContext *>(context)->evaluate(env, code, fname);
}

JNIEXPORT jlong JNICALL
Java_com_koushikdutta_quack_QuackContext_getHeapSize__J(JNIEnv *env, jclass type, jlong context) {
    return reinterpret_cast<JSContext *>(context)->getHeapSize(env);
}

JNIEXPORT void JNICALL
Java_com_koushikdutta_quack_QuackContext_runJobs(JNIEnv *env, jclass type, jlong context) {
    reinterpret_cast<JSContext *>(context)->runJobs(env);
}

} // extern "C"
