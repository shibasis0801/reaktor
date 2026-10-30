#include <jni.h>
#include <rsec/rsec.h>
#include <openssl/mem.h>
#include <array>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
struct NativeFailure { int status; };
void status(rsec_status_t value) { if (value != RSEC_OK) throw NativeFailure{value}; }

template<class T, class F> T guarded(JNIEnv* env, F call) {
    try { return call(); }
    catch (NativeFailure failure) {
        if (!env->ExceptionCheck()) {
            auto type = env->FindClass("dev/shibasis/reaktor/security/NativeSecurityException");
            if (type) {
                auto constructor = env->GetMethodID(type, "<init>", "(I)V");
                if (constructor) {
                    auto error = env->NewObject(type, constructor, static_cast<jint>(failure.status));
                    if (error) { env->Throw(static_cast<jthrowable>(error)); env->DeleteLocalRef(error); }
                }
                env->DeleteLocalRef(type);
            }
        }
    } catch (...) {
        if (!env->ExceptionCheck()) {
            auto type = env->FindClass("java/lang/IllegalStateException");
            if (type) { env->ThrowNew(type, "Native security binding failed"); env->DeleteLocalRef(type); }
        }
    }
    return T{};
}

struct Input {
    std::vector<uint8_t> value;
    Input(JNIEnv* env, jbyteArray bytes) {
        if (!bytes) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        auto length = env->GetArrayLength(bytes);
        if (length < 0 || length > 64 * 1024 * 1024 + 4096) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        value.resize(static_cast<size_t>(length));
        if (length) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(value.data()));
        if (env->ExceptionCheck()) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
    }
    Input(Input&&) = default;
    Input(const Input&) = delete;
    ~Input() { if (!value.empty()) OPENSSL_cleanse(value.data(), value.size()); }
    uint8_t* data() { return value.data(); }
    size_t size() const { return value.size(); }
    rsec_buffer_t buffer() { return {data(), size()}; }
};

struct Inputs {
    std::vector<Input> storage;
    std::vector<rsec_buffer_t> buffers;
    Inputs(JNIEnv* env, jobjectArray values, size_t maximum = 4095) {
        if (!values) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        auto count = env->GetArrayLength(values);
        if (count < 0 || static_cast<size_t>(count) > maximum) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        storage.reserve(count); buffers.reserve(count);
        for (jsize index = 0; index < count; ++index) {
            auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(values, index));
            if (env->ExceptionCheck() || !value) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
            try { storage.emplace_back(env, value); }
            catch (...) { env->DeleteLocalRef(value); throw; }
            env->DeleteLocalRef(value);
            buffers.push_back(storage.back().buffer());
        }
    }
};

struct Policy {
    Input tenant;
    Input app;
    Inputs ids;
    Inputs keys;
    std::vector<rsec_auth_key_t> auth;
    rsec_credential_policy_t value{};
    Policy(JNIEnv* env, jbyteArray tenantId, jbyteArray appId, jobjectArray keyIds, jobjectArray publicKeys)
        : tenant(env, tenantId), app(env, appId), ids(env, keyIds, 8), keys(env, publicKeys, 8) {
        if (ids.buffers.empty() || ids.buffers.size() != keys.buffers.size()) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        auth.reserve(ids.buffers.size());
        for (size_t index = 0; index < ids.buffers.size(); ++index) auth.push_back({ids.buffers[index], keys.buffers[index]});
        value = {tenant.buffer(), app.buffer(), auth.data(), auth.size()};
    }
};

struct Buffer {
    rsec_buffer_t value{};
    ~Buffer() { rsec_buffer_free(value); }
};
struct Commit {
    rsec_commit_t value{};
    ~Commit() { rsec_commit_free(value); }
};
struct Sealed {
    rsec_sealed_content_t value{};
    ~Sealed() { rsec_sealed_content_free(value); }
};
struct Opened {
    rsec_opened_content_t value{};
    ~Opened() { rsec_opened_content_free(value); }
};

template<class T> T* pointer(jlong handle) {
    if (!handle) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
    return reinterpret_cast<T*>(static_cast<uintptr_t>(handle));
}
jbyteArray copy(JNIEnv* env, rsec_buffer_t bytes) {
    if (bytes.len > 64 * 1024 * 1024 + 4096 || (bytes.len && !bytes.data)) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
    auto result = env->NewByteArray(static_cast<jsize>(bytes.len));
    if (!result) throw NativeFailure{RSEC_ERR_STORE};
    if (bytes.len) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.len), reinterpret_cast<const jbyte*>(bytes.data));
    if (env->ExceptionCheck()) { env->DeleteLocalRef(result); throw NativeFailure{RSEC_ERR_STORE}; }
    return result;
}
jobjectArray array(JNIEnv* env, std::initializer_list<rsec_buffer_t> values) {
    auto type = env->FindClass("[B");
    if (!type) throw NativeFailure{RSEC_ERR_STORE};
    auto result = env->NewObjectArray(static_cast<jsize>(values.size()), type, nullptr);
    env->DeleteLocalRef(type);
    if (!result) throw NativeFailure{RSEC_ERR_STORE};
    jsize index = 0;
    try {
        for (auto value : values) {
            auto bytes = copy(env, value);
            env->SetObjectArrayElement(result, index++, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) throw NativeFailure{RSEC_ERR_STORE};
        }
        return result;
    } catch (...) { env->DeleteLocalRef(result); throw; }
}
}

