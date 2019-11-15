#include "QuickJSContext.h"
#include <string>
#include <vector>

#define JS_IsUndefinedOrNull(value) (JS_IsUndefined(value) || JS_IsNull(value))

inline static JSValue toValueAsLocal(jlong object) {
    return JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(object));
}
inline static JSValue toValueAsDup(JSContext *ctx, jlong object) {
    return JS_DupValue(ctx, JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(object)));
}

inline uint32_t hash(uint64_t v) {
    return (uint32_t)(((v >> 32) & 0x00000000FFFFFFFF) ^ (v & 0x00000000FFFFFFFF));
}

static JSClassID customFinalizerClassId = 0;
static JSClassID quackObjectProxyClassId = 0;

static void javaWeakRefFinalizer(QuickJSContext *ctx, JSValue val, void *udata) {
    auto weakRef = reinterpret_cast<jobject>(udata);
    if (nullptr == weakRef)
        return;
    JNIEnv *env = getEnvFromJavaVM(ctx->javaVM);
    env->DeleteWeakGlobalRef(weakRef);
}

static void javaRefFinalizer(QuickJSContext *ctx, JSValue val, void *udata) {
    auto strongRef = reinterpret_cast<jobject>(udata);
    if (nullptr == strongRef)
        return;
    JNIEnv *env = getEnvFromJavaVM(ctx->javaVM);
    env->DeleteGlobalRef(strongRef);
}

static void customFinalizer(JSRuntime *rt, JSValue val) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(val, customFinalizerClassId));
    // prevent double finalization from when the java side dies
    data->finalizer(data->ctx, val, data->udata);
    free(data);
}

static void quackObjectFinalizer(JSRuntime *rt, JSValue val) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(val, quackObjectProxyClassId));
    if (data)
        data->finalizer(data->ctx, val, data->udata);
    free(data);
}

static struct JSClassDef customFinalizerClassDef = {
    .class_name = "CustomFinalizer",
    .finalizer = customFinalizer,
};

int quickjs_has(JSContext *ctx, JSValueConst obj, JSAtom atom) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, quackObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_has(object, atom);
}
JSValue quickjs_get(JSContext *ctx, JSValueConst obj, JSAtom atom, JSValueConst receiver) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, quackObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_get(object, atom, receiver);
}
/* return < 0 if exception or TRUE/FALSE */
int quickjs_set(JSContext *ctx, JSValueConst obj, JSAtom atom, JSValueConst value, JSValueConst receiver, int flags) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, quackObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_set(object, atom, value, receiver, flags);
}
JSValue quickjs_apply(JSContext *ctx, JSValueConst func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    CustomFinalizerData *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(func_obj, quackObjectProxyClassId));
    jobject object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_apply(object, this_val, argc, argv);
}
int quickjs_construct(JSContext *ctx, JSValue func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    QuickJSContext *qctx = reinterpret_cast<QuickJSContext *>(JS_GetContextOpaque(ctx));
    return qctx->quickjs_construct(func_obj, this_val, argc, argv);
}

struct JSClassExoticMethods quackObjectProxyMethods = {
    .has_property = quickjs_has,
    .get_property = quickjs_get,
    .set_property = quickjs_set,
    .construct = quickjs_construct,
};

static struct JSClassDef quackObjectProxyClassDef = {
    .class_name = "QuackObjectProxy",
    .finalizer = quackObjectFinalizer,
    .call = quickjs_apply,
    .exotic = &quackObjectProxyMethods,
};

