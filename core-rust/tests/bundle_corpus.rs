use std::fs;
use std::path::Path;

use lumenfall_core::{BundleValidationResponse, BundleValidator};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct Case {
    base_path: String,
    files: Vec<FileEntry>,
    expected: Expected,
}

#[derive(Debug, Deserialize, serde::Serialize)]
struct FileEntry {
    path: String,
    size: u64,
}

#[derive(Debug, Deserialize)]
struct Expected {
    ok: bool,
    kind: Option<String>,
    entry_path: Option<String>,
    preview_path: Option<String>,
    error_contains: Option<String>,
}

#[test]
fn validates_bundle_corpus() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/bundles");
    for entry in fs::read_dir(&root).unwrap() {
        let case_dir = entry.unwrap().path();
        if !case_dir.is_dir() {
            continue;
        }

        let name = case_dir.file_name().unwrap().to_string_lossy();
        let info_json = fs::read_to_string(case_dir.join("LivelyInfo.json")).unwrap();
        let case: Case =
            serde_json::from_str(&fs::read_to_string(case_dir.join("case.json")).unwrap()).unwrap();
        let files_json = serde_json::to_string(&case.files).unwrap();

        let response = BundleValidationResponse::from(
            BundleValidator::validate(&info_json, &case.base_path, &files_json)
                .unwrap_or_else(|error| panic!("{name}: validator failed: {error}")),
        );

        assert_eq!(response.ok, case.expected.ok, "{name}: ok mismatch");
        if let Some(kind) = case.expected.kind {
            assert_eq!(response.kind, kind, "{name}: kind mismatch");
        }
        assert_eq!(
            response.entry_path, case.expected.entry_path,
            "{name}: entry path mismatch"
        );
        assert_eq!(
            response.preview_path, case.expected.preview_path,
            "{name}: preview path mismatch"
        );
        if let Some(needle) = case.expected.error_contains {
            let error = response.error.unwrap_or_default();
            assert!(
                error.contains(&needle),
                "{name}: expected error containing {needle:?}, got {error:?}"
            );
        }
    }
}
