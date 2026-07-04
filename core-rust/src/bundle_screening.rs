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

    // Tracker / ad domains -> hard reject.
    const TRACKERS: &[&str] = &[
        "googletagmanager",
        "google-analytics",
        "analytics.google",
        "googletagservices",
        "adservice.google",
        "googlesyndication",
        "doubleclick",
        "facebook.net",
        "connect.facebook",
        "hotjar",
        "mixpanel",
        "segment.io",
        "api.segment",
        "amplitude",
        "sentry.io",
        "bugsnag",
        "adnxs.com",
        "adsrvr.org",
    ];
    for pattern in TRACKERS {
        if js.contains(pattern) {
            issues.push(format!("References {pattern} (tracker/ad script)"));
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

    if js.contains("navigator.serviceWorker") {
        issues.push("Registers a ServiceWorker (not appropriate for wallpapers)".to_string());
    }
    if js.contains("SharedWorker") {
        issues.push("Uses SharedWorker (not appropriate for wallpapers)".to_string());
    }
    if js.contains("window.open(") || js.contains("window.open (") {
        issues.push("Attempts to open browser windows".to_string());
    }
    if js.contains("location.replace(")
        || js.contains("location.assign(")
        || js.contains("window.location.replace(")
        || js.contains("window.location.assign(")
        || js.contains("location.href")
        || js.contains("window.location =")
    {
        issues.push("Attempts top-level navigation (not appropriate for wallpapers)".to_string());
    }
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
        assert!(report.issues.iter().any(|w| w.contains("top-level navigation")));
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
