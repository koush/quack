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
#include "DuktapeContext.h"
#include <memory>
#include <string>
#include <stdexcept>
#include <functional>
#include "java/JString.h"
#include "java/GlobalRef.h"
#include "java/JavaExceptions.h"
#include "StackChecker.h"
#include "duktape/duk_trans_socket.h"

namespace {

// Internal names used for properties in the Duktape context's global stash and bound variables.
// The \xff\xff part keeps the variable hidden from JavaScript (visible through C API only).
const char* JAVA_VM_PROP_NAME = "\xff\xffjavaVM";
const char* JAVA_THIS_PROP_NAME = "\xff\xffjava_this";
const char* JAVA_METHOD_PROP_NAME = "\xff\xffjava_method";
const char* DUKTAPE_CONTEXT_PROP_NAME = "\xff\xffjava_duktapecontext";

JNIEnv* getJNIEnv(duk_context *ctx) {
  duk_push_global_stash(ctx);
  duk_get_prop_string(ctx, -1, JAVA_VM_PROP_NAME);
  JavaVM* javaVM = static_cast<JavaVM*>(duk_require_pointer(ctx, -1));
  duk_pop_2(ctx);

  return getEnvFromJavaVM(javaVM);
}

DuktapeContext* getDuktapeContext(duk_context *ctx) {
  duk_push_global_stash(ctx);
  duk_get_prop_string(ctx, -1, DUKTAPE_CONTEXT_PROP_NAME);
  DuktapeContext* duktapeContext = static_cast<DuktapeContext*>(duk_require_pointer(ctx, -1));
  duk_pop_2(ctx);

  return duktapeContext;
}

jobject getJavaThis(duk_context* ctx) {
  duk_push_this(ctx);
  duk_get_prop_string(ctx, -1, JAVA_THIS_PROP_NAME);
  jobject thisObject = static_cast<jobject>(duk_require_pointer(ctx, -1));
  duk_pop_2(ctx);
  return thisObject;
}

duk_int_t eval_string_with_filename(duk_context *ctx, const char *src, const char *fileName) {
  duk_push_string(ctx, fileName);
  const int numArgs = 1;
  return duk_eval_raw(ctx, src, 0, numArgs | DUK_COMPILE_EVAL | DUK_COMPILE_SAFE |
                                   DUK_COMPILE_NOSOURCE | DUK_COMPILE_STRLEN);
}

// Called by Duktape to handle finalization of bound JavaObjects.
duk_ret_t javaObjectFinalizer(duk_context *ctx) {
  if (duk_get_prop_string(ctx, -1, JAVA_THIS_PROP_NAME)) {
    // Remove the global reference from the bound Java object.
    getJNIEnv(ctx)->DeleteGlobalRef(static_cast<jobject>(duk_require_pointer(ctx, -1)));
    duk_pop(ctx);
    duk_del_prop_string(ctx, -1, JAVA_METHOD_PROP_NAME);
  }

  // Pop the object passed in as an argument.
  duk_pop(ctx);
  return 0;
}

// Called by Duktape to handle finalization of bound JavaScriptObjects.
void javascriptObjectFinalizerInternal(duk_context *ctx) {
  // get the pointer or undefined
  if (duk_get_prop_string(ctx, -1, "__java_this")) {
    // Remove the global reference from the bound Java object.
    getJNIEnv(ctx)->DeleteWeakGlobalRef(static_cast<jobject>(duk_require_pointer(ctx, -1)));
    // delete the pointer prop
    duk_del_prop_string(ctx, -2, "__java_this");
  }
  // pop the pointer or undefined
  duk_pop(ctx);
}

// Called by Duktape to handle finalization of bound JavaScriptObjects.
duk_ret_t javascriptObjectFinalizer(duk_context *ctx) {
  javascriptObjectFinalizerInternal(ctx);

  // Pop the object passed in as an argument.
  duk_pop(ctx);
  return 0;
}

void fatalErrorHandler(void* udata, const char* msg) {
#ifndef NDEBUG
  duk_context* ctx = *reinterpret_cast<duk_context**>(udata);
  duk_push_context_dump(ctx);
  const char* debugContext = duk_get_string(ctx, -1);
  throw std::runtime_error(std::string(msg) + " - " + debugContext);
#else
  throw std::runtime_error(msg);
#endif
}

} // anonymous namespace

