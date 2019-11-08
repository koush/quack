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
#include "QuickJSContext.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_squareup_duktape_Duktape_createContext(JNIEnv* env, jclass type, jobject javaDuktape) {
  JavaVM* javaVM;
  env->GetJavaVM(&javaVM);
  try {
    return reinterpret_cast<jlong>(new QuickJSContext(javaVM, javaDuktape));
  } catch (std::bad_alloc&) {
    return 0L;
  }
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_destroyContext(JNIEnv *env, jclass type, jlong context) {
  delete reinterpret_cast<QuickJSContext*>(context);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_waitForDebugger(JNIEnv *env, jclass type, jlong context) {
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_cooperateDebugger(JNIEnv *env, jclass type, jlong context) {
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_isDebugging(JNIEnv *env, jclass type, jlong context) {
  return false;
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_debuggerAppNotify(JNIEnv *env, jclass type,
                                           jlong context,
                                           jobjectArray args) {
}

JNIEXPORT jstring JNICALL
Java_com_squareup_duktape_Duktape_stringify(JNIEnv *env, jclass type, jlong context, jlong object) {
  return reinterpret_cast<QuickJSContext*>(context)->stringify(env, object);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_setGlobalProperty(JNIEnv *env, jclass type, jlong context,
                                                    jobject property, jobject value) {
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_finalizeJavaScriptObject__JJ(JNIEnv *env, jclass type,
                                                               jlong context, jlong object) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_call(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobjectArray args) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_callMethod(
        JNIEnv *env, jclass type, jlong context, jlong object, jobject thiz, jobjectArray args) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_callProperty(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobject property,
                                           jobjectArray args) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key) {
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_setKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key, jobject value) {
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_setKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index, jobject value) {
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_setKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key, jobject value) {

}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_compileFunction__JLjava_lang_String_2Ljava_lang_String_2(
        JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_evaluate__JLjava_lang_String_2Ljava_lang_String_2(
    JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
}

JNIEXPORT jlong JNICALL
Java_com_squareup_duktape_Duktape_getHeapSize__J(JNIEnv *env, jclass type, jlong context) {
  return 0;
}

} // extern "C"
