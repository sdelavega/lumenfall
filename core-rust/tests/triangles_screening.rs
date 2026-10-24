// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
use lumenfall_core::{BundleScreener, Classification};

// The CodePen-derived Lively "Triangles & Light" bundle reads `document.location.href`
// (inside dat.gui) and assigns a local `location` variable (inside its WebGL renderer).
// Neither navigates; the bundle was wrongly rejected as "top-level navigation" in the
// 1.0 device smoke run.
const SCRIPT_JS: &str =
    include_str!("../../app/src/main/assets/wallpapers/LivelyTrianglesLight/js/script.js");

// The bundle is still rejected overall, for a different rule: its GUI export buttons call
// `window.open(` (see the PR notes). This test pins only the navigation rule.
#[test]
fn triangles_pattern_is_not_rejected_for_navigation() {
    assert!(
        SCRIPT_JS.contains("location.href"),
        "fixture lost the read pattern"
    );
    let report = BundleScreener::screen(SCRIPT_JS);
    assert!(
        !report.issues.iter().any(|i| i.contains("navigation")),
        "issues: {:?}",
        report.issues
    );
}

#[test]
fn triangles_bundle_with_an_injected_navigation_is_rejected() {
    let report = BundleScreener::screen(&format!(
        "{SCRIPT_JS}\nlocation.href = 'https://example.com';"
    ));
    assert_eq!(report.classification, Classification::Rejected);
}
