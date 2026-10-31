cmake_minimum_required(VERSION 3.12)

include_directories(
        ${CMAKE_CURRENT_LIST_DIR}/opus/include
)

find_package(shadowhook REQUIRED CONFIG)
add_subdirectory(opus)
add_subdirectory(samp)

target_compile_options(opus PRIVATE -O3)

# Instrument our code and libc memory operations; the proprietary engine is
# prebuilt, so its inline loads/stores cannot be instrumented by this switch.
option(GS_NATIVE_ASAN "Build an opt-in memory-error diagnostic APK" OFF)
if(GS_NATIVE_ASAN)
    foreach(target samp opus)
        target_compile_options(${target} PRIVATE -fsanitize=address -fno-omit-frame-pointer)
    endforeach()
    target_link_options(samp PRIVATE -fsanitize=address)
    target_compile_definitions(samp PRIVATE GS_NATIVE_ASAN=1)
endif()
