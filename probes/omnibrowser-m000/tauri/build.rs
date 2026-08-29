use std::{fs, path::PathBuf};

fn write_probe_icon() -> std::io::Result<()> {
    const WIDTH: u8 = 32;
    const HEIGHT: u8 = 32;
    const PIXEL_BYTES: usize = 32 * 32 * 4;
    const MASK_BYTES: usize = 32 * 4;
    const IMAGE_BYTES: u32 = (40 + PIXEL_BYTES + MASK_BYTES) as u32;

    let mut icon = Vec::with_capacity(22 + IMAGE_BYTES as usize);

    // ICONDIR
    icon.extend_from_slice(&0u16.to_le_bytes());
    icon.extend_from_slice(&1u16.to_le_bytes());
    icon.extend_from_slice(&1u16.to_le_bytes());

    // ICONDIRENTRY
    icon.push(WIDTH);
    icon.push(HEIGHT);
    icon.push(0);
    icon.push(0);
    icon.extend_from_slice(&1u16.to_le_bytes());
    icon.extend_from_slice(&32u16.to_le_bytes());
    icon.extend_from_slice(&IMAGE_BYTES.to_le_bytes());
    icon.extend_from_slice(&22u32.to_le_bytes());

    // BITMAPINFOHEADER. ICO stores XOR + AND heights together.
    icon.extend_from_slice(&40u32.to_le_bytes());
    icon.extend_from_slice(&32i32.to_le_bytes());
    icon.extend_from_slice(&64i32.to_le_bytes());
    icon.extend_from_slice(&1u16.to_le_bytes());
    icon.extend_from_slice(&32u16.to_le_bytes());
    icon.extend_from_slice(&0u32.to_le_bytes());
    icon.extend_from_slice(&(PIXEL_BYTES as u32).to_le_bytes());
    icon.extend_from_slice(&0i32.to_le_bytes());
    icon.extend_from_slice(&0i32.to_le_bytes());
    icon.extend_from_slice(&0u32.to_le_bytes());
    icon.extend_from_slice(&0u32.to_le_bytes());

    // Solid opaque BGRA pixels for a deterministic probe icon.
    for _ in 0..(32 * 32) {
        icon.extend_from_slice(&[0xA0, 0x60, 0x20, 0xFF]);
    }

    // Fully visible AND mask.
    icon.resize(icon.len() + MASK_BYTES, 0);

    let manifest_dir = PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let icons_dir = manifest_dir.join("icons");
    fs::create_dir_all(&icons_dir)?;
    fs::write(icons_dir.join("icon.ico"), icon)?;
    Ok(())
}

fn main() {
    write_probe_icon().expect("failed to create deterministic Windows probe icon");
    tauri_build::build()
}
