cmake_minimum_required(VERSION 3.0.0)
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CMAKE_CURRENT_SOURCE_DIR}/cmake/")

project(experimaestro)
set(LINKED_LIBRARIES "")
set(INCLUDE_DIRECTORIES "")

include(CMakeHelpers)
include(CMakeFindExtensions)

find_package(libssh REQUIRED)
find_package(Poco REQUIRED COMPONENTS NetSSL SQLite)

# --- Set YAML options
SET(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared library (YAML)")
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_CONTRIB OFF CACHE BOOL "" FORCE)

set(GCC_EXTRA_OPTIONS "${GCC_EXTRA_OPTIONS} -fPIC")
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/ext/yaml-cpp EXCLUDE_FROM_ALL)

# ---

SET(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
add_definitions("-Wall -Werror=return-type")

enable_testing()

set(PUBLIC_HEADERS
    include/public/xpm/commandline.hpp
    include/public/xpm/cpp.hpp 
    include/public/xpm/filesystem.hpp
    include/public/xpm/json.hpp
    include/public/xpm/logging.hpp
    include/public/xpm/register.hpp
    include/public/xpm/task.hpp
    include/public/xpm/type.hpp 
    include/public/xpm/scalar.hpp
    include/public/xpm/workspace.hpp
    include/public/xpm/xpm.hpp

    include/public/xpm/connectors/connectors.hpp
    include/public/xpm/connectors/ssh.hpp
    include/public/xpm/connectors/local.hpp

    include/public/xpm/launchers/launchers.hpp
    include/public/xpm/launchers/oar.hpp

    include/public/xpm/rpc/client.hpp
    include/public/xpm/rpc/jsonrpcclient.hpp
    include/public/xpm/rpc/server.hpp
    include/public/xpm/rpc/servercontext.hpp

    include/public/xpm/api.h
)

set(PRIVATE_HEADERS
    include/private/__xpm/common.hpp
    include/private/__xpm/scriptbuilder.hpp
    include/private/__xpm/jsonserialization.hpp
)

set(SOURCES
        # Include files
        ${PUBLIC_HEADERS} 

        ${PRIVATE_HEADERS}

        # Source files
        src/xpm.cpp
        src/filesystem.cpp
        src/workspace.cpp
        src/commandline.cpp
        src/private.cpp
        src/cpp.cpp 
        src/task.cpp 
        src/common.cpp 
        src/register.cpp 
        src/jsonserialization.cpp
        src/scalar.cpp
        src/scriptbuilder.cpp
        src/type.cpp

        # Launchers
        src/launchers/launchers.cpp 
        src/launchers/oar.cpp 

        # Connectors
        src/connectors/connectors.cpp        
        src/connectors/ssh.cpp        
        src/connectors/local.cpp        
        src/connectors/local_unix.cpp        

        # RPC sources
        src/rpc/notifications.cpp
        src/rpc/jsonrpcclient.cpp
        src/rpc/configuration.cpp
        src/rpc/client.cpp
        src/rpc/server.cpp
        src/rpc/servercontext.cpp

        # API
        src/api.cpp
)
        
find_package (Threads)
list(APPEND LINKED_LIBRARIES 
    ${LIBSSH_LIBRARIES} 
    ${CMAKE_THREAD_LIBS_INIT} 
    ${Poco_LIBRARIES}
    yaml-cpp
)
message(STATUS "Linked libraries: ${LINKED_LIBRARIES}")
SET(EXT_INSTALL_DIR "${CMAKE_BINARY_DIR}/external/googletest")

list(APPEND INCLUDE_DIRECTORIES 
    "${CMAKE_CURRENT_SOURCE_DIR}/ext"
    "${CMAKE_CURRENT_SOURCE_DIR}/include/public"
    "${CMAKE_CURRENT_SOURCE_DIR}/include/private"
    "${CMAKE_CURRENT_SOURCE_DIR}/ext/asio/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/ext/spdlog/include"
    "${LIBSSH_INCLUDE_DIR}"
    "${Poco_INCLUDE_DIRS}"
    "${Poco_INCLUDE_DIR}/include"
)
include_directories(${INCLUDE_DIRECTORIES})


# --- experimaestro library

if (UNIX)
    # Sets the default search path to be the origin
    SET(CMAKE_INSTALL_RPATH "\$ORIGIN")
endif()

add_library(experimaestro_shared SHARED ${SOURCES})
install(TARGETS experimaestro_shared LIBRARY DESTINATION lib)
install(DIRECTORY include/xpm DESTINATION include)

target_link_libraries(experimaestro_shared ${LINKED_LIBRARIES})
target_include_directories(experimaestro_shared  PRIVATE ${LIBSSH_INCLUDE_DIR})
set_target_properties(experimaestro_shared PROPERTIES 
    CXX_STANDARD 17
    OUTPUT_NAME experimaestro
    INSTALL_RPATH_USE_LINK_PATH TRUE
)

# ---- Web app

set(WEBAPPDIR ${CMAKE_SOURCE_DIR}/app/build)
set(WEBAPPINDEX ${WEBAPPDIR}/index.html)
set(WEBAPP_SOURCES 
    ${CMAKE_SOURCE_DIR}/app/src/App.js
    ${CMAKE_SOURCE_DIR}/app/src/Tasks.jsx
    ${CMAKE_SOURCE_DIR}/app/src/Experiments.jsx

    ${CMAKE_SOURCE_DIR}/app/src/index.js
    ${CMAKE_SOURCE_DIR}/app/src/store.js
    ${CMAKE_SOURCE_DIR}/app/src/client.js

    ${CMAKE_SOURCE_DIR}/app/sass/_jobs.scss
    ${CMAKE_SOURCE_DIR}/app/sass/App.scss
)
message(STATUS "Target ${WEBAPPINDEX} / ${CMAKE_SOURCE_DIR}/app/src / ${WEBAPP_SOURCEAS}")
add_custom_command(
    OUTPUT ${WEBAPPINDEX}
    COMMAND npm install
    COMMAND npm run build
    DEPENDS ${WEBAPP_SOURCES}
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/app
)
add_custom_target(webapp DEPENDS ${WEBAPPINDEX})

# ---- experimaestro executable

add_executable(experimaestro experimaestro/main.cpp)
target_link_libraries(experimaestro experimaestro_shared ${LINKED_LIBRARIES})
set_property(TARGET experimaestro PROPERTY CXX_STANDARD 17)


# --- Add googletest

add_subdirectory(test)

export(PACKAGE experimaestro)
export(TARGETS experimaestro experimaestro_shared FILE ${CMAKE_CURRENT_BINARY_DIR}/experimaestro.cmake EXPORT_LINK_INTERFACE_LIBRARIES)
