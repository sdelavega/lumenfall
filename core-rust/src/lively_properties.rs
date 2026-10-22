// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
use serde_json::{Map, Value};

use crate::Result;

#[derive(Debug, Clone, PartialEq)]
pub struct WallpaperProperty {
    pub key: String,
    pub display_name: String,
    pub property_type: String,
    pub default_value: Option<Value>,
    pub raw: Value,
}

pub struct LivelyPropertiesParser;

impl LivelyPropertiesParser {
    pub fn parse(text: &str) -> Result<Vec<WallpaperProperty>> {
        let value: Value = serde_json::from_str(text)
            .or_else(|_| serde_json::from_str(&strip_json_comments(text)))?;

        // Format 1: {"properties": [...]} — wrapped array. The Lively desktop
        // app exports this shape by default (PascalCase keys: Name, Type,
        // Value, Min, Max, Increment, Description, Items).
        if let Some(arr) = value.get("properties").and_then(|v| v.as_array()) {
            return Ok(parse_array(arr));
        }

        // Format 2: [...] — bare array (some hand-authored bundles).
        if let Some(arr) = value.as_array() {
            return Ok(parse_array(arr));
        }

        // Format 3: {"propName": {...}} — per-key object (lowercase keys:
        // type, value, min, max, step/increment, text/name, items).
        if let Some(obj) = value.as_object() {
            return Ok(parse_per_key(obj));
        }

        Ok(Vec::new())
    }
}

fn strip_json_comments(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    let mut in_string = false;
    let mut escaped = false;

    while let Some(ch) = chars.next() {
        if in_string {
            out.push(ch);
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            continue;
        }

        if ch == '"' {
            in_string = true;
            out.push(ch);
            continue;
        }

        if ch == '/' {
            match chars.peek().copied() {
                Some('/') => {
                    chars.next();
                    for next in chars.by_ref() {
                        if next == '\n' {
                            out.push('\n');
                            break;
                        }
                    }
                    continue;
                }
                Some('*') => {
                    chars.next();
                    let mut prev = '\0';
                    for next in chars.by_ref() {
                        if prev == '*' && next == '/' {
                            break;
                        }
                        prev = next;
                    }
                    continue;
                }
                _ => {}
            }
        }

        out.push(ch);
    }

    out
}

/// Parses the array formats (both wrapped `{"properties":[...]}` and bare
/// `[...]`). Each entry is an object whose `Name` field is the property key.
fn parse_array(arr: &[Value]) -> Vec<WallpaperProperty> {
    arr.iter()
        .filter_map(|entry| {
            let obj = entry.as_object()?;
            let key = get_str_ci(obj, "name")?;
            Some(normalize_property(key, obj))
        })
        .collect()
}

/// Parses the per-key format: `{"speed": {"type": "slider", ...}, ...}`.
/// The object key is the property identifier.
fn parse_per_key(obj: &Map<String, Value>) -> Vec<WallpaperProperty> {
    obj.iter()
        .filter_map(|(key, value)| {
            let entry = value.as_object()?;
            Some(normalize_property(key.clone(), entry))
        })
        .collect()
}