DuktapeContext::DuktapeContext(JavaVM* javaVM, jobject javaDuktape)
    : m_context(duk_create_heap(nullptr, nullptr, nullptr, &m_context, fatalErrorHandler))
    , m_objectType(m_javaValues.getObjectType(getEnvFromJavaVM(javaVM))) {
  if (!m_context) {
    throw std::bad_alloc();
  }

  JNIEnv *env = getEnvFromJavaVM(javaVM);
  m_javaDuktape = env->NewWeakGlobalRef(javaDuktape);

  m_booleanClass = findClass(env, "java/lang/Boolean");
  m_byteClass = findClass(env, "java/lang/Byte");
  m_shortClass = findClass(env, "java/lang/Short");
  m_integerClass = findClass(env, "java/lang/Integer");
  m_longClass = findClass(env, "java/lang/Long");
  m_floatClass = findClass(env, "java/lang/Float");
  m_doubleClass = findClass(env, "java/lang/Double");
  m_stringClass = findClass(env, "java/lang/String");
  m_objectClass = findClass(env, "java/lang/Object");

  m_duktapeObjectClass = findClass(env, "com/squareup/duktape/DuktapeObject");
  m_javaScriptObjectClass = findClass(env, "com/squareup/duktape/JavaScriptObject");
  m_javaObjectClass = findClass(env, "com/squareup/duktape/JavaObject");
  m_byteBufferClass = findClass(env, "java/nio/ByteBuffer");

  m_duktapeObjectGetMethod = env->GetMethodID(m_duktapeObjectClass, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
  m_duktapeObjectSetMethod = env->GetMethodID(m_duktapeObjectClass, "set", "(Ljava/lang/Object;Ljava/lang/Object;)V");
  m_duktapeObjectCallMethod = env->GetMethodID(m_duktapeObjectClass, "callMethod", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
  m_javaScriptObjectConstructor = env->GetMethodID(m_javaScriptObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;JJ)V");
  m_javaObjectConstructor = env->GetMethodID(m_javaObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;Ljava/lang/Object;)V");
  m_byteBufferAllocateDirect = env->GetStaticMethodID(m_byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");

  m_DebuggerSocket.client_sock = -1;

  // Stash the JVM object in the context, so we can find our way back from a Duktape C callback.
  duk_push_global_stash(m_context);
  duk_push_pointer(m_context, javaVM);
  duk_put_prop_string(m_context, -2, JAVA_VM_PROP_NAME);
  duk_push_pointer(m_context, this);
  duk_put_prop_string(m_context, -2, DUKTAPE_CONTEXT_PROP_NAME);
  duk_pop(m_context);
}

jclass DuktapeContext::findClass(JNIEnv *env, const char *className) {
    return (jclass)env->NewGlobalRef(env->FindClass(className));
}

DuktapeContext::~DuktapeContext() {
  // Delete the proxies before destroying the heap.
  duk_destroy_heap(m_context);
}

jobject DuktapeContext::popObject(JNIEnv *env) const {
  const int supportedTypeMask = DUK_TYPE_MASK_BOOLEAN | DUK_TYPE_MASK_NUMBER | DUK_TYPE_MASK_STRING;
  if (duk_check_type_mask(m_context, -1, supportedTypeMask)) {
    // The result is a supported scalar type - return it.
    return m_objectType->pop(m_context, env, false).l;
  }
  else if (duk_is_buffer_data(m_context, -1)) {
      duk_size_t size;
      void* p = duk_get_buffer_data(m_context, -1, &size);
      jobject byteBuffer = env->CallStaticObjectMethod(m_byteBufferClass, m_byteBufferAllocateDirect, (jint)size);
      memcpy(env->GetDirectBufferAddress(byteBuffer), p, size);
      duk_pop(m_context);
      return byteBuffer;
  }
  else if (duk_get_type(m_context, -1) == DUK_TYPE_OBJECT) {
    jobject javaThis = nullptr;

    // JavaScriptObject and JavaObject both contain a __java_this which points to the Java
    // instance of that object. JavaScriptObject will be created as needed, whereas JavaObject
    // will retrieve it from the JAVA_THIS_PROP_NAME  with __duktape_get Proxy handler.
    if (duk_has_prop_string(m_context, -1, "__java_this")) {
      duk_get_prop_string(m_context, -1, "__java_this");
      javaThis = reinterpret_cast<jobject>(duk_get_pointer(m_context, -1));
      // pop the pointer
      duk_pop(m_context);
      // this may be a weak or strong reference. make sure the weak reference is still valid.
      // weak references are used by JavaScript objects marshalled to Java.
      // strong references are used by Java objects marshalled to JavaScript.
      if (javaThis && env->IsSameObject(javaThis, nullptr)) {
        // todo: is this code still called with JavaScriptObject.finalize being implemented?
        // todo: finalize is called on GC, but is finalize run immediately after an object is deemed
        // todo: to be unreachable, or sometime afterwards?
        env->DeleteWeakGlobalRef(javaThis);
        javaThis = nullptr;
        duk_del_prop_string(m_context, -1, "__java_this");
      }
    }

    if (javaThis != nullptr) {
      // pop the JavaScript object
      duk_pop(m_context);
      return javaThis;
    }

    // get the pointer to this JavaScript object
    void* ptr = duk_get_heapptr(m_context, -1);

    // hold a reference to this JavaScript object in the stash by mapping the JavaScript object pointer to
    // object itself.
    duk_push_global_stash(m_context);
    duk_dup(m_context, -2);
    // use the pointer as an index for uniqueness. might be risky due to precision loss, but probably not.
    // can't use use duk_put_prop_heapptr since Objects as keys clobber each other:
    //      > f[{}] = 0
    //      0
    //      > f
    //              { '[object Object]': 0 }
    //      > f[{}] = 2
    //      2
    //      > f
    //              { '[object Object]': 2 }
    //      > f[{2:3}] = 4
    //      4
    //      > f
    //              { '[object Object]': 4 }
    duk_uarridx_t heapIndex = (duk_uarridx_t)reinterpret_cast<long>(ptr);
    duk_put_prop_index(m_context, -2, heapIndex);
    // pop the stash containing the hard reference
    duk_pop(m_context);

    // create a new holder for this JavaScript object
    javaThis = env->NewObject(m_javaScriptObjectClass, m_javaScriptObjectConstructor, reinterpret_cast<jlong>(m_javaDuktape), reinterpret_cast<jlong>(this), reinterpret_cast<jlong>(ptr));

    jweak weakRef = env->NewWeakGlobalRef(javaThis);
    // set a finalizer for the weak ref
    duk_push_c_function(m_context, javascriptObjectFinalizer, 1);
    duk_set_finalizer(m_context, -2);

    // attach the Java object's weak reference to the JavaScript object
    duk_push_pointer(m_context, weakRef);
    duk_put_prop_string(m_context, -2, "__java_this");

    // pop the JavaScript object, it is hard referenced
    duk_pop(m_context);

    return javaThis;
  } else {
    // The result is an unsupported type, undefined, or null.
    duk_pop(m_context);
    return nullptr;
  }
}

jobject DuktapeContext::popObject2(JNIEnv *env) const {
  jobject ret = popObject(env);
  duk_pop(m_context);
  return ret;
}

duk_ret_t DuktapeContext::duktapeSet() {
  JNIEnv *env = getJNIEnv(m_context);

  // pop the receiver, useless
  duk_pop(m_context);

  jobject value = popObject(env);
  jobject prop = popObject(env);

  // get the java reference
  duk_get_prop_string(m_context, -1, JAVA_THIS_PROP_NAME);
  jobject object = static_cast<jobject>(duk_require_pointer(m_context, -1));
  duk_pop(m_context);

  if (object == nullptr) {
    fatalErrorHandler(object, "DuktapeObject is null");
    return DUK_RET_REFERENCE_ERROR;
  }

  jclass objectClass = env->GetObjectClass(object);
  if (!env->IsAssignableFrom(objectClass, m_duktapeObjectClass)) {
      fatalErrorHandler(object, "Object is not DuktapeObject");
      return DUK_RET_REFERENCE_ERROR;
  }

  env->CallVoidMethod(object, m_duktapeObjectSetMethod, prop, value);
  if (!checkRethrowDuktapeError(env, m_context)) {
    return DUK_RET_ERROR;
  }

  // push the value that was just set.
  pushObject(env, value);
  return 1;
}

static duk_ret_t __duktape_set(duk_context *ctx) {
    DuktapeContext *duktapeContext = getDuktapeContext(ctx);
    return duktapeContext->duktapeSet();
}

duk_ret_t DuktapeContext::duktapeGet() {
  JNIEnv *env = getJNIEnv(m_context);

  // pop the receiver, useless
  duk_pop(m_context);

  jobject jprop;
  jobject object;
  {
    // need to specially handle string keys to get the java reference (JAVA_THIS_PROP_NAME)
    std::string prop;
    if (duk_get_type(m_context, -1) == DUK_TYPE_STRING) {
      // get the property name
      const char* cprop = duk_get_string(m_context, -1);
      // not a valid utf string. duktape internal.
      if (cprop[0] == '\x81') {
          duk_pop_2(m_context);
          duk_push_undefined(m_context);
          return 1;
      }
      jprop = env->NewStringUTF(cprop);
      prop = cprop;
      duk_pop(m_context);
    }
    else {
      jprop = popObject(env);
    }

    // get the java reference
    duk_get_prop_string(m_context, -1, JAVA_THIS_PROP_NAME);
    object = static_cast<jobject>(duk_require_pointer(m_context, -1));
    duk_pop(m_context);

    if (object == nullptr) {
      fatalErrorHandler(object, "DuktapeObject is null");
      return DUK_RET_REFERENCE_ERROR;
    }

    if (prop == "__java_this") {
      // short circuit the pointer retrieval from popObject here.
      duk_push_pointer(m_context, object);
      return 1;
    }
  }

  jclass objectClass = env->GetObjectClass(object);
  if (!env->IsAssignableFrom(objectClass, m_duktapeObjectClass)) {
    fatalErrorHandler(object, "Object is not DuktapeObject");
    return DUK_RET_REFERENCE_ERROR;
  }

  jobject push = env->CallObjectMethod(object, m_duktapeObjectGetMethod, jprop);
  if (!checkRethrowDuktapeError(env, m_context)) {
    return DUK_RET_ERROR;
  }

  pushObject(env, push);

  return 1;
}

static duk_ret_t __duktape_get(duk_context *ctx) {
  DuktapeContext *duktapeContext = getDuktapeContext(ctx);
  return duktapeContext->duktapeGet();
}

duk_ret_t DuktapeContext::duktapeHas() {
    JNIEnv *env = getJNIEnv(m_context);

    jobject prop = popObject(env);
    jobject object = static_cast<jobject>(duk_require_pointer(m_context, -1));
    duk_pop(m_context);

    jclass objectClass = env->GetObjectClass(object);
    if (!env->IsAssignableFrom(objectClass, m_duktapeObjectClass)) {
        fatalErrorHandler(object, "Object is not DuktapeObject");
        return DUK_RET_REFERENCE_ERROR;
    }

    // todo: actually implement has on the java side.

    jobject push = env->CallObjectMethod(object, m_duktapeObjectGetMethod, prop);
    if (!checkRethrowDuktapeError(env, m_context)) {
        return DUK_RET_ERROR;
    }

    duk_push_boolean(m_context, (duk_bool_t )(push != nullptr));

    return 1;
}

static duk_ret_t __duktape_has(duk_context *ctx) {
  DuktapeContext *duktapeContext = getDuktapeContext(ctx);
  return duktapeContext->duktapeHas();
}

duk_ret_t DuktapeContext::duktapeApply() {
  JNIEnv *env = getJNIEnv(m_context);

  // unpack the arguments
  duk_size_t argLen = duk_get_length(m_context, -1);
  jobjectArray javaArgs = env->NewObjectArray((jsize)argLen, m_objectClass, nullptr);
  for (duk_uarridx_t i = 0; i < argLen; i++) {
    duk_get_prop_index(m_context, -1, i);
    env->SetObjectArrayElement(javaArgs, (jsize)i, popObject(env));
  }
  // done, pop the argument list
  duk_pop(m_context);

  // get java this
  jobject javaThis = popObject(env);

  // get the java reference
  duk_get_prop_string(m_context, -1, JAVA_THIS_PROP_NAME);
  jobject object = static_cast<jobject>(duk_require_pointer(m_context, -1));
  duk_pop(m_context);

  jclass objectClass = env->GetObjectClass(object);
  if (!env->IsAssignableFrom(objectClass, m_duktapeObjectClass)) {
    fatalErrorHandler(object, "Object is not DuktapeObject");
    return DUK_RET_REFERENCE_ERROR;
  }

  jobject push = env->CallObjectMethod(object, m_duktapeObjectCallMethod, javaThis, javaArgs);
  if (!checkRethrowDuktapeError(env, m_context)) {
    return DUK_RET_ERROR;
  }

  pushObject(env, push);
  return 1;
}

static duk_ret_t __duktape_apply(duk_context *ctx) {
  DuktapeContext *duktapeContext = getDuktapeContext(ctx);
  return duktapeContext->duktapeApply();
}

void DuktapeContext::pushObject(JNIEnv *env, jlong object) {
    duk_push_heapptr(m_context, reinterpret_cast<void*>(object));
}

void DuktapeContext::pushObject(JNIEnv *env, jobject object) {
  if (object == nullptr) {
    duk_push_null(m_context);
    return;
  }

  {
    // try to push a native object first.
    jclass clazz = env->GetObjectClass(object);

    try {
      const JavaType* type = m_javaValues.get(env, clazz);
      jvalue value;
      value.l = object;
      type->push(m_context, env, value);

      return;
    }
    catch (...) {
      // not a native object, so marshall it
    }
  }

  // a JavaScriptObject can be unpacked back into a native duktape heap pointer/object
  // a DuktapeObject can support a duktape Proxy, and does not need any further boxing
  jclass objectClass = env->GetObjectClass(object);
  if (env->IsAssignableFrom(objectClass, m_javaScriptObjectClass)) {
    jfieldID contextField = env->GetFieldID(m_javaScriptObjectClass, "context", "J");
    jfieldID pointerField = env->GetFieldID(m_javaScriptObjectClass, "pointer", "J");

    DuktapeContext* context = reinterpret_cast<DuktapeContext*>(env->GetLongField(object, contextField));
    if (context == this) {
      void* ptr = reinterpret_cast<void*>(env->GetLongField(object, pointerField));
      duk_push_heapptr(m_context, ptr);
      return;
    }

    // a proxy already exists, but not for the correct DuktapeContext, so native javascript heap
    // pointer can't be used.
  }
  else if (env->IsAssignableFrom(objectClass, m_byteBufferClass)) {
    jlong capacity = env->GetDirectBufferCapacity(object);
    void *p = duk_push_fixed_buffer(m_context, (duk_size_t)capacity);
    memcpy(p, env->GetDirectBufferAddress(object), (size_t)capacity);
    return;
  }
  else if (!env->IsAssignableFrom(objectClass, m_duktapeObjectClass)) {
    // this is a normal Java object, so create a proxy for it to access fields and methods
    object = env->NewObject(m_javaObjectClass, m_javaObjectConstructor, reinterpret_cast<jlong>(m_javaDuktape), object);
  }

  // at this point, the object is guaranteed to be a JavaScriptObject from another DuktapeContext
  // or a DuktapeObject (java proxy of some sort). JavaScriptObject implements DuktapeObject,
  // so, it works without any further coercion.

  duk_get_global_string(m_context, "__makeProxy");

  const duk_idx_t objIndex = duk_require_normalize_index(m_context, duk_push_object(m_context));

  duk_push_pointer(m_context, env->NewGlobalRef(object));
  duk_put_prop_string(m_context, objIndex, JAVA_THIS_PROP_NAME);

  // set a finalizer for the ref
  duk_push_c_function(m_context, javaObjectFinalizer, 1);
  duk_set_finalizer(m_context, objIndex);

  // bind has
  duk_push_c_function(m_context, __duktape_has, 2);
  duk_put_prop_string(m_context, objIndex, "__duktape_has");

  // bind get
  duk_push_c_function(m_context, __duktape_get, 3);
  duk_put_prop_string(m_context, objIndex, "__duktape_get");

  // bind set
  duk_push_c_function(m_context, __duktape_set, 4);
  duk_put_prop_string(m_context, objIndex, "__duktape_set");

  // bind apply
  duk_push_c_function(m_context, __duktape_apply, 3);
  duk_put_prop_string(m_context, objIndex, "__duktape_apply");

  // make the proxy
  if (duk_pcall(m_context, 1) != DUK_EXEC_SUCCESS)
      queueJavaExceptionForDuktapeError(env, m_context);
}

jobject DuktapeContext::call(JNIEnv *env, jlong object, jobjectArray args) {
  CHECK_STACK(m_context);

  pushObject(env, object);

  jsize length = 0;
  if (args != nullptr) {
      length = env->GetArrayLength(args);
      for (int i = 0; i < length; i++) {
          jobject arg = env->GetObjectArrayElement(args, i);
          pushObject(env, arg);
      }
  }

  if (duk_pcall(m_context, length) != DUK_EXEC_SUCCESS) {
      queueJavaExceptionForDuktapeError(env, m_context);
      return nullptr;
  }

  duk_gc(m_context, 0);
  return popObject(env);
}

jobject DuktapeContext::callMethod(JNIEnv *env, jlong object, jobject thiz, jobjectArray args) {
  CHECK_STACK(m_context);

  // func
  pushObject(env, object);

  // this
  pushObject(env, thiz);

  jsize length = 0;
  if (args != nullptr) {
    length = env->GetArrayLength(args);
    for (int i = 0; i < length; i++) {
      jobject arg = env->GetObjectArrayElement(args, i);
      pushObject(env, arg);
    }
  }

  if (duk_pcall_method(m_context, length) != DUK_EXEC_SUCCESS) {
    queueJavaExceptionForDuktapeError(env, m_context);
    return nullptr;
  }

  duk_gc(m_context, 0);
  return popObject(env);
}

jobject DuktapeContext::callProperty(JNIEnv *env, jlong object, jobject property, jobjectArray args) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  duk_idx_t objectIndex = duk_normalize_index(m_context, -1);
  pushObject(env, property);

  jsize length = 0;
  if (args != nullptr) {
      length = env->GetArrayLength(args);
      for (int i = 0; i < length; i++) {
          jobject arg = env->GetObjectArrayElement(args, i);
          pushObject(env, arg);
      }
  }

  if (duk_pcall_prop(m_context, objectIndex, length) != DUK_EXEC_SUCCESS) {
      queueJavaExceptionForDuktapeError(env, m_context);
      // pop off indexed object before rethrowing error
      duk_pop(m_context);
      return nullptr;
  }

  duk_gc(m_context, 0);
  // pop twice since property call does not pop the indexed object
  return popObject2(env);
}

void DuktapeContext::setGlobalProperty(JNIEnv *env, jobject property, jobject value) {
  CHECK_STACK(m_context);

  duk_push_global_object(m_context);
  pushObject(env, property);
  pushObject(env, value);
  duk_put_prop(m_context, -3);
  duk_pop(m_context);
}

jobject DuktapeContext::getKeyInteger(JNIEnv *env, jlong object, jint index) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  duk_get_prop_index(m_context, -1, (duk_uarridx_t )index);
  // pop twice since indexing does not pop the indexed object
  return popObject2(env);
}

jobject DuktapeContext::getKeyObject(JNIEnv *env, jlong object, jobject key) {
  CHECK_STACK(m_context);

  // tbh this doesn't work if object is an actual java object vs a javascript object.
  // probably should throw.

  pushObject(env, object);
  pushObject(env, key);
  duk_get_prop(m_context, -2);
  // pop twice since indexing does not pop the indexed object
  return popObject2(env);
}

jobject DuktapeContext::getKeyString(JNIEnv *env, jlong object, jstring key) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  const JString instanceKey(env, key);
  duk_get_prop_string(m_context, -1, instanceKey);
  // pop twice since indexing does not pop the indexed object
  return popObject2(env);
}

