#pragma once

#include <common/CppBase.h>
#include <hermes/hermes.h>
#include <filesystem>
#include <fstream>
#include <iostream>

using facebook::hermes::HermesRuntime;
namespace jsi = facebook::jsi;

class Engine {
public:
    static void start();
    static unique_ptr<HermesRuntime> runtime;
    static jsi::PropNameID createPropName(const std::string &propName);
    static jsi::Function createFunction(const jsi::PropNameID &name, int argCount, jsi::HostFunctionType &&fn);
    static jsi::Function createFunction(const std::string &name, int argCount, jsi::HostFunctionType &&fn);
    static jsi::Value createFromUTF8String(const std::string &contents);
    static jsi::Value createFromAsciiString(const std::string &contents);
    static void createGlobalProperty(const std::string &name, jsi::Object object);
    static jsi::Object createFromHostObject(std::shared_ptr<jsi::HostObject> hostObject);
    static jsi::Value getProperty(const std::string &name);
    static jsi::Function getFunction(const std::string &name);
    static std::string getString(const jsi::Value &value);
};