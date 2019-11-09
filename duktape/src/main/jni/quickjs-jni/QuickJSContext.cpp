#include "QuickJSContext.h"

JNIEnv* getEnvFromJavaVM(JavaVM* javaVM) {
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

inline uint32_t hash(uint64_t v) {
    return ((v >> 32) & 0x00000000FFFFFFFF) ^ (v & 0x00000000FFFFFFFF);
}

static JSClassID customFinalizerClassId = 0;


static void javaWeakRefFinalizer(QuickJSContext *ctx, JSValue val, void *udata) {
    jobject weakRef = reinterpret_cast<jobject>(udata);
    if (nullptr == weakRef)
        return;
    JNIEnv *env = getEnvFromJavaVM(ctx->javaVM);
    env->DeleteWeakGlobalRef(weakRef);
}

static void javaRefFinalizer(QuickJSContext *ctx, JSValue val, void *udata) {
    jobject weakRef = reinterpret_cast<jobject>(udata);
    if (nullptr == weakRef)
        return;
    JNIEnv *env = getEnvFromJavaVM(ctx->javaVM);
    env->DeleteGlobalRef(weakRef);
}

static void customFinalizer(JSRuntime *rt, JSValue val) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(val, customFinalizerClassId));
    data->finalizer(data->ctx, val, data->udata);
    free(data);
}

static struct JSClassDef customFinalizerClassDef = {
    .class_name = "CustomFinalizer",
    .finalizer = customFinalizer,
};


QuickJSContext::QuickJSContext(JavaVM* javaVM, jobject javaDuktape):
    javaVM(javaVM) {
    // static run once mechanism
    JS_NewClassID(&customFinalizerClassId);

    runtime = JS_NewRuntime();
    ctx = JS_NewContext(runtime);
    stash = JS_NewObject(ctx);

    JNIEnv *env = getEnvFromJavaVM(javaVM);
    this->javaDuktape = env->NewWeakGlobalRef(javaDuktape);

    objectClass = findClass(env, "java/lang/Object");

    duktapeJavaObject = findClass(env, "com/squareup/duktape/DuktapeJavaObject");

    duktapeClass = findClass(env, "com/squareup/duktape/Duktape");
    duktapeObjectClass = findClass(env, "com/squareup/duktape/DuktapeObject");
    javaScriptObjectClass = findClass(env, "com/squareup/duktape/JavaScriptObject");
    javaObjectClass = findClass(env, "com/squareup/duktape/JavaObject");
    jsonObjectClass = findClass(env, "com/squareup/duktape/DuktapeJsonObject");
    byteBufferClass = findClass(env, "java/nio/ByteBuffer");

    duktapeHasMethod = env->GetMethodID(duktapeClass, "duktapeHas", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;)Z");
    duktapeGetMethod = env->GetMethodID(duktapeClass, "duktapeGet", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;)Ljava/lang/Object;");
    duktapeSetMethod = env->GetMethodID(duktapeClass, "duktapeSet", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;Ljava/lang/Object;)Z");
    duktapeCallMethodMethod = env->GetMethodID(duktapeClass, "duktapeCallMethod", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

    javaScriptObjectConstructor = env->GetMethodID(javaScriptObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;JJ)V");
    javaObjectConstructor = env->GetMethodID(javaObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;Ljava/lang/Object;)V");
    javaObjectGetObject = env->GetMethodID(duktapeJavaObject, "getObject", "(Ljava/lang/Class;)Ljava/lang/Object;");
    byteBufferAllocateDirect = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");

    // contextField = env->GetFieldID(javaScriptObjectClass, "context", "J");
    pointerField = env->GetFieldID(javaScriptObjectClass, "pointer", "J");

    jsonField = env->GetFieldID(jsonObjectClass, "json", "Ljava/lang/String;");

    javaThisAtom = privateAtom("javaThis");

    customFinalizerAtom = privateAtom("customFinalizer");
    JS_NewClass(runtime, customFinalizerClassId, &customFinalizerClassDef);
}

QuickJSContext::~QuickJSContext() {
    JS_FreeValue(ctx, stash);
    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);
}

