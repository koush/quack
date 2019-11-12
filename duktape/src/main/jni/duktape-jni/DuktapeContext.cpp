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
#include "../duktape/duk_trans_socket.h"

namespace {

// Internal names used for properties in the Duktape context's global stash and bound variables.
// The \xff\xff part keeps the variable hidden from JavaScript (visible through C API only).
const char* JAVA_VM_PROP_NAME = "\xff\xffjavaVM";
const char* JAVA_THIS_PROP_NAME = "\xff\xffjava_this";
const char* JAVASCRIPT_THIS_PROP_NAME = "__javascript_this";
const char* DUKTAPE_CONTEXT_PROP_NAME = "\xff\xffjava_duktapecontext";
const char* JAVA_EXCEPTION_PROP_NAME = "\xff\xffjava_exception";

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

duk_int_t eval_string_with_filename(duk_context *ctx, const char *src, const char *fileName) {
  duk_push_string(ctx, fileName);
  const int numArgs = 1;
  return duk_eval_raw(ctx, src, 0, numArgs | DUK_COMPILE_EVAL | DUK_COMPILE_SAFE |
                                   DUK_COMPILE_NOSOURCE | DUK_COMPILE_STRLEN);
}

// Called by Duktape to handle finalization of bound JavaObjects.
duk_ret_t javaObjectFinalizer(duk_context *ctx) {
  {
    CHECK_STACK(ctx);

    // todo: should this EVER be null? it's a global ref.
    if (duk_get_prop_string(ctx, -1, JAVASCRIPT_THIS_PROP_NAME)) {
      // Remove the global reference from the bound Java object.
      void* ptr = duk_require_pointer(ctx, -1);
      duk_del_prop_string(ctx, -2, JAVASCRIPT_THIS_PROP_NAME);
      if (ptr) {
        getJNIEnv(ctx)->DeleteGlobalRef(static_cast<jobject>(ptr));
      }
    }
    duk_pop(ctx);
  }

  // Pop the object passed in as an argument.
  duk_pop(ctx);
  return 0;
}

// Called by Duktape to handle finalization of bound JavaScriptObjects.
void javascriptObjectFinalizerInternal(duk_context *ctx) {
  CHECK_STACK(ctx);

  // get the pointer or undefined
  if (duk_get_prop_string(ctx, -1, JAVA_THIS_PROP_NAME)) {
    void* ptr = duk_require_pointer(ctx, -1);
    duk_del_prop_string(ctx, -2, JAVA_THIS_PROP_NAME);
    if (ptr) {
      getJNIEnv(ctx)->DeleteWeakGlobalRef(static_cast<jobject>(ptr));
    }
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
  DuktapeContext* context = reinterpret_cast<DuktapeContext*>(udata);
  duk_context* ctx = context->m_context;
  duk_push_context_dump(ctx);
  const char* debugContext = duk_get_string(ctx, -1);
  throw std::runtime_error(std::string(msg) + " - " + debugContext);
#else
  throw std::runtime_error(msg);
#endif
}

} // anonymous namespace

static void* tracked_alloc(void *udata, duk_size_t size) {
  DuktapeContext* context = reinterpret_cast<DuktapeContext*>(udata);
  void* ret = malloc(size);
  if (ret != nullptr) {
      context->pointers[ret] = size;
      context->m_heapSize += size;
  }
  return ret;
}
static void *tracked_realloc(void *udata, void *ptr, duk_size_t size) {
  DuktapeContext* context = reinterpret_cast<DuktapeContext*>(udata);
  void* ret = realloc(ptr, size);
  if (ret != nullptr) {
      if (context->pointers.find(ptr) != context->pointers.end()) {
          size_t allocated = context->pointers[ptr];
          context->pointers.erase(ptr);
          context->m_heapSize -= allocated;
      }
      context->pointers[ret] = size;
      context->m_heapSize += size;
  }
  return ret;
}
static void tracked_free(void *udata, void *ptr) {
  DuktapeContext* context = reinterpret_cast<DuktapeContext*>(udata);
  if (context->pointers.find(ptr) != context->pointers.end()) {
    size_t allocated = context->pointers[ptr];
    context->pointers.erase(ptr);
    context->m_heapSize -= allocated;
  }
  free(ptr);
}

class ContextSwitcher {
public:
    duk_context* originalContext;
    DuktapeContext* context;
    ContextSwitcher(DuktapeContext* context, duk_context* newContext) {
        this->context = context;
        originalContext = context->m_context;
        context->m_context = newContext;
    }
    ~ContextSwitcher() {
        context->m_context = originalContext;
    }
};

static duk_ret_t __duktape_get(duk_context *ctx);
static duk_ret_t __duktape_has(duk_context *ctx);
static duk_ret_t __duktape_set(duk_context *ctx);
static duk_ret_t __duktape_apply(duk_context *ctx);
static duk_ret_t __duktape_noop(duk_context *) { return 0; }

DuktapeContext::DuktapeContext(JavaVM* javaVM, jobject javaDuktape)
    : m_context(duk_create_heap(tracked_alloc, tracked_realloc, tracked_free, this, fatalErrorHandler))
    , m_heapSize(0)
    , m_objectType(m_javaValues.getObjectType(getEnvFromJavaVM(javaVM))) {
  if (!m_context) {
    throw std::bad_alloc();
  }

  JNIEnv *env = getEnvFromJavaVM(javaVM);
  m_javaDuktape = env->NewWeakGlobalRef(javaDuktape);

  m_objectClass = findClass(env, "java/lang/Object");

  jclass duktapeJavaObject = findClass(env, "com/squareup/duktape/DuktapeJavaObject");

  m_duktapeClass = findClass(env, "com/squareup/duktape/Duktape");
  m_duktapeObjectClass = findClass(env, "com/squareup/duktape/DuktapeObject");
  m_javaScriptObjectClass = findClass(env, "com/squareup/duktape/JavaScriptObject");
  m_javaObjectClass = findClass(env, "com/squareup/duktape/JavaObject");
  m_jsonObjectClass = findClass(env, "com/squareup/duktape/DuktapeJsonObject");
  m_byteBufferClass = findClass(env, "java/nio/ByteBuffer");

  m_duktapeHasMethod = env->GetMethodID(m_duktapeClass, "duktapeHas", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;)Z");
  m_duktapeGetMethod = env->GetMethodID(m_duktapeClass, "duktapeGet", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;)Ljava/lang/Object;");
  m_duktapeSetMethod = env->GetMethodID(m_duktapeClass, "duktapeSet", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;Ljava/lang/Object;)Z");
  m_duktapeCallMethodMethod = env->GetMethodID(m_duktapeClass, "duktapeCallMethod", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

  m_javaScriptObjectConstructor = env->GetMethodID(m_javaScriptObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;JJ)V");
  m_javaObjectConstructor = env->GetMethodID(m_javaObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;Ljava/lang/Object;)V");
  m_javaObjectGetObject = env->GetMethodID(duktapeJavaObject, "getObject", "(Ljava/lang/Class;)Ljava/lang/Object;");
  m_byteBufferAllocateDirect = env->GetStaticMethodID(m_byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");

  m_contextField = env->GetFieldID(m_javaScriptObjectClass, "context", "J");
  m_pointerField = env->GetFieldID(m_javaScriptObjectClass, "pointer", "J");

  m_jsonField = env->GetFieldID(m_jsonObjectClass, "json", "Ljava/lang/String;");

  m_DebuggerSocket.client_sock = -1;

  // Stash the JVM object in the context, so we can find our way back from a Duktape C callback.
  duk_push_global_stash(m_context);
  duk_push_pointer(m_context, javaVM);
  duk_put_prop_string(m_context, -2, JAVA_VM_PROP_NAME);
  duk_push_pointer(m_context, this);
  duk_put_prop_string(m_context, -2, DUKTAPE_CONTEXT_PROP_NAME);
  duk_pop(m_context);

  // bind the traps
  duk_push_global_stash(m_context);

  std::string proxyScript =
    "(function(__duktape_has, __duktape_get, __duktape_set, __duktape_apply) {\n"
    "var __proxyHandler = {\n"
    "\thas: function(f, prop) { return __duktape_has(f.target, prop); },\n"
    "\tget: function(f, prop, receiver) { return __duktape_get(f.target, prop, receiver); },\n"
    "\tset: function(f, prop, value, receiver) { return __duktape_set(f.target, prop, value, receiver); },\n"
    "\tapply: function(f, thisArg, argumentsList) { return __duktape_apply(f.target, thisArg, argumentsList); },\n"
    "};\n"
    "return function(obj) {\n"
    "\tfunction f() {};\n"
    "\tf.target = obj;\n"
    "\treturn new Proxy(f, __proxyHandler);\n"
    "};\n"
    "});\n";
  duk_eval_string(m_context, proxyScript.c_str());

  // proxy traps
  duk_push_c_function(m_context, __duktape_has, 2);
  duk_push_c_function(m_context, __duktape_get, 3);
  duk_push_c_function(m_context, __duktape_set, 4);
  duk_push_c_function(m_context, __duktape_apply, 3);

  duk_pcall(m_context, 4);
  duk_put_prop_string(m_context, -2, "__makeProxy");

  duk_pop(m_context);
}

jlong DuktapeContext::getHeapSize(JNIEnv *env) {
  return m_heapSize;
}

jclass DuktapeContext::findClass(JNIEnv *env, const char *className) {
    return (jclass)env->NewGlobalRef(env->FindClass(className));
}

DuktapeContext::~DuktapeContext() {
  duk_trans_socket_finish(&m_DebuggerSocket);
  // Delete the proxies before destroying the heap.
  duk_destroy_heap(m_context);
}

jobject DuktapeContext::popObject(JNIEnv *env) const {
  duk_int_t dukType = duk_get_type(m_context, -1);
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

    // JavaScriptObject and JavaObject both contain an internal "this" which points to the Java
    // instance of that object. Try to extract that object.

    // Duktape Java Proxy can NOT be queried for JAVA_THIS_PROP_NAME since it is a special hidden
    // key (check the string prefix). So, query for JAVASCRIPT_THIS_PROP_NAME, which is
    // a "normal" key. The proxy trap will not be invoked otherwise. This key is not enumerated
    // due to the Java side proxy implementation.

    // However, Duktape JavaScript objects should use JAVA_THIS_PROP_NAME, because
    // JAVASCRIPT_THIS_PROP_NAME is publicly visible and this causes key iteration pollution issues.

    duk_bool_t hasThis = duk_has_prop_string(m_context, -1, JAVASCRIPT_THIS_PROP_NAME);
    if (hasThis) {
        duk_get_prop_string(m_context, -1, JAVASCRIPT_THIS_PROP_NAME);
    }
    else {
        // this code will not be trapped on Duktape Java Proxy due to the key name.
        hasThis = duk_has_prop_string(m_context, -1, JAVA_THIS_PROP_NAME);
        if (hasThis) {
            duk_get_prop_string(m_context, -1, JAVA_THIS_PROP_NAME);
        }
    }

    if (hasThis) {
      javaThis = reinterpret_cast<jobject>(duk_get_pointer(m_context, -1));
      // pop the pointer
      duk_pop(m_context);
      // Duktape JavaScript objects only hold weak references to their Java counterparts, which
      // may be invalid. Check this, and delete as necessary.
      if (javaThis && env->IsSameObject(javaThis, nullptr)) {
        env->DeleteWeakGlobalRef(javaThis);
        javaThis = nullptr;
        duk_del_prop_string(m_context, -1, JAVA_THIS_PROP_NAME);
      }
    }

    if (javaThis != nullptr) {
      // found an existing Java proxy tucked away in this object. Must create a
      // local ref before popping, because the pop finalizer may destroy the global (weak) ref.
      javaThis = env->NewLocalRef(javaThis);
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
    javaThis = env->NewObject(m_javaScriptObjectClass, m_javaScriptObjectConstructor, m_javaDuktape, reinterpret_cast<jlong>(this), reinterpret_cast<jlong>(ptr));

    jweak weakRef = env->NewWeakGlobalRef(javaThis);
    // set a finalizer for the weak ref
    duk_push_c_function(m_context, javascriptObjectFinalizer, 1);
    duk_set_finalizer(m_context, -2);

    // attach the Java object's weak reference to the JavaScript object
    duk_push_pointer(m_context, weakRef);
    duk_put_prop_string(m_context, -2, JAVA_THIS_PROP_NAME);

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

  // pop the value
  jobject value = popObject(env);
  // pop the prop
  jobject prop = popObject(env);

  // get the java reference
  duk_get_prop_string(m_context, -1, JAVASCRIPT_THIS_PROP_NAME);
  jobject object = static_cast<jobject>(duk_require_pointer(m_context, -1));
  // pop the real target, real jobject, and the target
  duk_pop_2(m_context);

  if (object == nullptr) {
    fatalErrorHandler(object, "DuktapeObject is null");
    return DUK_RET_REFERENCE_ERROR;
  }

  jclass objectClass = env->GetObjectClass(object);
  jboolean assignable = env->IsAssignableFrom(objectClass, m_duktapeObjectClass);
  env->DeleteLocalRef(objectClass);
  if (!assignable) {
      fatalErrorHandler(object, "Object is not DuktapeObject");
      return DUK_RET_REFERENCE_ERROR;
  }

  jboolean ret = env->CallBooleanMethod(m_javaDuktape, m_duktapeSetMethod, object, prop, value);
  if (!checkRethrowDuktapeErrorException(env, m_context)) {
    return DUK_RET_ERROR;
  }

  // push the boolean result to indicate whether the set was successful.
  duk_push_boolean(m_context, ret);
  return 1;
}

static duk_ret_t __duktape_set(duk_context *ctx) {
    DuktapeContext *duktapeContext = getDuktapeContext(ctx);
    {
        const ContextSwitcher _(duktapeContext, ctx);
        duk_ret_t ret = duktapeContext->duktapeSet();
        if (ret != DUK_RET_ERROR) {
            return ret;
        }
    }
    duk_throw(ctx);
}

duk_ret_t DuktapeContext::duktapeGet() {
  JNIEnv *env = getJNIEnv(m_context);

  // pop the receiver, useless
  duk_pop(m_context);

  jobject jprop;
  jobject object;
  {
    std::string prop;
    if (duk_get_type(m_context, -1) == DUK_TYPE_STRING) {
      // get the property name
      const char* cprop = duk_get_string(m_context, -1);
      prop = cprop;
      // not a valid utf string. duktape internal.
      if (cprop[0] == '\x81') {
          duk_pop_2(m_context);
          duk_push_undefined(m_context);
          return 1;
      }
      jprop = env->NewStringUTF(cprop);
      // pop the property
      duk_pop(m_context);
    }
    else {
      // pop the property
      jprop = popObject(env);
    }

    // get the java reference
    duk_get_prop_string(m_context, -1, JAVASCRIPT_THIS_PROP_NAME);
    object = static_cast<jobject>(duk_require_pointer(m_context, -1));
    // pop the real target, real jobject, and the target
    duk_pop_2(m_context);

    if (object == nullptr) {
      fatalErrorHandler(object, "DuktapeObject is null");
      return DUK_RET_REFERENCE_ERROR;
    }

    if (prop == JAVASCRIPT_THIS_PROP_NAME) {
      // short circuit the pointer retrieval from popObject here.
      duk_push_pointer(m_context, object);
      return 1;
    }
  }

  jclass objectClass = env->GetObjectClass(object);
  jboolean assignable = env->IsAssignableFrom(objectClass, m_duktapeObjectClass);
  env->DeleteLocalRef(objectClass);
  if (!assignable) {
    fatalErrorHandler(object, "Object is not DuktapeObject");
    return DUK_RET_REFERENCE_ERROR;
  }

//  jobject push = env->CallObjectMethod(object, m_duktapeObjectGetMethod, jprop);
  jobject push = env->CallObjectMethod(m_javaDuktape, m_duktapeGetMethod, object, jprop);
  env->DeleteLocalRef(jprop);
  if (!checkRethrowDuktapeErrorException(env, m_context)) {
    return DUK_RET_ERROR;
  }

  pushObject(env, push);

  return 1;
}

static duk_ret_t __duktape_get(duk_context *ctx) {
  DuktapeContext *duktapeContext = getDuktapeContext(ctx);
    {
        const ContextSwitcher _(duktapeContext, ctx);
        duk_ret_t ret = duktapeContext->duktapeGet();
        if (ret != DUK_RET_ERROR) {
            return ret;
        }
    }
    duk_throw(ctx);
}

duk_ret_t DuktapeContext::duktapeHas() {
    JNIEnv *env = getJNIEnv(m_context);

    std::string prop;
    if (duk_get_type(m_context, -1) == DUK_TYPE_STRING) {
        // get the property name
        const char* cprop = duk_get_string(m_context, -1);
        prop = cprop;
        if (cprop[0] == '\x81') {
            // pop target and prop
            duk_pop_2(m_context);
            // not a valid utf string. duktape internal.
            duk_push_boolean(m_context, (duk_bool_t )false);
            return 1;
        }
    }

    if (prop == JAVASCRIPT_THIS_PROP_NAME) {
        // short circuit the pointer retrieval from popObject here.
        duk_pop_2(m_context);
        duk_push_boolean(m_context, (duk_bool_t )true);
        return 1;
    }

    // get the java reference
    jobject jprop = popObject(env);
    duk_get_prop_string(m_context, -1, JAVASCRIPT_THIS_PROP_NAME);
    jobject object = static_cast<jobject>(duk_require_pointer(m_context, -1));
    // pop the real target, real jobject, and the target
    duk_pop_2(m_context);


    jclass objectClass = env->GetObjectClass(object);
    jboolean assignable = env->IsAssignableFrom(objectClass, m_duktapeObjectClass);
    env->DeleteLocalRef(objectClass);
    if (!assignable) {
      fatalErrorHandler(object, "Object is not DuktapeObject");
      return DUK_RET_REFERENCE_ERROR;
    }

    jboolean has = env->CallBooleanMethod(m_javaDuktape, m_duktapeHasMethod, object, jprop);
    if (!checkRethrowDuktapeErrorException(env, m_context)) {
        return DUK_RET_ERROR;
    }

    duk_push_boolean(m_context, (duk_bool_t )has);

    return 1;
}

static duk_ret_t __duktape_has(duk_context *ctx) {
  DuktapeContext *duktapeContext = getDuktapeContext(ctx);
    {
        const ContextSwitcher _(duktapeContext, ctx);
        duk_ret_t ret = duktapeContext->duktapeHas();
        if (ret != DUK_RET_ERROR) {
            return ret;
        }
    }
    duk_throw(ctx);
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
  duk_get_prop_string(m_context, -1, JAVASCRIPT_THIS_PROP_NAME);
  jobject object = static_cast<jobject>(duk_require_pointer(m_context, -1));
  duk_pop_2(m_context);

  jclass objectClass = env->GetObjectClass(object);
  jboolean assignable = env->IsAssignableFrom(objectClass, m_duktapeObjectClass);
  env->DeleteLocalRef(objectClass);
  if (!assignable) {
    env->DeleteLocalRef(javaArgs);
    fatalErrorHandler(object, "Object is not DuktapeObject");
    return DUK_RET_REFERENCE_ERROR;
  }

  jobject push = env->CallObjectMethod(m_javaDuktape, m_duktapeCallMethodMethod, object, javaThis, javaArgs);
  env->DeleteLocalRef(javaArgs);
  if (!checkRethrowDuktapeErrorException(env, m_context)) {
    return DUK_RET_ERROR;
  }

  pushObject(env, push);
  return 1;
}

static duk_ret_t __duktape_apply(duk_context *ctx) {
  DuktapeContext *duktapeContext = getDuktapeContext(ctx);
    {
        const ContextSwitcher _(duktapeContext, ctx);
        duk_ret_t ret = duktapeContext->duktapeApply();
        if (ret != DUK_RET_ERROR) {
            return ret;
        }
    }
    duk_throw(ctx);
}

void DuktapeContext::pushObject(JNIEnv *env, jlong object) {
    duk_push_heapptr(m_context, reinterpret_cast<void*>(object));
}

void DuktapeContext::pushObject(JNIEnv *env, jobject object, bool deleteLocalRef) {
  if (object == nullptr) {
    duk_push_null(m_context);
    return;
  }

  jclass objectClass = env->GetObjectClass(object);

  // try to push a native object first.
  {
    const JavaType* type = m_javaValues.get(env, objectClass);
    if (type != nullptr) {
      jvalue value;
      value.l = object;
      type->push(m_context, env, value);
      // safe to delete the local refs now
      if (deleteLocalRef)
          env->DeleteLocalRef(object);
      env->DeleteLocalRef(objectClass);
      return;
    }
  }

  // a JavaScriptObject can be unpacked back into a native duktape heap pointer/object
  // a DuktapeObject can support a duktape Proxy, and does not need any further boxing
  if (env->IsAssignableFrom(objectClass, m_javaScriptObjectClass)) {
    DuktapeContext* context = reinterpret_cast<DuktapeContext*>(env->GetLongField(object, m_contextField));
    if (context == this) {
      void* ptr = reinterpret_cast<void*>(env->GetLongField(object, m_pointerField));
      duk_push_heapptr(m_context, ptr);

      if (deleteLocalRef)
        env->DeleteLocalRef(object);
      env->DeleteLocalRef(objectClass);
      return;
    }

    // a proxy already exists, but not for the correct DuktapeContext, so native javascript heap
    // pointer can't be used.
  }
  else if (env->IsAssignableFrom(objectClass, m_byteBufferClass)) {
    jlong capacity = env->GetDirectBufferCapacity(object);
    void *p = duk_push_fixed_buffer(m_context, (duk_size_t)capacity);
    memcpy(p, env->GetDirectBufferAddress(object), (size_t)capacity);

    if (deleteLocalRef)
      env->DeleteLocalRef(object);
    env->DeleteLocalRef(objectClass);
    return;
  }
  else if (env->IsAssignableFrom(objectClass, m_jsonObjectClass)) {
    jstring json = (jstring)env->GetObjectField(object, m_jsonField);
    JString jString(env, json);
    duk_push_string(m_context, jString);
    // if this is passed bad json, the process crashes. so do not pass bad json.
    // this is a fast path, so sanity checking is disabled. cleaning up a busted
    // stack due to an incomplete method call is gnarly as well.
    duk_json_decode(m_context, -1);
    if (deleteLocalRef)
        env->DeleteLocalRef(object);
    return;
  }
  else if (!env->IsAssignableFrom(objectClass, m_duktapeObjectClass)) {
    // this is a normal Java object, so create a proxy for it to access fields and methods
    jobject wrappedObject = env->NewObject(m_javaObjectClass, m_javaObjectConstructor, m_javaDuktape, object);
    // safe to delete the local ref now
    if (deleteLocalRef)
      env->DeleteLocalRef(object);
    deleteLocalRef = true;
    object = wrappedObject;
  }

  env->DeleteLocalRef(objectClass);

  // at this point, the object is guaranteed to be a JavaScriptObject from another DuktapeContext
  // or a DuktapeObject (java proxy of some sort). JavaScriptObject implements DuktapeObject,
  // so, it works without any further coercion.

  duk_push_global_stash(m_context);
  duk_get_prop_string(m_context, -1, "__makeProxy");
  duk_swap(m_context, -2, -1);
  duk_pop(m_context);

  const duk_idx_t objIndex = duk_require_normalize_index(m_context, duk_push_object(m_context));

  jobject ptr = env->NewGlobalRef(object);
  duk_push_pointer(m_context, ptr);
  // safe to delete the local ref now
  if (deleteLocalRef)
      env->DeleteLocalRef(object);
  duk_put_prop_string(m_context, objIndex, JAVASCRIPT_THIS_PROP_NAME);

  // set a finalizer for the ref
  duk_push_c_function(m_context, javaObjectFinalizer, 1);
  duk_set_finalizer(m_context, objIndex);

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
  pushObject(env, thiz, false);

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
  pushObject(env, property, false);

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
  pushObject(env, property, false);
  pushObject(env, value, false);
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
  pushObject(env, key, false);
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

void DuktapeContext::waitForDebugger(JNIEnv *env, jstring connectionString) {
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
  duk_gc(m_context, 0);
}

jboolean DuktapeContext::isDebugging() {
    return (jboolean)(m_DebuggerSocket.client_sock > 0 ? JNI_TRUE : JNI_FALSE);
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

jboolean DuktapeContext::setKeyString(JNIEnv *env, jlong object, jstring key, jobject value) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  pushObject(env, value, false);
  const JString instanceKey(env, key);
  duk_bool_t ret = duk_put_prop_string(m_context, -2, instanceKey);

  // pop indexed object
  duk_pop(m_context);

  return (jboolean)(ret == 1);
}

jboolean DuktapeContext::setKeyInteger(JNIEnv *env, jlong object, jint index, jobject value) {
  CHECK_STACK(m_context);

  pushObject(env, object);
  pushObject(env, value, false);
  duk_bool_t ret = duk_put_prop_index(m_context, -2, index);

  // pop indexed object
  duk_pop(m_context);

  return (jboolean)(ret == 1);
}

jboolean DuktapeContext::setKeyObject(JNIEnv *env, jlong object, jobject key, jobject value) {
  CHECK_STACK(m_context);

  // tbh this doesn't work if object is an actual java object vs a javascript object.
  // probably should throw.

  pushObject(env, object);
  pushObject(env, key, false);
  pushObject(env, value, false);
  duk_bool_t ret = duk_put_prop(m_context, -3);

  // pop indexed object
  duk_pop(m_context);

  return (jboolean)(ret == 1);
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


void queueIllegalArgumentException(JNIEnv* env, const std::string& message) {
  const jclass illegalArgumentException = env->FindClass("java/lang/IllegalArgumentException");
  env->ThrowNew(illegalArgumentException, message.c_str());
}

void queueDuktapeException(JNIEnv* env, const std::string& message) {
  const jclass exceptionClass = env->FindClass("com/squareup/duktape/DuktapeException");
  env->ThrowNew(exceptionClass, message.c_str());
}

void queueNullPointerException(JNIEnv* env, const std::string& message) {
  jclass exceptionClass = env->FindClass("java/lang/NullPointerException");
  env->ThrowNew(exceptionClass, message.c_str());
}

bool checkRethrowDuktapeErrorInternal(JNIEnv* env, duk_context* ctx) {
  if (!env->ExceptionCheck()) {
    return true;
  }

  // The Java call threw an exception - it should be propagated back through JavaScript.
  // first push the object and grab the message if applicable
  jthrowable e = env->ExceptionOccurred();
  env->ExceptionClear();
  DuktapeContext* duktapeContext = getDuktapeContext(ctx);
  duktapeContext->pushObject(env, e, false);
  jclass clazz = env->GetObjectClass(e);
  jmethodID getMessage = env->GetMethodID(clazz, "toString", "()Ljava/lang/String;");
  auto jmessage = (jstring)env->CallObjectMethod(e, getMessage);
  std::string msg;
  if (jmessage == nullptr) {
    msg = "Java Exception";
  }
  else {
    msg = std::string("Java Exception ") + JString(env, jmessage).str();
  }

  // create a duktape error and set the exception on a property of that error
  duk_push_error_object(ctx, DUK_ERR_EVAL_ERROR, msg.c_str());
  duk_swap_top(ctx, -2);
  duk_put_prop_string(ctx, -2, JAVA_EXCEPTION_PROP_NAME);

  jclass exceptionClass = env->FindClass("com/squareup/duktape/DuktapeException");
  duk_get_prop_string(ctx, -1, "stack");
  std::string stack = duk_safe_to_string(ctx, -1);
  duk_pop(ctx);

  // add the Duktape JavaScript stack to this exception.
  const jmethodID addJavaStack =
          env->GetStaticMethodID(exceptionClass,
                                 "addJavaStack",
                                 "(Ljava/lang/String;Ljava/lang/Throwable;)Ljava/lang/String;");
  jobject newStack = env->CallStaticObjectMethod(exceptionClass, addJavaStack, env->NewStringUTF(stack.c_str()), e);
  duktapeContext->pushObject(env, newStack);
  duk_put_prop_string(ctx, -2, "stack");

  return false;
}

bool checkRethrowDuktapeError(JNIEnv* env, duk_context* ctx) {
    if (!checkRethrowDuktapeErrorInternal(env, ctx)) {
        duk_throw(ctx);
        return false;
    }
    return true;
}

bool checkRethrowDuktapeErrorException(JNIEnv* env, duk_context* ctx) {
    return checkRethrowDuktapeErrorInternal(env, ctx);
}

void queueJavaExceptionForDuktapeError(JNIEnv *env, duk_context *ctx) {
  jclass exceptionClass = env->FindClass("com/squareup/duktape/DuktapeException");

  // If it's a Duktape error object, try to pull out the full stacktrace.
  if (duk_is_error(ctx, -1) && duk_has_prop_string(ctx, -1, "stack")) {
    duk_get_prop_string(ctx, -1, "stack");
    const char* stack = duk_safe_to_string(ctx, -1);

    // Is there an exception thrown from a Java method?
    if (duk_has_prop_string(ctx, -2, JAVA_EXCEPTION_PROP_NAME)) {
      duk_get_prop_string(ctx, -2, JAVA_EXCEPTION_PROP_NAME);
      DuktapeContext* duktapeContext = getDuktapeContext(ctx);
      jobject wrappedEx = duktapeContext->popObject(env);
      jthrowable ex = (jthrowable)env->CallObjectMethod(wrappedEx, duktapeContext->m_javaObjectGetObject, nullptr);

      // add the Duktape JavaScript stack to this exception.
      const jmethodID addDuktapeStack =
              env->GetStaticMethodID(exceptionClass,
                                     "addDuktapeStack",
                                     "(Ljava/lang/Throwable;Ljava/lang/String;)V");
      env->CallStaticVoidMethod(exceptionClass, addDuktapeStack, ex, env->NewStringUTF(stack));

      // Rethrow the Java exception.
      env->Throw(ex);
    } else {
      env->ThrowNew(exceptionClass, stack);
    }
    // Pop the stack text.
    duk_pop(ctx);
  } else {
    // Not an error or no stacktrace, just convert to a string.
    env->ThrowNew(exceptionClass, duk_safe_to_string(ctx, -1));
  }

  duk_pop(ctx);
}
