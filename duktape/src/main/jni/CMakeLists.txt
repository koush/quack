cmake_minimum_required(VERSION 3.4.1)

file(GLOB_RECURSE duktape_SRC
    "duktape-jni/*.h"
    "duktape-jni/*.c"
    "duktape-jni/*.cpp"

    "duktape/*.h"
    "duktape/*.c"
    "duktape/*.cpp"
)

add_definitions(-DCONFIG_VERSION="2019-10-27")

file(GLOB quickjs_SRC
    "quickjs-jni/*.h"
    "quickjs-jni/*.c"
    "quickjs-jni/*.cpp"
    "quickjs/*.h"
    "quickjs/quickjs.c"
    "quickjs/quickjs-debugger.c"
    "quickjs/quickjs-debugger-transport-unix.c"
    "quickjs/libunicode.c"
    "quickjs/libregexp.c"
    "quickjs/cutils.c"
)

add_library(duktape SHARED ${duktape_SRC})

target_link_libraries(duktape)
