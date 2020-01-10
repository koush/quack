#include "QuickJSContext.h"
#include <string>
#include <vector>
extern "C" {
#include "../../../../../../quickjs/quickjs-libc.h"
}

#define JS_IsUndefinedOrNull(value) (JS_IsUndefined(value) || JS_IsNull(value))

inline JSValueHolder QuickJSContext::toValueAsLocal(jlong object) {
    JSValue twin = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(object));
    return JSValueHolder(ctx, JS_GetProperty(ctx, twin, atomHoldsJavaScriptObject));
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
    auto *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(val, customFinalizerClassId));
    if (!data)
        return;
    if (data->finalizer)
        data->finalizer(data->ctx, val, data->udata);
    free(data);
}

static void quackObjectFinalizer(JSRuntime *rt, JSValue val) {
    auto *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(val, quackObjectProxyClassId));
    if (!data)
        return;
    data->finalizer(data->ctx, val, data->udata);
    free(data);
}

// called when the JavaScriptObject on the Java side gets collected.
// the object may continue living in the QuickJS side, but clean up all references
// to the java side.
// this will trigger a GC on the stashed twin, which will clean up the weak global reference.
void QuickJSContext::finalizeJavaScriptObjects(JNIEnv *env, jlongArray objects) {
    jsize len = env->GetArrayLength(objects);
    jlong *ptr = env->GetLongArrayElements(objects, 0);
    for (int i = 0; i < len; i++) {
        // delete the entry in the stash that was keeping this alive from the java side.
        stash.erase(ptr[i]);
    }
}

void QuickJSContext::setFinalizerOnFinalizerObject(JSValue finalizerObject, CustomFinalizer finalizer, void *udata) {
    auto *data = new CustomFinalizerData();
    *data = {
        this,
        finalizer,
        udata
    };
    assert(JS_IsObject(finalizerObject));
    JS_SetOpaque(finalizerObject, data);
}

static struct JSClassDef customFinalizerClassDef = {
    .class_name = "CustomFinalizer",
    .finalizer = customFinalizer,
};

static int quickjs_has(JSContext *ctx, JSValueConst obj, JSAtom atom) {
    auto *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, quackObjectProxyClassId));
    auto object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_has(object, atom);
}
static JSValue quickjs_get(JSContext *ctx, JSValueConst obj, JSAtom atom, JSValueConst receiver) {
    auto *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, quackObjectProxyClassId));
    auto object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_get(object, atom, receiver);
}
/* return < 0 if exception or TRUE/FALSE */
static int quickjs_set(JSContext *ctx, JSValueConst obj, JSAtom atom, JSValueConst value, JSValueConst receiver, int flags) {
    auto *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(obj, quackObjectProxyClassId));
    auto object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_set(object, atom, value, receiver, flags);
}
static JSValue quickjs_construct(JSContext *ctx, JSValue func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    auto *qctx = reinterpret_cast<QuickJSContext *>(JS_GetContextOpaque(ctx));
    return qctx->quickjs_construct(func_obj, this_val, argc, argv);
}
JSValue quickjs_apply(JSContext *ctx, JSValueConst func_obj, JSValueConst this_val, int argc, JSValueConst *argv, int flags) {
    if (flags & JS_CALL_FLAG_CONSTRUCTOR) {
        return quickjs_construct(ctx, func_obj, this_val, argc, argv);
    }
    auto *data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(func_obj, quackObjectProxyClassId));
    auto object = reinterpret_cast<jobject>(data->udata);
    return data->ctx->quickjs_apply(object, this_val, argc, argv);
}

struct JSClassExoticMethods quackObjectProxyMethods = {
    .has_property = quickjs_has,
    .get_property = quickjs_get,
    .set_property = quickjs_set,
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

    // test
    JS_SetModuleLoaderFunc(runtime, NULL, js_module_loader, NULL);
    js_std_add_helpers(ctx, 0, nullptr);
    js_init_module_std(ctx, "std");
    js_init_module_os(ctx, "os");
    const char *str = "import * as std from 'std';\n"
        "import * as os from 'os';\n"
        "globalThis.std = std;\n"
        "globalThis.os = os;\n";
    JS_Eval(ctx, str, strlen(str), "<input>", JS_EVAL_TYPE_MODULE);
    // end test

