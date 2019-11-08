cmake_minimum_required(VERSION 3.4.1)

file(GLOB common_SRC
    "*.h"
    "*.c"
    "*.cpp"
)

file(GLOB java_SRC
    "java/*.h"
    "java/*.c"
    "java/*.cpp"
)

file(GLOB duktape_SRC
    "duktape/*.h"
    "duktape/*.c"
    "duktape/*.cpp"
)

add_definitions(-DCONFIG_VERSION="2019-10-27")

file(GLOB quickjs_SRC
    "quickjs/*.h"
    "quickjs/quickjs.c"
    "quickjs/quickjs-debugger.c"
    "quickjs/quickjs-debugger-transport-unix.c"
    "quickjs/libunicode.c"
    "quickjs/libregexp.c"
    "quickjs/cutils.c"
)

add_library(duktape SHARED ${common_SRC} ${java_SRC} ${duktape_SRC} ${quickjs_SRC})

target_link_libraries(duktape)
