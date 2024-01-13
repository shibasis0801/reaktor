#include "Reaktor.h"
//#include <core-cpp/base.h>
#include <string>
#include <flatbuffers/flexbuffers.h>

int reaktorTest() {
    std::string x = "Hello World Shibasis here";
    return 100 + x.size();
//    return getNumber();
}
const char* result;

const char* getName() {
    flexbuffers::Builder builder(1024);
    builder.Vector([&]() {
        builder.String("shibasis");
    });
    builder.Finish();

    auto data = builder.GetBuffer();
    auto vector = flexbuffers::GetRoot(data).AsVector();
    result = vector[0].ToString().c_str();
    printf("result: %s\n", result);
//    return result;
    return "shibasis";
}

/*
xcrun --sdk iphonesimulator clang++ -arch arm64 -c Reaktor.cpp -o Reaktor.o -I ../../flatbuffers/include/ -std=c++17
ar rcs libReaktor.a reaktor.o

I didn't link with libflatbuffers.a but still it worked.
Haven't understood cinterop properly yet.

Next steps:
 1. CMakelists.txt unified for android and iOS
 2. import objective-c headers

*/


char* getNameCpp() {
    std::string name = "shibasisCpp";
    char* heapStr = new char[name.length() + 1];
    std::strcpy(heapStr, name.c_str());
    return heapStr;
}

int sendByteArray(const uint8_t* bytes, int size) {
    for (size_t i = 0; i < size; ++i) {
        printf("%x ", bytes[i]);
    }
    printf("\n");
    return 1;
}

@implementation ExampleClass

- (NSString *)getHelloWorld {
return @"Hello, World!";
}

@end