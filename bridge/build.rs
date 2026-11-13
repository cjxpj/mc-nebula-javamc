//! 编译期把「这份源码长什么样」烧进 dll，作为产物来源的一手证据。
//!
//! build.ps1 事后写的清单、jar 里的 manifest 都可能和 dll 一起被替换，
//! 只有编译器在编译这一刻读到的内容无法事后追认（除非重新编译）。
//! 输出格式：NEBULA_SOURCES=<相对路径>:<SHA256大写>;<...>\0
//! 由 src/lib.rs 的 nebula_embedded_sources() 放进 .rodata。
use std::{env, fs, path::Path};

use sha2::{Digest, Sha256};

fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect()
}

fn collect_rs(dir: &Path, base: &Path, out: &mut Vec<(String, String)>) {
    let Ok(rd) = fs::read_dir(dir) else {
        return;
    };
    let mut entries: Vec<_> = rd.flatten().collect();
    entries.sort_by_key(|e| e.path());
    for entry in entries {
        let path = entry.path();
        if path.is_dir() {
            collect_rs(&path, base, out);
        } else if path.extension().and_then(|s| s.to_str()) == Some("rs") {
            // 键名 = 相对 bridge/src 的路径，与 regress.py 的 fingerprint() 一致。
            let rel = path
                .strip_prefix(base)
                .unwrap_or(&path)
                .to_string_lossy()
                .replace('\\', "/");
            let Ok(bytes) = fs::read(&path) else {
                continue;
            };
            out.push((rel, sha256_hex(&bytes)));
        }
    }
}

fn main() {
    let root = env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR");
    let root = Path::new(&root);
    let mut entries: Vec<(String, String)> = Vec::new();
    let src = root.join("src");
    collect_rs(&src, &src, &mut entries);
    if let Ok(bytes) = fs::read(root.join("Cargo.toml")) {
        entries.push(("Cargo.toml".to_string(), sha256_hex(&bytes)));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    let marker = entries
        .iter()
        .map(|(k, v)| format!("{k}:{v}"))
        .collect::<Vec<_>>()
        .join(";");
    println!("cargo:rustc-env=NEBULA_EMBEDDED_SOURCES={marker}");
    println!("cargo:rerun-if-changed=src");
    println!("cargo:rerun-if-changed=Cargo.toml");
}