jobject DuktapeContext::evaluate(JNIEnv* env, jstring code, jstring fname) {
  CHECK_STACK(m_context);

  const JString sourceCode(env, code);
  const JString fileName(env, fname);

  if (eval_string_with_filename(m_context, sourceCode, fileName) != DUK_EXEC_SUCCESS) {
    queueJavaExceptionForDuktapeError(env, m_context);
    return nullptr;
  }

  return popObject(env);
}

jobject DuktapeContext::compile(JNIEnv* env, jstring code, jstring fname) {
  CHECK_STACK(m_context);

  const JString sourceCode(env, code);
  const JString fileName(env, fname);

  duk_push_string(m_context, fileName);
  if (duk_pcompile_string_filename(m_context, DUK_COMPILE_FUNCTION, sourceCode) != DUK_EXEC_SUCCESS) {
      queueJavaExceptionForDuktapeError(env, m_context);
      return nullptr;
  }
  return popObject(env);
}

void DuktapeContext::waitForDebugger() {
  duk_trans_socket_init();
  duk_trans_socket_waitconn(&m_DebuggerSocket);

  duk_debugger_attach(m_context,
                      duk_trans_socket_read_cb,
                      duk_trans_socket_write_cb,
                      duk_trans_socket_peek_cb,
                      duk_trans_socket_read_flush_cb,
                      duk_trans_socket_write_flush_cb,
                      NULL,
                      duk_trans_socket_detached_cb,
                      &m_DebuggerSocket);
}