JSAtom QuickJSContext::privateAtom(const char *str) {
    return JS_NewAtomLenPrivate(ctx, str, strlen(str));
}

void QuickJSContext::setFinalizer(JavaVM *vm, JSValue value, CustomFinalizer finalizer, void *udata) {
    JSValue finalizerObject = JS_NewObjectClass(ctx, customFinalizerClassId);
    struct CustomFinalizerData *data = new CustomFinalizerData();
    *data = {
        this,
        finalizer,
        udata,
    };
    JS_SetOpaque(finalizerObject, data);
    JS_SetProperty(ctx, value, customFinalizerAtom, finalizerObject);
}


jclass QuickJSContext::findClass(JNIEnv *env, const char *className) {
    return (jclass)env->NewGlobalRef(env->FindClass(className));
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

// value will be cleaned up by caller.
jobject QuickJSContext::toObject(JNIEnv *env, JSValue value) {
    if (JS_IsUndefined(value) || JS_IsNull(value))
        return nullptr;

    jvalue ret;
    if (JS_IsInteger(value)) {
        JS_ToInt32(ctx, &ret.i, value);
        return ret.l;
    }
    else if (JS_IsNumber(value)) {
        JS_ToFloat64(ctx, &ret.d, value);
        return ret.l;
    }
    else if (JS_IsBool(value)) {
        ret.z = JS_ToBool(ctx, value);
        return ret.l;
    }
    else if (JS_IsString(value)) {
        return toString(env, value);
    }
    else if (JS_IsArrayBuffer(value)) {
        size_t size;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &size, value);
        jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, byteBufferAllocateDirect, (jint)size);
        memcpy(env->GetDirectBufferAddress(byteBuffer), ptr, size);
        return byteBuffer;
    }
    else if (!JS_IsObject(value)) {
        assert(false);
        return nullptr;
    }

    // attempt to find an existing JavaScriptObject that exists on the java side
    JSValue found = JS_GetProperty(ctx, value, javaThisAtom);
    if (!JS_IsUndefined(found)) {
        int64_t javaPtr;
        JS_ToInt64(ctx, &javaPtr, found);
        jobject javaThis = reinterpret_cast<jobject>(javaPtr);
        if (javaThis) {
            // found something, but make sure the weak ref is still alive.
            // if so, return a global ref, because the global ref could be freed
            // in the middle of being used.
            if (!env->IsSameObject(javaThis, nullptr))
                return env->NewLocalRef(javaThis);
            // it was collected, so clean it up
            env->DeleteWeakGlobalRef(javaThis);
            JS_DeleteProperty(ctx, value, javaThisAtom, 0);
        }
    }

    // if (true) return env->NewStringUTF("WTF WHY");

    // no luck, so create a JavaScriptObject
    void* ptr = JS_VALUE_GET_PTR(value);
    jobject javaThis = env->NewObject(javaScriptObjectClass, javaScriptObjectConstructor, javaDuktape,
        reinterpret_cast<jlong>(this), reinterpret_cast<jlong>(ptr));

    // stash this to hold a reference, and to free automatically on runtime shutdown.
    int64_t id = reinterpret_cast<int64_t>(ptr);
    value = JS_DupValue(ctx, value);
    JS_SetPropertyInt64(ctx, stash, id, value);

    setFinalizer(javaVM, value, javaWeakRefFinalizer, env->NewWeakGlobalRef(javaThis));

    return javaThis;
}

jobject QuickJSContext::evaluate(JNIEnv *env, jstring code, jstring filename) {
    auto codeStr = env->GetStringUTFChars(code, 0);
    auto result = JS_Eval(ctx, codeStr, strlen(codeStr), env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL);
    auto ret = toObject(env, result);
    JS_FreeValue(ctx, result);
    return ret;
}