QuickJSContext::QuickJSContext(JavaVM* javaVM, jobject javaQuack):
    javaVM(javaVM) {
    runtime = JS_NewRuntime();
    ctx = JS_NewContext(runtime);
    JS_SetMaxStackSize(ctx, 1024 * 1024 * 4);
    stash = JS_NewObject(ctx);

    auto global = hold(JS_GetGlobalObject(ctx));
    uint8ArrayConstructor = JS_GetPropertyStr(ctx, global, "Uint8Array");
    uint8ArrayPrototype = JS_GetPropertyStr(ctx, uint8ArrayConstructor, "prototype");

    const char *thrower_str = "(function() { try { throw new Error(); } catch (e) { return e; } })";
    thrower_function = JS_Eval(ctx, thrower_str, strlen(thrower_str), "<thrower>", JS_EVAL_TYPE_GLOBAL);

    JS_SetContextOpaque(ctx, this);

    atomHoldsJavaObject = privateAtom("javaObject");
    customFinalizerAtom = privateAtom("customFinalizer");
    javaExceptionAtom = privateAtom("javaException");
    // JS_NewClassID is static run once mechanism
    JS_NewClassID(&customFinalizerClassId);
    JS_NewClassID(&quackObjectProxyClassId);
    JS_NewClass(runtime, customFinalizerClassId, &customFinalizerClassDef);
    JS_NewClass(runtime, quackObjectProxyClassId, &quackObjectProxyClassDef);

    JNIEnv *env = getEnvFromJavaVM(javaVM);
    this->javaQuack = env->NewWeakGlobalRef(javaQuack);

    // primitives
    objectClass = findClass(env, "java/lang/Object");
    objectToString = env->GetMethodID(objectClass, "toString", "()Ljava/lang/String;");
    booleanClass = findClass(env, "java/lang/Boolean");
    booleanValueOf = env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    booleanValue = env->GetMethodID(booleanClass, "booleanValue", "()Z");
    intClass = findClass(env, "java/lang/Integer");
    intValueOf = env->GetStaticMethodID(intClass, "valueOf", "(I)Ljava/lang/Integer;");
    intValue = env->GetMethodID(intClass, "intValue", "()I");
    doubleClass = findClass(env, "java/lang/Double");
    doubleValueOf = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
    doubleValue = env->GetMethodID(doubleClass, "doubleValue", "()D");
    stringClass = findClass(env, "java/lang/String");

    // ByteBuffer
    byteBufferClass = findClass(env, "java/nio/ByteBuffer");
    byteBufferAllocateDirect = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");

    // Quack
    quackClass = findClass(env, "com/koushikdutta/quack/QuackContext");
    quackHasMethod = env->GetMethodID(quackClass, "quackHas", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;)Z");
    quackGetMethod = env->GetMethodID(quackClass, "quackGet", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;)Ljava/lang/Object;");
    quackSetMethod = env->GetMethodID(quackClass, "quackSet", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;Ljava/lang/Object;)Z");
    quackApply = env->GetMethodID(quackClass, "quackApply", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    quackConstruct = env->GetMethodID(quackClass, "quackConstruct", "(Lcom/koushikdutta/quack/QuackObject;[Ljava/lang/Object;)Ljava/lang/Object;");
    quackObjectClass = findClass(env, "com/koushikdutta/quack/QuackObject");

    // QuackJsonObject
    quackjsonObjectClass = findClass(env, "com/koushikdutta/quack/QuackJsonObject");
    quackJsonField = env->GetFieldID(quackjsonObjectClass, "json", "Ljava/lang/String;");

    // JavaScriptObject
    javaScriptObjectClass = findClass(env, "com/koushikdutta/quack/JavaScriptObject");
    javaScriptObjectConstructor = env->GetMethodID(javaScriptObjectClass, "<init>", "(Lcom/koushikdutta/quack/QuackContext;JJ)V");
    contextField = env->GetFieldID(javaScriptObjectClass, "context", "J");
    pointerField = env->GetFieldID(javaScriptObjectClass, "pointer", "J");

    // JavaObject
    javaObjectClass = findClass(env, "com/koushikdutta/quack/JavaObject");
    javaObjectConstructor = env->GetMethodID(javaObjectClass, "<init>", "(Lcom/koushikdutta/quack/QuackContext;Ljava/lang/Object;)V");

    // QuackJavaObject
    quackJavaObject = findClass(env, "com/koushikdutta/quack/QuackJavaObject");
    quackJavaObjectGetObject = env->GetMethodID(quackJavaObject, "getObject", "(Ljava/lang/Class;)Ljava/lang/Object;");

    // exceptions
    quackExceptionClass = findClass(env, "com/koushikdutta/quack/QuackException");
    addJSStack =env->GetStaticMethodID(quackExceptionClass, "addJSStack","(Ljava/lang/Throwable;Ljava/lang/String;)V");
    addJavaStack = env->GetStaticMethodID(quackExceptionClass, "addJavaStack", "(Ljava/lang/String;Ljava/lang/Throwable;)Ljava/lang/String;");
}

QuickJSContext::~QuickJSContext() {
    JS_FreeValue(ctx, uint8ArrayPrototype);
    JS_FreeValue(ctx, uint8ArrayConstructor);
    JS_FreeValue(ctx, stash);
    JS_FreeValue(ctx, thrower_function);
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
    // the JavaScriptObject is referenced in two spots:
    // on the private atom for the JSValue and also in the stash.
    // delete them both, and the finalizer will be triggered.
    JSValue value = toValueAsLocal(object);

    // JSValue prop that has the finalizer thats attached to the weak ref of the JavaScriptObject
    JS_DeleteProperty(ctx, value, customFinalizerAtom, 0);

    // delete the entry in the stash that was keeping this alive from the java side.
    auto id = hash((uint64_t)object);
    auto prop = JS_NewAtomUInt32(ctx, id);
    assert(JS_DeleteProperty(ctx, stash, prop, 0));
    JS_FreeAtom(ctx, prop);
}

void QuickJSContext::setFinalizerOnFinalizerObject(JSValue finalizerObject, CustomFinalizer finalizer, void *udata) {
    struct CustomFinalizerData *data = new CustomFinalizerData();
    *data = {
        this,
        finalizer,
        udata
    };
    assert(JS_IsObject(finalizerObject));
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
    return toString(env, js_debugger_json_stringify(ctx, toValueAsLocal(object)));
}


JSValue QuickJSContext::toObject(JNIEnv *env, jobject value) {
    if (value == nullptr)
        return JS_NULL;

    auto clazz = env->GetObjectClass(value);
    const auto clazzHolder = LocalRefHolder(env, clazz);

    if (env->IsAssignableFrom(clazz, booleanClass))
        return JS_NewBool(ctx, env->CallBooleanMethodA(value, booleanValue, nullptr));
    else if (env->IsAssignableFrom(clazz, intClass))
        return JS_NewInt32(ctx, env->CallIntMethod(value, intValue, nullptr));
    else if (env->IsAssignableFrom(clazz, doubleClass))
        return JS_NewFloat64(ctx, env->CallDoubleMethodA(value, doubleValue, nullptr));
    else if (env->IsAssignableFrom(clazz, stringClass))
        return toString(env, reinterpret_cast<jstring>(value));
    else if (env->IsAssignableFrom(clazz, byteBufferClass)) {
        jlong capacity = env->GetDirectBufferCapacity(value);
        auto buffer = hold(JS_NewArrayBufferCopy(ctx, reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(value)), (size_t)capacity));
        JSValue args[] = { (JSValue)buffer };
        return JS_CallConstructor(ctx, uint8ArrayConstructor, 1, args);
    }
    else if (env->IsAssignableFrom(clazz, quackjsonObjectClass)) {
        jstring json = (jstring)env->GetObjectField(value, quackJsonField);
        const char *jsonPtr = env->GetStringUTFChars(json, 0);
        return JS_ParseJSON(ctx, jsonPtr, (size_t)env->GetStringUTFLength(json), "<QuackJsonObject>");
    }
    else if (env->IsAssignableFrom(clazz, javaScriptObjectClass)) {
        QuickJSContext *context = reinterpret_cast<QuickJSContext *>(env->GetLongField(value, contextField));
        // matching context, grab the native JSValue
        if (context == this)
            return toValueAsDup(ctx, env->GetLongField(value, pointerField));
        // a proxy already exists, but not for the correct QuackContext, so native javascript heap
        // pointer can't be used.
    }
    else if (!env->IsAssignableFrom(clazz, quackObjectClass)) {
        // a QuackObject can support a quack Proxy, and does not need any further boxing
        // so, this must be a normal Java object, create a proxy for it to access fields and methods
        value = env->NewObject(javaObjectClass, javaObjectConstructor, javaQuack, value);
    }

    // at this point, the object is guaranteed to be a JavaScriptObject from another QuackContext
    // or a QuackObject (java proxy of some sort). JavaScriptObject implements QuackObject,
    // so, it works without any further coercion.

    JSValue ret = JS_NewObjectClass(ctx, quackObjectProxyClassId);
    setFinalizerOnFinalizerObject(ret, javaRefFinalizer, env->NewGlobalRef(value));
    return ret;
}

