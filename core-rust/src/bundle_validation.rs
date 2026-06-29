use std::collections::BTreeSet;
use std::path::{Component, Path};

use serde::{Deserialize, Serialize};

use crate::{LivelyInfo, LivelyInfoParser, Result, WallpaperKind};

const MAX_FILES: usize = 2_000;
const MAX_TOTAL_BYTES: u64 = 512 * 1024 * 1024;
const MAX_SINGLE_FILE_BYTES: u64 = 256 * 1024 * 1024;

const WEB_ENTRIES: &[&str] = &["index.html", "index.htm"];
const VIDEO_EXTENSIONS: &[&str] = &["mp4", "mov", "m4v", "webm", "mkv"];
const IMAGE_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "webp", "gif", "bmp"];
const PREVIEW_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "webp", "gif"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BundleValidation {
    pub info: LivelyInfo,
    pub kind: WallpaperKind,
    pub entry_path: String,
    pub preview_path: Option<String>,
    pub warnings: Vec<String>,
}

#[derive(Debug, Deserialize)]
pub struct BundleFile {
    pub path: String,
    #[serde(default)]
    pub size: u64,
}

pub struct BundleValidator;

impl BundleValidator {
    pub fn validate(
        info_json: &str,
        base_path: &str,
        files_json: &str,
    ) -> Result<BundleValidation> {
        let info = LivelyInfoParser::parse(info_json)?;
        let files: Vec<BundleFile> = serde_json::from_str(files_json)?;
        Ok(validate(info, base_path, files))
    }
}

fn validate(info: LivelyInfo, base_path: &str, files: Vec<BundleFile>) -> BundleValidation {
    let mut errors = Vec::new();
    let mut warnings = Vec::new();

    if files.len() > MAX_FILES {
        errors.push(format!(
            "Bundle has too many files: {} > {}",
            files.len(),
            MAX_FILES
        ));
    }

    let mut total_bytes = 0u64;
    let mut normalized = BTreeSet::new();
    for file in files {
        total_bytes = total_bytes.saturating_add(file.size);
        if file.size > MAX_SINGLE_FILE_BYTES {
            errors.push(format!("File is too large: {}", file.path));
        }
        match normalize_path(&file.path) {
            Some(path) => {
                normalized.insert(path);
            }
            None => errors.push(format!("Unsafe path in bundle: {}", file.path)),
        }
    }

    if total_bytes > MAX_TOTAL_BYTES {
        errors.push(format!(
            "Bundle is too large: {} bytes > {} bytes",
            total_bytes, MAX_TOTAL_BYTES
        ));
    }

    let base = match normalize_base_path(base_path) {
        Some(path) => path,
        None => {
            errors.push(format!("Unsafe metadata base path: {base_path}"));
            String::new()
        }
    };

    let kind = info.kind;
    if kind == WallpaperKind::Unsupported {
        errors.push("Unsupported Lively wallpaper type".to_string());
    }

    let entry_path = match resolve_entry(&info, &base, &normalized) {
        Some(path) => path,
        None => {
            errors.push("No supported entry file found for bundle type".to_string());
            String::new()
        }
    };

    let preview_path = info
        .preview_name
        .as_deref()
        .and_then(|name| resolve_declared_file(&base, name, &normalized))
        .filter(|path| has_extension(path, PREVIEW_EXTENSIONS));

    if info.preview_name.is_some() && preview_path.is_none() {
        warnings.push("Declared preview is missing or has an unsupported extension".to_string());
    }

    BundleValidation {
        info,
        kind,
        entry_path,
        preview_path,
        warnings: if errors.is_empty() { warnings } else { errors },
    }
}

fn resolve_entry(info: &LivelyInfo, base_path: &str, files: &BTreeSet<String>) -> Option<String> {
    if let Some(name) = info.file_name.as_deref() {
        return resolve_declared_file(base_path, name, files)
            .filter(|path| kind_supports_path(info.kind, path));
    }

    let candidates = files.iter().filter(|path| {
        path.starts_with(base_path)
            && !file_stem(path)
                .map(|stem| stem.to_ascii_lowercase().contains("preview"))
                .unwrap_or(false)
    });

    match info.kind {
        WallpaperKind::Web => candidates
            .filter(|path| {
                WEB_ENTRIES
                    .iter()
                    .any(|entry| file_name(path).eq_ignore_ascii_case(entry))
            })
            .min_by_key(|path| path_depth(path))
            .cloned(),
        WallpaperKind::Video => candidates
            .filter(|path| has_extension(path, VIDEO_EXTENSIONS))
            .min_by_key(|path| path_depth(path))
            .cloned(),
        WallpaperKind::Image => candidates
            .filter(|path| has_extension(path, IMAGE_EXTENSIONS))
            .min_by_key(|path| path_depth(path))
            .cloned(),
        WallpaperKind::Unsupported => None,
    }
}

fn resolve_declared_file(
    base_path: &str,
    declared: &str,
    files: &BTreeSet<String>,
) -> Option<String> {
    let joined = if base_path.is_empty() {
        declared.to_string()
    } else {
        format!("{base_path}/{declared}")
    };
    let normalized = normalize_path(&joined)?;
    files.contains(&normalized).then_some(normalized)
}

fn kind_supports_path(kind: WallpaperKind, path: &str) -> bool {
    match kind {
        WallpaperKind::Web => WEB_ENTRIES
            .iter()
            .any(|entry| file_name(path).eq_ignore_ascii_case(entry)),
        WallpaperKind::Video => has_extension(path, VIDEO_EXTENSIONS),
        WallpaperKind::Image => has_extension(path, IMAGE_EXTENSIONS),
        WallpaperKind::Unsupported => false,
    }
}

