// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
use crate::blocklist::{MINER_DOMAINS, TRACKER_DOMAINS};
use serde::Serialize;

/// Screens a web bundle's collected script source (all .js/.html text) and
/// classifies it the same way ÆtherDesk's WallpaperValidator does:
///
/// - `Allowed` — run without extra constraints.
/// - `AllowedWithLimits` — run, but the host clamps FPS and rate-limits
///   external network requests.
/// - `Rejected` — do not import; `issues` holds human-readable reasons.
///
/// The heuristics are ported from ÆtherDesk (Import/WallpaperValidator.swift)
/// so a bundle screens identically on both platforms. Kotlin owns file I/O and
/// supplies the concatenated source; this module only decides.
pub const DEFAULT_FPS_CAP: u32 = 30;
pub const DEFAULT_NETWORK_BUDGET_PER_MINUTE: u32 = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Classification {
    Allowed,
    AllowedWithLimits,
    Rejected,
}

#[derive(Debug, Clone)]
pub struct ScreeningReport {
    pub classification: Classification,
    pub warnings: Vec<String>,
    pub issues: Vec<String>,
}

pub struct BundleScreener;

impl BundleScreener {
    pub fn screen(script_source: &str) -> ScreeningReport {
        let mut warnings = Vec::new();
        let mut issues = Vec::new();

        inspect(script_source, &mut warnings, &mut issues);

        let classification = if !issues.is_empty() {
            Classification::Rejected
        } else if !warnings.is_empty() {
            Classification::AllowedWithLimits
        } else {
            Classification::Allowed
        };

        ScreeningReport {
            classification,
            warnings,
            issues,
        }
    }
}

fn inspect(js: &str, warnings: &mut Vec<String>, issues: &mut Vec<String>) {
    let set_interval_count = count(js, "setInterval");
    if set_interval_count > 3 {
        warnings.push(format!(
            "Uses setInterval {set_interval_count} times — possible timer storm"
        ));
    }

    let fetch_count = count(js, "fetch(") + count(js, "XMLHttpRequest");
    if fetch_count > 10 {
        issues.push(format!(
            "Excessive network call sites ({fetch_count}) — exceeds budget"
        ));
    } else if fetch_count > 0 {
        warnings.push(format!(
            "Performs network requests ({fetch_count} call sites) — will be rate-limited"
        ));
    }

    if js.contains("while(true)") || js.contains("while (true)") {
        issues.push("Contains infinite while(true) loop".to_string());
    }

    if js.contains("navigator.geolocation") {
        warnings.push("Requests geolocation".to_string());
    }
    if js.contains("Notification.requestPermission") {
        warnings.push("Requests system notification permission".to_string());
    }

    // Tracker / ad domains -> hard reject. Full domains only: bare words such
    // as "amplitude" or "segment" appear in legitimate animation code.
    for domain in TRACKER_DOMAINS {
        if js.contains(domain) {
            issues.push(format!("References {domain} (tracker/ad script)"));
        }
    }

    const SUSPICIOUS: &[&str] = &[
        "document.write(",
        "eval(",
        "Function(",
        "WebAssembly",
        "atob(",
        "String.fromCharCode",
    ];
    for pattern in SUSPICIOUS {
        if js.contains(pattern) {
            warnings.push(format!("Uses {pattern}"));
        }
    }

    // GPU / rendering resource checks. shadowBlur is the most expensive
    // Canvas 2D operation; many call sites usually means every-frame glow.
    let shadow_blur_count = count(js, "shadowBlur");
    if shadow_blur_count > 5 {
        warnings.push(format!(
            "Heavy GPU shadow usage ({shadow_blur_count} shadowBlur calls) — may drain battery"
        ));
    }

    let composite_count = count(js, "globalCompositeOperation");
    if composite_count > 5 {
        warnings.push(format!(
            "Heavy composite blending ({composite_count} mode switches)"
        ));
    }

    if js.contains("getContext('webgl")
        || js.contains("getContext(\"webgl")
        || js.contains("getContext(`webgl")
    {
        warnings.push("Uses WebGL (GPU-intensive)".to_string());
    }

    if js.len() > 500_000 {
        warnings.push(format!("Large script payload ({} KB)", js.len() / 1024));
    }

    // Hard rejects: patterns that have no place in a wallpaper.
    const MINERS: &[&str] = &[
        "coinhive",
        "CoinHive",
        "cryptonight",
        "CryptoMiner",
        "minero",
        "hashrate",
        "stratum+tcp",
    ];
    for pattern in MINERS {
        if js.contains(pattern) {
            issues.push(format!("Contains crypto mining code ({pattern})"));
        }
    }
    for domain in MINER_DOMAINS {
        if js.contains(domain) {
            issues.push(format!("Contains crypto mining code ({domain})"));
        }
    }

    if js.contains("navigator.serviceWorker") {
        issues.push("Registers a ServiceWorker (not appropriate for wallpapers)".to_string());
    }
    if js.contains("SharedWorker") {
        issues.push("Uses SharedWorker (not appropriate for wallpapers)".to_string());
    }
    if js.contains("window.open(") || js.contains("window.open (") {
        issues.push("Attempts to open browser windows".to_string());
    }
    if navigates_away(js) {
        issues.push("Attempts top-level navigation (not appropriate for wallpapers)".to_string());
    }
}

