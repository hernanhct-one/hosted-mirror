cmake_minimum_required(VERSION 3.22)
project(hostedmirror C CXX)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(UX ${TP}/UxPlay/lib)
set(PLIST ${TP}/libplist)
set(ALAC ${TP}/alac/codec)
set(SSL ${TP}/openssl-android/${ANDROID_ABI})

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- OpenSSL (libcrypto estático, ver scripts/build-openssl.sh) ---
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${SSL}/lib/libcrypto.a)

# --- libplist ---
file(GLOB PLIST_SRC ${PLIST}/src/*.c)
add_library(plist STATIC ${PLIST_SRC} ${PLIST}/libcnary/node.c ${PLIST}/libcnary/node_list.c)
target_include_directories(plist PUBLIC ${PLIST}/include PRIVATE ${PLIST}/src ${PLIST}/libcnary/include)
target_compile_definitions(plist PUBLIC LIBPLIST_STATIC PRIVATE PACKAGE_VERSION="2.8.0"
    HAVE_STRNDUP HAVE_MEMMEM HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_STRPTIME)
target_compile_options(plist PRIVATE -w)

# --- Motor AirPlay de UxPlay (GPL-3) ---
file(GLOB UX_SRC ${UX}/*.c)
file(GLOB UX_LLHTTP ${UX}/llhttp/*.c)
file(GLOB UX_PLAYFAIR ${UX}/playfair/*.c)
file(GLOB UX_MDNSD ${UX}/mdnsd/*.c)
add_library(airplay STATIC ${UX_SRC} ${UX_LLHTTP} ${UX_PLAYFAIR} ${UX_MDNSD})
target_include_directories(airplay PUBLIC ${UX} ${UX}/llhttp ${UX}/playfair ${UX}/mdnsd ${SSL}/include)
target_compile_definitions(airplay PUBLIC NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L
    STANDALONE TARGET_POSIX _LINUX _REENTRANT)
target_compile_options(airplay PRIVATE -O2 -w)
target_link_libraries(airplay PUBLIC plist crypto)

# --- Decodificador ALAC de Apple (Apache 2.0) ---
add_library(alac STATIC ${ALAC}/ALACDecoder.cpp ${ALAC}/ALACBitUtilities.c ${ALAC}/EndianPortable.c
    ${ALAC}/ag_dec.c ${ALAC}/dp_dec.c ${ALAC}/matrix_dec.c)
target_include_directories(alac PUBLIC ${ALAC})
target_compile_options(alac PRIVATE -O2 -w)

# --- Puente JNI de Hosted Mirror ---
add_library(hostedmirror SHARED hm_bridge.cpp)
target_link_libraries(hostedmirror airplay alac log)
target_link_options(hostedmirror PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
