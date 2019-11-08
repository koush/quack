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
#include "DuktapeContext.h"
#include "java/GlobalRef.h"
#include "java/JavaExceptions.h"
#error foo
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_squareup_duktape_Duktape_createContext(JNIEnv* env, jclass type, jobject javaDuktape) {
  JavaVM* javaVM;
  env->GetJavaVM(&javaVM);
  try {
    return reinterpret_cast<jlong>(new DuktapeContext(javaVM, javaDuktape));
  } catch (std::bad_alloc&) {
    return 0L;
  }
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_destroyContext(JNIEnv *env, jclass type, jlong context) {
  delete reinterpret_cast<DuktapeContext*>(context);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_waitForDebugger(JNIEnv *env, jclass type, jlong context) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  duktape->waitForDebugger();
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_cooperateDebugger(JNIEnv *env, jclass type, jlong context) {
    DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
    duktape->cooperateDebugger();
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_isDebugging(JNIEnv *env, jclass type, jlong context) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return (jboolean)duktape->isDebugging();
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_debuggerAppNotify(JNIEnv *env, jclass type,
                                           jlong context,
                                           jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->debuggerAppNotify(env, args);
}

JNIEXPORT jstring JNICALL
Java_com_squareup_duktape_Duktape_stringify(JNIEnv *env, jclass type, jlong context, jlong object) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->stringify(env, object);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_setGlobalProperty(JNIEnv *env, jclass type, jlong context,
                                                    jobject property, jobject value) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  duktape->setGlobalProperty(env, property, value);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_finalizeJavaScriptObject__JJ(JNIEnv *env, jclass type,
                                                               jlong context, jlong object) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  duktape->finalizeJavaScriptObject(env, object);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_call(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->call(env, object, args);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_callMethod(
        JNIEnv *env, jclass type, jlong context, jlong object, jobject thiz, jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->callMethod(env, object, thiz, args);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_callProperty(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobject property,
                                           jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->callProperty(env, object, property, args);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  return duktape->getKeyObject(env, object, key);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  return duktape->getKeyInteger(env, object, index);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  return duktape->getKeyString(env, object, key);
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_setKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key, jobject value) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return (jboolean)false;
  }
  return duktape->setKeyObject(env, object, key, value);
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_setKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index, jobject value) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return (jboolean)false;
  }
  return duktape->setKeyInteger(env, object, index, value);
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_setKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key, jobject value) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return (jboolean)false;
  }
  return duktape->setKeyString(env, object, key, value);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_compileFunction__JLjava_lang_String_2Ljava_lang_String_2(
        JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  try {
    return duktape->compile(env, code, fname);
  } catch (const std::invalid_argument& e) {
    queueIllegalArgumentException(env, e.what());
  } catch (const std::exception& e) {
    queueDuktapeException(env, e.what());
  }
  return nullptr;
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_evaluate__JLjava_lang_String_2Ljava_lang_String_2(
    JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  try {
    return duktape->evaluate(env, code, fname);
  } catch (const std::invalid_argument& e) {
    queueIllegalArgumentException(env, e.what());
  } catch (const std::exception& e) {
    queueDuktapeException(env, e.what());
  }
  return nullptr;
}

JNIEXPORT jlong JNICALL
Java_com_squareup_duktape_Duktape_getHeapSize__J(JNIEnv *env, jclass type, jlong context) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->getHeapSize();
}

} // extern "C"