void DuktapeContext::cooperateDebugger() {
    duk_debugger_cooperate(m_context);
}

bool DuktapeContext::isDebugging() {
    return m_DebuggerSocket.client_sock > 0;
}

void DuktapeContext::debuggerAppNotify(JNIEnv *env, jobjectArray args) {
  CHECK_STACK(m_context);

  jsize length = 0;
  if (args != nullptr) {
    length = env->GetArrayLength(args);
    for (int i = 0; i < length; i++) {
      jobject arg = env->GetObjectArrayElement(args, i);
      pushObject(env, arg);
    }
  }

  duk_debugger_notify(m_context, length);
}

void DuktapeContext::setKeyString(JNIEnv *env, jlong object, jstring key, jobject value) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  pushObject(env, value);
  const JString instanceKey(env, key);
  duk_put_prop_string(m_context, -2, instanceKey);

  // pop indexed object
  duk_pop(m_context);
}

void DuktapeContext::setKeyInteger(JNIEnv *env, jlong object, jint index, jobject value) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  pushObject(env, value);
  duk_put_prop_index(m_context, -2, index);

  // pop indexed object
  duk_pop(m_context);
}

void DuktapeContext::setKeyObject(JNIEnv *env, jlong object, jobject key, jobject value) {
  CHECK_STACK(m_context);

  // tbh this doesn't work if object is an actual java object vs a javascript object.
  // probably should throw.

  pushObject(env, object);
  pushObject(env, key);
  pushObject(env, value);
  duk_put_prop(m_context, -3);

  // pop indexed object
  duk_pop(m_context);
}