/// Normalizes a raw property object (from any format) into a
/// [`WallpaperProperty`]. All keys in `raw` are lowercased so the Kotlin UI
/// reads them consistently regardless of which format the bundle used.
/// `Increment` is aliased to `step` (the key the Kotlin slider control reads).
fn normalize_property(key: String, raw_obj: &Map<String, Value>) -> WallpaperProperty {
    let mut normalized = Map::new();

    // Pass 1: insert all non-aliased keys, lowercased. `or_insert` ensures
    // first occurrence wins within a single pass (BTreeMap iteration is
    // alphabetical, so e.g. "Min" → "min" and "min" → "min" deduplicate
    // correctly regardless of which case the bundle used).
    for (k, v) in raw_obj {
        let lk = k.to_ascii_lowercase();
        if lk != "increment" {
            normalized.entry(lk).or_insert(v.clone());
        }
    }

    // Pass 2: alias `Increment` → `step`, but only if `step` isn't already
    // present. This runs after pass 1 so a real `step`/`Step` key always
    // wins over `Increment`/`increment`.
    if let Some(inc) = raw_obj
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case("increment"))
        .map(|(_, v)| v.clone())
    {
        normalized
            .entry("step".to_string())
            .or_insert(inc);
    }

    let raw = Value::Object(normalized);

    let display_name = get_str(&raw, "text")
        .or_else(|| get_str(&raw, "description"))
        .or_else(|| get_str(&raw, "name"))
        .unwrap_or_else(|| key.clone());

    let property_type = get_str(&raw, "type")
        .map(|t| normalize_type(&t))
        .unwrap_or_else(|| "unknown".to_string());
    let default_value = raw.get("value").cloned();

    WallpaperProperty {
        key,
        display_name,
        property_type,
        default_value,
        raw,
    }
}

fn normalize_type(property_type: &str) -> String {
    match property_type.to_ascii_lowercase().as_str() {
        // Lively's exported array format uses "range" for numeric slider
        // controls and "bool" for checkboxes. The Android UI consumes the
        // normalized control vocabulary used by the per-key format.
        "range" => "slider".to_string(),
        "bool" | "boolean" => "checkbox".to_string(),
        other => other.to_string(),
    }
}

fn get_str(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(|v| v.as_str()).map(|s| s.to_string())
}

