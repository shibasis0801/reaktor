# take parameters from the caller
function(fi_dependency name)
    add_subdirectory(
            ../../${name}/cpp
            ${CMAKE_CURRENT_BINARY_DIR}/${name}
    )
endfunction()

function(fi_auto_dependencies)
    if(NOT DEFINED REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS OR NOT DEFINED REAKTOR_NATIVE_DEPENDENCY_TARGETS)
        return()
    endif()

    set(source_dirs ${REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS})
    set(target_names ${REAKTOR_NATIVE_DEPENDENCY_TARGETS})
    set(project_names ${REAKTOR_NATIVE_DEPENDENCY_PROJECTS})

    list(LENGTH source_dirs source_count)
    if(source_count EQUAL 0)
        return()
    endif()

    math(EXPR last_index "${source_count} - 1")
    foreach(index RANGE ${last_index})
        list(GET source_dirs ${index} dependency_source_dir)
        list(GET target_names ${index} dependency_target_name)
        list(GET project_names ${index} dependency_project_name)
        if(NOT TARGET ${dependency_target_name} AND DEFINED REAKTOR_NATIVE_DEPENDENCY_LIBRARY_FILES)
            list(GET REAKTOR_NATIVE_DEPENDENCY_LIBRARY_FILES ${index} dependency_library_file)
            if(ANDROID)
                add_library(${dependency_target_name} SHARED IMPORTED GLOBAL)
            else()
                add_library(${dependency_target_name} STATIC IMPORTED GLOBAL)
            endif()
            set_target_properties(${dependency_target_name} PROPERTIES
                IMPORTED_LOCATION "${dependency_library_file}"
                INTERFACE_INCLUDE_DIRECTORIES "${REAKTOR_NATIVE_DEPENDENCY_INCLUDE_DIRS_${index}}"
            )
        elseif(NOT TARGET ${dependency_target_name})
            set(_saved_dependency_projects "${REAKTOR_NATIVE_DEPENDENCY_PROJECTS}")
            set(_saved_dependency_sources "${REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS}")
            set(_saved_dependency_targets "${REAKTOR_NATIVE_DEPENDENCY_TARGETS}")
            set(REAKTOR_NATIVE_DEPENDENCY_PROJECTS "" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS "" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_TARGETS "" CACHE STRING "" FORCE)
            add_subdirectory(
                    "${dependency_source_dir}"
                    "${CMAKE_CURRENT_BINARY_DIR}/deps/${dependency_project_name}"
            )
            set(REAKTOR_NATIVE_DEPENDENCY_PROJECTS "${_saved_dependency_projects}" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS "${_saved_dependency_sources}" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_TARGETS "${_saved_dependency_targets}" CACHE STRING "" FORCE)
        endif()
        set_property(GLOBAL APPEND PROPERTY REAKTOR_NATIVE_DEPENDENCY_TARGETS_PROP ${dependency_target_name})
    endforeach()
endfunction()

function(fi_link_dependencies target)
    get_property(dependency_targets GLOBAL PROPERTY REAKTOR_NATIVE_DEPENDENCY_TARGETS_PROP)
    if(dependency_targets)
        list(REMOVE_DUPLICATES dependency_targets)
        target_link_libraries(${target} PUBLIC ${dependency_targets})
    endif()
endfunction()
