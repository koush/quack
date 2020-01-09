cmake_minimum_required(VERSION 3.4.1)

file(GLOB common_SRC
    "../../../../quack-jni/src/main/jni/*.h"
    "../../../../quack-jni/src/main/jni/*.cpp"
)
file(GLOB_RECURSE duktape_SRC
    "../../../../quack-jni/src/main/jni/duktape-jni/*.h"
    "../../../../quack-jni/src/main/jni/duktape-jni/*.c"
    "../../../../quack-jni/src/main/jni/duktape-jni/*.cpp"

    "../../../../quack-jni/src/main/jni/duktape/*.h"
    "../../../../quack-jni/src/main/jni/duktape/*.c"
    "../../../../quack-jni/src/main/jni/duktape/*.cpp"
)

add_definitions(-DCONFIG_VERSION="2019-10-27")

file(GLOB quickjs_SRC
    "../../../../quack-jni/src/main/jni/quickjs-jni/*.h"
    "../../../../quack-jni/src/main/jni/quickjs-jni/*.c"
    "../../../../quack-jni/src/main/jni/quickjs-jni/*.cpp"
    "../../../../../../quickjs/*.h"
    "../../../../../quickjs/quickjs.c"
    "../../../../../quickjs/libbf.c"
    "../../../../../quickjs/quickjs-libc.c"
    "../../../../../quickjs/quickjs-debugger.c"
    "../../../../../quickjs/quickjs-debugger-transport-unix.c"
    "../../../../../quickjs/libunicode.c"
    "../../../../../quickjs/libregexp.c"
    "../../../../../quickjs/cutils.c"
)

add_library(quack SHARED ${common_SRC} ${quickjs_SRC} ${duktape_SRC})

target_link_libraries(quack)
