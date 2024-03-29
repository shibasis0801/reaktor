#include <droid/JFlexBuffer.h>

// adding a timer obviously slows down the process, rely on flamegraphs.
long timer = 0;

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
//    timer = 0;
//    auto start = high_resolution_clock::now();
    auto it = Flex_Create();
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
    return it;
}

jlong JFlexBuffer::ParseJson(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                            jni::alias_ref<jni::JString> jsonString) {
//    auto start = high_resolution_clock::now();
    auto it = Flex_ParseJson(pointer, jsonString->toStdString().c_str());
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
    return it;
}

void JFlexBuffer::Destroy(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer) {
//    auto start = high_resolution_clock::now();
    Flex_Destroy(pointer);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();

}

jlong JFlexBuffer::Finish(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer) {
//    auto start = high_resolution_clock::now();
    Flex_Finish(pointer);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
    return timer;
}

/*
This is zero-copy if you do not wish to have access to the raw array internally.
DirectByteBuffers allow you to expose an existing C++ array to Java.
You can get random access to all the elements, but if you need the raw array you need to copy.
Java Unsafe is not present in Android and Project Panama is still not supported.
So most likely, this is the best way to transfer arrays from C++ to Java.
 */
jni::local_ref<jni::JByteBuffer>
JFlexBuffer::GetBuffer(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer) {
//    auto start = high_resolution_clock::now();
    auto flexArray = Flex_GetBuffer(pointer);
    auto buffer = jni::JByteBuffer::wrapBytes(const_cast<uint8_t *>(flexArray.buffer), flexArray.size);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
    return buffer;
}

#define nullOrValue(str) (str == nullptr) ? nullptr : str->toStdString().c_str()

void JFlexBuffer::Null(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                       jni::alias_ref<jni::JString> key) {
//    auto start = high_resolution_clock::now();
    Flex_Null(pointer,nullOrValue(key));
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::Int(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                      jni::alias_ref<jni::JString> key, FlexPointer value) {
//    auto start = high_resolution_clock::now();
    Flex_Int(pointer,nullOrValue(key), value);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::Float(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                        jni::alias_ref<jni::JString> key, jfloat value) {
//    auto start = high_resolution_clock::now();
    Flex_Float(pointer, nullOrValue(key), value);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::Double(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                         jni::alias_ref<jni::JString> key, jdouble value) {
//    auto start = high_resolution_clock::now();
    Flex_Double(pointer, nullOrValue(key), value);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::Bool(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                       jni::alias_ref<jni::JString> key, jboolean value) {
//    auto start = high_resolution_clock::now();
    Flex_Bool(pointer, nullOrValue(key), value);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::String(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                         jni::alias_ref<jni::JString> key, jni::alias_ref<jni::JString> value) {
//    auto start = high_resolution_clock::now();
    Flex_String(pointer, nullOrValue(key), value->toStdString().c_str());
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::Blob(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                       jni::alias_ref<jni::JString> key, jni::alias_ref<jni::JArrayByte> value) {
//    auto start = high_resolution_clock::now();
    // copy and send the blob.
    // to avoid copies, get direct bytebuffer.
    Flex_Blob(pointer, nullOrValue(key), nullptr, value->size());
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

FlexPointer JFlexBuffer::StartMap(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                             jni::alias_ref<jni::JString> key) {
//    auto start = high_resolution_clock::now();
    auto it = Flex_StartMap(pointer, nullOrValue(key));
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
    return it;
}

void JFlexBuffer::EndMap(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, FlexPointer mapStart) {
//    auto start = high_resolution_clock::now();
    Flex_EndMap(pointer, mapStart);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

FlexPointer JFlexBuffer::StartVector(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer,
                                jni::alias_ref<jni::JString> key) {
//    auto start = high_resolution_clock::now();
    auto it = Flex_StartVector(pointer, nullOrValue(key));
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
    return it;
}

void JFlexBuffer::EndVector(jni::alias_ref<JFlexBuffer> self, FlexPointer pointer, FlexPointer vectorStart) {
//    auto start = high_resolution_clock::now();
    Flex_EndVector(pointer, vectorStart);
//    timer += duration_cast<microseconds>(high_resolution_clock::now() - start).count();
}

void JFlexBuffer::registerNatives() {
    javaClassStatic()->registerNatives({
        makeNativeMethod("Create", JFlexBuffer::Create),
        makeNativeMethod("ParseJson", JFlexBuffer::ParseJson),
        makeNativeMethod("Destroy", JFlexBuffer::Destroy),
        makeNativeMethod("Finish", JFlexBuffer::Finish),
        makeNativeMethod("jniGetBuffer", JFlexBuffer::GetBuffer),
        makeNativeMethod("Null", JFlexBuffer::Null),
        makeNativeMethod("Int", JFlexBuffer::Int),
        makeNativeMethod("Float", JFlexBuffer::Float),
        makeNativeMethod("Double", JFlexBuffer::Double),
        makeNativeMethod("Bool", JFlexBuffer::Bool),
        makeNativeMethod("String", JFlexBuffer::String),
        makeNativeMethod("Blob", JFlexBuffer::Blob),
        makeNativeMethod("StartMap", JFlexBuffer::StartMap),
        makeNativeMethod("EndMap", JFlexBuffer::EndMap),
        makeNativeMethod("StartVector", JFlexBuffer::StartVector),
        makeNativeMethod("EndVector", JFlexBuffer::EndVector)
    });
}