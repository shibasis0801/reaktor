#pragma once

#include <jni.h>
#include <fbjni/fbjni.h>
#include <fbjni/ByteBuffer.h>
#include <common/CppBase.h>

// Some templating magic beyond my skills can convert packages into java fqcn
#define JAVA_DESCRIPTOR(fqcn) static auto constexpr kJavaDescriptor = fqcn;
namespace jni = facebook::jni;
