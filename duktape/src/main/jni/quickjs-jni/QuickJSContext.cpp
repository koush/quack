#include "QuickJSContext.h"
#include <string>

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

inline static JSValue toValue(jlong object) {
    return JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(object));
}

inline static jvalue toValue(jobject object) {
    jvalue ret;
    ret.l = object;
    return ret;
}

inline uint32_t hash(uint64_t v) {
    return ((v >> 32) & 0x00000000FFFFFFFF) ^ (v & 0x00000000FFFFFFFF);
}

static JSClassID customFinalizerClassId = 0;
static JSClassID duktapeObjectProxyClassId = 0;

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
    // prevent double finalization from when the java side dies
    if (data->finalized)
        return;
    data->finalized = true;
    data->finalizer(data->ctx, val, data->udata);
    free(data);
}

static void duktapeObjectFinalizer(JSRuntime *rt, JSValue val) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(val, duktapeObjectProxyClassId));
    if (data->finalized)
        return;
    data->finalized = true;
    data->finalizer(data->ctx, val, data->udata);
    free(data);
}

static struct JSClassDef customFinalizerClassDef = {
    .class_name = "CustomFinalizer",
    .finalizer = customFinalizer,
};

int quickjs_has(JSContext *ctx, JSValueConst obj, JSAtom atom) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, duktapeObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_has(object, atom);
}
JSValue quickjs_get(JSContext *ctx, JSValueConst obj, JSAtom atom, JSValueConst receiver) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, duktapeObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_get(object, atom, receiver);
}
/* return < 0 if exception or TRUE/FALSE */
int quickjs_set(JSContext *ctx, JSValueConst obj, JSAtom atom, JSValueConst value, JSValueConst receiver, int flags) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, duktapeObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_set(object, atom, value, receiver, flags);
}
JSValue quickjs_apply(JSContext *ctx, JSValueConst func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(func_obj, duktapeObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_apply(object, this_val, argc, argv);
}

struct JSClassExoticMethods duktapeObjectProxyMethods = {
    .has_property = quickjs_has,
    .get_property = quickjs_get,
    .set_property = quickjs_set,
};

static struct JSClassDef duktapeObjectProxyClassDef = {
    .class_name = "DuktapeObjectProxy",
    .finalizer = duktapeObjectFinalizer,
    .call = quickjs_apply,
    .exotic = &duktapeObjectProxyMethods,
};

QuickJSContext::QuickJSContext(JavaVM* javaVM, jobject javaDuktape):
    javaVM(javaVM) {
    runtime = JS_NewRuntime();
    ctx = JS_NewContext(runtime);
    stash = JS_NewObject(ctx);

    JS_SetContextOpaque(ctx, this);

    atomHoldsJavaScriptObject = privateAtom("javascriptObject");
    atomHoldsJavaObject = privateAtom("javaObject");
    customFinalizerAtom = privateAtom("customFinalizer");
    // JS_NewClassID is static run once mechanism
    JS_NewClassID(&customFinalizerClassId);
    JS_NewClassID(&duktapeObjectProxyClassId);
    JS_NewClass(runtime, customFinalizerClassId, &customFinalizerClassDef);
    JS_NewClass(runtime, duktapeObjectProxyClassId, &duktapeObjectProxyClassDef);

    JNIEnv *env = getEnvFromJavaVM(javaVM);
    this->javaDuktape = env->NewWeakGlobalRef(javaDuktape);

    // primitives
    objectClass = findClass(env, "java/lang/Object");
    booleanClass = findClass(env, "java/lang/Boolean");
    booleanValueOf = env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    intClass = findClass(env, "java/lang/Integer");
    intValueOf = env->GetStaticMethodID(intClass, "valueOf", "(I)Ljava/lang/Integer;");
    doubleClass = findClass(env, "java/lang/Double");
    doubleValueOf = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
    stringClass = findClass(env, "java/lang/String");

    // ByteBuffer
    byteBufferClass = findClass(env, "java/nio/ByteBuffer");
    byteBufferAllocateDirect = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");

    // Duktape
    duktapeClass = findClass(env, "com/squareup/duktape/Duktape");
    duktapeHasMethod = env->GetMethodID(duktapeClass, "duktapeHas", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;)Z");
    duktapeGetMethod = env->GetMethodID(duktapeClass, "duktapeGet", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;)Ljava/lang/Object;");
    duktapeSetMethod = env->GetMethodID(duktapeClass, "duktapeSet", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;Ljava/lang/Object;)Z");
    duktapeCallMethodMethod = env->GetMethodID(duktapeClass, "duktapeCallMethod", "(Lcom/squareup/duktape/DuktapeObject;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    duktapeObjectClass = findClass(env, "com/squareup/duktape/DuktapeObject");

    // DuktapeJsonObject
    duktapejsonObjectClass = findClass(env, "com/squareup/duktape/DuktapeJsonObject");
    duktapeJsonField = env->GetFieldID(duktapejsonObjectClass, "json", "Ljava/lang/String;");

    // JavaScriptObject
    javaScriptObjectClass = findClass(env, "com/squareup/duktape/JavaScriptObject");
    javaScriptObjectConstructor = env->GetMethodID(javaScriptObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;JJ)V");
    contextField = env->GetFieldID(javaScriptObjectClass, "context", "J");
    pointerField = env->GetFieldID(javaScriptObjectClass, "pointer", "J");

    // JavaObject
    javaObjectClass = findClass(env, "com/squareup/duktape/JavaObject");
    javaObjectConstructor = env->GetMethodID(javaObjectClass, "<init>", "(Lcom/squareup/duktape/Duktape;Ljava/lang/Object;)V");

    // DuktapeJavaObject
    duktapeJavaObject = findClass(env, "com/squareup/duktape/DuktapeJavaObject");
    duktapeJavaObjectGetObject = env->GetMethodID(duktapeJavaObject, "getObject", "(Ljava/lang/Class;)Ljava/lang/Object;");
}

QuickJSContext::~QuickJSContext() {
    JS_FreeValue(ctx, stash);
    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);
}

JSAtom QuickJSContext::privateAtom(const char *str) {
    return JS_NewAtomLenPrivate(ctx, str, strlen(str));
}

// called when the JavaScriptObject on the Java side gets collected.
// the object may continue living in the QuickJS side, but clean up all references
// to the java side.
void QuickJSContext::finalizeJavaScriptObject(JNIEnv *env, jlong object) {
    JSValue value = toValue(object);
    JSValue finalizer = JS_GetProperty(ctx, value, atomHoldsJavaScriptObject);
    if (JS_IsNull(finalizer) || JS_IsUndefined(finalizer))
        return;
    // manually trigger the finalizer, is not allowed to run twice
    customFinalizer(runtime, finalizer);
    // this triggers finalizer again maybe, but it no ops.
    JS_DeleteProperty(ctx, value, atomHoldsJavaScriptObject, 0);
    // delete the entry in the stash that was keeping this alive from the java side.
    auto prop = JS_NewAtomUInt32(ctx, hash((uint64_t)object));
    JS_DeleteProperty(ctx, stash, prop, 0);
    JS_FreeAtom(ctx, prop);
}

void QuickJSContext::setFinalizerOnFinalizerObject(JSValue finalizerObject, CustomFinalizer finalizer, void *udata) {
    struct CustomFinalizerData *data = new CustomFinalizerData();
    *data = {
        this,
        finalizer,
        udata,
        false,
    };
    JS_SetOpaque(finalizerObject, data);
}

void QuickJSContext::setFinalizer(JSValue value, CustomFinalizer finalizer, void *udata) {
    JSValue finalizerObject = JS_NewObjectClass(ctx, customFinalizerClassId);
    setFinalizerOnFinalizerObject(finalizerObject, finalizer, udata);
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

std::string QuickJSContext::toStdString(JSValue value) {
    const char *str = JS_ToCString(ctx, value);
    std::string ret = str;
    JS_FreeCString(ctx, str);
    return ret;
}


static std::string toStdString(JNIEnv *env, jstring value) {
    const char *str = env->GetStringUTFChars(value, 0);
    std::string ret = str;
    return ret;
}

JSValue QuickJSContext::toString(JNIEnv *env, jstring value) {
    return JS_NewString(ctx, env->GetStringUTFChars(value, 0));
}

jstring QuickJSContext::stringify(JNIEnv *env, jlong object) {
    return toString(env, js_debugger_json_stringify(ctx, toValue(object)));
}


JSValue QuickJSContext::toObject(JNIEnv *env, jobject value) {
    if (value == nullptr)
        return JS_NULL;

    auto clazz = env->GetObjectClass(value);
    const auto clazzHolder = LocalRefHolder(env, clazz);

    if (env->IsAssignableFrom(clazz, booleanClass))
        return JS_NewBool(ctx, toValue(value).z);
    else if (env->IsAssignableFrom(clazz, intClass))
        return JS_NewInt32(ctx, toValue(value).i);
    else if (env->IsAssignableFrom(clazz, doubleClass))
        return JS_NewFloat64(ctx, toValue(value).d);
    else if (env->IsAssignableFrom(clazz, stringClass))
        return toString(env, reinterpret_cast<jstring>(value));
    else if (env->IsAssignableFrom(clazz, byteBufferClass)) {
        jlong capacity = env->GetDirectBufferCapacity(value);
        return JS_NewArrayBufferCopy(ctx, reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(value)), (size_t)capacity);
    }
    else if (env->IsAssignableFrom(clazz, duktapejsonObjectClass)) {
        jstring json = (jstring)env->GetObjectField(value, duktapeJsonField);
        const char *jsonPtr = env->GetStringUTFChars(json, 0);
        return JS_ParseJSON(ctx, jsonPtr, env->GetStringUTFLength(json), "<DuktapeJsonObject>");
    }
    else if (env->IsAssignableFrom(clazz, javaScriptObjectClass)) {
        QuickJSContext *context = reinterpret_cast<QuickJSContext *>(env->GetLongField(value, contextField));
        // matching context, grab the native JSValue
        if (context == this)
            return toValue(env->GetLongField(value, pointerField));
        // a proxy already exists, but not for the correct DuktapeContext, so native javascript heap
        // pointer can't be used.
    }
    else if (!env->IsAssignableFrom(clazz, duktapeObjectClass)) {
        // a DuktapeObject can support a duktape Proxy, and does not need any further boxing
        // so, this must be a normal Java object, create a proxy for it to access fields and methods
        value = env->NewObject(javaObjectClass, javaObjectConstructor, javaDuktape, value);
    }

    // at this point, the object is guaranteed to be a JavaScriptObject from another DuktapeContext
    // or a DuktapeObject (java proxy of some sort). JavaScriptObject implements DuktapeObject,
    // so, it works without any further coercion.

    JSValue ret = JS_NewObjectClass(ctx, duktapeObjectProxyClassId);
    setFinalizerOnFinalizerObject(ret, javaRefFinalizer, env->NewGlobalRef(value));
    return ret;
}

static jobject box(JNIEnv *env, jclass boxedClass, jmethodID boxer, jvalue value) {
    return env->CallStaticObjectMethodA(boxedClass, boxer, &value);
}

// value will be cleaned up by caller.
jobject QuickJSContext::toObject(JNIEnv *env, JSValue value) {
    if (JS_IsUndefined(value) || JS_IsNull(value))
        return nullptr;

    jvalue ret;
    if (JS_IsInteger(value)) {
        JS_ToInt32(ctx, &ret.i, value);
        return box(env, intClass, intValueOf, ret);
    }
    else if (JS_IsNumber(value)) {
        JS_ToFloat64(ctx, &ret.d, value);
        return box(env, doubleClass, doubleValueOf, ret);
    }
    else if (JS_IsBool(value)) {
        ret.z = JS_ToBool(ctx, value);
        return box(env, booleanClass, booleanValueOf, ret);
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
    else if (JS_IsFunction(ctx, value)) {

    }
    else if (JS_IsException(value)) {
        const auto exception = JS_GetException(ctx);
        std::string val = toStdString(exception);
        return nullptr;
    }
    else if (!JS_IsObject(value)) {
        assert(false);
        return nullptr;
    }

    // attempt to find an existing JavaScriptObject that exists on the java side (weak ref)
    JSValue found = JS_GetProperty(ctx, value, atomHoldsJavaScriptObject);
    if (!JS_IsUndefined(found) && !JS_IsNull(found)) {
        int64_t javaPtr;
        JS_ToInt64(ctx, &javaPtr, found);
        jobject javaThis = reinterpret_cast<jobject>(javaPtr);
        if (javaThis) {
            // found something, but make sure the weak ref is still alive.
            // if so, return a new local ref, because the global weak ref could be by side effect gc.
            if (!env->IsSameObject(javaThis, nullptr))
                return env->NewLocalRef(javaThis);
            // it was collected, so clean it up
            env->DeleteWeakGlobalRef(javaThis);
            // remove the property to prevent double deletes.
            JS_DeleteProperty(ctx, value, atomHoldsJavaScriptObject, 0);
        }
    }
    else {
        // check if this is a JavaObject that just needs to be unboxed (global ref)
        found = JS_GetProperty(ctx, value, atomHoldsJavaObject);
        if (!JS_IsUndefined(found) && !JS_IsNull(found)) {
            int64_t javaPtr;
            JS_ToInt64(ctx, &javaPtr, found);
            return reinterpret_cast<jobject>(javaPtr);
        }
    }

    // no luck, so create a JavaScriptObject
    void* ptr = JS_VALUE_GET_PTR(value);
    jobject javaThis = env->NewObject(javaScriptObjectClass, javaScriptObjectConstructor, javaDuktape,
        reinterpret_cast<jlong>(this), reinterpret_cast<jlong>(ptr));

    // stash this to hold a reference, and to free automatically on runtime shutdown.
    auto id = hash(reinterpret_cast<uint64_t>(ptr));
    value = JS_DupValue(ctx, value);
    JS_SetPropertyUint32(ctx, stash, id, value);

    setFinalizer(value, javaWeakRefFinalizer, env->NewWeakGlobalRef(javaThis));

    return javaThis;
}

jobject QuickJSContext::evaluate(JNIEnv *env, jstring code, jstring filename) {
    auto codeStr = env->GetStringUTFChars(code, 0);
    auto result = hold(JS_Eval(ctx, codeStr, strlen(codeStr), env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL));
    auto ret = toObject(env, result);
    return ret;
}

jobject QuickJSContext::compile(JNIEnv* env, jstring code, jstring filename) {
    std::string wrapped = ::toStdString(env, code);
    wrapped = "(" + wrapped + ")";

    auto codeStr = wrapped.c_str();
    auto result = hold(JS_Eval(ctx, codeStr, wrapped.size(), env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL));
    auto ret = toObject(env, result);
    return ret;
}

void QuickJSContext::setGlobalProperty(JNIEnv *env, jobject property, jobject value) {
    const auto global = JS_GetGlobalObject(ctx);
    setKeyInternal(env, global, property, value);
}

jobject QuickJSContext::callInternal(JNIEnv *env, JSValue func, JSValue thiz, jobjectArray args) {
    jsize length = 0;
    JSValue *valueArgs = nullptr;
    if (args != nullptr) {
        length = env->GetArrayLength(args);
        if (length != 0) {
            valueArgs = new JSValue[length];
            for (int i = 0; i < length; i++) {
                jobject arg = env->GetObjectArrayElement(args, i);
                JSValue argValue = toObject(env, arg);
                // env->DeleteLocalRef(arg);
                valueArgs[i] = argValue;
            }
        }
    }

    // todo: exception checking

    auto ret = JS_Call(ctx, func, thiz, length, valueArgs);
    if (valueArgs != nullptr) {
        for (int i = 0; i < length; i++) {
            JS_FreeValue(ctx, valueArgs[i]);
        }
        delete valueArgs;
    }

    return toObject(env, ret);
}

jobject QuickJSContext::call(JNIEnv *env, jlong object, jobjectArray args) {
    auto global = hold(JS_GetGlobalObject(ctx));
    auto func = toValue(object);
    return callInternal(env, func, global, args);
}

jobject QuickJSContext::callProperty(JNIEnv *env, jlong object, jobject property, jobjectArray args) {
    auto thiz = toValue(object);
    auto propertyJSValue = hold(toObject(env, property));
    auto propertyAtom = JS_ValueToAtom(ctx, propertyJSValue);
    auto func = hold(JS_GetProperty(ctx, thiz, propertyAtom));
    JS_FreeAtom(ctx, propertyAtom);

    return callInternal(env, func, thiz, args);
}

jobject QuickJSContext::callMethod(JNIEnv *env, jlong method, jobject object, jobjectArray args) {
    auto thiz = hold(toObject(env, object));
    auto func = toValue(method);
    return callInternal(env, func, thiz, args);
}

jobject QuickJSContext::getKeyString(JNIEnv* env, jlong object, jstring key) {
    auto thiz = toValue(object);
    // todo: exception checking
    return toObject(env, JS_GetPropertyStr(ctx, thiz, env->GetStringUTFChars(key, 0)));
}

jobject QuickJSContext::getKeyInteger(JNIEnv* env, jlong object, jint index) {
    auto thiz = toValue(object);
    // todo: exception checking
    return toObject(env, JS_GetPropertyUint32(ctx, thiz, (uint32_t)index));
}

jobject QuickJSContext::getKeyObject(JNIEnv* env, jlong object, jobject key) {
    auto thiz = toValue(object);
    auto propertyJSValue = hold(toObject(env, key));
    auto propertyAtom = JS_ValueToAtom(ctx, propertyJSValue);
    // todo: exception checking
    auto ret = toObject(env, JS_GetProperty(ctx, thiz, propertyAtom));
    JS_FreeAtom(ctx, propertyAtom);
    return ret;
}

jboolean QuickJSContext::setKeyString(JNIEnv* env, jlong object, jstring key, jobject value) {
    auto thiz = toValue(object);
    auto set = hold(toObject(env, value));
    // -1 is exception. 0 and 1 for set.
    int ret = JS_SetPropertyStr(ctx, thiz, env->GetStringUTFChars(key, 0), JS_DupValue(ctx, set));
    return ret;
}

jboolean QuickJSContext::setKeyInteger(JNIEnv* env, jlong object, jint index, jobject value) {
    auto thiz = toValue(object);
    auto set = hold(toObject(env, value));
    // -1 is exception. 0 and 1 for set.
    int ret = JS_SetPropertyUint32(ctx, thiz, (uint32_t)index, JS_DupValue(ctx, set));
    return ret;
}

jboolean QuickJSContext::setKeyInternal(JNIEnv* env, JSValue thiz, jobject key, jobject value) {
    auto set = hold(toObject(env, value));

    auto propertyJSValue = hold(toObject(env, key));
    auto propertyAtom = JS_ValueToAtom(ctx, propertyJSValue);
    // -1 is exception. 0 and 1 for set.
    int ret = JS_SetProperty(ctx, thiz, propertyAtom, JS_DupValue(ctx, set));
    JS_FreeAtom(ctx, propertyAtom);
    return ret;
}

jboolean QuickJSContext::setKeyObject(JNIEnv* env, jlong object, jobject key, jobject value) {
    auto thiz = toValue(object);
    return setKeyInternal(env, thiz, key, value);
}

int QuickJSContext::quickjs_has(jobject object, JSAtom atom) {
    if (atom == atomHoldsJavaScriptObject)
        return false;
    if (atom == atomHoldsJavaObject)
        return true;

    const auto prop = hold(JS_AtomToValue(ctx, atom));
    JNIEnv *env = getEnvFromJavaVM(javaVM);
    const auto jprop = LocalRefHolder(env, toObject(env, prop));
    jboolean has = env->CallBooleanMethod(javaDuktape, duktapeHasMethod, object, (jobject)jprop);
    // todo: exception
    return has;
}
JSValue QuickJSContext::quickjs_get(jobject object, JSAtom atom, JSValueConst receiver) {
    if (atom == atomHoldsJavaScriptObject)
        return JS_UNDEFINED;
    JNIEnv *env = getEnvFromJavaVM(javaVM);

    if (atom == atomHoldsJavaObject)
        return JS_NewInt64(ctx, reinterpret_cast<int64_t>(env->NewLocalRef(object)));

    auto prop = hold(JS_AtomToValue(ctx, atom));
    const auto jprop = LocalRefHolder(env, toObject(env, prop));
    jobject result = env->CallObjectMethod(javaDuktape, duktapeGetMethod, object, (jobject)jprop);
    // todo: exception
    return toObject(env, result);
}
int QuickJSContext::quickjs_set(jobject object, JSAtom atom, JSValueConst value, JSValueConst receiver, int flags) {
    if (atom == atomHoldsJavaScriptObject)
        return false;
    if (atom == atomHoldsJavaObject)
        return false;

    auto prop = hold(JS_AtomToValue(ctx, atom));
    JNIEnv *env = getEnvFromJavaVM(javaVM);
    const auto jprop = LocalRefHolder(env, toObject(env, prop));
    jboolean ret = env->CallBooleanMethod(javaDuktape, duktapeSetMethod, object, (jobject)jprop, value);
    // todo: exceptions
    return ret;
}
JSValue QuickJSContext::quickjs_apply(jobject func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    JNIEnv *env = getEnvFromJavaVM(javaVM);

    // unpack the arguments
    jobjectArray javaArgs = env->NewObjectArray((jsize)argc, objectClass, nullptr);
    for (int i = 0; i < argc; i++) {
        env->SetObjectArrayElement(javaArgs, (jsize)i, toObject(env, argv[i]));
    }

    const auto thiz = LocalRefHolder(env, toObject(env, this_val));
    jobject result = env->CallObjectMethod(javaDuktape, duktapeCallMethodMethod, func_obj, (jobject)thiz, javaArgs);
    env->DeleteLocalRef(javaArgs);
    // todo: exceptions

    return toObject(env, result);
}
