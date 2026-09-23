//! Generates the Kotlin bindings from the compiled library:
//!
//!   cargo run --bin uniffi-bindgen -- generate --library target/<triple>/release/libaegis_comms_crypto.so \
//!       --language kotlin --out-dir <dir>
fn main() {
    uniffi::uniffi_bindgen_main()
}