static jobject box(JNIEnv *env, jclass boxedClass, jmethodID boxer, jvalue value) {
    return env->CallStaticObjectMethodA(boxedClass, boxer, &value);
}

// value will be cleaned up by caller.
jobject QuickJSContext::toObject(JNIEnv *env, JSValue value) {
    if (JS_IsUndefinedOrNull(value))
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
        ret.z = (jboolean)(JS_ToBool(ctx, value) ? true : false);
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
    else if (JS_IsException(value)) {
        const auto exception = JS_GetException(ctx);
        std::string val = toStdString(exception);
        return nullptr;
    }
    else if (!JS_IsObject(value)) {
        // todo: symbol?
        return nullptr;
    }

    // this does not seem to be dup'd, so don't hold it.
    auto prototype = JS_GetPrototype(ctx, value);
    if (JS_VALUE_GET_PTR((JSValue)prototype) == JS_VALUE_GET_PTR(uint8ArrayPrototype)) {
        size_t offset;
        size_t size;
        size_t bpe;
        auto ab = hold(JS_GetTypedArrayBuffer(ctx, value, &offset, &size, &bpe));

        size_t ab_size;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &ab_size, ab);

        jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, byteBufferAllocateDirect, (jint)size);
        memcpy(env->GetDirectBufferAddress(byteBuffer), ptr + offset, size);
        return byteBuffer;
    }

    // attempt to find an existing JavaScriptObject that exists on the java side (weak ref)
    auto finalizerFound = hold(JS_GetProperty(ctx, value, customFinalizerAtom));
    if (!JS_IsUndefinedOrNull(finalizerFound)) {
        auto data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(finalizerFound, customFinalizerClassId));
        auto javaThis = reinterpret_cast<jobject>(data->udata);
        if (javaThis) {
            // found something, but make sure the weak ref is still alive.
            // if so, return a new local ref, because the global weak ref could be by side effect gc.
            if (!env->IsSameObject(javaThis, nullptr))
                return env->NewLocalRef(javaThis);
            // it was collected, so clean it up
            data->udata = nullptr;
            env->DeleteWeakGlobalRef(javaThis);
            // remove the property to prevent double deletes.
            JS_DeleteProperty(ctx, value, customFinalizerAtom, 0);
        }
    }
    else {
        // check if this is a JavaObject that just needs to be unboxed (global ref)
        auto javaValue = JS_GetProperty(ctx, value, atomHoldsJavaObject);
        if (!JS_IsUndefinedOrNull(javaValue)) {
            int64_t javaPtr;
            JS_ToInt64(ctx, &javaPtr, javaValue);
            return env->NewLocalRef(reinterpret_cast<jobject>(javaPtr));
        }
    }

    // no luck, so create a JavaScriptObject
    void* ptr = JS_VALUE_GET_PTR(value);
    jobject javaThis = env->NewObject(javaScriptObjectClass, javaScriptObjectConstructor, javaQuack,
        reinterpret_cast<jlong>(this), reinterpret_cast<jlong>(ptr));

    // stash this to hold a reference, and to free automatically on runtime shutdown.
    auto id = hash(reinterpret_cast<uint64_t>(ptr));
    value = JS_DupValue(ctx, value);
    auto prop = JS_NewAtomUInt32(ctx, id);
    JS_SetProperty(ctx, stash, prop, value);

    setFinalizer(value, javaWeakRefFinalizer, env->NewWeakGlobalRef(javaThis));

    return javaThis;
}

