// Mbed TLS's changes to its default configuration (native/CMakeLists.txt
// gives it as MBEDTLS_USER_CONFIG_FILE). libdatachannel needs DTLS-SRTP to
// compile, also with no media.

#ifndef P2PML_MBEDTLS_USER_CONFIG_H
#define P2PML_MBEDTLS_USER_CONFIG_H

#define MBEDTLS_SSL_DTLS_SRTP

// No debug log: libdatachannel sets no debug callback, but with this option
// each DTLS record still goes through the debug functions (seen in a profile
// on a phone).
#undef MBEDTLS_DEBUG_C

#endif