/// Objects that alias the page's `location`; anything else before a `.location`
/// (`buffer.location`, `mesh.location`) is an unrelated property.
const LOCATION_OWNERS: [&str; 6] = ["window", "top", "self", "parent", "document", "globalThis"];

/// True when the script navigates the page away: assigns to `location`,
/// `location.href` (also via `window.`/`top.`/`document.`...) or calls
/// `location.assign()`/`location.replace()`. Merely reading the URL
/// (`location.href` in a comparison or concatenation) is harmless and common
/// in ordinary Lively wallpapers, so it passes. A textual heuristic like the
/// rest of the screener: the runtime still refuses main-frame navigation off
/// the bundle origin.
fn navigates_away(js: &str) -> bool {
    let bytes = js.as_bytes();
    let mut from = 0;
    let mut local: Option<bool> = None;
    while let Some(found) = js[from..].find("location") {
        let start = from + found;
        let end = start + "location".len();
        from = end;
        if start > 0 && is_ident(bytes[start - 1]) || bytes.get(end).is_some_and(|b| is_ident(*b)) {
            continue;
        }
        let bare = if start > 0 && bytes[start - 1] == b'.' {
            if !owner_before(js, start - 1) {
                continue;
            }
            false
        } else {
            true
        };

        let mut at = skip_ws(bytes, end);
        let mut is_href = false;
        if bytes.get(at) == Some(&b'.') {
            let member = skip_ws(bytes, at + 1);
            let name_end = ident_end(bytes, member);
            match &js[member..name_end] {
                "assign" | "replace" => {
                    if bytes.get(skip_ws(bytes, name_end)) == Some(&b'(') {
                        return true;
                    }
                    continue;
                }
                "href" => {
                    is_href = true;
                    at = skip_ws(bytes, name_end);
                }
                _ => continue,
            }
        }
        if !is_assignment_op(bytes, at) {
            continue;
        }
        // GLSL `layout(location = 0) in ...` is a shader qualifier, not an assignment.
        if bare && !is_href && prev_non_ws(bytes, start) == Some(b'(') {
            continue;
        }
        if !bare || is_href || !*local.get_or_insert_with(|| declared_local(js)) {
            return true;
        }
    }
    false
}

fn prev_non_ws(bytes: &[u8], at: usize) -> Option<u8> {
    bytes[..at].iter().rev().find(|b| !b.is_ascii_whitespace()).copied()
}

fn is_ident(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'$'
}

fn skip_ws(bytes: &[u8], mut at: usize) -> usize {
    while bytes.get(at).is_some_and(|b| b.is_ascii_whitespace()) {
        at += 1;
    }
    at
}

fn ident_end(bytes: &[u8], mut at: usize) -> usize {
    while bytes.get(at).is_some_and(|b| is_ident(*b)) {
        at += 1;
    }
    at
}

/// The identifier ending just before the `.` at `dot` (skipping nested owners
/// such as `window.top.`) is one of the `location` aliases.
fn owner_before(js: &str, dot: usize) -> bool {
    let bytes = js.as_bytes();
    let mut start = dot;
    while start > 0 && is_ident(bytes[start - 1]) {
        start -= 1;
    }
    LOCATION_OWNERS.contains(&&js[start..dot])
}