jobject QuickJSContext::toObjectCheckQuickJSError(JNIEnv *env, JSValue value) {
    if (!JS_IsException(value))
        return toObject(env, value);
    auto exception = hold(JS_GetException(ctx));
    rethrowQuickJSErrorToJava(env, exception);
    return nullptr;
}

jobject QuickJSContext::evaluate(JNIEnv *env, jstring code, jstring filename) {
    auto codeStr = env->GetStringUTFChars(code, 0);
    size_t len = strlen(codeStr);
    char* mem = (char*)js_malloc(ctx, len + 1);
    mem[len] = '\0';
    memcpy(mem, codeStr, len);
    auto result = hold(JS_Eval(ctx, mem, len, env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL));
    return toObjectCheckQuickJSError(env, result);
}

jobject QuickJSContext::compile(JNIEnv* env, jstring code, jstring filename) {
    std::string wrapped = ::toStdString(env, code);
    wrapped = "(" + wrapped + ")";

    auto codeStr = wrapped.c_str();
    auto result = hold(JS_Eval(ctx, codeStr, wrapped.size(), env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL));
    return toObjectCheckQuickJSError(env, result);
}

void QuickJSContext::setGlobalProperty(JNIEnv *env, jobject property, jobject value) {
    const auto global = hold(JS_GetGlobalObject(ctx));
    checkQuickJSErrorAndThrow(env, setKeyInternal(env, global, property, value));
}

