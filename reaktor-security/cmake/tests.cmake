set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
add_subdirectory(cpp/external/googletest EXCLUDE_FROM_ALL)

enable_testing()
include(GoogleTest)
add_executable(rsec_tests
    src/commonTest/cpp/MlsEngineTest.cpp
    src/commonTest/cpp/RsecCapiTest.cpp)
target_link_libraries(rsec_tests PRIVATE ReaktorSecurity GTest::gtest_main)
gtest_discover_tests(rsec_tests)
