reaktor -> Set of libraries for Kotlin and React Native
flatinvoker -> Uses FlexBuffers to perform rpc


there are cross dependencies in between reaktor and flatinvoker, that is why both are colocated


reaktor/
    reaktor-core/
        This is the root library. It contains basic tools like WeakReference, Atomic types.
        It also contains the primary cross platform abstraction Adapter. 
        An adapter is a class that exposes platform specific functionality in a cross platform way. 
        Shared capabilities are defined in the Adapter interface, and platform specific capabilities are add-on interfaces.
        This allows you to code to capabilities instead of platforms, allowing much more robust and flexible code.

flatinvoker/
    flatinvoker-core/
        This contains the kotlin-serialization support for FlexBuffers
        It uses the C++ implementation of FlexBuffers to perform the serialization and deserialization
    flatinvoker-react/
        This uses JSI and FlexBuffer serialization to create native modules for Android and iOS
        It also contains the React type converters and full support for Flows
        It has a cross platform bridge module which you can use to perform the installation of JSI modules. 
    flatinvoker-compiler/
        This generates the glue code needed to call kotlin modules from typescript as if they were typescript modules. 

dependeasy/
    This is a plugin to help with multi-platform dependencies. 
    It has abstractions to setting up multiplatform projects fast.
    It provides CMake support for Kotlin/Native.
    It also provides size benchmarking tools
    










https://docs.google.com/document/d/1dwy5Cy9FO5CpWikQ4a2AUtIu2tHRKMmm9ezaycKIp9A/edit


Use react-native-performance (shopify) to benchmark