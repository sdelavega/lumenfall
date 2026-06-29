use serde::Deserialize;

use crate::Result;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WallpaperKind {
    Web,
    Video,
    Image,
    Unsupported,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LivelyInfo {
    pub title: String,
    pub author: Option<String>,
    pub kind: WallpaperKind,
    pub file_name: Option<String>,
    pub preview_name: Option<String>,
}

pub struct LivelyInfoParser;

impl LivelyInfoParser {
    pub fn parse(text: &str) -> Result<LivelyInfo> {
        let raw: RawLivelyInfo = serde_json::from_str(text)?;
        Ok(LivelyInfo {
            title: raw.title.unwrap_or_else(|| "Untitled".to_string()),
            author: raw.author,
            kind: map_kind(raw.wallpaper_type.as_ref()),
            file_name: raw.file_name,
            preview_name: raw.preview,
        })
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct RawLivelyInfo {
    #[serde(alias = "title")]
    title: Option<String>,
    #[serde(alias = "author")]
    author: Option<String>,
    #[serde(rename = "Type", alias = "type")]
    wallpaper_type: Option<RawType>,
    #[serde(alias = "fileName")]
    file_name: Option<String>,
    #[serde(alias = "preview")]
    preview: Option<String>,
}

/// Lively serializes `Type` either as the `WallpaperType` enum *integer*
/// (the common case exported by the desktop app, since the enum is written as
/// a number by default) or, in some hand-authored bundles, as a string.
/// Accept both shapes instead of erroring on the integer form.
#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum RawType {
    Code(i64),
    Name(String),
}

fn map_kind(value: Option<&RawType>) -> WallpaperKind {
    match value {
        Some(RawType::Code(code)) => map_kind_code(*code),
        Some(RawType::Name(name)) => map_kind_name(name),
        None => WallpaperKind::Unsupported,
    }
}

/// Mapping follows Lively's `WallpaperType` enum order:
/// 0 app, 1 web, 2 webaudio, 3 url, 4 bizhawk, 5 unity, 6 godot,
/// 7 video, 8 gif, 9 unityaudio, 10 videostream, 11 picture.
fn map_kind_code(code: i64) -> WallpaperKind {
    match code {
        1 | 2 | 3 => WallpaperKind::Web, // web, webaudio, url (all WebView-rendered)
        7 | 10 => WallpaperKind::Video,  // video, videostream
        8 | 11 => WallpaperKind::Image,  // gif, picture
        _ => WallpaperKind::Unsupported, // app, bizhawk, unity, godot, unityaudio
    }
}

fn map_kind_name(name: &str) -> WallpaperKind {
    let normalized = name.trim().to_ascii_lowercase();
    // Tolerate stringified codes like "7" that some exporters emit.
    if let Ok(code) = normalized.parse::<i64>() {
        return map_kind_code(code);
    }
    match normalized.as_str() {
        "web" | "webpage" | "webaudio" | "url" => WallpaperKind::Web,
        "video" | "videostream" => WallpaperKind::Video,
        "gif" | "picture" | "image" => WallpaperKind::Image,
        _ => WallpaperKind::Unsupported,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pascal_case_lively_info() {
        let info = LivelyInfoParser::parse(
            r#"{
                "Title": "Matrix Rain",
                "Author": "Stephen",
                "Type": "web",
                "FileName": "index.html",
                "Preview": "preview.gif"
            }"#,
        )
        .unwrap();

        assert_eq!(info.title, "Matrix Rain");
        assert_eq!(info.author.as_deref(), Some("Stephen"));
        assert_eq!(info.kind, WallpaperKind::Web);
        assert_eq!(info.file_name.as_deref(), Some("index.html"));
        assert_eq!(info.preview_name.as_deref(), Some("preview.gif"));
    }

    #[test]
    fn defaults_unknown_type_to_unsupported() {
        let info = LivelyInfoParser::parse(r#"{"Title":"Oddity","Type":"shader"}"#).unwrap();

        assert_eq!(info.title, "Oddity");
        assert_eq!(info.kind, WallpaperKind::Unsupported);
    }

    #[test]
    fn parses_integer_enum_type() {
        // Lively's WallpaperType: 1 == web, 7 == video, 11 == picture.
        let web = LivelyInfoParser::parse(r#"{"Title":"W","Type":1}"#).unwrap();
        assert_eq!(web.kind, WallpaperKind::Web);

        let video = LivelyInfoParser::parse(r#"{"Title":"V","Type":7}"#).unwrap();
        assert_eq!(video.kind, WallpaperKind::Video);

        let picture = LivelyInfoParser::parse(r#"{"Title":"P","Type":11}"#).unwrap();
        assert_eq!(picture.kind, WallpaperKind::Image);
    }

    #[test]
    fn unknown_integer_enum_is_unsupported() {
        // 5 == unity, which has no Android renderer.
        let info = LivelyInfoParser::parse(r#"{"Title":"U","Type":5}"#).unwrap();
        assert_eq!(info.kind, WallpaperKind::Unsupported);
    }

    #[test]
    fn parses_stringified_code() {
        // "8" == gif → Image.
        let info = LivelyInfoParser::parse(r#"{"Title":"G","Type":"8"}"#).unwrap();
        assert_eq!(info.kind, WallpaperKind::Image);
    }
}