fn normalize_base_path(path: &str) -> Option<String> {
    if path.is_empty() || path == "." {
        return Some(String::new());
    }
    normalize_path(path)
}

fn normalize_path(path: &str) -> Option<String> {
    if path.is_empty() || path.starts_with('/') || path.starts_with('\\') {
        return None;
    }

    let mut parts = Vec::new();
    for component in Path::new(path).components() {
        match component {
            Component::Normal(part) => {
                let text = part.to_string_lossy();
                if text.is_empty() {
                    return None;
                }
                parts.push(text.to_string());
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => return None,
        }
    }

    if parts.is_empty() {
        None
    } else {
        Some(parts.join("/"))
    }
}

fn has_extension(path: &str, extensions: &[&str]) -> bool {
    let extension = Path::new(path)
        .extension()
        .map(|value| value.to_string_lossy().to_ascii_lowercase());
    extension
        .as_deref()
        .map(|ext| extensions.iter().any(|allowed| ext == *allowed))
        .unwrap_or(false)
}

fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn file_stem(path: &str) -> Option<&str> {
    let name = file_name(path);
    name.rsplit_once('.').map(|(stem, _)| stem)
}

fn path_depth(path: &str) -> usize {
    path.bytes().filter(|byte| *byte == b'/').count()
}

#[derive(Debug, Serialize)]
pub struct BundleValidationResponse {
    pub ok: bool,
    pub error: Option<String>,
    pub title: String,
    pub author: Option<String>,
    pub kind: &'static str,
    pub entry_path: Option<String>,
    pub preview_path: Option<String>,
    pub warnings: Vec<String>,
}

impl From<BundleValidation> for BundleValidationResponse {
    fn from(value: BundleValidation) -> Self {
        let ok = !value.entry_path.is_empty() && value.kind != WallpaperKind::Unsupported;
        let error = if ok {
            None
        } else {
            Some(value.warnings.join("; "))
        };
        Self {
            ok,
            error,
            title: value.info.title,
            author: value.info.author,
            kind: kind_name(value.kind),
            entry_path: ok.then_some(value.entry_path),
            preview_path: value.preview_path,
            warnings: if ok { value.warnings } else { Vec::new() },
        }
    }
}

pub fn kind_name(kind: WallpaperKind) -> &'static str {
    match kind {
        WallpaperKind::Web => "web",
        WallpaperKind::Video => "video",
        WallpaperKind::Image => "image",
        WallpaperKind::Unsupported => "unsupported",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn files(paths: &[&str]) -> String {
        serde_json::to_string(
            &paths
                .iter()
                .map(|path| serde_json::json!({ "path": path, "size": 42 }))
                .collect::<Vec<_>>(),
        )
        .unwrap()
    }

    fn validate_ok(info: &str, base: &str, paths: &[&str]) -> BundleValidationResponse {
        BundleValidationResponse::from(
            BundleValidator::validate(info, base, &files(paths)).unwrap(),
        )
    }

    #[test]
    fn accepts_declared_web_entry_and_preview() {
        let response = validate_ok(
            r#"{"Title":"Canvas","Type":1,"FileName":"index.html","Preview":"preview.gif"}"#,
            "",
            &["LivelyInfo.json", "index.html", "preview.gif"],
        );

        assert!(response.ok);
        assert_eq!(response.kind, "web");
        assert_eq!(response.entry_path.as_deref(), Some("index.html"));
        assert_eq!(response.preview_path.as_deref(), Some("preview.gif"));
    }

    #[test]
    fn infers_nested_video_entry_when_filename_is_missing() {
        let response = validate_ok(
            r#"{"Title":"Loop","Type":7}"#,
            "bundle",
            &[
                "bundle/LivelyInfo.json",
                "bundle/preview.jpg",
                "bundle/loop.mp4",
            ],
        );

        assert!(response.ok);
        assert_eq!(response.kind, "video");
        assert_eq!(response.entry_path.as_deref(), Some("bundle/loop.mp4"));
    }

    #[test]
    fn accepts_animated_image_entry() {
        let response = validate_ok(
            r#"{"Title":"Pulse","Type":"8","FileName":"pulse.webp"}"#,
            "",
            &["LivelyInfo.json", "pulse.webp"],
        );

        assert!(response.ok);
        assert_eq!(response.kind, "image");
        assert_eq!(response.entry_path.as_deref(), Some("pulse.webp"));
    }

    #[test]
    fn rejects_declared_path_traversal() {
        let response = validate_ok(
            r#"{"Title":"Bad","Type":1,"FileName":"../index.html"}"#,
            "bundle",
            &["bundle/LivelyInfo.json", "bundle/index.html"],
        );

        assert!(!response.ok);
        assert!(response.error.unwrap().contains("No supported entry"));
    }

    #[test]
    fn rejects_unsupported_lively_type() {
        let response = validate_ok(
            r#"{"Title":"Unity","Type":5,"FileName":"main.exe"}"#,
            "",
            &["LivelyInfo.json", "main.exe"],
        );

        assert!(!response.ok);
        assert!(response.error.unwrap().contains("Unsupported"));
    }

    #[test]
    fn rejects_missing_entry() {
        let response = validate_ok(
            r#"{"Title":"Missing","Type":1,"FileName":"index.html"}"#,
            "",
            &["LivelyInfo.json"],
        );

        assert!(!response.ok);
        assert!(response.error.unwrap().contains("No supported entry"));
    }
}
