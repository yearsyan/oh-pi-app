#include "pi_ssh.h"

#include <jni.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct pi_ssh_jni_bytes {
    char *value;
    size_t length;
} pi_ssh_jni_bytes;

typedef struct pi_ssh_jni_output_context {
    JNIEnv *environment;
    jobject listener;
    jmethodID method;
} pi_ssh_jni_output_context;

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

static int pi_ssh_jni_forward_command_output(void *opaque_context,
                                             int32_t stream,
                                             const uint8_t *data,
                                             size_t size)
{
    pi_ssh_jni_output_context *context =
        (pi_ssh_jni_output_context *)opaque_context;
    jbyteArray chunk;

    if (context == NULL || context->environment == NULL ||
        context->listener == NULL || context->method == NULL || size > INT_MAX) {
        return -1;
    }
    chunk = (*context->environment)->NewByteArray(context->environment,
                                                  (jsize)size);
    if (chunk == NULL) {
        return -1;
    }
    if (size > 0) {
        (*context->environment)->SetByteArrayRegion(
            context->environment,
            chunk,
            0,
            (jsize)size,
            (const jbyte *)data);
    }
    if (!(*context->environment)->ExceptionCheck(context->environment)) {
        (*context->environment)->CallVoidMethod(context->environment,
                                                context->listener,
                                                context->method,
                                                (jint)stream,
                                                chunk);
    }
    (*context->environment)->DeleteLocalRef(context->environment, chunk);
    return (*context->environment)->ExceptionCheck(context->environment) ? -1 : 0;
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

static jobjectArray pi_ssh_jni_command_output(
    JNIEnv *environment,
    const pi_ssh_command_result *result)
{
    jclass byte_array_class = NULL;
    jobjectArray output = NULL;
    jbyteArray standard_output = NULL;
    jbyteArray standard_error = NULL;

    if (result->stdout_size > INT_MAX || result->stderr_size > INT_MAX) {
        return NULL;
    }
    byte_array_class = (*environment)->FindClass(environment, "[B");
    if (byte_array_class == NULL) {
        return NULL;
    }
    output = (*environment)->NewObjectArray(environment,
                                            2,
                                            byte_array_class,
                                            NULL);
    if (output == NULL) {
        goto cleanup;
    }
    standard_output =
        (*environment)->NewByteArray(environment, (jsize)result->stdout_size);
    standard_error =
        (*environment)->NewByteArray(environment, (jsize)result->stderr_size);
    if (standard_output == NULL || standard_error == NULL) {
        output = NULL;
        goto cleanup;
    }
    if (result->stdout_size > 0) {
        (*environment)->SetByteArrayRegion(
            environment,
            standard_output,
            0,
            (jsize)result->stdout_size,
            (const jbyte *)result->stdout_data);
    }
    if (result->stderr_size > 0 &&
        !(*environment)->ExceptionCheck(environment)) {
        (*environment)->SetByteArrayRegion(
            environment,
            standard_error,
            0,
            (jsize)result->stderr_size,
            (const jbyte *)result->stderr_data);
    }
    if (!(*environment)->ExceptionCheck(environment)) {
        (*environment)->SetObjectArrayElement(environment,
                                              output,
                                              0,
                                              standard_output);
        (*environment)->SetObjectArrayElement(environment,
                                              output,
                                              1,
                                              standard_error);
    }
    if ((*environment)->ExceptionCheck(environment)) {
        output = NULL;
    }

cleanup:
    if (standard_output != NULL) {
        (*environment)->DeleteLocalRef(environment, standard_output);
    }
    if (standard_error != NULL) {
        (*environment)->DeleteLocalRef(environment, standard_error);
    }
    (*environment)->DeleteLocalRef(environment, byte_array_class);
    return output;
}

static jobjectArray pi_ssh_jni_key_pair_output(
    JNIEnv *environment,
    const pi_ssh_key_pair *key_pair)
{
    jclass byte_array_class = NULL;
    jobjectArray output = NULL;
    jbyteArray private_key = NULL;
    jbyteArray public_key = NULL;
    size_t private_key_size = strlen(key_pair->private_key);
    size_t public_key_size = strlen(key_pair->public_key);

    if (private_key_size > INT_MAX || public_key_size > INT_MAX) {
        return NULL;
    }
    byte_array_class = (*environment)->FindClass(environment, "[B");
    if (byte_array_class == NULL) {
        return NULL;
    }
    output = (*environment)->NewObjectArray(environment,
                                            2,
                                            byte_array_class,
                                            NULL);
    if (output == NULL) {
        goto cleanup;
    }
    private_key =
        (*environment)->NewByteArray(environment, (jsize)private_key_size);
    public_key =
        (*environment)->NewByteArray(environment, (jsize)public_key_size);
    if (private_key == NULL || public_key == NULL) {
        output = NULL;
        goto cleanup;
    }
    (*environment)->SetByteArrayRegion(environment,
                                      private_key,
                                      0,
                                      (jsize)private_key_size,
                                      (const jbyte *)key_pair->private_key);
    if (!(*environment)->ExceptionCheck(environment)) {
        (*environment)->SetByteArrayRegion(environment,
                                          public_key,
                                          0,
                                          (jsize)public_key_size,
                                          (const jbyte *)key_pair->public_key);
    }
    if (!(*environment)->ExceptionCheck(environment)) {
        (*environment)->SetObjectArrayElement(environment,
                                              output,
                                              0,
                                              private_key);
        (*environment)->SetObjectArrayElement(environment,
                                              output,
                                              1,
                                              public_key);
    }
    if ((*environment)->ExceptionCheck(environment)) {
        output = NULL;
    }

cleanup:
    if (private_key != NULL) {
        (*environment)->DeleteLocalRef(environment, private_key);
    }
    if (public_key != NULL) {
        (*environment)->DeleteLocalRef(environment, public_key);
    }
    (*environment)->DeleteLocalRef(environment, byte_array_class);
    return output;
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

JNIEXPORT jobjectArray JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeExecute(
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
    jbyteArray command,
    jbyteArray standard_input,
    jint connect_timeout_ms,
    jint command_timeout_ms,
    jint max_output_bytes,
    jobject output_listener,
    jintArray exit_status,
    jintArray error_code,
    jobjectArray error_strings)
{
    pi_ssh_jni_bytes values[8];
    pi_ssh_command_config config;
    pi_ssh_command_result result;
    pi_ssh_error error;
    pi_ssh_jni_output_context output_context;
    jobjectArray output = NULL;
    jclass output_listener_class = NULL;
    bool copied;
    size_t index;

    (void)receiver;
    memset(values, 0, sizeof(values));
    memset(&output_context, 0, sizeof(output_context));
    pi_ssh_command_result_init(&result);
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
             pi_ssh_jni_copy_bytes(environment, command, true, &values[6]) &&
             pi_ssh_jni_copy_bytes(environment,
                                   standard_input,
                                   false,
                                   &values[7]);
    if (!copied) {
        goto cleanup;
    }

    pi_ssh_command_config_init(&config);
    config.ssh_host = values[0].value;
    config.ssh_port = (uint16_t)ssh_port;
    config.username = values[1].value;
    config.auth_type = (int32_t)auth_type;
    config.password = values[2].value;
    config.private_key = values[3].value;
    config.private_key_passphrase = values[4].value;
    config.expected_host_key_sha256 = values[5].value;
    config.command = values[6].value;
    config.stdin_data = (const uint8_t *)values[7].value;
    config.stdin_size = values[7].length;
    config.connect_timeout_ms = connect_timeout_ms > 0
                                    ? (uint32_t)connect_timeout_ms
                                    : PI_SSH_DEFAULT_CONNECT_TIMEOUT_MS;
    config.command_timeout_ms = command_timeout_ms > 0
                                    ? (uint32_t)command_timeout_ms
                                    : PI_SSH_DEFAULT_COMMAND_TIMEOUT_MS;
    config.max_output_bytes = max_output_bytes > 0
                                  ? (size_t)max_output_bytes
                                  : PI_SSH_DEFAULT_MAX_OUTPUT_BYTES;

    if (output_listener != NULL) {
        output_listener_class =
            (*environment)->GetObjectClass(environment, output_listener);
        if (output_listener_class == NULL) {
            goto cleanup;
        }
        output_context.method =
            (*environment)->GetMethodID(environment,
                                        output_listener_class,
                                        "onOutput",
                                        "(I[B)V");
        (*environment)->DeleteLocalRef(environment, output_listener_class);
        output_listener_class = NULL;
        if (output_context.method == NULL) {
            goto cleanup;
        }
        output_context.environment = environment;
        output_context.listener = output_listener;
    }

    if (pi_ssh_command_execute_streaming(
            &config,
            &result,
            output_listener == NULL ? NULL : pi_ssh_jni_forward_command_output,
            output_listener == NULL ? NULL : &output_context,
            &error) != 0) {
        if (!(*environment)->ExceptionCheck(environment)) {
            pi_ssh_jni_write_error(environment,
                                   &error,
                                   error_code,
                                   error_strings);
        }
        goto cleanup;
    }
    if (exit_status != NULL &&
        (*environment)->GetArrayLength(environment, exit_status) > 0) {
        jint status = (jint)result.exit_status;
        (*environment)->SetIntArrayRegion(environment,
                                          exit_status,
                                          0,
                                          1,
                                          &status);
    }
    if (!(*environment)->ExceptionCheck(environment)) {
        output = pi_ssh_jni_command_output(environment, &result);
    }

cleanup:
    if (output_listener_class != NULL) {
        (*environment)->DeleteLocalRef(environment, output_listener_class);
    }
    pi_ssh_command_result_free(&result);
    for (index = 0; index < sizeof(values) / sizeof(values[0]); ++index) {
        pi_ssh_jni_free_bytes(&values[index]);
    }
    return output;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeGenerateEd25519KeyPair(
    JNIEnv *environment,
    jobject receiver,
    jbyteArray passphrase,
    jintArray error_code,
    jobjectArray error_strings)
{
    pi_ssh_jni_bytes passphrase_value;
    pi_ssh_key_pair key_pair;
    pi_ssh_error error;
    jobjectArray output = NULL;

    (void)receiver;
    memset(&passphrase_value, 0, sizeof(passphrase_value));
    pi_ssh_key_pair_init(&key_pair);
    if (!pi_ssh_jni_copy_bytes(environment,
                               passphrase,
                               false,
                               &passphrase_value)) {
        goto cleanup;
    }
    if (pi_ssh_key_pair_generate_ed25519(passphrase_value.value,
                                         &key_pair,
                                         &error) != 0) {
        pi_ssh_jni_write_error(environment,
                               &error,
                               error_code,
                               error_strings);
        goto cleanup;
    }
    output = pi_ssh_jni_key_pair_output(environment, &key_pair);

cleanup:
    pi_ssh_key_pair_free(&key_pair);
    pi_ssh_jni_free_bytes(&passphrase_value);
    return output;
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
