// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
use lumenfall_core::{BundleScreener, Classification};

const INDEX_HTML: &str =
    include_str!("../../app/src/main/assets/wallpapers/Rainfall/index.html");
const RAINFALL_JS: &str =
    include_str!("../../app/src/main/assets/wallpapers/Rainfall/rainfall.js");

#[test]
fn bundled_rainfall_is_not_rejected() {
    let source = format!("{INDEX_HTML}\n{RAINFALL_JS}");
    let report = BundleScreener::screen(&source);
    assert_ne!(
        report.classification,
        Classification::Rejected,
        "issues: {:?}",
        report.issues
    );
    assert!(report.issues.is_empty(), "issues: {:?}", report.issues);
}