/// `=` or a compound assignment, but not `==`, `===` or an arrow `=>`.
fn is_assignment_op(bytes: &[u8], at: usize) -> bool {
    match bytes.get(at) {
        Some(b'=') => !matches!(bytes.get(at + 1), Some(b'=') | Some(b'>')),
        Some(b'+') => bytes.get(at + 1) == Some(&b'='),
        Some(b'|' | b'&' | b'?') => bytes.get(at + 1) == bytes.get(at) && bytes.get(at + 2) == Some(&b'='),
        _ => false,
    }
}

/// A bare `location = ...` is only navigation when `location` is the window's;
/// WebGL code commonly declares its own (`var attribute, uniform, location;`
/// or a parameter) and assigns it. The script declaring a `location` anywhere
/// is taken as that case.
fn declared_local(js: &str) -> bool {
    ["var", "let", "const", "function"].iter().any(|kw| {
        js.match_indices(kw).any(|(i, _)| {
            let bytes = js.as_bytes();
            let boundary_before = i == 0 || !is_ident(bytes[i - 1]);
            let after = i + kw.len();
            if !boundary_before || bytes.get(after).is_some_and(|b| is_ident(*b)) {
                return false;
            }
            // Bytes, not str slices: the 400-byte cap may fall inside a multi-byte char.
            let tail = &bytes[after..];
            let stop = tail
                .iter()
                .take(400)
                .position(|c| matches!(c, b';' | b'{' | b'='))
                .unwrap_or(tail.len().min(400));
            let b = &tail[..stop];
            b.windows("location".len()).enumerate().filter(|(_, w)| *w == b"location").any(|(j, _)| {
                let k = j + "location".len();
                (j == 0 || !is_ident(b[j - 1]) && b[j - 1] != b'.')
                    && b.get(k).is_none_or(|c| !is_ident(*c))
            })
        })
    })
}

fn count(haystack: &str, needle: &str) -> usize {
    haystack.matches(needle).count()
}

#[derive(Debug, Serialize)]
pub struct ScreeningResponse {
    pub ok: bool,
    pub error: Option<String>,
    pub classification: &'static str,
    pub warnings: Vec<String>,
    pub issues: Vec<String>,
    pub fps_cap: u32,
    pub network_budget_per_minute: u32,
}

