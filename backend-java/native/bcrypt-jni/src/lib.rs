// pyca/bcrypt 4.3.0 checkpw 适配序列（Apache-2.0，src/_bcrypt/src/lib.rs）的 JNI 移植。
// 密码学计算全部委托 bcrypt 0.17.0（MIT），此处不实现任何密码算法。
use base64::{alphabet::BCRYPT, engine::general_purpose::NO_PAD, engine::GeneralPurpose, Engine};
use jni::errors::ErrorPolicy;
use jni::objects::{JByteArray, JClass};
use jni::sys::jint;
use jni::{Env, EnvUnowned};
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, Zeroizing};

/// 与 Java NativeBcrypt.ABI_VERSION 对应；JNI 签名或返回码语义变化时递增。
const ABI_VERSION: jint = 1;

// 返回码：1 匹配、0 不匹配、-1 输入/哈希格式错误、-2 JNI 错误、-3 panic、-4 内部错误。
// 任何负值都不得被调用方当作口令通过。
enum NativeError {
    InvalidInput,
    Jni,
}

impl From<jni::errors::Error> for NativeError {
    fn from(_: jni::errors::Error) -> Self {
        Self::Jni
    }
}

struct CodeOnly;

impl ErrorPolicy<jint, NativeError> for CodeOnly {
    type Captures<'local: 'method, 'method> = ();

    fn on_error<'local: 'method, 'method>(
        env: &mut Env<'local>,
        _: &mut (),
        error: NativeError,
    ) -> jni::errors::Result<jint> {
        if env.exception_check() {
            env.exception_clear();
        }
        Ok(match error {
            NativeError::InvalidInput => -1,
            NativeError::Jni => -2,
        })
    }

    fn on_panic<'local: 'method, 'method>(
        env: &mut Env<'local>,
        _: &mut (),
        _: Box<dyn std::any::Any + Send + 'static>,
    ) -> jni::errors::Result<jint> {
        if env.exception_check() {
            env.exception_clear();
        }
        Ok(-3)
    }

    fn on_internal_jni_error<'local: 'method, 'method>(_: &mut (), _: jni::errors::Error) -> jint {
        -4
    }

    fn on_internal_panic<'local: 'method, 'method>(
        _: &mut (),
        _: Box<dyn std::any::Any + Send + 'static>,
    ) -> jint {
        -4
    }
}

/// 复制 Java byte[] 到 Rust 堆（可选截断）；昂贵的哈希开始前已结束 JNI 访问，不持有 critical 数组。
fn copy_region(env: &Env<'_>, input: &JByteArray<'_>, limit: Option<usize>)
    -> Result<Zeroizing<Vec<u8>>, NativeError> {
    let len = input.len(env)?;
    let count = limit.map_or(len, |max| len.min(max));
    let mut signed = Zeroizing::new(vec![0i8; count]);
    input.get_region(env, 0, &mut signed)?;
    let bytes = Zeroizing::new(signed.iter().map(|b| *b as u8).collect());
    signed.zeroize();
    Ok(bytes)
}

/// pyca/bcrypt 4.3.0 checkpw：口令按原始字节截前 72，解析前缀/cost/盐后重算，
/// 再对完整编码串做常数时间比较（不走 bcrypt::verify 的尾部比较捷径）。
fn pyca_430_checkpw(password: &[u8], encoded: &[u8]) -> Result<jint, NativeError> {
    let password = &password[..password.len().min(72)];
    let parts: Vec<&[u8]> = encoded.split(|b| *b == b'$').filter(|p| !p.is_empty()).collect();
    if parts.len() != 3 {
        return Err(NativeError::InvalidInput);
    }
    let version = match parts[0] {
        b"2a" => bcrypt::Version::TwoA,
        b"2b" => bcrypt::Version::TwoB,
        b"2y" => bcrypt::Version::TwoY,
        b"2x" => bcrypt::Version::TwoX,
        _ => return Err(NativeError::InvalidInput),
    };
    let cost = std::str::from_utf8(parts[1])
        .map_err(|_| NativeError::InvalidInput)?
        .parse::<u32>()
        .map_err(|_| NativeError::InvalidInput)?;
    if parts[2].len() < 22 {
        return Err(NativeError::InvalidInput);
    }
    let codec = GeneralPurpose::new(&BCRYPT, NO_PAD);
    let salt: [u8; 16] = codec
        .decode(&parts[2][..22])
        .map_err(|_| NativeError::InvalidInput)?
        .try_into()
        .map_err(|_| NativeError::InvalidInput)?;
    let computed = bcrypt::hash_with_salt(password, cost, salt).map_err(|_| NativeError::InvalidInput)?;
    let canonical = computed.format_for_version(version);
    Ok(if bool::from(canonical.as_bytes().ct_eq(encoded)) { 1 } else { 0 })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_dpdk_assistant_auth_NativeBcrypt_abiVersion0<'local>(
    _raw: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jint {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_dpdk_assistant_auth_NativeBcrypt_checkpw0<'local>(
    mut raw: EnvUnowned<'local>,
    _class: JClass<'local>,
    password: JByteArray<'local>,
    hash: JByteArray<'local>,
) -> jint {
    raw.with_env(|env| -> Result<jint, NativeError> {
        let password = copy_region(env, &password, Some(72))?;
        let hash = copy_region(env, &hash, None)?;
        pyca_430_checkpw(&password, &hash)
    })
    .resolve::<CodeOnly>()
}