    JS_SetMaxStackSize(ctx, 1024 * 1024 * 4);

    auto global = hold(JS_GetGlobalObject(ctx));
    uint8ArrayConstructor = JS_GetPropertyStr(ctx, global, "Uint8Array");
    uint8ArrayPrototype = JS_GetPropertyStr(ctx, uint8ArrayConstructor, "prototype");
    auto arrayBufferConstructor = hold(JS_GetPropertyStr(ctx, global, "ArrayBuffer"));
    arrayBufferPrototype = JS_GetPropertyStr(ctx, arrayBufferConstructor, "prototype");

    const char *thrower_str = "(function() { try { throw new Error(); } catch (e) { return e; } })";
    thrower_function = JS_Eval(ctx, thrower_str, strlen(thrower_str), "<thrower>", JS_EVAL_TYPE_GLOBAL);

    JS_SetContextOpaque(ctx, this);

    atomHoldsJavaObject = privateAtom("javaObject");
    atomHoldsJavaScriptObject = privateAtom("javaScriptObject");
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
    longClass = findClass(env, "java/lang/Long");
    longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    longValue = env->GetMethodID(longClass, "longValue", "()J");
    doubleClass = findClass(env, "java/lang/Double");
    doubleValueOf = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
    doubleValue = env->GetMethodID(doubleClass, "doubleValue", "()D");
    stringClass = findClass(env, "java/lang/String");

    // ByteBuffer
    byteBufferClass = findClass(env, "java/nio/ByteBuffer");
    byteBufferAllocateDirect = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    auto bufferClass = env->FindClass("java/nio/Buffer");
    bufferGetPosition = env->GetMethodID(bufferClass, "position", "()I");
    bufferGetLimit = env->GetMethodID(bufferClass, "limit", "()I");
    bufferSetPosition = env->GetMethodID(bufferClass, "position", "(I)Ljava/nio/Buffer;");
    bufferClear = env->GetMethodID(bufferClass, "clear", "()Ljava/nio/Buffer;");
    env->DeleteLocalRef(bufferClass);