impl From<ScreeningReport> for ScreeningResponse {
    fn from(report: ScreeningReport) -> Self {
        Self {
            ok: true,
            error: None,
            classification: match report.classification {
                Classification::Allowed => "allowed",
                Classification::AllowedWithLimits => "limited",
                Classification::Rejected => "rejected",
            },
            warnings: report.warnings,
            issues: report.issues,
            fps_cap: DEFAULT_FPS_CAP,
            network_budget_per_minute: DEFAULT_NETWORK_BUDGET_PER_MINUTE,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_bundle_is_allowed() {
        let report = BundleScreener::screen(
            "const ctx = canvas.getContext('2d'); requestAnimationFrame(draw);",
        );
        assert_eq!(report.classification, Classification::Allowed);
        assert!(report.warnings.is_empty());
    }

    #[test]
    fn network_use_downgrades_to_limits() {
        let report = BundleScreener::screen("fetch('https://api.example.com/weather')");
        assert_eq!(report.classification, Classification::AllowedWithLimits);
        assert!(report.warnings.iter().any(|w| w.contains("rate-limited")));
    }

    #[test]
    fn webgl_downgrades_to_limits() {
        let report = BundleScreener::screen("const gl = canvas.getContext('webgl2');");
        assert_eq!(report.classification, Classification::AllowedWithLimits);
    }

    #[test]
    fn tracker_reference_rejects() {
        let report =
            BundleScreener::screen("var s = 'https://www.googletagmanager.com/gtag/js';");
        assert_eq!(report.classification, Classification::Rejected);
    }

    #[test]
    fn every_blocked_domain_rejects() {
        for domain in crate::blocklist::BLOCKED_DOMAINS {
            let report = BundleScreener::screen(&format!("var u = 'https://{domain}/x.js';"));
            assert_eq!(report.classification, Classification::Rejected, "{domain}");
        }
    }

    #[test]
    fn screener_ignores_line_segments_and_amplitude_words() {
        let report = BundleScreener::screen(
            "const l = new THREE.LineSegments(geo, mat); y = amplitude * Math.sin(t);",
        );
        assert_eq!(report.classification, Classification::Allowed);
        assert!(report.issues.is_empty());
    }

    #[test]
    fn screener_does_not_reject_privacy_hosts() {
        let report = BundleScreener::screen(
            "if (caps.geo) { x = 'https://ipapi.co/json'; y = 'https://api.bigdatacloud.net/data'; }",
        );
        assert!(report.issues.is_empty());
        assert!(report.warnings.is_empty());
        assert_eq!(report.classification, Classification::Allowed);
    }

    #[test]
    fn miner_rejects() {
        let report = BundleScreener::screen("var pool = 'stratum+tcp://pool.example:3333';");
        assert_eq!(report.classification, Classification::Rejected);
    }

    #[test]
    fn service_worker_rejects() {
        let report = BundleScreener::screen("navigator.serviceWorker.register('/sw.js')");
        assert_eq!(report.classification, Classification::Rejected);
    }

    #[test]
    fn infinite_loop_rejects() {
        let report = BundleScreener::screen("while (true) { mine(); }");
        assert_eq!(report.classification, Classification::Rejected);
    }

    #[test]
    fn top_level_navigation_rejects() {
        let report = BundleScreener::screen("location.replace('missing.html');");
        assert_eq!(report.classification, Classification::Rejected);
        assert!(
            report
                .issues
                .iter()
                .any(|w| w.contains("top-level navigation"))
        );
    }

    fn nav_rejected(js: &str) -> bool {
        BundleScreener::screen(js)
            .issues
            .iter()
            .any(|w| w.contains("top-level navigation"))
    }

    #[test]
    fn reading_location_is_not_navigation() {
        for js in [
            "var u = location.href;",
            "localStorage.getItem(document.location.href + '.gui');",
            "if (window.location.href === 'x') {}",
            "if (location.href == 'x' || top.location.href != y) {}",
            "var q = window.location.search; var h = location.hash;",
            "const same = location.href===other;",
            "items.forEach(location => draw(location));",
            "buffer.location = gl.getUniformLocation(p, id);",
            "var a, location, b; location = buffer.location;",
            "function f(location) { location = location + 1; }",
            "layout(location = 0) in vec3 p;",
            "var mylocation = 1; mylocation = 2; mylocation.replace('a','b');",
            "str.location.replace('a');",
        ] {
            assert!(!nav_rejected(js), "should not reject: {js}");
        }
    }

    #[test]
    fn multibyte_text_near_declarations_does_not_panic() {
        for n in 395..405 {
            let js = format!("var {}é\nlocation = 'x';", "a".repeat(n));
            let _ = BundleScreener::screen(&js);
        }
    }

    #[test]
    fn navigation_assignments_and_calls_reject() {
        for js in [
            "location = 'https://example.com';",
            "location.href = 'https://example.com';",
            "location.href='x';",
            "window.location = 'x';",
            "window.location.href = 'x';",
            "top.location = 'x';",
            "top.location.href = 'x';",
            "document.location = 'x';",
            "window.top.location.href += '#x';",
            "location.href ||= 'x';",
            "var e = 'é'; location = 'x';",
            "// function \u{e9}\nvar x = 1; location = 'x';",
            "location.assign('x');",
            "location.assign ('x');",
            "window.location.assign('x');",
            "location.replace('x');",
            "top.location.replace('x');",
            "document.location.replace('x');",
            "location\n  .href\n  = 'x';",
        ] {
            assert!(nav_rejected(js), "should reject: {js}");
        }
    }

    #[test]
    fn excessive_fetch_sites_reject() {
        let js = "fetch(u);".repeat(11);
        let report = BundleScreener::screen(&js);
        assert_eq!(report.classification, Classification::Rejected);
    }

    #[test]
    fn eval_is_warning_not_reject() {
        let report = BundleScreener::screen("eval(code);");
        assert_eq!(report.classification, Classification::AllowedWithLimits);
        assert!(report.warnings.iter().any(|w| w.contains("eval(")));
    }
}