jobject QuickJSContext::callInternal(JNIEnv *env, JSValue func, JSValue thiz, jobjectArray args) {
    jsize length = 0;
    std::vector<JSValue> valueArgs;
    if (args != nullptr) {
        length = env->GetArrayLength(args);
        if (length != 0) {
            for (int i = 0; i < length; i++) {
                const auto arg = LocalRefHolder(env, env->GetObjectArrayElement(args, i));
                JSValue argValue = toObject(env, arg);
                /// is it possible to fail during marshalling? would be catastrophic.
                if (JS_IsException(argValue))
                    assert(false);
                valueArgs.push_back(argValue);
            }
        }
    }

    auto ret = hold(JS_Call(ctx, func, thiz, length, &valueArgs.front()));
    for (JSValue & valueArg : valueArgs) {
        JS_FreeValue(ctx, valueArg);
    }

    return toObjectCheckQuickJSError(env, ret);
}

jobject QuickJSContext::call(JNIEnv *env, jlong object, jobjectArray args) {
    auto global = hold(JS_GetGlobalObject(ctx));
    auto func = toValueAsLocal(object);
    return callInternal(env, func, global, args);
}

jobject QuickJSContext::callProperty(JNIEnv *env, jlong object, jobject property, jobjectArray args) {
    auto thiz = toValueAsLocal(object);
    auto propertyJSValue = hold(toObject(env, property));
    auto propertyAtom = JS_ValueToAtom(ctx, propertyJSValue);
    auto func = hold(JS_GetProperty(ctx, thiz, propertyAtom));
    JS_FreeAtom(ctx, propertyAtom);

    return callInternal(env, func, thiz, args);
}

jobject QuickJSContext::callMethod(JNIEnv *env, jlong method, jobject object, jobjectArray args) {
    auto thiz = hold(toObject(env, object));
    auto func = toValueAsLocal(method);
    return callInternal(env, func, thiz, args);
}

jobject QuickJSContext::getKeyString(JNIEnv* env, jlong object, jstring key) {
    return toObjectCheckQuickJSError(env, hold(JS_GetPropertyStr(ctx, toValueAsLocal(object), env->GetStringUTFChars(key, 0))));
}

jobject QuickJSContext::getKeyInteger(JNIEnv* env, jlong object, jint index) {
    return toObjectCheckQuickJSError(env, hold(JS_GetPropertyUint32(ctx, toValueAsLocal(object), (uint32_t)index)));
}

jobject QuickJSContext::getKeyObject(JNIEnv* env, jlong object, jobject key) {
    auto thiz = toValueAsLocal(object);
    auto propertyJSValue = hold(toObject(env, key));
    auto propertyAtom = JS_ValueToAtom(ctx, propertyJSValue);
    auto ret = toObjectCheckQuickJSError(env, hold(JS_GetProperty(ctx, thiz, propertyAtom)));
    JS_FreeAtom(ctx, propertyAtom);
    return ret;
}

jboolean QuickJSContext::setKeyString(JNIEnv* env, jlong object, jstring key, jobject value) {
    auto thiz = toValueAsLocal(object);
    auto set = hold(toObject(env, value));
    return checkQuickJSErrorAndThrow(env, JS_SetPropertyStr(ctx, thiz, env->GetStringUTFChars(key, 0), JS_DupValue(ctx, set)));
}

jboolean QuickJSContext::setKeyInteger(JNIEnv* env, jlong object, jint index, jobject value) {
    auto thiz = toValueAsLocal(object);
    auto set = hold(toObject(env, value));
    return checkQuickJSErrorAndThrow(env, JS_SetPropertyUint32(ctx, thiz, (uint32_t)index, JS_DupValue(ctx, set)));
}

jboolean QuickJSContext::setKeyInternal(JNIEnv* env, JSValue thiz, jobject key, jobject value) {
    auto set = hold(toObject(env, value));
    auto propertyJSValue = hold(toObject(env, key));
    auto propertyAtom = JS_ValueToAtom(ctx, propertyJSValue);
    return checkQuickJSErrorAndThrow(env, JS_SetProperty(ctx, thiz, propertyAtom, JS_DupValue(ctx, set)));
}

