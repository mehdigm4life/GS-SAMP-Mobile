cmake_minimum_required(VERSION 3.12)
project(samp)

set(CMAKE_CXX_STANDARD 20)

if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    add_definitions(-DVER_x32=true)
elseif(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_definitions(-DVER_x32=false)
endif()

# Add source files
file(GLOB_RECURSE SOURCES
        ${CMAKE_CURRENT_LIST_DIR}/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/**/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/**/**/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/**/**/**/**/**/*.c*
        ${CMAKE_CURRENT_LIST_DIR}/**/**/**/**/**/**/**/**/**/*.c*
        )

# Add include directories
include_directories(
        ${CMAKE_CURRENT_LIST_DIR}
        ${CMAKE_CURRENT_LIST_DIR}/util/
        ${CMAKE_CURRENT_LIST_DIR}/vendor/armhook
        ${CMAKE_CURRENT_LIST_DIR}/vendor/imgui
        ${CMAKE_CURRENT_LIST_DIR}/vendor/
        ${CMAKE_CURRENT_LIST_DIR}/game/RW/
        ${CMAKE_CURRENT_LIST_DIR}/game/Core/
)

# Opus
#add_library(libopus STATIC IMPORTED)
#set_target_properties(libopus PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/vendor/opus/libopus.a)

add_library(samp SHARED ${SOURCES})

# A stripped library cannot answer which function an offset belongs to, and that
# is the only question a crash report asks. AGP strips the library it packages,
# so a copy of the freshly linked one - still carrying its symbols and DWARF -
# is kept next to the build tree for the symbol map step.
add_custom_command(TARGET samp POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy
                $<TARGET_FILE:samp> ${CMAKE_BINARY_DIR}/libsamp-unstripped.so
        COMMENT "keeping an unstripped copy of libsamp.so for crash analysis")
target_link_libraries(samp log EGL GLESv3 opus shadowhook::shadowhook ${CMAKE_CURRENT_SOURCE_DIR}/vendor/bass/libs/${ANDROID_ABI}/libbass.so)

# Set compilation flags
#
# No -s here. -s strips the symbol table at link time, so every crash offset in
# libsamp.so became unreadable - the packaged library is stripped by AGP anyway,
# which means dropping -s costs nothing in the APK and keeps the unstripped copy
# (and the system tombstone) able to name a function.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -w -g -fvisibility=hidden -pthread -Wall -O3 -fexceptions")

# With -fvisibility=hidden every symbol that is not explicitly exported is kept
# out of .dynsym. Debug builds are far easier to reason about (and to diagnose
# with dlsym/readelf) when everything is exported, so mirror what upstream does
# on its newer branch: default visibility for Debug, hidden for Release.
# JNI_OnLoad itself is marked JNIEXPORT, so it is exported in both cases.
target_compile_options(samp PRIVATE
        $<$<CONFIG:Debug>:-fvisibility=default>
        )

