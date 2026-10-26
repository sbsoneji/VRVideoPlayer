# Builds "cardboard_sdk_jni": the tiny JNI bridge that lets the Cardboard SDK's Java API
# (com.google.cardboard.sdk.CardboardView etc.) talk to the native Cardboard SDK library.
#
# This mirrors how the upstream "paperscope" demo builds the same library. We only need the two
# source files below; everything else is the prebuilt libGfxPluginCardboard.so that the ':sdk'
# Gradle module produces (extracted by the "extractNdk" task in app/build.gradle).

cmake_minimum_required(VERSION 3.22.1)
project(vrvideoplayer VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)
add_compile_options(-Wall -Wextra)

find_library(ANDROID_LIB android)
find_library(GLESv2_LIB GLESv2)
find_library(LOG_LIB log)

set(CARDBOARD_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../sdk)
set(CARDBOARD_JAVA_API_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../java_api)

# Prebuilt Cardboard SDK library extracted from the SDK's .aar by Gradle.
set(CARDBOARD_LIB ${CMAKE_CURRENT_SOURCE_DIR}/libraries/jni/${ANDROID_ABI}/libGfxPluginCardboard.so)

add_library(cardboard_sdk_jni SHARED
    ${CARDBOARD_JAVA_API_DIR}/src/main/jni/cardboard_jni.cc
    ${CARDBOARD_SDK_DIR}/jni_utils/android/jni_utils.cc
)

target_include_directories(cardboard_sdk_jni PRIVATE
    ${CARDBOARD_SDK_DIR}
)

target_link_libraries(cardboard_sdk_jni PRIVATE
    ${CARDBOARD_LIB}
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${GLESv2_LIB}
)

# Required for devices with 16 KB memory pages.
target_link_options(cardboard_sdk_jni PRIVATE "-Wl,-z,max-page-size=16384")
