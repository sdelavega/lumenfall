mod bundle_screening;
mod bundle_validation;
mod lively_info;
mod lively_properties;

pub use bundle_screening::{BundleScreener, Classification, ScreeningReport, ScreeningResponse};
pub use bundle_validation::{BundleValidation, BundleValidationResponse, BundleValidator};
pub use lively_info::{LivelyInfo, LivelyInfoParser, WallpaperKind};
pub use lively_properties::{LivelyPropertiesParser, WallpaperProperty};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use serde::Serialize;

#[derive(Debug, thiserror::Error)]
pub enum LumenfallError {
    #[error("invalid JSON: {0}")]
    InvalidJson(#[from] serde_json::Error),
}

pub type Result<T> = std::result::Result<T, LumenfallError>;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sdelavega_lumenfall_core_LumenfallCore_parseLivelyInfo(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let output = env
        .get_string(&input)
        .map(|text| parse_info_json(text.to_string_lossy().as_ref()))
        .unwrap_or_else(|error| error_json(error.to_string()));

    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sdelavega_lumenfall_core_LumenfallCore_parseLivelyProperties(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let output = env
        .get_string(&input)
        .map(|text| parse_properties_json(text.to_string_lossy().as_ref()))
        .unwrap_or_else(|error| error_json(error.to_string()));

    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sdelavega_lumenfall_core_LumenfallCore_validateBundle(
    mut env: JNIEnv,
    _class: JClass,
    info_json: JString,
    base_path: JString,
    files_json: JString,
) -> jstring {
    let output = env
        .get_string(&info_json)
        .and_then(|info| {
            env.get_string(&base_path).and_then(|base| {
                env.get_string(&files_json).map(|files| {
                    validate_bundle_json(
                        info.to_string_lossy().as_ref(),
                        base.to_string_lossy().as_ref(),
                        files.to_string_lossy().as_ref(),
                    )
                })
            })
        })
        .unwrap_or_else(|error| error_json(error.to_string()));

    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sdelavega_lumenfall_core_LumenfallCore_screenBundle(
    mut env: JNIEnv,
    _class: JClass,
    script_source: JString,
) -> jstring {
    let output = env
        .get_string(&script_source)
        .map(|source| screen_bundle_json(source.to_string_lossy().as_ref()))
        .unwrap_or_else(|error| error_json(error.to_string()));

    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn screen_bundle_json(script_source: &str) -> String {
    serde_json::to_string(&ScreeningResponse::from(BundleScreener::screen(
        script_source,
    )))
    .unwrap_or_else(error_json)
}

fn parse_info_json(text: &str) -> String {
    match LivelyInfoParser::parse(text) {
        Ok(info) => serde_json::to_string(&InfoResponse::from(info)).unwrap_or_else(error_json),
        Err(error) => error_json(error.to_string()),
    }
}

fn parse_properties_json(text: &str) -> String {
    match LivelyPropertiesParser::parse(text) {
        Ok(properties) => serde_json::to_string(&PropertiesResponse {
            ok: true,
            error: None,
            properties: properties.into_iter().map(PropertyResponse::from).collect(),
        })
        .unwrap_or_else(error_json),
        Err(error) => error_json(error.to_string()),
    }
}

fn validate_bundle_json(info_json: &str, base_path: &str, files_json: &str) -> String {
    match BundleValidator::validate(info_json, base_path, files_json) {
        Ok(validation) => serde_json::to_string(&BundleValidationResponse::from(validation))
            .unwrap_or_else(error_json),
        Err(error) => error_json(error.to_string()),
    }
}

fn error_json(error: impl ToString) -> String {
    serde_json::json!({
        "ok": false,
        "error": error.to_string()
    })
    .to_string()
}

#[derive(Debug, Serialize)]
struct InfoResponse {
    ok: bool,
    error: Option<String>,
    title: String,
    author: Option<String>,
    description: Option<String>,
    license: Option<String>,
    homepage: Option<String>,
    tags: Vec<String>,
    release_date: Option<String>,
    version: Option<String>,
    kind: &'static str,
    file_name: Option<String>,
    preview_name: Option<String>,
}

impl From<LivelyInfo> for InfoResponse {
    fn from(value: LivelyInfo) -> Self {
        Self {
            ok: true,
            error: None,
            title: value.title,
            author: value.author,
            description: value.description,
            license: value.license,
            homepage: value.homepage,
            tags: value.tags,
            release_date: value.release_date,
            version: value.version,
            kind: match value.kind {
                WallpaperKind::Web => "web",
                WallpaperKind::Video => "video",
                WallpaperKind::Image => "image",
                WallpaperKind::Unsupported => "unsupported",
            },
            file_name: value.file_name,
            preview_name: value.preview_name,
        }
    }
}

#[derive(Debug, Serialize)]
struct PropertiesResponse {
    ok: bool,
    error: Option<String>,
    properties: Vec<PropertyResponse>,
}

#[derive(Debug, Serialize)]
struct PropertyResponse {
    key: String,
    display_name: String,
    property_type: String,
    default_value: Option<serde_json::Value>,
    raw: serde_json::Value,
}

impl From<WallpaperProperty> for PropertyResponse {
    fn from(value: WallpaperProperty) -> Self {
        Self {
            key: value.key,
            display_name: value.display_name,
            property_type: value.property_type,
            default_value: value.default_value,
            raw: value.raw,
        }
    }
}

#[cfg(test)]
mod jni_names {
    #[test]
    fn exports_match_package() {
        let source = include_str!("lib.rs");
        // Built from pieces so this test does not match itself.
        let stale = ["Java_com_", "aetherdesk"].concat();
        assert!(!source.contains(&stale), "stale JNI package in lib.rs");
        assert!(source.contains("Java_com_sdelavega_lumenfall_core_LumenfallCore_parseLivelyInfo"));
    }
}