/// Case-insensitive string lookup on a [`Map`] whose keys may be PascalCase
/// (array format) or lowercase (per-key format).
fn get_str_ci(obj: &Map<String, Value>, key: &str) -> Option<String> {
    obj.iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .and_then(|(_, v)| v.as_str())
        .map(|s| s.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Per-key format (existing, must still pass) ──────────────────────────

    #[test]
    fn parses_lively_properties() {
        let properties = LivelyPropertiesParser::parse(
            r##"{
                "rainColor": {
                    "text": "Rain Color",
                    "type": "color",
                    "value": "#00ff88"
                },
                "speed": {
                    "name": "Speed",
                    "type": "slider",
                    "value": 8
                }
            }"##,
        )
        .unwrap();

        assert_eq!(properties.len(), 2);
        assert_eq!(properties[0].key, "rainColor");
        assert_eq!(properties[0].display_name, "Rain Color");
        assert_eq!(properties[0].property_type, "color");
        assert_eq!(properties[1].key, "speed");
        assert_eq!(properties[1].display_name, "Speed");
    }

    // ── Wrapped array format ────────────────────────────────────────────────

    #[test]
    fn parses_wrapped_array_format() {
        let properties = LivelyPropertiesParser::parse(
            r#"{
                "properties": [
                    {
                        "Name": "speed",
                        "Description": "Rain speed",
                        "Type": "slider",
                        "Value": 5,
                        "Min": 1,
                        "Max": 20,
                        "Increment": 1
                    }
                ]
            }"#,
        )
        .unwrap();

        assert_eq!(properties.len(), 1);
        assert_eq!(properties[0].key, "speed");
        assert_eq!(properties[0].display_name, "Rain speed");
        assert_eq!(properties[0].property_type, "slider");
        assert_eq!(properties[0].default_value, Some(Value::from(5)));
    }

    #[test]
    fn wrapped_array_min_max_step_accessible() {
        let properties = LivelyPropertiesParser::parse(
            r#"{"properties":[{"Name":"s","Type":"slider","Value":50,"Min":0,"Max":100,"Increment":5}]}"#,
        )
        .unwrap();

        let raw = &properties[0].raw;
        assert_eq!(raw.get("min"), Some(&Value::from(0)));
        assert_eq!(raw.get("max"), Some(&Value::from(100)));
        // Increment is aliased to step.
        assert_eq!(raw.get("step"), Some(&Value::from(5)));
    }

    #[test]
    fn wrapped_array_description_as_display_name() {
        let properties = LivelyPropertiesParser::parse(
            r##"{"properties":[{"Name":"c","Description":"Color","Type":"color","Value":"#ff0000"}]}"##,
        )
        .unwrap();

        assert_eq!(properties[0].display_name, "Color");
    }

    #[test]
    fn wrapped_array_bool_property() {
        let properties = LivelyPropertiesParser::parse(
            r#"{"properties":[{"Name":"enabled","Type":"bool","Value":true}]}"#,
        )
        .unwrap();

        assert_eq!(properties[0].property_type, "checkbox");
        assert_eq!(properties[0].default_value, Some(Value::from(true)));
    }

    #[test]
    fn wrapped_array_range_property_normalizes_to_slider() {
        let properties = LivelyPropertiesParser::parse(
            r#"{"properties":[{"Name":"speed","Type":"range","Value":1,"Min":0,"Max":5,"Increment":0.5}]}"#,
        )
        .unwrap();

        assert_eq!(properties[0].property_type, "slider");
    }

    // ── Bare array format ──────────────────────────────────────────────────

    #[test]
    fn parses_bare_array_format() {
        let properties = LivelyPropertiesParser::parse(
            r#"[{"Name":"x","Type":"slider","Value":42,"Min":0,"Max":100}]"#,
        )
        .unwrap();

        assert_eq!(properties.len(), 1);
        assert_eq!(properties[0].key, "x");
        assert_eq!(properties[0].property_type, "slider");
    }

    // ── Cross-format normalization ─────────────────────────────────────────

    #[test]
    fn increment_aliased_to_step_in_per_key_format() {
        let properties = LivelyPropertiesParser::parse(
            r#"{"s":{"type":"slider","value":1,"increment":0.5}}"#,
        )
        .unwrap();

        assert_eq!(properties[0].raw.get("step"), Some(&Value::from(0.5)));
    }

    #[test]
    fn step_wins_over_increment() {
        let properties = LivelyPropertiesParser::parse(
            r#"{"s":{"type":"slider","value":1,"step":2,"increment":5}}"#,
        )
        .unwrap();

        assert_eq!(properties[0].raw.get("step"), Some(&Value::from(2)));
    }

    #[test]
    fn items_accessible_regardless_of_case() {
        let properties = LivelyPropertiesParser::parse(
            r#"{"d":{"type":"dropdown","value":0,"items":["a","b"]}}"#,
        )
        .unwrap();

        assert!(properties[0].raw.get("items").is_some());
    }

    #[test]
    fn parses_lively_properties_with_comments() {
        let properties = LivelyPropertiesParser::parse(
            r##"{
                // Lively bundled wallpapers sometimes contain JS-style comments.
                "hillOpacityFac": {
                    "max": 1000,
                    "min": 0,
                    "text": "Hill Opacity",
                    "type": "slider",
                    "value": 50
                },
                "url": {
                    "type": "textbox",
                    "value": "https://example.test/path"
                },
                /* Keep block comments out too. */
                "timeToggle": {
                    "type": "checkbox",
                    "value": true
                }
            }"##,
        )
        .unwrap();

        assert_eq!(properties.len(), 3);
        let hill = properties
            .iter()
            .find(|p| p.key == "hillOpacityFac")
            .unwrap();
        let url = properties.iter().find(|p| p.key == "url").unwrap();
        let time = properties.iter().find(|p| p.key == "timeToggle").unwrap();

        assert_eq!(hill.display_name, "Hill Opacity");
        assert_eq!(
            url.default_value,
            Some(Value::from("https://example.test/path"))
        );
        assert_eq!(time.property_type, "checkbox");
    }

    #[test]
    fn empty_object_returns_empty() {
        let properties = LivelyPropertiesParser::parse("{}").unwrap();
        assert!(properties.is_empty());
    }

    #[test]
    fn empty_array_returns_empty() {
        let properties = LivelyPropertiesParser::parse("[]").unwrap();
        assert!(properties.is_empty());
    }
}
