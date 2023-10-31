#include "Reaktor.h"
//#include <core-cpp/base.h>
#include <string>

int reaktorTest() {
    std::string x = "Hello World Shibasis here";
    return 100 + x.size();
//    return getNumber();
}

const char* getName() {
    return "shibasis";
}


char* getNameCpp() {
    std::string name = "shibasisCpp";
    char* heapStr = new char[name.length() + 1];
    std::strcpy(heapStr, name.c_str());
    return heapStr;
}