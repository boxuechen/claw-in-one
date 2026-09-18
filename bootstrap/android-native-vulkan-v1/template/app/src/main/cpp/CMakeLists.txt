cmake_minimum_required(VERSION 3.22.1)
project(claw_vulkan LANGUAGES C)

set(NATIVE_APP_GLUE
  ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c
)
set_source_files_properties(${NATIVE_APP_GLUE} PROPERTIES
  COMPILE_OPTIONS -Wno-unused-parameter
)

add_library(claw_vulkan SHARED main.c ${NATIVE_APP_GLUE})
target_include_directories(claw_vulkan PRIVATE
  ${ANDROID_NDK}/sources/android/native_app_glue
)

target_compile_features(claw_vulkan PRIVATE c_std_11)
target_compile_options(claw_vulkan PRIVATE -Wall -Wextra -Werror)
target_link_libraries(claw_vulkan PRIVATE android log vulkan)
