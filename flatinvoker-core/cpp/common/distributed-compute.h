#pragma once
#include <cstdint>
#include <cstdlib>
#include <unordered_map>
#include <string>

using std::uint8_t;

// Every Language exposes a channel to receive input
struct PlatformChannel {
    virtual ~PlatformChannel() = default;
    virtual void send(const uint8_t *data, size_t count) = 0;
};

struct Handler {

};

// When a binary command comes to a language through the channel, it is converted to a flatbuf Command
// Switches are implemented in the language itself, this is for representation
// Switch holds a list of the implementations in the language itself
struct Switch {

};

// Holds which implementations exist in which languages
// Is exposed to all languages, and is used to registerImplementations
struct Registry {
    std::unordered_map<std::string, std::string> registeredHandlers;
};

// Holds
struct Router {
    std::unordered_map<std::string, PlatformChannel> inputChannels;
    Registry registry;
};