jboolean QuickJSContext::setKeyObject(JNIEnv* env, jlong object, jobject key, jobject value) {
    return setKeyInternal(env, toValueAsLocal(object), key, value);
}

int QuickJSContext::quickjs_has(jobject object, JSAtom atom) {
    if (atom == customFinalizerAtom)
        return false;
    if (atom == atomHoldsJavaObject)
        return true;

    const auto prop = hold(JS_AtomToValue(ctx, atom));
    JNIEnv *env = getEnvFromJavaVM(javaVM);
    const auto jprop = LocalRefHolder(env, toObject(env, prop));
    jboolean has = env->CallBooleanMethod(javaQuack, quackHasMethod, object, (jobject)jprop);
    if (rethrowJavaExceptionToQuickJS(env))
        return -1;
    return has;
}
JSValue QuickJSContext::quickjs_get(jobject object, JSAtom atom, JSValueConst receiver) {
    if (atom == customFinalizerAtom)
        return JS_UNDEFINED;
    JNIEnv *env = getEnvFromJavaVM(javaVM);

    if (atom == atomHoldsJavaObject)
        return JS_NewInt64(ctx, reinterpret_cast<int64_t>(env->NewLocalRef(object)));

    auto prop = hold(JS_AtomToValue(ctx, atom));
    const auto jprop = LocalRefHolder(env, toObject(env, prop));
    jobject result = env->CallObjectMethod(javaQuack, quackGetMethod, object, (jobject)jprop);

    if (rethrowJavaExceptionToQuickJS(env))
        return JS_EXCEPTION;

    return toObject(env, result);
}
int QuickJSContext::quickjs_set(jobject object, JSAtom atom, JSValueConst value, JSValueConst receiver, int flags) {
    if (atom == customFinalizerAtom)
        return false;
    if (atom == atomHoldsJavaObject)
        return false;

    auto prop = hold(JS_AtomToValue(ctx, atom));
    JNIEnv *env = getEnvFromJavaVM(javaVM);
    const auto jprop = LocalRefHolder(env, toObject(env, prop));
    const auto jvalue = LocalRefHolder(env, toObject(env, value));
    jboolean ret = env->CallBooleanMethod(javaQuack, quackSetMethod, object, (jobject)jprop, (jobject)jvalue);

    if (rethrowJavaExceptionToQuickJS(env))
        return -1;

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
    jobject result = env->CallObjectMethod(javaQuack, quackApply, func_obj, (jobject)thiz, javaArgs);
    env->DeleteLocalRef(javaArgs);

    if (rethrowJavaExceptionToQuickJS(env))
        return JS_EXCEPTION;

    return toObject(env, result);
}
int QuickJSContext::quickjs_construct(JSValue func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    JNIEnv *env = getEnvFromJavaVM(javaVM);

    // unpack the arguments
    jobjectArray javaArgs = env->NewObjectArray((jsize)argc, objectClass, nullptr);
    for (int i = 0; i < argc; i++) {
        env->SetObjectArrayElement(javaArgs, (jsize)i, toObject(env, argv[i]));
    }

    const auto thiz = LocalRefHolder(env, toObject(env, func_obj));
    auto result = LocalRefHolder(env, env->CallObjectMethod(javaQuack, quackConstruct, (jobject)thiz, javaArgs));
    env->DeleteLocalRef(javaArgs);

    if (rethrowJavaExceptionToQuickJS(env))
        return -1;

    auto value = LocalRefHolder(env, env->NewObject(javaObjectClass, javaObjectConstructor, javaQuack, (jobject)result));

    setFinalizerOnFinalizerObject(this_val, javaRefFinalizer, env->NewGlobalRef(value));
    return 1;
}

jboolean QuickJSContext::checkQuickJSErrorAndThrow(JNIEnv *env, int maybeException) {
    if (maybeException >= 0)
        return (jboolean)(maybeException ? JNI_TRUE : JNI_FALSE);

    auto exception = hold(JS_GetException(ctx));
    assert(JS_IsException(exception));
    rethrowQuickJSErrorToJava(env, exception);
    return JNI_FALSE;
}