#define JNI_METHOD(name) Java_dev_shibasis_reaktor_security_ReaktorSecurityNative_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jobject, jbyteArray tenant, jbyteArray app,
    jobjectArray ids, jobjectArray keys, jint suite, jbyteArray snapshot) {
    return guarded<jlong>(env, [&] {
        if (rsec_abi_version() != 2) throw NativeFailure{RSEC_ERR_POLICY};
        Policy policy(env, tenant, app, ids, keys);
        rsec_engine_t* result = nullptr;
        if (snapshot) {
            Input state(env, snapshot);
            status(rsec_engine_restore_verified(&policy.value, state.data(), state.size(), &result));
        } else {
            if (suite < 1 || suite > std::numeric_limits<uint16_t>::max()) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
            status(rsec_engine_create_verified(&policy.value, static_cast<uint16_t>(suite), &result));
        }
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(result));
    });
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(keyRing)(JNIEnv* env, jobject, jbyteArray tenant, jbyteArray app,
    jobjectArray ids, jobjectArray keys, jbyteArray encoded) {
    return guarded<jlong>(env, [&] {
        Policy policy(env, tenant, app, ids, keys);
        Input bytes(env, encoded);
        rsec_key_ring_t* result = nullptr;
        status(rsec_key_ring_create(&policy.value, bytes.data(), bytes.size(), &result));
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(result));
    });
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv* env, jobject, jlong handle, jboolean ring) {
    guarded<int>(env, [&] {
        if (ring) rsec_key_ring_destroy(pointer<rsec_key_ring_t>(handle));
        else rsec_engine_destroy(pointer<rsec_engine_t>(handle));
        return 0;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(bytes)(JNIEnv* env, jobject, jlong handle, jint operation, jbyteArray id, jbyteArray argument) {
    return guarded<jbyteArray>(env, [&] {
        auto engine = pointer<rsec_engine_t>(handle);
        Input name(env, id), input(env, argument);
        Buffer out;
        switch (operation) {
            case 0: status(rsec_engine_snapshot(engine, &out.value)); break;
            case 1: status(rsec_engine_key_ring(engine, &out.value)); break;
            case 2: status(rsec_device_public_key(engine, &out.value)); break;
            case 3: status(rsec_generate_key_package(engine, &out.value)); break;
            case 4: status(rsec_last_resort_key_package(engine, &out.value)); break;
            case 5: status(rsec_device_proof(engine, name.data(), name.size(), input.data(), input.size(), &out.value)); break;
            default: throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        }
        return copy(env, out.value);
    });
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(action)(JNIEnv* env, jobject, jlong handle, jint operation, jbyteArray id, jbyteArray argument) {
    return guarded<jlong>(env, [&] {
        auto engine = pointer<rsec_engine_t>(handle);
        Input name(env, id), input(env, argument);
        uint64_t epoch = 0;
        uint8_t advanced = 0;
        switch (operation) {
            case 0: status(rsec_install_credential(engine, input.data(), input.size())); break;
            case 1: status(rsec_discard_pending(engine, name.data(), name.size())); break;
            case 2: status(rsec_epoch(engine, name.data(), name.size(), &epoch)); break;
            case 3: status(rsec_accept_pending(engine, name.data(), name.size(), input.data(), input.size(), &epoch)); break;
            case 4: status(rsec_join_group(engine, name.data(), name.size(), input.data(), input.size())); break;
            case 5: status(rsec_process_handshake(engine, name.data(), name.size(), input.data(), input.size(), &advanced)); epoch = advanced; break;
            default: throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        }
        if (epoch > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) throw NativeFailure{RSEC_ERR_POLICY};
        return static_cast<jlong>(epoch);
    });
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(members)(JNIEnv* env, jobject, jlong handle, jbyteArray id, jobjectArray users) {
    guarded<int>(env, [&] {
        Input name(env, id);
        Inputs input(env, users);
        status(rsec_set_members(pointer<rsec_engine_t>(handle), name.data(), name.size(), input.buffers.data(), input.buffers.size()));
        return 0;
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(roster)(JNIEnv* env, jobject, jlong handle, jbyteArray id) {
    return guarded<jobjectArray>(env, [&] {
        Input name(env, id);
        auto engine = pointer<rsec_engine_t>(handle);
        size_t count = 0;
        status(rsec_roster_count(engine, name.data(), name.size(), &count));
        if (count > 4096) throw NativeFailure{RSEC_ERR_POLICY};
        auto type = env->FindClass("[B");
        if (!type) throw NativeFailure{RSEC_ERR_STORE};
        auto result = env->NewObjectArray(static_cast<jsize>(count), type, nullptr);
        env->DeleteLocalRef(type);
        if (!result) throw NativeFailure{RSEC_ERR_STORE};
        try {
            for (size_t index = 0; index < count; ++index) {
                Buffer credential;
                status(rsec_roster_credential(engine, name.data(), name.size(), index, &credential.value));
                auto bytes = copy(env, credential.value);
                env->SetObjectArrayElement(result, static_cast<jsize>(index), bytes);
                env->DeleteLocalRef(bytes);
                if (env->ExceptionCheck()) throw NativeFailure{RSEC_ERR_STORE};
            }
            return result;
        } catch (...) { env->DeleteLocalRef(result); throw; }
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(prepare)(JNIEnv* env, jobject, jlong handle, jint operation,
    jbyteArray id, jobjectArray packages, jint index, jbyteArray info) {
    return guarded<jobjectArray>(env, [&] {
        auto engine = pointer<rsec_engine_t>(handle);
        Input name(env, id), groupInfo(env, info);
        Inputs input(env, packages);
        Commit out;
        switch (operation) {
            case 0: status(rsec_pending_commit(engine, name.data(), name.size(), &out.value)); break;
            case 1: status(rsec_prepare_create(engine, name.data(), name.size(), input.buffers.data(), input.buffers.size(), &out.value)); break;
            case 2: status(rsec_prepare_add(engine, name.data(), name.size(), input.buffers.data(), input.buffers.size(), &out.value)); break;
            case 3:
                if (index < 0) throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
                status(rsec_prepare_remove(engine, name.data(), name.size(), static_cast<uint32_t>(index), &out.value)); break;
            case 4: status(rsec_prepare_rotate(engine, name.data(), name.size(), &out.value)); break;
            case 5: status(rsec_prepare_external_join(engine, name.data(), name.size(), groupInfo.data(), groupInfo.size(), &out.value)); break;
            case 6: status(rsec_prepare_membership(engine, name.data(), name.size(), input.buffers.data(), input.buffers.size(), groupInfo.data(), groupInfo.size(), &out.value)); break;
            default: throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        }
        std::array<uint8_t, 8> epoch{};
        for (size_t i = 0; i < epoch.size(); ++i) epoch[7 - i] = static_cast<uint8_t>(out.value.epoch >> (i * 8));
        return array(env, {out.value.welcome, out.value.commit, out.value.group_info, {epoch.data(), epoch.size()}});
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(seal)(JNIEnv* env, jobject, jlong handle,
    jbyteArray id, jbyteArray envelope, jbyteArray plaintext, jboolean circle, jboolean anonymous) {
    return guarded<jobjectArray>(env, [&] {
        Input name(env, id), aad(env, envelope), content(env, plaintext);
        Sealed out;
        status(rsec_seal_content(pointer<rsec_engine_t>(handle), name.data(), name.size(), aad.data(), aad.size(),
            content.data(), content.size(), circle ? 1 : 0, anonymous ? 1 : 0, &out.value));
        return array(env, {out.value.header, out.value.commitment, out.value.ciphertext});
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(open)(JNIEnv* env, jobject, jlong handle, jboolean ring,
    jbyteArray id, jbyteArray envelope, jbyteArray header, jbyteArray commitment, jbyteArray ciphertext, jboolean circle) {
    return guarded<jobjectArray>(env, [&] {
        Input name(env, id), aad(env, envelope), head(env, header), tag(env, commitment), body(env, ciphertext);
        rsec_sealed_content_t content{head.buffer(), tag.buffer(), body.buffer()};
        Opened out;
        if (ring) status(rsec_key_ring_open(pointer<rsec_key_ring_t>(handle), name.data(), name.size(), aad.data(), aad.size(), &content, circle ? 1 : 0, &out.value));
        else status(rsec_open_content(pointer<rsec_engine_t>(handle), name.data(), name.size(), aad.data(), aad.size(), &content, circle ? 1 : 0, &out.value));
        std::array<uint8_t, 2> flags{out.value.authenticated, out.value.duplicate};
        return array(env, {out.value.plaintext, out.value.franking_key, out.value.sender_credential, {flags.data(), flags.size()}});
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(storage)(JNIEnv* env, jobject, jint operation, jbyteArray key, jbyteArray aad, jbyteArray input) {
    return guarded<jbyteArray>(env, [&] {
        Input secret(env, key), envelope(env, aad), content(env, input);
        Buffer out;
        switch (operation) {
            case 0: status(rsec_storage_key(&out.value)); break;
            case 1: status(rsec_storage_seal(secret.data(), secret.size(), envelope.data(), envelope.size(), content.data(), content.size(), &out.value)); break;
            case 2: status(rsec_storage_open(secret.data(), secret.size(), envelope.data(), envelope.size(), content.data(), content.size(), &out.value)); break;
            default: throw NativeFailure{RSEC_ERR_INVALID_ARGUMENT};
        }
        return copy(env, out.value);
    });
}
