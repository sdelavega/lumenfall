// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later

//! The single network blocklist shared by the bundle screener (Rust) and the
//! WebView network policy (Kotlin, via the `blocklistJson` JNI export).

use serde::Serialize;

/// Ad networks and analytics/trackers.
pub const TRACKER_DOMAINS: &[&str] = &[
    "google-analytics.com",
    "analytics.google.com",
    "googletagmanager.com",
    "googletagservices.com",
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "adservice.google.com",
    "adnxs.com",
    "adsrvr.org",
    "connect.facebook.net",
    "facebook.net",
    "hotjar.com",
    "mixpanel.com",
    "segment.io",
    "api.segment.com",
    "amplitude.com",
    "sentry.io",
    "bugsnag.com",
];

/// Crypto-mining services.
pub const MINER_DOMAINS: &[&str] = &[
    "coinhive.com",
    "crypto-loot.com",
    "minero.cc",
    "jsecoin.com",
    "monerominer.rocks",
    "webmr.eu",
];

/// Every blocked domain (trackers + miners), 25 in total.
pub const BLOCKED_DOMAINS: &[&str] = &[
    "google-analytics.com",
    "analytics.google.com",
    "googletagmanager.com",
    "googletagservices.com",
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "adservice.google.com",
    "adnxs.com",
    "adsrvr.org",
    "connect.facebook.net",
    "facebook.net",
    "hotjar.com",
    "mixpanel.com",
    "segment.io",
    "api.segment.com",
    "amplitude.com",
    "sentry.io",
    "bugsnag.com",
    "coinhive.com",
    "crypto-loot.com",
    "minero.cc",
    "jsecoin.com",
    "monerominer.rocks",
    "webmr.eu",
];

/// Proprietary geolocation services. Network-blocked only; never a screening
/// signal (legitimate bundles reference them behind capability flags).
pub const PRIVACY_BLOCKED_HOSTS: &[&str] = &["ipapi.co", "api.bigdatacloud.net"];

/// Hosts blocked regardless of any user setting (cloud metadata).
pub const ALWAYS_BLOCKED_HOSTS: &[&str] = &["metadata.google.internal"];

/// Host suffixes blocked regardless of any user setting (mDNS, DNS rebinding).
pub const ALWAYS_BLOCKED_SUFFIXES: &[&str] = &[".local", ".nip.io", ".sslip.io"];

fn matches_domain(host: &str, domain: &str) -> bool {
    host == domain
        || host
            .strip_suffix(domain)
            .is_some_and(|prefix| prefix.ends_with('.'))
}

/// True when `host` is blocked: exact or subdomain match for domains and
/// hosts, suffix match for suffixes. Case-insensitive; one trailing dot ignored.
pub fn is_blocked_host(host: &str) -> bool {
    let lowered = host.to_ascii_lowercase();
    let host = lowered.strip_suffix('.').unwrap_or(&lowered);
    BLOCKED_DOMAINS
        .iter()
        .chain(PRIVACY_BLOCKED_HOSTS)
        .chain(ALWAYS_BLOCKED_HOSTS)
        .any(|d| matches_domain(host, d))
        || ALWAYS_BLOCKED_SUFFIXES.iter().any(|s| host.ends_with(s))
}

#[derive(Serialize)]
struct BlocklistJson {
    domains: &'static [&'static str],
    hosts: Vec<&'static str>,
    suffixes: &'static [&'static str],
}

/// JSON for the Kotlin side: `{"domains":[...],"hosts":[...],"suffixes":[...]}`.
/// Privacy hosts are included in `hosts`.
pub fn blocklist_json() -> String {
    let hosts = ALWAYS_BLOCKED_HOSTS
        .iter()
        .chain(PRIVACY_BLOCKED_HOSTS)
        .copied()
        .collect();
    serde_json::to_string(&BlocklistJson {
        domains: BLOCKED_DOMAINS,
        hosts,
        suffixes: ALWAYS_BLOCKED_SUFFIXES,
    })
    .unwrap_or_else(|_| "{\"domains\":[],\"hosts\":[],\"suffixes\":[]}".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_entries_present() {
        for d in ["googletagmanager.com", "doubleclick.net", "coinhive.com"] {
            assert!(BLOCKED_DOMAINS.contains(&d), "{d}");
        }
        assert_eq!(BLOCKED_DOMAINS.len(), 25);
        for h in ["ipapi.co", "api.bigdatacloud.net"] {
            assert!(PRIVACY_BLOCKED_HOSTS.contains(&h), "{h}");
            assert!(is_blocked_host(h));
        }
        assert!(ALWAYS_BLOCKED_HOSTS.contains(&"metadata.google.internal"));
        assert!(!is_blocked_host("metadata.azure.com"));
    }

    #[test]
    fn tracker_and_miner_lists_partition_blocked_domains() {
        let mut both: Vec<&str> = TRACKER_DOMAINS.iter().chain(MINER_DOMAINS).copied().collect();
        both.sort();
        let mut all = BLOCKED_DOMAINS.to_vec();
        all.sort();
        assert_eq!(both, all);
    }

    #[test]
    fn case_and_trailing_dot_still_match() {
        assert!(is_blocked_host("DoubleClick.NET."));
    }

    #[test]
    fn json_shape() {
        let v: serde_json::Value = serde_json::from_str(&blocklist_json()).unwrap();
        assert_eq!(v["domains"].as_array().unwrap().len(), 25);
        assert!(v["hosts"].as_array().unwrap().iter().any(|h| h == "ipapi.co"));
        assert!(v["suffixes"].as_array().unwrap().iter().any(|h| h == ".local"));
    }

    #[test]
    fn subdomains_blocked() {
        assert!(is_blocked_host("stats.g.doubleclick.net"));
        assert!(is_blocked_host("x.nip.io"));
    }

    #[test]
    fn lookalikes_allowed() {
        assert!(!is_blocked_host("notdoubleclick.net.example"));
        assert!(!is_blocked_host("api.open-meteo.com"));
    }
}