void QuickJSContext::rethrowQuickJSErrorToJava(JNIEnv *env, JSValue exception) {
    // try to pull a stack trace out
    if (JS_IsError(ctx, exception)) {

        // this might actually be a native java exception, which propagated from in which case
        // the js and java call stacks need merging.
        auto javaException = hold(JS_GetProperty(ctx, exception, javaExceptionAtom));

        if (!JS_IsUndefinedOrNull(javaException)) {
//            auto errorMessage = hold(JS_ToString(ctx, exception));
            auto stack = hold(JS_GetPropertyStr(ctx, exception, "stack"));

            jobject unwrappedException = toObject(env, javaException);
            jthrowable ex = (jthrowable)env->CallObjectMethod(unwrappedException, quackJavaObjectGetObject, nullptr);
            env->CallStaticVoidMethod(quackExceptionClass, addJSStack, ex, toString(env, stack));
            env->Throw(ex);
        }
        else {
            auto errorMessage = hold(JS_ToString(ctx, exception));
            auto stack = hold(JS_GetPropertyStr(ctx, exception, "stack"));

//            env->ThrowNew(quackExceptionClass, (toStdString(errorMessage) + "\n" + toStdString(stack)).c_str());

            std::string str;
            if (!JS_IsUndefinedOrNull(errorMessage))
                str = toStdString(errorMessage);
            else
                str = "QuickJS Java unknown Error";
            str += "\n";
            if (!JS_IsUndefinedOrNull(stack))
                str += toStdString(stack);
            else
                str += "    at unknown (unknown)\n";
            env->ThrowNew(quackExceptionClass, str.c_str());
        }
    }
    else {
        // js can throw strings and ints and all sorts of stuff, so who knows what this is.
        // just stringify it as the message.
        auto string = hold(JS_ToString(ctx, exception));
        env->ThrowNew(quackExceptionClass, toStdString(string).c_str());
    }
}

bool QuickJSContext::rethrowJavaExceptionToQuickJS(JNIEnv *env) {
    if (!env->ExceptionCheck())
        return false;

    jthrowable e = env->ExceptionOccurred();
    env->ExceptionClear();

    auto jmessage = LocalRefHolder(env, env->CallObjectMethod(e, objectToString));
    std::string message;
    if (jmessage != nullptr) {
        message = ::toStdString(env, (jstring)(jobject)jmessage);
    }
    else {
        message = "Java Exception";
    }

    // grab js stack
    JSValue error = JS_Call(ctx, thrower_function, JS_UNDEFINED, 0, nullptr);
    auto stack = hold(JS_GetPropertyStr(ctx, error, "stack"));

    // merge the stacks
    auto newStack = LocalRefHolder(env,
        env->CallStaticObjectMethod(quackExceptionClass,
            addJavaStack,
            env->NewStringUTF((message + "\n" + toStdString(stack)).c_str()), e));
    auto newStackValue = toObject(env, newStack);
    auto newMessage = toString(env, (jstring)(jobject)jmessage);

    // update the stack
    JS_DefinePropertyValueStr(ctx, error, "stack", newStackValue, 0);
    JS_DefinePropertyValueStr(ctx, error, "message", newMessage, 0);

    JS_Throw(ctx, error);
    return true;
}

jboolean QuickJSContext::hasPendingJobs(JNIEnv *env) {
    return (jboolean)(JS_IsJobPending(JS_GetRuntime(ctx)) ? JNI_TRUE : JNI_FALSE);
}

void QuickJSContext::runJobs(JNIEnv *env) {
    while (JS_IsJobPending(runtime)) {
        JSContext *pctx;
//        if (JS_ExecutePendingJob(runtime, &pctx))
//            JS_FreeValue(ctx, JS_GetException(ctx));



        if (JS_ExecutePendingJob(runtime, &pctx) < 0)
            printf("uhhh\n");
    }
}

jlong QuickJSContext::getHeapSize(JNIEnv* env) {
    JSMemoryUsage usage;
    JS_ComputeMemoryUsage(runtime, &usage);
    return (jlong)usage.memory_used_size;
}

void QuickJSContext::waitForDebugger(JNIEnv *env, jstring connectionString) {
    js_debugger_wait_connection(ctx, ::toStdString(env, connectionString).c_str());
}

jboolean QuickJSContext::isDebugging() {
    return (jboolean)(js_debugger_is_transport_connected(ctx) ? JNI_TRUE : JNI_FALSE);
}

void QuickJSContext::cooperateDebugger() {
    js_debugger_cooperate(ctx);
}