    // Quack
    quackClass = findClass(env, "com/koushikdutta/quack/QuackContext");
    quackHasMethod = env->GetMethodID(quackClass, "quackHas", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;)Z");
    quackGetMethod = env->GetMethodID(quackClass, "quackGet", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;)Ljava/lang/Object;");
    quackSetMethod = env->GetMethodID(quackClass, "quackSet", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;Ljava/lang/Object;)Z");
    quackApplyMethod = env->GetMethodID(quackClass, "quackApply", "(Lcom/koushikdutta/quack/QuackObject;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    quackConstructMethod = env->GetMethodID(quackClass, "quackConstruct", "(Lcom/koushikdutta/quack/QuackObject;[Ljava/lang/Object;)Ljava/lang/Object;");
    quackMapNativeMethod = env->GetMethodID(quackClass, "quackMapNative", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    quackUnmapNativeMethod = env->GetMethodID(quackClass, "quackUnmapNative", "(Ljava/lang/Object;)Ljava/lang/Object;");
    quackObjectClass = findClass(env, "com/koushikdutta/quack/QuackObject");

    // QuackJsonObject
    quackjsonObjectClass = findClass(env, "com/koushikdutta/quack/QuackJsonObject");
    quackJsonField = env->GetFieldID(quackjsonObjectClass, "json", "Ljava/lang/String;");

    // JavaScriptObject
    javaScriptObjectClass = findClass(env, "com/koushikdutta/quack/JavaScriptObject");
    javaScriptObjectConstructor = env->GetMethodID(javaScriptObjectClass, "<init>", "(Lcom/koushikdutta/quack/QuackContext;JJ)V");

    // QuackJavaScriptObject (interface, which can be implemented by proxies)
    quackJavaScriptObjectClass = findClass(env, "com/koushikdutta/quack/QuackJavaScriptObject");
    quackGetNativeContext = env->GetMethodID(quackJavaScriptObjectClass, "getNativeContext", "()J");
    quackGetNativePointer = env->GetMethodID(quackJavaScriptObjectClass, "getNativePointer", "()J");

    // JavaObject
    javaObjectClass = findClass(env, "com/koushikdutta/quack/JavaObject");
    javaObjectConstructor = env->GetMethodID(javaObjectClass, "<init>", "(Lcom/koushikdutta/quack/QuackContext;Ljava/lang/Object;)V");

    // QuackJavaObject
    quackJavaObject = findClass(env, "com/koushikdutta/quack/QuackJavaObject");
    quackJavaObjectGetObject = env->GetMethodID(quackJavaObject, "getObject", "()Ljava/lang/Object;");

    // exceptions
    quackExceptionClass = findClass(env, "com/koushikdutta/quack/QuackException");
    addJSStack =env->GetStaticMethodID(quackExceptionClass, "addJSStack","(Ljava/lang/Throwable;Ljava/lang/String;)V");
    addJavaStack = env->GetStaticMethodID(quackExceptionClass, "addJavaStack", "(Ljava/lang/String;Ljava/lang/Throwable;)Ljava/lang/String;");
}

QuickJSContext::~QuickJSContext() {
    JS_FreeValue(ctx, uint8ArrayPrototype);
    JS_FreeValue(ctx, uint8ArrayConstructor);
    JS_FreeValue(ctx, arrayBufferPrototype);
    stash.clear();
    JS_FreeValue(ctx, thrower_function);
    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);
}

JSAtom QuickJSContext::privateAtom(const char *str) {
    return JS_NewAtomLenPrivate(ctx, str, strlen(str));
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
    return toString(env, JS_JSONStringify(ctx, toValueAsLocal(object), JS_UNDEFINED, JS_UNDEFINED));
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
    else if (env->IsAssignableFrom(clazz, longClass))
        return JS_NewInt64(ctx, env->CallLongMethod(value, longValue, nullptr));
    else if (env->IsAssignableFrom(clazz, doubleClass))
        return JS_NewFloat64(ctx, env->CallDoubleMethodA(value, doubleValue, nullptr));
    else if (env->IsAssignableFrom(clazz, stringClass))
        return toString(env, reinterpret_cast<jstring>(value));

    LocalRefHolder tempHolder(env, nullptr);

    if (env->IsAssignableFrom(clazz, byteBufferClass)) {
        jlong capacity = env->GetDirectBufferCapacity(value);
        if (capacity >= 0) {
            // ArrayBuffer and Uint8Arrays originating from QuickJS are mapped to DirectByteBuffers in Java
            // Java allocated DirectByteBuffers are deep copied because position and limit are mutable properties
            // that can't be mapped to immutable JavaScript buffer types.
            auto nativeValue = env->CallObjectMethod(javaQuack, quackUnmapNativeMethod, value);
            tempHolder = LocalRefHolder(env, nativeValue);
            if (nativeValue == nullptr) {
                int position = env->CallIntMethod(value, bufferGetPosition);
                int limit = env->CallIntMethod(value, bufferGetLimit);
//                jvalue newPosition;
//                newPosition.i = limit;
//                env->CallObjectMethod(value, bufferSetPosition, newPosition);
                auto buffer = hold(JS_NewArrayBufferCopy(ctx,
                    reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(value))  + position,
                    (size_t)(limit - position)));
                JSValue args[] = { (JSValue)buffer };
                return JS_CallConstructor(ctx, uint8ArrayConstructor, 1, args);
            }

            value = tempHolder;
            clazz = env->GetObjectClass(value);
        }
    }

    if (env->IsAssignableFrom(clazz, quackjsonObjectClass)) {
        auto json = (jstring)env->GetObjectField(value, quackJsonField);
        const char *jsonPtr = env->GetStringUTFChars(json, 0);
        return JS_ParseJSON(ctx, jsonPtr, (size_t)env->GetStringUTFLength(json), "<QuackJsonObject>");
    }
    else if (env->IsAssignableFrom(clazz, quackJavaScriptObjectClass)) {
        auto *context = reinterpret_cast<QuickJSContext *>(env->CallLongMethod(value, quackGetNativeContext));
        // matching context, grab the native JSValue
        if (context == this) {
            auto twin = toValueAsLocal(env->CallLongMethod(value, quackGetNativePointer));
            return JS_DupValue(ctx, twin);
        }
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
    JS_SetConstructorBit(ctx, ret, 1);
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
    size_t buf_size;
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

    if (JS_IsException(value)) {
        const auto exception = JS_GetException(ctx);
        std::string val = toStdString(exception);
        return nullptr;
    }
//    else if (!JS_IsObject(value) && !JS_IsSymbol(value)) {
//        // todo: symbol?
//        return nullptr;
//    }

    // attempt to find an existing JavaScriptObject (or object like ByteBuffer) that exists on the java side (weak ref)
    auto twinFound = hold(JS_GetProperty(ctx, value, customFinalizerAtom));
    int ownProp;
    if (!JS_IsUndefinedOrNull(twinFound) && (ownProp = JS_GetOwnProperty(ctx, nullptr, value, customFinalizerAtom))) {
        // exception
        if (ownProp == -1)
            return nullptr;

        auto data = reinterpret_cast<CustomFinalizerData *>(JS_GetOpaque(twinFound, customFinalizerClassId));
        auto javaThis = reinterpret_cast<jobject>(data->udata);
        if (javaThis) {
            // found something, but make sure the weak ref is still alive.
            // if so, return a new local ref, because the global weak ref could be by side effect gc.
            if (!env->IsSameObject(javaThis, nullptr)) {
                jobject localJavaThis = env->NewLocalRef(javaThis);
                // DirectByteBuffers from QuickJS need to be cleared to reset the position and limit:
                // The buffer is owned by QuickJS/JavaScript, so position and limit only have meaning
                // on the Java side.
                if (env->IsInstanceOf(localJavaThis, byteBufferClass) && env->GetDirectBufferCapacity(localJavaThis) >= 0)
                    LocalRefHolder(env, env->CallObjectMethod(localJavaThis, bufferClear));
                return localJavaThis;
            }
            // the jobject is dead, so remove the twin from the JSValue and from the stash.
            // eventually the Java side will trigger the finalizer which will again
            // try to remove the twin from the stash, but it will already be gone.
            JS_DeleteProperty(ctx, value, customFinalizerAtom, 0);
            jlong foundPtr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR((JSValue)twinFound));
            stash.erase(foundPtr);
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

    // must create a JavaScriptObject jobject to pass back to Java

    // Create a QuicKJS twin object to mirror the lifetime of java side JavaScriptObject
    // The JavaScriptObject will stash a hard reference to the twin.
    // The twin will hold a weak global reference to the JavaScriptObject.
    // The twin will also hold a reference to the JSValue. This will be
    // used to convert the JavaScriptObject back into the JSValue.
    // The JavaScriptObject may be collected on the Java side, and continue surviving on the
    // QuickJS side. So any references held from the QuickJS side need to be mindful of this.
    // The JavaScriptObject can't stash a reference to the JSValue itself, but
    // rather must stash the twin due to GC race conditions.
    // The JSValue will hold a reference to the most recent twin.
    // Garbage collection of the JavaScriptObject will trigger garbage collection of the
    // twin, which may indirectly trigger garbage collection of the JSValue.
    JSValue twin = JS_NewObjectClass(ctx, customFinalizerClassId);

    // grab a pointer to the twin finalizer object and create the JavaScriptObject
    // with the twin
    jlong ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(twin));
    jobject javaThis = env->NewObject(javaScriptObjectClass, javaScriptObjectConstructor, javaQuack,
                reinterpret_cast<jlong>(this), ptr);


    // whether the JavaScriptObject instance should be interned.
    // disabled because this is contingent on GC being run consistently by the JVM
    // which allows global weak ref to be deleted.
    // todo: reenable this optionally? perform this on jvm side?
    bool trackJavaScriptObjectInstance = false;

    // this does not seem to be dup'd, so don't hold it.
    auto prototype = JS_GetPrototype(ctx, value);

    // if the JSValue is an ArrayBuffer or Uint8Array, create a
    // corresponding DirectByteBuffer, rather than marshalling the JavaScriptObject.
    if (JS_VALUE_GET_PTR((JSValue)prototype) == JS_VALUE_GET_PTR(arrayBufferPrototype)) {
        void *buf = JS_GetArrayBuffer(ctx, &buf_size, value);
        jobject byteBuffer = env->NewDirectByteBuffer(buf, buf_size);
        // this holds a weak ref to the DirectByteBuffer and a strong ref to the QuickJS ArrayBuffer.
        env->CallVoidMethod(javaQuack, quackMapNativeMethod, byteBuffer, javaThis);
        javaThis = byteBuffer;
        trackJavaScriptObjectInstance = false;
    }
    else if (JS_VALUE_GET_PTR((JSValue)prototype) == JS_VALUE_GET_PTR(uint8ArrayPrototype)) {
        size_t offset;
        size_t size;
        size_t bpe;
        auto ab = hold(JS_GetTypedArrayBuffer(ctx, value, &offset, &size, &bpe));

        size_t ab_size;
        uint8_t *ab_ptr = JS_GetArrayBuffer(ctx, &ab_size, ab);

        jobject byteBuffer = env->NewDirectByteBuffer(ab_ptr + offset, size);
        // this holds a weak ref to the DirectByteBuffer and a strong ref to the QuickJS Uint8Array.
        env->CallVoidMethod(javaQuack, quackMapNativeMethod, byteBuffer, javaThis);
        javaThis = byteBuffer;
        trackJavaScriptObjectInstance = false;
    }

    // set the finalizer on the twin to release the tracked JavaScriptObject
    if (trackJavaScriptObjectInstance) {
        setFinalizerOnFinalizerObject(twin, javaWeakRefFinalizer, env->NewWeakGlobalRef(javaThis));
        // set the finalizer object on the JSValue for tracking
        JS_SetProperty(ctx, value, customFinalizerAtom, JS_DupValue(ctx, twin));
    }
    else {
        // not tracked, but set nullptr.
        setFinalizerOnFinalizerObject(twin, nullptr, nullptr);
    }

    // stash the twin to hold a reference, and to free the global weak ref on runtime shutdown.
    stash[ptr] = hold(twin);

    // set the JSValue on the finalizer object
    JS_SetProperty(ctx, twin, atomHoldsJavaScriptObject, JS_DupValue(ctx, value));

    return javaThis;
}

jobject QuickJSContext::toObjectCheckQuickJSError(JNIEnv *env, JSValue value) {
    if (!JS_IsException(value))
        return toObject(env, value);
    auto exception = hold(JS_GetException(ctx));
    rethrowQuickJSErrorToJava(env, exception);
    return nullptr;
}

jobject QuickJSContext::evaluateInternal(JNIEnv *env, jstring code, jstring filename, int flags) {
    auto codeStr = env->GetStringUTFChars(code, 0);
    size_t len = strlen(codeStr);
    char* mem = (char*)js_malloc(ctx, len + 1);
    mem[len] = '\0';
    memcpy(mem, codeStr, len);
    if (!(flags & JS_EVAL_TYPE_MODULE)) {
        auto result = hold(JS_Eval(ctx, mem, len, env->GetStringUTFChars(filename, 0), flags));
        return toObjectCheckQuickJSError(env, result);
    }

    JSValue result = JS_Eval(ctx, mem, len, env->GetStringUTFChars(filename, 0), flags);
    if ((flags & JS_EVAL_TYPE_MODULE) && !JS_IsException(result)) {
        js_module_set_import_meta(ctx, result, 1, 1);
        result = JS_EvalFunction(ctx, result);
    }
    return toObjectCheckQuickJSError(env, result);
}

jobject QuickJSContext::compile(JNIEnv* env, jstring code, jstring filename) {
    std::string wrapped = ::toStdString(env, code);
    wrapped = "(" + wrapped + ")";

    auto codeStr = wrapped.c_str();
    auto result = hold(JS_Eval(ctx, codeStr, wrapped.size(), env->GetStringUTFChars(filename, 0), JS_EVAL_TYPE_GLOBAL));
    return toObjectCheckQuickJSError(env, result);
}

jobject QuickJSContext::getGlobalObject(JNIEnv *env) {
    return toObject(env, hold(JS_GetGlobalObject(ctx)));
}

static void freeValues(JSContext *ctx, std::vector<JSValue> &valueArgs) {
    for (JSValue & valueArg : valueArgs) {
        JS_FreeValue(ctx, valueArg);
    }
}

bool QuickJSContext::callArgs(JNIEnv *env, jobjectArray args, std::vector<JSValue> &valueArgs) {
    if (args != nullptr) {
        int length = env->GetArrayLength(args);
        if (length != 0) {
            for (int i = 0; i < length; i++) {
                const auto arg = LocalRefHolder(env, env->GetObjectArrayElement(args, i));
                JSValue argValue = toObject(env, arg);
                valueArgs.push_back(argValue);
                /// is it possible to fail during marshalling? would be catastrophic.
                if (JS_IsException(argValue)) {
                    rethrowQuickJSErrorToJava(env, argValue);
                    freeValues(ctx, valueArgs);
                    return false;
                }
            }
        }
    }

    return true;
}

jobject QuickJSContext::callInternal(JNIEnv *env, JSValue func, JSValue thiz, jobjectArray args) {
    std::vector<JSValue> valueArgs;
    if (!callArgs(env, args, valueArgs))
        return nullptr;

    auto ret = hold(JS_Call(ctx, func, thiz, valueArgs.size(), &valueArgs.front()));
    freeValues(ctx, valueArgs);

    return toObjectCheckQuickJSError(env, ret);
}

jobject QuickJSContext::callConstructor(JNIEnv *env, jlong object, jobjectArray args) {
    auto func = toValueAsLocal(object);

    std::vector<JSValue> valueArgs;
    if (!callArgs(env, args, valueArgs))
        return nullptr;

    auto ret = hold(JS_CallConstructor(ctx, func, valueArgs.size(), &valueArgs.front()));
    freeValues(ctx, valueArgs);

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
    auto result = LocalRefHolder(env, env->CallObjectMethod(javaQuack, quackGetMethod, object, (jobject)jprop));

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
        env->SetObjectArrayElement(javaArgs, (jsize)i, LocalRefHolder(env, toObject(env, argv[i])));
    }

    const auto thiz = LocalRefHolder(env, toObject(env, this_val));
    auto result = LocalRefHolder(env, env->CallObjectMethod(javaQuack, quackApplyMethod, func_obj, (jobject)thiz, javaArgs));
    env->DeleteLocalRef(javaArgs);

    if (rethrowJavaExceptionToQuickJS(env))
        return JS_EXCEPTION;

    return toObject(env, result);
}
JSValue QuickJSContext::quickjs_construct(JSValue func_obj, JSValueConst this_val, int argc, JSValueConst *argv) {
    JNIEnv *env = getEnvFromJavaVM(javaVM);

    // unpack the arguments
    jobjectArray javaArgs = env->NewObjectArray((jsize)argc, objectClass, nullptr);
    for (int i = 0; i < argc; i++) {
        env->SetObjectArrayElement(javaArgs, (jsize)i, LocalRefHolder(env, toObject(env, argv[i])));
    }

    const auto thiz = LocalRefHolder(env, toObject(env, func_obj));
    auto result = LocalRefHolder(env, env->CallObjectMethod(javaQuack, quackConstructMethod, (jobject)thiz, javaArgs));
    env->DeleteLocalRef(javaArgs);

    if (rethrowJavaExceptionToQuickJS(env))
        return JS_EXCEPTION;

    auto value = LocalRefHolder(env, env->NewObject(javaObjectClass, javaObjectConstructor, javaQuack, (jobject)result));
    return toObject(env, value);
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
            jthrowable ex = (jthrowable)env->CallObjectMethod(unwrappedException, quackJavaObjectGetObject);
            env->CallStaticVoidMethod(quackExceptionClass, addJSStack, ex, toString(env, stack));
            env->Throw(ex);
            env->DeleteLocalRef(ex);
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
    JS_DefinePropertyValue(ctx, error, javaExceptionAtom, toObject(env, e), 0);

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
