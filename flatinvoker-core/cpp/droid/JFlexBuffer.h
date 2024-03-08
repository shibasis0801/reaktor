#pragma once

#include <droid/DroidBase.h>
#include <common/Flex.h>
#include "flatbuffers/idl.h"
#include "flatbuffers/buffer.h"
#include <chrono>


struct JFlexBuffer : public jni::JavaClass<JFlexBuffer> {
    JAVA_DESCRIPTOR("Ldev/shibasis/flatinvoker/core/FlexBuffer;");

    // This needs to be changed to match iOS implementation
    static jni::local_ref<jni::JByteBuffer> parseJson(jni::alias_ref<JFlexBuffer> self, jni::alias_ref<jni::JString> jsonString);

    static FlexPointer Create(jni::alias_ref<JFlexBuffer> self);

    static void ParseJson(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> jsonString);

    static void Destroy(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer);

    static void Finish(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer);

    static jni::local_ref<jni::JByteBuffer> GetBuffer(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer);

    static void Null(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key);

    static void Int(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key, FlexPointer value);

    static void Float(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key, jfloat value);

    static void Double(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key, jdouble value);

    static void Bool(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key, jboolean value);

    static void String(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key, jni::alias_ref<jni::JString> value);

    static void Blob(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key, jni::alias_ref<jni::JArrayByte> value);

    static FlexPointer StartMap(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key);

    static void EndMap(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, FlexPointer mapStart);

    static FlexPointer StartVector(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, jni::alias_ref<jni::JString> key);

    static void EndVector(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, FlexPointer vectorStart);

    static void registerNatives();
};
