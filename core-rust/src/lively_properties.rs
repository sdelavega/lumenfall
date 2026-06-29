use std::collections::BTreeMap;

use serde::Deserialize;
use serde_json::Value;

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
        let raw: BTreeMap<String, RawWallpaperProperty> = serde_json::from_str(text)?;
        Ok(raw
            .into_iter()
            .map(|(key, value)| {
                let raw = value.raw();
                WallpaperProperty {
                    display_name: value.text.or(value.name).unwrap_or_else(|| key.clone()),
                    property_type: value.property_type.unwrap_or_else(|| "unknown".to_string()),
                    default_value: value.value,
                    raw,
                    key,
                }
            })
            .collect())
    }
}

#[derive(Debug, Deserialize)]
struct RawWallpaperProperty {
    text: Option<String>,
    name: Option<String>,
    #[serde(rename = "type")]
    property_type: Option<String>,
    value: Option<Value>,
    #[serde(flatten)]
    raw_fields: BTreeMap<String, Value>,
}

impl RawWallpaperProperty {
    fn raw(&self) -> Value {
        let mut object = serde_json::Map::new();
        if let Some(text) = &self.text {
            object.insert("text".to_string(), Value::String(text.clone()));
        }
        if let Some(name) = &self.name {
            object.insert("name".to_string(), Value::String(name.clone()));
        }
        if let Some(property_type) = &self.property_type {
            object.insert("type".to_string(), Value::String(property_type.clone()));
        }
        if let Some(value) = &self.value {
            object.insert("value".to_string(), value.clone());
        }
        object.extend(self.raw_fields.clone());
        Value::Object(object)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
