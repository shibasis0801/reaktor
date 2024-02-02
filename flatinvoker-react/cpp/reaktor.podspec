# Todo Figure out a way to add this cocoapod as dependency in batcave
react="../node_modules/react-native"
Pod::Spec.new do |spec|
    spec.name                     = 'reaktor'
    spec.version                  = '1.0'
    spec.homepage                 = 'reaktor'
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = ''
    spec.summary                  = 'reaktor'
    spec.source_files = [
    "darwin/*.{cpp,mm,h}",
    "modules/*.{cpp,mm,h}",
    "types/*.{cpp,mm,h}",
    "*.{cpp,mm,h}",
    "../node_modules/react-native/ReactCommon/jsi/**/*.{cpp,h}",
    "../node_modules/react-native/ReactCommon/callinvoker/**/*.{cpp,h}"
    ]
    spec.header_mappings_dir = "."
    # spec.preserve_paths = "**/*", "*"
    spec.pod_target_xcconfig = {
        'OTHER_LDFLAGS' => '-l"stdc++"',
        "HEADER_SEARCH_PATHS" => "\"$(PODS_TARGET_SRCROOT)/ReactCommon\" \"$(PODS_TARGET_SRCROOT)\" \"$(PODS_ROOT)/RCT-Folly\" \"$(PODS_ROOT)/boost\" \"$(PODS_ROOT)/boost-for-react-native\" \"$(PODS_ROOT)/DoubleConversion\" \"$(PODS_ROOT)/Headers/Private/React-Core\" ",
        "CLANG_CXX_LANGUAGE_STANDARD" => "c++17",
        "CLANG_CXX_LIBRARY" => "libc++"
    }

    # dangerous may pick from github, ask abhishek
    spec.dependency "React-callinvoker"
    spec.dependency "React"
    spec.dependency "React-Core"
    spec.dependency "React-jsi"
    spec.libraries                = ['c++', 'c++abi']
    spec.ios.deployment_target = '11'
end
# modify this config to preserve header paths