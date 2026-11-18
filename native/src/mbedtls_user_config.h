// Mbed TLS's options beyond its default configuration (native/CMakeLists.txt
// gives it as MBEDTLS_USER_CONFIG_FILE). libdatachannel needs DTLS-SRTP to
// compile, also with no media.

#ifndef P2PML_MBEDTLS_USER_CONFIG_H
#define P2PML_MBEDTLS_USER_CONFIG_H

#define MBEDTLS_SSL_DTLS_SRTP

#endif