jstring DuktapeContext::stringify(JNIEnv *env, jlong object) {
  CHECK_STACK(m_context);
  duk_get_global_string(m_context, "JSON");
  duk_idx_t objectIndex = duk_normalize_index(m_context, -1);

  duk_push_string(m_context, "stringify");
  pushObject(env, object);
  if (duk_pcall_prop(m_context, objectIndex, 1) != DUK_EXEC_SUCCESS) {
    queueJavaExceptionForDuktapeError(env, m_context);
    // pop off indexed object before rethrowing error
    duk_pop(m_context);
    return nullptr;
  }

  return (jstring)popObject2(env);
}

void DuktapeContext::finalizeJavaScriptObject(JNIEnv *env, jlong object) {
  CHECK_STACK(m_context);

  // the JavaScriptObject (java representation) was collected.

  // clean up the ref to the duktape heap object
  void* ptr = reinterpret_cast<void*>(object);
  duk_push_heapptr(m_context, ptr);
  // unset the finalizer, no longer necessary
  duk_push_undefined(m_context);
  duk_set_finalizer(m_context, -2);
  // release the ref to the javascript object
  javascriptObjectFinalizerInternal(m_context);
  duk_pop(m_context);

  // the Java side kept this duktape heap object alive with a reference in the global stash.
  // can delete that now.
  duk_push_global_stash(m_context);
  duk_uarridx_t heapIndex = (duk_uarridx_t)reinterpret_cast<long>(ptr);
  duk_del_prop_index(m_context, -1, heapIndex);
  duk_pop(m_context);
}
