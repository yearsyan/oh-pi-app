#include "pi_ssh.h"

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct pi_ssh_jni_bytes {
    char *value;
    size_t length;
} pi_ssh_jni_bytes;

static void pi_ssh_jni_zero(void *value, size_t length)
{
    volatile unsigned char *bytes = (volatile unsigned char *)value;
    while (length > 0) {
        *bytes++ = 0;
        --length;
    }
}

static bool pi_ssh_jni_copy_bytes(JNIEnv *environment,
                                  jbyteArray source,
                                  bool required,
                                  pi_ssh_jni_bytes *result)
{
    jsize length;

    memset(result, 0, sizeof(*result));
    if (source == NULL) {
        return !required;
    }
    length = (*environment)->GetArrayLength(environment, source);
    result->value = (char *)calloc((size_t)length + 1u, 1u);
    if (result->value == NULL) {
        jclass exception =
            (*environment)->FindClass(environment, "java/lang/OutOfMemoryError");
        if (exception != NULL) {
            (void)(*environment)->ThrowNew(environment,
                                           exception,
                                           "Could not copy SSH configuration");
            (*environment)->DeleteLocalRef(environment, exception);
        }
        return false;
    }
    if (length > 0) {
        (*environment)->GetByteArrayRegion(environment,
                                           source,
                                           0,
                                           length,
                                           (jbyte *)result->value);
        if ((*environment)->ExceptionCheck(environment)) {
            pi_ssh_jni_zero(result->value, (size_t)length + 1u);
            free(result->value);
            memset(result, 0, sizeof(*result));
            return false;
        }
    }
    result->length = (size_t)length;
    return true;
}

static void pi_ssh_jni_free_bytes(pi_ssh_jni_bytes *value)
{
    if (value->value != NULL) {
        pi_ssh_jni_zero(value->value, value->length + 1u);
        free(value->value);
    }
    memset(value, 0, sizeof(*value));
}

static void pi_ssh_jni_write_error(JNIEnv *environment,
                                   const pi_ssh_error *error,
                                   jintArray error_code,
                                   jobjectArray error_strings)
{
    jint code = (jint)error->code;
    jstring message = NULL;
    jstring fingerprint = NULL;

    if (error_code != NULL &&
        (*environment)->GetArrayLength(environment, error_code) > 0) {
        (*environment)->SetIntArrayRegion(environment, error_code, 0, 1, &code);
    }
    if (error_strings == NULL ||
        (*environment)->GetArrayLength(environment, error_strings) < 2 ||
        (*environment)->ExceptionCheck(environment)) {
        return;
    }
    message = (*environment)->NewStringUTF(environment, error->message);
    if (message != NULL) {
        (*environment)->SetObjectArrayElement(environment,
                                              error_strings,
                                              0,
                                              message);
        (*environment)->DeleteLocalRef(environment, message);
    }
    if (error->host_key_sha256[0] != '\0' &&
        !(*environment)->ExceptionCheck(environment)) {
        fingerprint =
            (*environment)->NewStringUTF(environment, error->host_key_sha256);
        if (fingerprint != NULL) {
            (*environment)->SetObjectArrayElement(environment,
                                                  error_strings,
                                                  1,
                                                  fingerprint);
            (*environment)->DeleteLocalRef(environment, fingerprint);
        }
    }
}

JNIEXPORT jlong JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeStart(
    JNIEnv *environment,
    jobject receiver,
    jbyteArray ssh_host,
    jint ssh_port,
    jbyteArray username,
    jint auth_type,
    jbyteArray password,
    jbyteArray private_key,
    jbyteArray private_key_passphrase,
    jbyteArray expected_host_key_sha256,
    jbyteArray remote_host,
    jint remote_port,
    jint connect_timeout_ms,
    jint keepalive_interval_seconds,
    jintArray error_code,
    jobjectArray error_strings)
{
    pi_ssh_jni_bytes values[7];
    pi_ssh_tunnel_config config;
    pi_ssh_error error;
    pi_ssh_tunnel *tunnel = NULL;
    bool copied;
    size_t index;

    (void)receiver;
    memset(values, 0, sizeof(values));
    copied = pi_ssh_jni_copy_bytes(environment, ssh_host, true, &values[0]) &&
             pi_ssh_jni_copy_bytes(environment, username, true, &values[1]) &&
             pi_ssh_jni_copy_bytes(environment, password, false, &values[2]) &&
             pi_ssh_jni_copy_bytes(environment, private_key, false, &values[3]) &&
             pi_ssh_jni_copy_bytes(environment,
                                   private_key_passphrase,
                                   false,
                                   &values[4]) &&
             pi_ssh_jni_copy_bytes(environment,
                                   expected_host_key_sha256,
                                   false,
                                   &values[5]) &&
             pi_ssh_jni_copy_bytes(environment,
                                   remote_host,
                                   true,
                                   &values[6]);
    if (!copied) {
        goto cleanup;
    }

    pi_ssh_tunnel_config_init(&config);
    config.ssh_host = values[0].value;
    config.ssh_port = (uint16_t)ssh_port;
    config.username = values[1].value;
    config.auth_type = (int32_t)auth_type;
    config.password = values[2].value;
    config.private_key = values[3].value;
    config.private_key_passphrase = values[4].value;
    config.expected_host_key_sha256 = values[5].value;
    config.remote_host = values[6].value;
    config.remote_port = (uint16_t)remote_port;
    config.connect_timeout_ms = (uint32_t)connect_timeout_ms;
    config.keepalive_interval_seconds =
        (uint32_t)keepalive_interval_seconds;

    tunnel = pi_ssh_tunnel_start(&config, &error);
    if (tunnel == NULL) {
        pi_ssh_jni_write_error(environment,
                               &error,
                               error_code,
                               error_strings);
    }

cleanup:
    for (index = 0; index < sizeof(values) / sizeof(values[0]); ++index) {
        pi_ssh_jni_free_bytes(&values[index]);
    }
    return (jlong)(intptr_t)tunnel;
}

JNIEXPORT jint JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeLocalPort(
    JNIEnv *environment,
    jobject receiver,
    jlong handle)
{
    (void)environment;
    (void)receiver;
    return (jint)pi_ssh_tunnel_local_port(
        (const pi_ssh_tunnel *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeState(
    JNIEnv *environment,
    jobject receiver,
    jlong handle)
{
    (void)environment;
    (void)receiver;
    return (jint)pi_ssh_tunnel_state(
        (const pi_ssh_tunnel *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeLastError(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jintArray error_code,
    jobjectArray error_strings)
{
    pi_ssh_error error;
    (void)receiver;
    pi_ssh_tunnel_copy_last_error(
        (const pi_ssh_tunnel *)(intptr_t)handle,
        &error);
    pi_ssh_jni_write_error(environment,
                           &error,
                           error_code,
                           error_strings);
}

JNIEXPORT void JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeFree(
    JNIEnv *environment,
    jobject receiver,
    jlong handle)
{
    (void)environment;
    (void)receiver;
    pi_ssh_tunnel_free((pi_ssh_tunnel *)(intptr_t)handle);
}

JNIEXPORT jstring JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeVersion(
    JNIEnv *environment,
    jobject receiver)
{
    (void)receiver;
    return (*environment)->NewStringUTF(environment, pi_ssh_library_version());
}
