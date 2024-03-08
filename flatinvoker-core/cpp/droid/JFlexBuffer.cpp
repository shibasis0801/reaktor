#include <droid/JFlexBuffer.h>


// Timer lambda
auto measureTime = [](auto&& func, auto&&... args) {
    auto start = std::chrono::high_resolution_clock::now();

    // Forwarding arguments to the function
    std::forward<decltype(func)>(func)(std::forward<decltype(args)>(args)...);

    auto finish = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(finish - start);
};

/* ByteBuffer Transport
     // Difficult to prevent a copy and move the data. AFAIK FlatBuffers creates its own buffers internally
    // Direct ByteBuffers are great for passing from C++ to Java (no copy, mem in sharedHeap)
    // But it means a different area from FlatBuffers storage
    auto outputBuffer = jni::JByteBuffer::allocateDirect(builder.GetSize());
    outputBuffer->order(jni::JByteOrder::nativeOrder());
    auto copyTime = measureTime([&]() {
        std::memcpy(const_cast<uint8_t *>(outputBuffer->getDirectBytes()), data.data(), builder.GetSize());
    });
 */
jni::local_ref<jni::JByteBuffer>
JFlexBuffer::parseJson(jni::alias_ref<JFlexBuffer> _, jni::alias_ref<jni::JString> jsonString) {

    flatbuffers::Parser parser;
    flexbuffers::Builder builder(1024);

    parser.ParseFlexBuffer(jsonString->toStdString().c_str(), nullptr, &builder);

    auto data = builder.GetBuffer();
    auto byteBuffer = jni::JByteBuffer::wrapBytes(data.data(), data.size());
    return byteBuffer;
}



FlexPointer JFlexBuffer::Create(jni::alias_ref<JFlexBuffer> self) {
    return Flex_Create();
}

void JFlexBuffer::ParseJson(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                            jni::alias_ref<jni::JString> jsonString) {
    Flex_ParseJson(pointer, jsonString->toStdString().c_str());
}

void JFlexBuffer::Destroy(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer) {
    Flex_Destroy(pointer);
}

void JFlexBuffer::Finish(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer) {
    Flex_Finish(pointer);
}

jni::local_ref<jni::JByteBuffer>
JFlexBuffer::GetBuffer(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer) {
    auto flexArray = Flex_GetBuffer(pointer);
    auto buffer = jni::JByteBuffer::wrapBytes(const_cast<uint8_t *>(flexArray.buffer), flexArray.size);
    return buffer;
}

#define nullOrValue(str) (str == nullptr) ? nullptr : str->toStdString().c_str()

void JFlexBuffer::Null(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                       jni::alias_ref<jni::JString> key) {
    Flex_Null(pointer,nullOrValue(key));
}

void JFlexBuffer::Int(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                      jni::alias_ref<jni::JString> key, FlexPointer value) {
    Flex_Int(pointer,nullOrValue(key), value);
}

void JFlexBuffer::Float(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                        jni::alias_ref<jni::JString> key, jfloat value) {
    Flex_Float(pointer, nullOrValue(key), value);
}

void JFlexBuffer::Double(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                         jni::alias_ref<jni::JString> key, jdouble value) {
    Flex_Double(pointer, nullOrValue(key), value);
}

void JFlexBuffer::Bool(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                       jni::alias_ref<jni::JString> key, jboolean value) {
    Flex_Bool(pointer, nullOrValue(key), value);
}

void JFlexBuffer::String(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                         jni::alias_ref<jni::JString> key, jni::alias_ref<jni::JString> value) {
    Flex_String(pointer, nullOrValue(key), value->toStdString().c_str());
}

void JFlexBuffer::Blob(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                       jni::alias_ref<jni::JString> key, jni::alias_ref<jni::JArrayByte> value) {
    // copy and send the blob.
    // to avoid copies, get direct bytebuffer.
    Flex_Blob(pointer, nullOrValue(key), nullptr, value->size());
}

FlexPointer JFlexBuffer::StartMap(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                             jni::alias_ref<jni::JString> key) {
    return Flex_StartMap(pointer, nullOrValue(key));
}

void JFlexBuffer::EndMap(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, FlexPointer mapStart) {
    Flex_EndMap(pointer, mapStart);
}

FlexPointer JFlexBuffer::StartVector(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                                jni::alias_ref<jni::JString> key) {
    return Flex_StartVector(pointer, nullOrValue(key));
}

void JFlexBuffer::EndVector(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, FlexPointer vectorStart) {
    Flex_EndVector(pointer, vectorStart);
}

void JFlexBuffer::registerNatives() {
    javaClassStatic()->registerNatives({
        makeNativeMethod("jniCreate", JFlexBuffer::Create),
        makeNativeMethod("jniParseJson", JFlexBuffer::ParseJson),
        makeNativeMethod("jniDestroy", JFlexBuffer::Destroy),
        makeNativeMethod("jniFinish", JFlexBuffer::Finish),
        makeNativeMethod("jniGetBuffer", JFlexBuffer::GetBuffer),
        makeNativeMethod("jniNull", JFlexBuffer::Null),
        makeNativeMethod("jniInt", JFlexBuffer::Int),
        makeNativeMethod("jniFloat", JFlexBuffer::Float),
        makeNativeMethod("jniDouble", JFlexBuffer::Double),
        makeNativeMethod("jniBool", JFlexBuffer::Bool),
        makeNativeMethod("jniString", JFlexBuffer::String),
        makeNativeMethod("jniBlob", JFlexBuffer::Blob),
        makeNativeMethod("jniStartMap", JFlexBuffer::StartMap),
        makeNativeMethod("jniEndMap", JFlexBuffer::EndMap),
        makeNativeMethod("jniStartVector", JFlexBuffer::StartVector),
        makeNativeMethod("jniEndVector", JFlexBuffer::EndVector)
    });
}